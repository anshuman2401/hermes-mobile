package com.hermes.bot.feature.sessions

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * HOME-S9 source-contract guard (frozen plan
 * `specs/plans/HOME-001-S9-offline-cache-section-plan-v1.md`, §6 File B).
 *
 * Guards the compact, repository-flagged offline provenance line and the
 * absence of the removed subtitle/card (established pattern:
 * `SessionsHealthStripSourceContractTest`).
 *
 * Source-text assertions supplement, never replace, behavior tests.
 */
class SessionsOfflineSectionSourceContractTest {

    private fun locate(dir: String, fileName: String): File =
        File("$dir$fileName").let {
            if (it.exists()) it
            else File("app/$dir$fileName")
        }

    private val screenSource: String by lazy {
        val f = locate("src/main/java/com/hermes/bot/feature/sessions/", "SessionsScreen.kt")
        assertTrue("SessionsScreen.kt must exist", f.exists())
        f.readText()
    }

    @Test
    fun sessionsScreen_warningBannerRemoved() {
        // TEST-005: FZ-1/INV-1 drift guard — the loud warning Surface is gone.
        assertFalse(
            "SessionsScreen.kt must not contain \"Offline cache (7-day window)\"",
            screenSource.contains("Offline cache (7-day window)")
        )
        assertFalse(
            "SessionsScreen.kt must not contain \"Warning: Displaying offline cache\"",
            screenSource.contains("Warning: Displaying offline cache")
        )
        assertFalse(
            "SessionsScreen.kt must not use Icons.Default.Warning",
            screenSource.contains("Icons.Default.Warning")
        )
    }

    @Test
    fun sessionsScreen_offlineDisclosureIsConditionalAndProfileBadgeIsGone() {
        // TEST-006: the provenance disclosure is cache-only; the former live
        // list heading and adjacent selected-profile badge must stay absent.
        assertTrue(
            "SessionsScreen.kt must derive the section label via SessionsOfflineSectionPolicy.sectionLabel(",
            screenSource.contains("SessionsOfflineSectionPolicy.sectionLabel(")
        )
        assertFalse(
            "SessionsScreen.kt must not call the section label for live data",
            screenSource.contains("SessionsOfflineSectionPolicy.sectionLabel(false)")
        )
        assertFalse(
            "SessionsScreen.kt must not render the removed live list heading",
            screenSource.contains("Text(\"Recent Conversations\")")
        )
        assertFalse(
            "SessionsScreen.kt must not render the selected-profile badge",
            screenSource.contains("text = profileName") ||
                screenSource.contains("secondaryContainer")
        )
        val disclosureStart = screenSource.indexOf("if (isOfflineCache) {")
        val statusStart = screenSource.indexOf("if (uiState?.staleProfileNotice != null)")
        assertTrue("offline disclosure must have a cache-state guard", disclosureStart >= 0)
        assertTrue("offline disclosure must end before the status row", statusStart > disclosureStart)
        val disclosureBlock = screenSource.substring(disclosureStart, statusStart)
        assertTrue(
            "offline label must be rendered inside the cache-state guard",
            disclosureBlock.contains("SessionsOfflineSectionPolicy.sectionLabel(isOfflineCache)")
        )
        assertFalse("offline subtitle was removed", disclosureBlock.contains("sectionSubtitle("))
        assertTrue("offline label is quiet secondary text",
            disclosureBlock.contains("style = MaterialTheme.typography.labelSmall") &&
                disclosureBlock.contains("color = MaterialTheme.colorScheme.onSurfaceVariant"))
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
    fun sessionsScreen_provenanceAppearsBeforeDateGroups() {
        val provenance = screenSource.indexOf("SessionsOfflineSectionPolicy.sectionLabel(")
        val groupedHeadings = screenSource.indexOf("SessionsPinnedPolicy.groups(")

        assertTrue("offline label must be present", provenance >= 0)
        assertTrue("grouping must follow provenance", groupedHeadings > provenance)
        assertFalse(screenSource.contains("SessionsHealthStripPolicy.showReconnect("))
        assertFalse(screenSource.contains("ConnectionHealthStrip("))
        assertTrue(screenSource.contains("if (uiState?.staleProfileNotice != null)"))
        assertTrue(screenSource.contains("uiState?.staleProfileNotice?.let { notice ->"))
        assertTrue(screenSource.contains("PullToRefreshBox(isRefreshing = isLoading"))
    }
}
