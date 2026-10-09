package com.hermes.bot.feature.settings

import com.hermes.bot.data.insights.*
import com.hermes.bot.data.local.AuthCredential
import com.hermes.bot.data.local.AuthCredentialLease
import com.hermes.bot.data.origin.CanonicalOrigin
import com.hermes.bot.test.MainDispatcherRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class HostInsightsViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private val scope = InsightsScope("default", "synthetic", CanonicalOrigin.parse("http://example.invalid:8080").getOrThrow(),
        AuthCredentialLease("synthetic", "http://example.invalid:8080", "synthetic", AuthCredential("cookie_session", "basic", emptyMap())), 1)
    private open inner class Repository : HostInsightsRepository {
        var current = true
        var profiles = listOf("default", "work")
        val requests = mutableListOf<String>()
        override suspend fun capture(): InsightsRead<InsightsScope> = InsightsRead.Ready(scope)
        override suspend fun isCurrent(scope: InsightsScope) = current
        override suspend fun health(scope: InsightsScope): InsightsRead<HostHealth> = InsightsRead.Ready(HostHealth(InsightsRead.Failed(InsightsFailure.UNSUPPORTED), InsightsRead.Failed(InsightsFailure.UNSUPPORTED)))
        override suspend fun usage(scope: InsightsScope, days: Int): InsightsRead<HostUsage> { requests.add(days.toString()); return InsightsRead.Ready(HostUsage(days, emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList())) }
        override suspend fun analyticsRoster(): InsightsRead<AnalyticsRoster> = InsightsRead.Ready(AnalyticsRoster(profiles, scope))
        override suspend fun analyticsRosterCurrent(roster: AnalyticsRoster) = current
        override suspend fun analyticsUsage(roster: AnalyticsRoster, profile: String, days: Int): InsightsRead<HostUsage> {
            requests.add("$profile:$days")
            return InsightsRead.Ready(HostUsage(days, emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList()))
        }
        override suspend fun logs(scope: InsightsScope, file: HostLogFile, level: HostLogLevel): InsightsRead<HostLogTail> { requests.add("${file.wire}:${level.wire}"); return InsightsRead.Ready(HostLogTail(listOf("synthetic"))) }
    }
    @Test fun filtersRefreshAndSearchDoesNotFetch() {
        val repo = Repository(); val vm = HostInsightsViewModel(repo) { 42L }
        vm.open(HostInsightsPage.LOGS)
        vm.search("test")
        assertEquals(listOf("gateway:ALL"), repo.requests)
        vm.file(HostLogFile.AGENT); vm.search("second"); vm.level(HostLogLevel.ERROR)
        assertEquals(listOf("gateway:ALL", "agent:ALL", "agent:ERROR"), repo.requests)
        assertEquals("", vm.state.value.query); assertEquals(42L, vm.state.value.fetchedAt)
        vm.search("kept"); vm.refresh(); assertEquals("kept", vm.state.value.query)
        vm.leave(); assertNull(vm.state.value.logs); assertNull(vm.state.value.profile)
        assertEquals(HostLogFile.GATEWAY, vm.state.value.file)
        assertEquals(HostLogLevel.ALL, vm.state.value.level)
    }
    @Test fun defaultPeriodAndChangedPeriodAreExplicit() {
        val repo = Repository(); val vm = HostInsightsViewModel(repo)
        vm.open(HostInsightsPage.ANALYTICS); vm.period(7); vm.period(7); vm.period(90); vm.period(365)
        assertEquals(listOf("default:30", "default:7", "default:90"), repo.requests); assertEquals(90, vm.state.value.usage!!.days)
    }
    @Test fun analyticsDefaultsToFirstListedWhenDefaultAbsentAndChoiceDoesNotPersist() {
        val repo = Repository().apply { profiles = listOf("work", "other") }
        val vm = HostInsightsViewModel(repo)
        vm.open(HostInsightsPage.ANALYTICS)
        assertEquals("work", vm.state.value.profile)
        vm.analyticsProfile("other")
        assertEquals(listOf("work:30", "other:30"), repo.requests)
        vm.leave()
        vm.open(HostInsightsPage.ANALYTICS)
        assertEquals("work", vm.state.value.profile)
        assertEquals("work:30", repo.requests.last())
    }
    @Test fun analyticsRefreshKeepsChoiceWhenPresentAndFallsBackWhenRemoved() {
        val repo = Repository()
        val vm = HostInsightsViewModel(repo)
        vm.open(HostInsightsPage.ANALYTICS)
        vm.analyticsProfile("work")
        vm.refresh()
        assertEquals("work", vm.state.value.profile)
        repo.profiles = listOf("other", "default")
        vm.refresh()
        assertEquals("default", vm.state.value.profile)
        assertEquals(listOf("default:30", "work:30", "work:30", "default:30"), repo.requests)
    }
    @Test fun analyticsEmptyRosterNeverRequestsUsage() {
        val repo = Repository().apply { profiles = emptyList() }
        val vm = HostInsightsViewModel(repo)
        vm.open(HostInsightsPage.ANALYTICS)
        assertTrue(vm.state.value.analyticsRosterEmpty)
        assertTrue(repo.requests.isEmpty())
    }
    @Test fun lateAnalyticsProfileResultCannotReplaceNewChoice() = runBlocking {
        val old = CompletableDeferred<InsightsRead<HostUsage>>()
        val repo = object : Repository() {
            override suspend fun analyticsUsage(roster: AnalyticsRoster, profile: String, days: Int): InsightsRead<HostUsage> =
                if (profile == "default") withContext(NonCancellable) { old.await() }
                else InsightsRead.Ready(HostUsage(days, listOf(Metric("Sessions", 2.0)), emptyList(), emptyList(), emptyList(), emptyList(), emptyList()))
        }
        val vm = HostInsightsViewModel(repo)
        vm.open(HostInsightsPage.ANALYTICS)
        vm.analyticsProfile("work")
        old.complete(InsightsRead.Ready(HostUsage(30, listOf(Metric("Sessions", 1.0)), emptyList(), emptyList(), emptyList(), emptyList(), emptyList())))
        assertEquals("work", vm.state.value.profile)
        assertEquals(2.0, vm.state.value.usage!!.totals.single().value)
    }
    @Test fun nonCooperativeLateReadCannotReplaceNewerFilter() = runBlocking {
        val old = CompletableDeferred<InsightsRead<HostLogTail>>()
        val repo = object : Repository() {
            override suspend fun logs(scope: InsightsScope, file: HostLogFile, level: HostLogLevel): InsightsRead<HostLogTail> =
                if (file == HostLogFile.GATEWAY) withContext(NonCancellable) { old.await() } else InsightsRead.Ready(HostLogTail(listOf("new synthetic")))
        }
        val vm = HostInsightsViewModel(repo)
        vm.open(HostInsightsPage.LOGS); assertTrue(vm.state.value.loading)
        vm.file(HostLogFile.AGENT)
        old.complete(InsightsRead.Ready(HostLogTail(listOf("old synthetic"))))
        assertEquals(listOf("new synthetic"), vm.state.value.logs!!.lines)
    }
    @Test fun scopeChangeAndDisposalDiscardLateContent() = runBlocking {
        val pending = CompletableDeferred<InsightsRead<HostLogTail>>()
        val repo = object : Repository() {
            override suspend fun logs(scope: InsightsScope, file: HostLogFile, level: HostLogLevel) = withContext(NonCancellable) { pending.await() }
        }
        val vm = HostInsightsViewModel(repo); vm.open(HostInsightsPage.LOGS)
        repo.current = false; pending.complete(InsightsRead.Ready(HostLogTail(listOf("synthetic"))))
        assertNull(vm.state.value.logs); assertEquals(InsightsFailure.STALE, vm.state.value.failure)
        vm.leave(); assertNull(vm.state.value.fetchedAt)
    }
    @Test fun leavingPageClearsAndRejectsNonCooperativeLateRead() = runBlocking {
        val pending = CompletableDeferred<InsightsRead<HostLogTail>>()
        val repo = object : Repository() {
            override suspend fun logs(scope: InsightsScope, file: HostLogFile, level: HostLogLevel) = withContext(NonCancellable) { pending.await() }
        }
        val vm = HostInsightsViewModel(repo); vm.open(HostInsightsPage.LOGS)
        vm.search("synthetic"); vm.leave()
        pending.complete(InsightsRead.Ready(HostLogTail(listOf("synthetic"))))
        assertNull(vm.state.value.logs); assertNull(vm.state.value.profile)
        assertEquals("", vm.state.value.query); assertFalse(vm.state.value.loading)
    }
    @Test fun failuresAreSafeAndRetryWorks() {
        val repo = object : Repository() {
            var fail = true
            override suspend fun logs(scope: InsightsScope, file: HostLogFile, level: HostLogLevel): InsightsRead<HostLogTail> {
                if (fail) throw IllegalStateException("synthetic private exception")
                return super.logs(scope, file, level)
            }
        }
        val vm = HostInsightsViewModel(repo); vm.open(HostInsightsPage.LOGS)
        assertEquals(InsightsFailure.UNAVAILABLE, vm.state.value.failure)
        assertFalse(vm.state.value.toString().contains("synthetic"))
        repo.fail = false; vm.refresh(); assertNull(vm.state.value.failure); assertNotNull(vm.state.value.logs)
    }
    @Test fun copyProjectionPreservesWhitespaceAndOriginalLineNumbers() {
        val lines = listOf("  synthetic\t ", "", "synthetic\ncontinuation", "unmatched")
        assertEquals(lines.joinToString("\n"), HostLogProjection.copy(HostLogProjection.visible(lines, "")))
        val filtered = HostLogProjection.visible(lines, "SYnTHETIC")
        assertEquals(listOf(1, 3), filtered.map { it.index })
        assertEquals("  synthetic\t \nsynthetic\ncontinuation", HostLogProjection.copy(filtered))
        assertEquals("", HostLogProjection.copy(HostLogProjection.visible(lines, "missing")))
    }
    @Test fun copyProjectionKeepsHostProvidedNewlinesWithoutDuplicatingSeparators() {
        val lines = listOf("  synthetic first\n", "\n", "synthetic last\r\n")
        assertEquals("  synthetic first\n\nsynthetic last\r\n", HostLogProjection.copy(HostLogProjection.visible(lines, "")))
        assertEquals("  synthetic first\nsynthetic last\r\n", HostLogProjection.copy(HostLogProjection.visible(lines, "synthetic")))
        assertEquals("synthetic last\r\n", HostLogProjection.copy(HostLogProjection.visible(lines, "last")))
    }
    @Test fun missingMetricsDifferFromZeroAndCostsHaveSeparateUnits() {
        assertEquals("Unavailable", formatMetric(Metric("", null)))
        assertEquals("0", formatMetric(Metric("", 0.0)))
        assertEquals("$1.2500", formatMetric(Metric("", 1.25, "USD")))
        assertEquals("1d 1h 0m", formatMetric(Metric("", 90000.0, "seconds")))
    }
}
