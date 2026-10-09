package com.hermes.bot.feature.sessions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * HOME-S7 source-contract guard (frozen plan v1, §7 File B).
 *
 * Freezes the no-new-calls / single filtered-list / placement decisions a
 * reviewer would otherwise check by eye (established pattern:
 * `SessionsResumeCardSourceContractTest`, `SessionsNewChatProminenceSourceContractTest`).
 *
 * Source-text assertions supplement, never replace, behavior tests.
 */
class SessionsSearchSourceContractTest {

    private fun locate(fileName: String): File =
        File("src/main/java/com/hermes/bot/feature/sessions/$fileName").let {
            if (it.exists()) it
            else File("app/src/main/java/com/hermes/bot/feature/sessions/$fileName")
        }

    private val screenSource: String by lazy {
        val f = locate("SessionsScreen.kt")
        assertTrue("SessionsScreen.kt must exist", f.exists())
        f.readText()
    }

    private val viewModelSource: String by lazy {
        val f = locate("SessionsViewModel.kt")
        assertTrue("SessionsViewModel.kt must exist", f.exists())
        f.readText()
    }

    private val uiStateSource: String by lazy {
        val f = locate("SessionsUiState.kt")
        assertTrue("SessionsUiState.kt must exist", f.exists())
        f.readText()
    }

    private val dateGroupingPolicySource: String by lazy {
        val f = locate("SessionsDateGroupingPolicy.kt")
        assertTrue("SessionsDateGroupingPolicy.kt must exist", f.exists())
        f.readText()
    }

    @Test
    fun sessionsScreen_searchDerivedFromPolicy_noNewGatewayCalls() {
        // TEST-007: INV-3 drift guard — the screen filters via the pure policy
        // and introduces no gateway/repository calls of its own.
        assertTrue(
            "SessionsScreen.kt must derive the filtered list via SessionsSearchPolicy.filterSessions(",
            screenSource.contains("SessionsSearchPolicy.filterSessions(")
        )
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
    fun sessionsScreen_lazyColumnUsesFilteredList_notRawSessions() {
        // S2: the LazyColumn consumes only groups derived from the one
        // filtered result; no raw-list row site may return.
        assertTrue(
            "SessionsScreen.kt must feed grouped sessions to the LazyColumn",
            screenSource.contains("items(group.sessions, key = { it.id })")
        )
        assertFalse(
            "SessionsScreen.kt must not keep an items(sessions, occurrence",
            screenSource.contains("items(sessions,")
        )
        assertEquals(
            "the grouped LazyColumn must have exactly one keyed original-id row site",
            1,
            Regex("\\bitems\\(group\\.sessions, key = \\{ it.id \\}\\)")
                .findAll(screenSource).count()
        )
    }

    @Test
    fun sessionsScreen_searchHasNoPinnedUnfilteredSession() {
        // TEST-009: a no-match query cannot leave an unfiltered shortcut above the list.
        assertEquals(1, Regex("\\bitems\\(group\\.sessions, key = \\{ it.id \\}\\)")
            .findAll(screenSource).count())
        assertEquals(1, Regex("SessionsSearchPolicy\\.filterSessions\\(")
            .findAll(screenSource).count())
        assertTrue(screenSource.contains("SessionsPinnedPolicy.groups(filteredSessions, uiState?.pinnedSessionIds.orEmpty(), pinnedOnly, now, zone)"))
        assertFalse(screenSource.contains("resumeCardCandidate("))
        assertFalse(screenSource.contains("onOpenChat(resumeCandidate.id)"))
        assertTrue(screenSource.contains("if (groupedSessions.isEmpty()"))
    }

    @Test
    fun sessionsScreen_relativeLabelsUseSafeEpochConversion_forBadTimestamps() {
        // S2 bad-timestamp guard: malformed wire epoch seconds must not be
        // truncated directly into an Instant before relative-label rendering.
        assertTrue(
            "SessionsScreen.kt must reuse the safe epoch conversion",
            screenSource.contains("effectiveSessionTimestamp(session, zone)")
        )
        assertTrue(
            "SessionsDateGroupingPolicy.kt must reject non-finite epoch seconds",
            dateGroupingPolicySource.contains("if (!epochSeconds.isFinite()) return null")
        )
        assertTrue(
            "SessionsDateGroupingPolicy.kt must use the shared safe conversion",
            dateGroupingPolicySource.contains("Instant.ofEpochSecond(wholeSeconds.toLong(), nanos)")
        )
        assertFalse(
            "SessionsScreen.kt must not construct Instants from raw epoch seconds",
            screenSource.contains("Instant.ofEpochSecond(")
        )
    }

    @Test
    fun sessionsScreen_profileChromeAndSearchKeepExistingActions_withoutExtraHeading() {
        assertTrue(screenSource.contains("AtelierSessionChrome("))
        assertTrue(screenSource.contains("fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding()"))
        assertFalse(screenSource.contains("val profileHeading"))
        assertFalse(screenSource.contains("Continue where you left off"))
        assertTrue(screenSource.contains("if (profileSwitcher != null)"))
        assertTrue(screenSource.contains("profileSwitcher()"))
        assertTrue(screenSource.contains("viewModel.refreshSessions()"))
        assertTrue(screenSource.contains("Refresh sessions"))
        assertTrue(screenSource.contains("onClick = onNavigateToSettings"))
        assertTrue(screenSource.contains("Open settings"))
        assertTrue(screenSource.contains("Text(\"Search conversations...\")"))
        assertTrue(screenSource.contains("shape = AtelierShapes.field"))
        assertTrue(screenSource.contains("surfaceContainer"))
        assertTrue(screenSource.contains("defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)"))
        assertTrue(screenSource.contains("onValueChange = { viewModel?.onSearchQueryChange(it) }"))
        assertTrue(screenSource.contains("onClick = { viewModel?.onSearchQueryChange(\"\") }"))
        assertTrue(screenSource.contains("contentDescription = \"Clear search\""))
        val search = screenSource.indexOf("placeholder = { Text(\"Search conversations...\") }")
        assertTrue(search > screenSource.indexOf("AtelierSessionChrome("))
        assertTrue(search < screenSource.indexOf("// Owner UI decision (2026-09-24)"))
        assertTrue(search < screenSource.indexOf("SessionErrorBanner("))
        assertTrue(search < screenSource.indexOf("SessionsOfflineSectionPolicy.sectionLabel("))
        assertFalse(screenSource.contains("ProfileStatusBadge("))
        assertFalse(screenSource.contains("SessionsOfflineSectionPolicy.sectionLabel(false)"))
    }

    @Test
    fun sessionsViewModel_searchQueryOnlySetBySearchChange() {
        // TEST-010: FZ-3 drift guard / INV-6 — exactly one new VM setter, and
        // `searchQuery` is declared exactly once in SessionsUiState with the
        // default "".
        assertEquals(
            "SessionsViewModel.kt must declare onSearchQueryChange( exactly once",
            1,
            Regex("\\bfun onSearchQueryChange\\(").findAll(viewModelSource).count()
        )
        val stateDecl = Regex("\\bval searchQuery: String\\s*=\\s*\"\"").findAll(uiStateSource).count()
        assertEquals(
            "SessionsUiState.kt must declare `val searchQuery: String = \"\"` exactly once",
            1,
            stateDecl
        )
    }
}
