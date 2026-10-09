package io.github.anshuman2401.hermesmobile.feature.sessions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Sessions presentation guard: the profile badge carries connection status,
 * while list loading uses one indicator and recovery actions remain contextual.
 *
 * Source-text assertions supplement, never replace, behavior tests.
 */
class SessionsHealthStripSourceContractTest {

    private fun locate(dir: String, fileName: String): File =
        File("$dir$fileName").let {
            if (it.exists()) it
            else File("app/$dir$fileName")
        }

    private val screenSource: String by lazy {
        val f = locate("src/main/java/io/github/anshuman2401/hermesmobile/feature/sessions/", "SessionsScreen.kt")
        assertTrue("SessionsScreen.kt must exist", f.exists())
        f.readText()
    }

    private val navShellSource: String by lazy {
        val f = locate("src/main/java/io/github/anshuman2401/hermesmobile/navigation/", "HermesNavShell.kt")
        assertTrue("HermesNavShell.kt must exist", f.exists())
        f.readText()
    }

    private val profileBadgeSource: String by lazy {
        val f = locate("src/main/java/io/github/anshuman2401/hermesmobile/design/", "ProfileStatusBadge.kt")
        assertTrue("ProfileStatusBadge.kt must exist", f.exists())
        f.readText()
    }

    @Test
    fun sessionsScreen_networkFailureBannerIsSuppressed() {
        assertFalse(
            "Sessions must not contain the duplicate network-failure banner title",
            screenSource.contains("\"Network Connection Failure\"")
        )
        val errorCall = screenSource.substringAfter("// Owner UI decision (2026-09-24)")
            .substringBefore("if (isOfflineCache) {")
        assertTrue(
            "Sessions must render only non-network errors",
            errorCall.contains("if (currentError != null && currentError !is SessionError.NetworkFailure)")
        )
    }

    @Test
    fun sessionsScreen_topBarSubtitleRemoved_singleStatusSurface() {
        // TEST-005: FZ-1/INV-1 drift guard — top bar removed, controls relocated into content, subtitle strings are gone.
        assertTrue(
            "SessionsScreen.kt must keep content-relocated refresh action",
            screenSource.contains("Refresh sessions")
        )
        assertTrue(
            "SessionsScreen.kt must keep content-relocated settings action",
            screenSource.contains("Open settings")
        )
        assertFalse(
            "SessionsScreen.kt must not contain \"Hermes Connected\"",
            screenSource.contains("Hermes Connected")
        )
        assertFalse(
            "SessionsScreen.kt must not contain \"Cached (offline)\"",
            screenSource.contains("Cached (offline)")
        )
        assertFalse(
            "SessionsScreen.kt must not contain \"Status: Connected\"",
            screenSource.contains("Status: Connected")
        )
        assertFalse(
            "SessionsScreen.kt must not contain \"Status: Cached offline\"",
            screenSource.contains("Status: Cached offline")
        )
    }

    @Test
    fun sessionsScreen_noNewGatewayCalls() {
        assertFalse(
            "SessionsScreen.kt must not call listSessions( directly (no new gateway calls)",
            screenSource.contains("listSessions(")
        )
        assertFalse(
            "SessionsScreen.kt must not call resumeSession( directly (no new gateway calls)",
            screenSource.contains("resumeSession(")
        )
        assertFalse(
            "SessionsScreen.kt must not call createDraftSession( directly (no new gateway calls)",
            screenSource.contains("createDraftSession(")
        )
    }

    @Test
    fun sessionsScreen_hasOneListLoadingIndicatorAndNoOfflineReconnectCard() {
        assertFalse(screenSource.contains("LinearProgressIndicator("))
        assertFalse(screenSource.contains("Text(\"Syncing…\")"))
        assertFalse(screenSource.contains("fun ConnectionHealthStrip("))
        assertFalse(screenSource.contains("SessionsHealthStripPolicy.showReconnect("))
        assertEquals("One list refresh indicator", 1,
            Regex("\\bPullToRefreshBox\\(").findAll(screenSource).count())
        assertTrue(screenSource.contains("PullToRefreshBox(isRefreshing = isLoading"))
        assertFalse("List loading must not add another spinner",
            screenSource.contains("if (isLoading) {\n                        CircularProgressIndicator("))
        assertTrue("Initial empty list retains loading copy", screenSource.contains("Loading sessions…"))
        assertTrue("Host failure retains a contextual Retry action",
            screenSource.contains("OutlinedButton(onClick = onReconnect"))
        assertFalse("Sessions card must have no status dot", screenSource.contains("ConnectionStatusDot("))
    }

    @Test
    fun profileStatusBadge_dotIsGreenWhenConnectedRedWhenNot() {
        // TEST-009 (new, owner UI decision 2026-09-23): the profile badge
        // composable owns the green/red dot — green family when connected,
        // red (error) family when not; no Disconnect affordance anywhere.
        assertTrue(
            "ProfileStatusBadge.kt must choose dot color by connection state",
            profileBadgeSource.contains("fun dotColor(isConnected: Boolean")
        )
        assertTrue(
            "ProfileStatusBadge.kt connected dot must use colorScheme.secondary (green family)",
            profileBadgeSource.contains("connectedColor = MaterialTheme.colorScheme.secondary")
        )
        assertTrue(
            "ProfileStatusBadge.kt disconnected dot must use colorScheme.error (red family)",
            profileBadgeSource.contains("disconnectedColor = MaterialTheme.colorScheme.error")
        )
        assertEquals("ProfileStatusBadge must own the sole connection dot call", 1,
            Regex("\\bConnectionStatusDot\\(").findAll(profileBadgeSource).count() - 1)
        assertFalse(
            "ProfileStatusBadge.kt must not embed any Hermes Go copied assets",
            profileBadgeSource.contains("hermes_flutter")
        )
    }

    @Test
    fun sessionsScreen_connectCardHasNoConnectedTextOrDisconnectButton() {
        // TEST-010 (new): the Connect screen's connected card drops the
        // "Connected" text and Disconnect button per owner decision.
        val connectSource = locate(
            "src/main/java/io/github/anshuman2401/hermesmobile/feature/connect/", "ConnectScreen.kt"
        ).readText()
        assertFalse("Connect connected card must not call ConnectionStatusDot",
            connectSource.contains("ConnectionStatusDot("))
        assertTrue("HTTP warning retained", connectSource.contains("HTTP · Not encrypted by HTTP"))
        assertTrue("Capability summary retained", connectSource.contains("uiState.capabilitySummary"))
        assertTrue("Open Sessions action retained", connectSource.contains("onClick = onOpenSessions"))
        assertFalse(
            "ConnectScreen.kt connected card must not contain Text(\"Connected\")",
            connectSource.contains("Text(\n                                text = \"Connected\"")
        )
        assertFalse(
            "ConnectScreen.kt connected card must not contain a Disconnect button",
            connectSource.contains("Text(\"Disconnect\")")
        )
    }

    @Test
    fun navShell_bothSessionsCallSitesPassOnRetryAsReconnect() {
        // Preserve both Sessions reconnect callbacks independently of Work's
        // newly approved in-page reconnect action (covered by CronWiringTest).
        val chatsSection = navShellSource.substringAfter("BottomNavTab.CHATS -> {")
            .substringBefore("BottomNavTab.WORK -> {")
        assertEquals("Both SessionsScreen layouts must remain wired", 2,
            Regex("\\bSessionsScreen\\s*\\(").findAll(chatsSection).count())
        assertEquals(
            "Both SessionsScreen layouts must pass `onReconnect = onRetry`",
            2,
            Regex("\\bonReconnect\\s*=\\s*onRetry\\b").findAll(chatsSection).count()
        )
    }
}
