package io.github.anshuman2401.hermesmobile.hardening

import io.github.anshuman2401.hermesmobile.data.gateway.GatewayRedactor
import io.github.anshuman2401.hermesmobile.data.local.AesGcmCipherEngine
import io.github.anshuman2401.hermesmobile.data.local.AuthCredential
import io.github.anshuman2401.hermesmobile.data.local.CacheAccessPartition
import io.github.anshuman2401.hermesmobile.data.local.CacheReadResult
import io.github.anshuman2401.hermesmobile.data.local.ConnectionProfile
import io.github.anshuman2401.hermesmobile.data.local.Draft
import io.github.anshuman2401.hermesmobile.data.local.EncryptedVault
import io.github.anshuman2401.hermesmobile.data.local.InMemoryMasterKeyProvider
import io.github.anshuman2401.hermesmobile.data.local.LocalDiagnostic
import io.github.anshuman2401.hermesmobile.data.local.PendingTurnRecord
import io.github.anshuman2401.hermesmobile.data.local.PendingTurnState
import io.github.anshuman2401.hermesmobile.data.local.SessionSummaryCache
import io.github.anshuman2401.hermesmobile.feature.chat.ChatSecurityPolicy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * BLD-008: Comprehensive security, privacy, telemetry exclusion, and WebView prohibition test.
 * Enforces architectural constraints from ADR-0001, ADR-0003, and specs/prd.md (AC-09).
 */
class SecurityAndPrivacyHardeningTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun architecture_strictlyZeroWebViewUsageInMainSources() {
        val projectDir = File(System.getProperty("user.dir") ?: ".")
        val mainSourceDir = File(projectDir, "src/main/java")
        val sources = if (mainSourceDir.exists()) mainSourceDir else File("app/src/main/java")

        assertTrue("Main source directory must exist", sources.exists())

        val forbiddenTokens = listOf(
            "android.webkit.WebView",
            "android.webkit.WebChromeClient",
            "android.webkit.WebViewClient",
            "addJavascriptInterface",
            "loadDataWithBaseURL"
        )

        sources.walkTopDown().filter { it.extension == "kt" || it.extension == "java" }.forEach { file ->
            val text = file.readText()
            for (forbidden in forbiddenTokens) {
                assertFalse(
                    "Forbidden WebView token '$forbidden' found in ${file.relativeTo(sources)}",
                    text.contains(forbidden)
                )
            }
        }
    }

    @Test
    fun architecture_strictlyZeroTelemetryOrAnalyticsInBuildDependencies() {
        val projectDir = File(System.getProperty("user.dir") ?: ".")
        val buildGradle = File(projectDir, "build.gradle.kts").takeIf { it.exists() }
            ?: File("app/build.gradle.kts")

        assertTrue("app/build.gradle.kts must exist", buildGradle.exists())
        val content = buildGradle.readText().lowercase()

        val forbiddenTelemetryPackages = listOf(
            "firebase-analytics",
            "com.mixpanel",
            "com.segment",
            "com.adjust",
            "com.appsflyer",
            "com.facebook.android:facebook-core",
            "com.google.android.gms:play-services-analytics",
            "io.sentry:sentry-android"
        )

        for (pkg in forbiddenTelemetryPackages) {
            assertFalse(
                "Forbidden telemetry package '$pkg' found in build.gradle.kts",
                content.contains(pkg)
            )
        }
    }

    @Test
    fun uriPolicy_rejectsComprehensiveDangerousAndMalformedSchemeCorpus() {
        val dangerousSchemes = listOf(
            "javascript:alert(document.cookie)",
            "JAVASCRIPT:alert(1)",
            "javaSCRIPT:window.close()",
            "data:text/html;base64,PHNjcmlwdD5hbGVydCgxKTwvc2NyaXB0Pg==",
            "data:text/plain;charset=utf-8,hello",
            "file:///etc/passwd",
            "file:///sdcard/Download/malicious.apk",
            "content://com.android.providers.telephony/siminfo",
            "content://media/external/images",
            "intent://scan/#Intent;scheme=zxing;package=com.google.zxing.client.android;end",
            "intent:#Intent;action=android.intent.action.VIEW;end",
            "vbscript:MsgBox(1)",
            "blob:https://example.com/uuid-here",
            "about:blank",
            "chrome://flags",
            "resource://android/res",
            "smb://192.168.1.1/share",
            "nfs://fileserver/export",
            "jar:file:/path/to/archive.jar!/entry",
            "view-source:https://example.com",
            "ssh://git@github.com",
            "tel:123456789",
            "sms:123456789",
            "market://details?id=com.malware"
        )

        for (uri in dangerousSchemes) {
            assertFalse("Dangerous scheme must be rejected: $uri", ChatSecurityPolicy.isValidExternalUrl(uri))
            assertNull("Sanitizing dangerous scheme must return null: $uri", ChatSecurityPolicy.sanitizeUrl(uri))
        }
    }

    @Test
    fun uriPolicy_acceptsStrictlySafeHttpAndHttpsSchemes() {
        val safeUrls = listOf(
            "https://hermes.agent.local",
            "https://hermes.agent.local:8443/chat",
            "https://example.com/documentation?topic=agent&page=1#overview",
            "http://192.168.1.50:8080/status",
            "http://localhost:3000"
        )

        for (uri in safeUrls) {
            assertTrue("Valid HTTP/HTTPS must be accepted: $uri", ChatSecurityPolicy.isValidExternalUrl(uri))
            assertEquals(uri, ChatSecurityPolicy.sanitizeUrl(uri))
        }
    }

    @Test
    fun secretRedaction_handlesExoticTokensAndComplexLogPatterns() {
        val logWithMultipleSecrets = """
            Connecting with Authorization: Bearer sk-ant-secret123456789
            Set-Cookie: hermes_session=raw_secret_session_cookie_abc
            API Key: ghp_999999999999999999999999999999999999
            User login: password="superSecretPassword!"
            subprotocol: hermes-gateway-ticket.ticket_secure_xyz_123
        """.trimIndent()

        val redacted = GatewayRedactor.redactString(logWithMultipleSecrets)

        assertFalse(redacted.contains("sk-ant-secret123456789"))
        assertFalse(redacted.contains("raw_secret_session_cookie_abc"))
        assertFalse(redacted.contains("ghp_999999999999999999999999999999999999"))
        assertFalse(redacted.contains("superSecretPassword!"))
        assertFalse(redacted.contains("ticket_secure_xyz_123"))

        assertTrue(redacted.contains("Bearer [REDACTED]"))
        assertTrue(redacted.contains("hermes_session=[REDACTED]"))
        assertTrue(redacted.contains("password=[REDACTED]"))
        assertTrue(redacted.contains("hermes-gateway-ticket.[REDACTED]"))
    }

    @Test
    fun privacyReset_clearAllDeletesEveryStoredEntityCompletely() = runBlocking {
        val dir = tempFolder.newFolder("privacy_reset_vault")
        val vault = EncryptedVault(dir, AesGcmCipherEngine(InMemoryMasterKeyProvider()))

        // 1. Populate all vault entities
        val profile = ConnectionProfile(
            localId = "reset-prof", normalizedOrigin = "http://reset.example.test:9119", displayLabel = "Synthetic reset"
        )
        vault.saveConnectionProfile(profile)
        vault.saveAuthCredential(AuthCredential(
            driverKind = AuthCredential.DRIVER_COOKIE_SESSION, providerId = "basic",
            cookies = mapOf("c" to "synthetic-value"), canonicalOrigin = profile.normalizedOrigin
        ))
        vault.saveDraft(Draft("sess-1", "My secret draft"))
        val layoutPartition = CacheAccessPartition("reset-prof", "origin-x", "epoch-x", "u")
        vault.saveSessionSummaryCache(
            SessionSummaryCache(emptyList(), System.currentTimeMillis() / 1000),
            layoutPartition
        )
        vault.savePendingTurn(PendingTurnRecord("turn-1", "sess-1", null, "Prompt content", PendingTurnState.PREPARED))
        vault.recordDiagnostic(LocalDiagnostic("INFO", "Connect", "Connected ok"))

        // Verify entities exist
        assertTrue(vault.getConnectionProfile() != null)
        assertTrue(vault.getAuthCredential() != null)
        assertTrue(vault.getDraft("sess-1") != null)
        assertTrue(vault.readSessionSummaryCache(layoutPartition) is CacheReadResult.Available)
        assertTrue(vault.getPendingTurn("turn-1") != null)
        assertTrue(vault.getDiagnostics().isNotEmpty())

        val storedFiles = dir.walkTopDown().filter { it.isFile }.toList()
        assertTrue("Populated entities must have physical encrypted storage", storedFiles.isNotEmpty())

        // 2. Perform total privacy reset
        vault.clearAll()
        assertTrue("Every previously stored file must be physically removed", storedFiles.none { it.exists() })
        assertTrue("No stored files remain", dir.walkTopDown().none { it.isFile })

        // 3. Assert all entities return null or empty
        assertNull("Connection profile must be cleared", vault.getConnectionProfile())
        assertNull("Auth credentials must be cleared", vault.getAuthCredential())
        assertNull("Drafts must be cleared", vault.getDraft("sess-1"))
        assertTrue("Draft list must be empty", vault.getAllDrafts().isEmpty())
        assertTrue(
            "Summary cache must be cleared",
            vault.readSessionSummaryCache(layoutPartition) is CacheReadResult.Absent
        )
        assertNull("Pending turn must be cleared", vault.getPendingTurn("turn-1"))
        assertTrue("Pending turns list must be empty", vault.getAllPendingTurns().isEmpty())
        assertTrue("Diagnostics must be cleared", vault.getDiagnostics().isEmpty())
    }
}
