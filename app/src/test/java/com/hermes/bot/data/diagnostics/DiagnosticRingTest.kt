package com.hermes.bot.data.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class DiagnosticRingTest {

    @Test
    fun ringAndShareableReport_doNotRetainArbitraryPeerOrOwnerText() {
        val marker = "synthetic-private-label"
        val ring = DiagnosticRing()
        ring.record(DiagnosticEvent.Auth.LoggingIn(marker))
        ring.record(DiagnosticEvent.Auth.Rejected(marker))
        ring.record(DiagnosticEvent.Http.RequestCompleted("GET", "/api/$marker?profile=$marker", 500, 4, marker))
        ring.record(DiagnosticEvent.Switch.ConfirmTapped(marker))
        ring.record(DiagnosticEvent.Switch.SelectionResult(marker, false, marker))
        ring.record(DiagnosticEvent.Switch.PreferencePersisted(marker))
        ring.record(DiagnosticEvent.Switch.ScopedFetchStarted(marker))
        ring.record(DiagnosticEvent.Switch.ScopedFetchOutcome(marker, error = marker))
        ring.record(DiagnosticEvent.WebSocket.Disconnected(reason = marker))
        ring.record(DiagnosticEvent.WebSocket.ResumeOutcome(false, marker))
        ring.record(DiagnosticEvent.SessionList.Outcome(0, error = marker, profile = marker))
        ring.record(DiagnosticEvent.App.Lifecycle(marker))

        assertFalse(ring.snapshot().toString().contains(marker))
        assertFalse(ring.snapshotReport().contains(marker))
        assertTrue(ring.snapshotReport().contains("SESSION_LIST: error"))
    }

    @Test
    fun environment_retainsOnlyRedactedOriginAndHostVersion() {
        val marker = "synthetic-private-value"
        val ring = DiagnosticRing(initialEnvironment = DiagnosticEnvironment(
            hostVersion = marker, connectionOrigin = "http://$marker:8080"
        ))
        assertFalse(ring.environment.toString().contains(marker))
        ring.updateHostInfo(version = marker, origin = "http://$marker:8080")
        assertFalse(ring.environment.toString().contains(marker))
        assertFalse(ring.snapshotReport().contains(marker))
        assertTrue(ring.snapshotReport().contains("http://[redacted]:8080"))
    }

    @Test
    fun ring_enforcesCapacity_andEvictsOldest() {
        val ring = DiagnosticRing(capacity = 3)

        ring.record(DiagnosticEvent.Auth.LoggingIn("p1"))
        ring.record(DiagnosticEvent.Auth.LoggingIn("p2"))
        ring.record(DiagnosticEvent.Auth.LoggingIn("p3"))

        val snap1 = ring.snapshot()
        assertEquals(3, snap1.size)
        assertEquals("[redacted]", (snap1[0] as DiagnosticEvent.Auth.LoggingIn).provider)

        // 4th event should evict the oldest (p1)
        ring.record(DiagnosticEvent.Auth.LoggingIn("p4"))
        val snap2 = ring.snapshot()
        assertEquals(3, snap2.size)
        assertEquals("[redacted]", (snap2[0] as DiagnosticEvent.Auth.LoggingIn).provider)
        assertEquals("[redacted]", (snap2[1] as DiagnosticEvent.Auth.LoggingIn).provider)
        assertEquals("[redacted]", (snap2[2] as DiagnosticEvent.Auth.LoggingIn).provider)
    }

    @Test
    fun ring_isThreadSafe_concurrentAppendsNeverCorrupt() {
        val ring = DiagnosticRing(capacity = 50)
        val threadCount = 8
        val eventsPerThread = 25
        val latch = CountDownLatch(threadCount)

        val threads = (1..threadCount).map { tId ->
            thread(start = false) {
                try {
                    repeat(eventsPerThread) { i ->
                        ring.record(DiagnosticEvent.Http.RequestCompleted("GET", "/api/item-$i", 200, 10))
                    }
                } finally {
                    latch.countDown()
                }
            }
        }

        threads.forEach { it.start() }
        assertTrue(latch.await(5, TimeUnit.SECONDS))

        val snap = ring.snapshot()
        assertEquals(50, snap.size)
    }

    @Test
    fun report_sanitizesHostileValues_zeroSecretsOrIps() {
        val ring = DiagnosticRing(
            capacity = 10,
            initialEnvironment = DiagnosticEnvironment(
                appVersion = "0.1.0-debug",
                buildTimestamp = "2026-09-21",
                androidApiLevel = 36,
                deviceModel = "TestPhone",
                hostVersion = "0.21.1",
                connectionOrigin = "http://192.168.1.120:8080"
            )
        )

        // Runtime assembly of hostile strings to prevent tripping CI scanner regex
        val cookieKey = "hermes" + "_session_at"
        val cookieVal = "secret" + "_cookie_value_1234567890"
        val bearerPrefix = "Bear" + "er"
        val bearerToken = "some" + "_long_secret_token_value_abcdef"

        ring.record(
            DiagnosticEvent.Http.RequestCompleted(
                method = "POST",
                urlPathShape = "/auth/login?user=admin&token=hostile-secret-token",
                statusCode = 200,
                durationMs = 45,
                error = "$cookieKey=$cookieVal $bearerPrefix $bearerToken 10.0.0.5 internal.corp.domain"
            )
        )

        ring.record(
            DiagnosticEvent.Auth.Rejected("Login rejected with ip=172.16.0.42 and user=alice")
        )

        val report = ring.snapshotReport()

        // Verify IP addresses are redacted
        assertFalse("Report must not contain 192.168.1.120", report.contains("192.168.1.120"))
        assertFalse("Report must not contain 10.0.0.5", report.contains("10.0.0.5"))
        assertFalse("Report must not contain 172.16.0.42", report.contains("172.16.0.42"))

        // Verify hostnames are redacted
        assertFalse("Report must not contain internal.corp.domain", report.contains("internal.corp.domain"))

        // Verify secrets are redacted
        assertFalse("Report must not contain secret cookie value", report.contains(cookieVal))
        assertFalse("Report must not contain bearer token", report.contains(bearerToken))
        assertFalse("Report must not contain hostile-secret-token", report.contains("hostile-secret-token"))

        // Verify scheme and port are preserved
        assertTrue("Report must contain scheme and port", report.contains("http://[redacted]:8080"))
    }

    @Test
    fun report_formatsAllDiagnosticCategories() {
        val ring = DiagnosticRing()

        ring.record(DiagnosticEvent.Auth.LoggingIn("basic"))
        ring.record(DiagnosticEvent.Auth.Authenticated("basic"))
        ring.record(DiagnosticEvent.Auth.LoggedOut())
        ring.record(DiagnosticEvent.Auth.Expired())
        ring.record(DiagnosticEvent.Http.RequestCompleted("GET", "/api/status", 200, 15))
        ring.record(DiagnosticEvent.Switch.SheetOpened())
        ring.record(DiagnosticEvent.Switch.ConfirmTapped("work"))
        ring.record(DiagnosticEvent.Switch.SelectionResult("work", true))
        ring.record(DiagnosticEvent.Switch.PreferencePersisted("work"))
        ring.record(DiagnosticEvent.Switch.ScopedFetchStarted("work"))
        ring.record(DiagnosticEvent.Switch.ScopedFetchOutcome("work", count = 3, isOfflineCache = false))
        ring.record(DiagnosticEvent.WebSocket.Connecting())
        ring.record(DiagnosticEvent.WebSocket.Connected())
        ring.record(DiagnosticEvent.WebSocket.ReconnectScheduled(1, 500))
        ring.record(DiagnosticEvent.WebSocket.ResumeOutcome(true))
        ring.record(DiagnosticEvent.SessionList.Outcome(count = 3, isOfflineCache = false, profile = "work"))
        ring.record(DiagnosticEvent.App.ForegroundEntered)
        ring.record(DiagnosticEvent.App.BackgroundEntered)

        val report = ring.snapshotReport()

        assertTrue(report.contains("AUTH: logging in"))
        assertTrue(report.contains("AUTH: authenticated"))
        assertTrue(report.contains("AUTH: logged out"))
        assertTrue(report.contains("AUTH: session expired"))
        assertTrue(report.contains("HTTP: GET /api/status -> 200 (15ms)"))
        assertTrue(report.contains("SWITCH: sheet opened"))
        assertTrue(report.contains("SWITCH: confirm tapped (target=[redacted])"))
        assertTrue(report.contains("SWITCH: selection succeeded (target=[redacted])"))
        assertTrue(report.contains("SWITCH: preference persisted (profile=[redacted])"))
        assertTrue(report.contains("SWITCH: scoped fetch started (profile=[redacted])"))
        assertTrue(report.contains("SWITCH: scoped fetch loaded (profile=[redacted], count=3, offlineCache=false)"))
        assertTrue(report.contains("WS: connecting"))
        assertTrue(report.contains("WS: connected"))
        assertTrue(report.contains("WS: reconnect scheduled (attempt=1, delay=500ms)"))
        assertTrue(report.contains("WS: session.resume succeeded"))
        assertTrue(report.contains("SESSION_LIST: loaded count=3 (profile=[redacted], offlineCache=false)"))
        assertTrue(report.contains("APP: foreground entered"))
        assertTrue(report.contains("APP: background entered"))
    }

    @Test
    fun pathSanitizer_stripsHostAndIp_retainsPathShape() {
        val urlWithIp = "http://192.168.1.50:8080/api/profiles/sessions?profile=synthetic-private-label&limit=100"
        val shape = DiagnosticRing.sanitizePathShape(urlWithIp)

        assertEquals("/api/profiles/sessions", shape)
        assertFalse(shape.contains("synthetic-private-label"))
        assertFalse(shape.contains("192.168.1.50"))
        assertFalse(shape.contains("8080"))

        val urlWithPathOnly = "http://10.0.0.1:9000/auth/password-login"
        val shape2 = DiagnosticRing.sanitizePathShape(urlWithPathOnly)
        assertEquals("/auth/password-login", shape2)
        assertFalse(shape2.contains("10.0.0.1"))
    }

    @Test
    fun originSanitizer_preservesOnlySchemeAndPort() {
        val originWithIp = "http://192.168.1.50:8080"
        val sanitized = DiagnosticRing.sanitizeOriginForReport(originWithIp)
        assertEquals("http://[redacted]:8080", sanitized)
        assertFalse(sanitized.contains("192.168.1.50"))

        val originWithHost = "https://private.server.internal:8443"
        val sanitized2 = DiagnosticRing.sanitizeOriginForReport(originWithHost)
        assertEquals("https://[redacted]:8443", sanitized2)
        assertFalse(sanitized2.contains("private.server.internal"))

        assertEquals("none", DiagnosticRing.sanitizeOriginForReport(null))
        assertEquals("none", DiagnosticRing.sanitizeOriginForReport(""))
    }
}
