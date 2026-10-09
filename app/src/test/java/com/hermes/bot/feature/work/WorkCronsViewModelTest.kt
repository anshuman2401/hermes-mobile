package com.hermes.bot.feature.work

import com.hermes.bot.data.context.*
import com.hermes.bot.data.cron.*
import com.hermes.bot.test.MainDispatcherRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WorkCronsViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private fun job(id: String = "synthetic-job", profile: String = "alpha", enabled: Boolean = true) =
        CronJob(id, profile, "Synthetic job", "Synthetic instructions", "0 9 * * *", "Daily at 09:00", enabled, "2030-01-01T09:00:00+00:00", null)

    private class Repo : CronRepository {
        var jobs = emptyList<CronJob>()
        var listCalls = 0
        var listFailure: CronFailure? = null
        var failuresRemaining: Int = Int.MAX_VALUE
        var cooperativeRead = false
        var cancelledReads = 0
        var writes = 0
        var result: CronResult<Unit> = CronResult.Success(Unit)
        var pending: CompletableDeferred<CronResult<Unit>>? = null
        var listPending: CompletableDeferred<CronResult<List<CronJob>>>? = null
        override suspend fun list(): CronResult<List<CronJob>> {
            listCalls++
            if (listFailure != null && failuresRemaining-- > 0) return CronResult.Failure(listFailure!!)
            return listPending?.let {
                if (cooperativeRead) try { it.await() } finally {
                    if (!currentCoroutineContext().isActive) cancelledReads++
                } else withContext(NonCancellable) { it.await() }
            } ?: CronResult.Success(jobs)
        }
        override suspend fun runs(job: CronJob) = CronResult.Success(listOf(CronRun(1.0, false)))
        override suspend fun save(original: CronJob?, profile: String, name: String, prompt: String, schedule: String): CronResult<Unit> {
            writes++
            return pending?.await() ?: result
        }
        override suspend fun act(job: CronJob, action: CronAction): CronResult<Unit> { writes++; return result }
    }

    private fun vm(repo: Repo, source: RosterSource = RosterSource.LIVE): WorkCronsViewModel {
        val roster = object : ContextProfileRepository by FakeContextProfileRepository() {
            override suspend fun getProfiles() = ContextProfilesResult.Success(listOf(ContextProfile("alpha"), ContextProfile("beta"), ContextProfile("empty")), "alpha", source = source, capturedAtMillis = if (source == RosterSource.CACHED) 1000L else null)
        }
        return WorkCronsViewModel(repo, roster).also { it.connectionChanged(true, "synthetic-connection"); it.pageStarted() }
    }
    private fun draft(vm: WorkCronsViewModel) {
        vm.create()
        vm.updateDraft(CronDraft(profile = "alpha", name = "Synthetic job", prompt = "Synthetic instructions"))
    }

    @Test fun allProfilesDefaultAndFiltersAreLocal() {
        val repo = Repo().apply { jobs = listOf(job(), job("other", "beta", false)) }
        val vm = vm(repo)
        assertNull(vm.uiState.value.profile)
        assertEquals(2, vm.uiState.value.visibleJobs.size)
        assertEquals(listOf("alpha", "beta", "empty"), vm.uiState.value.profiles)
        vm.filterProfile("beta")
        vm.filterStatus(CronStatusFilter.PAUSED)
        assertEquals(listOf("beta"), vm.uiState.value.visibleJobs.map { it.profile })
        vm.filterProfile("empty")
        assertTrue(vm.uiState.value.visibleJobs.isEmpty())
        assertEquals(1, repo.listCalls)
    }

    @Test fun cachedProfileRosterRetainsItsSourceAndCaptureTime() {
        val repo = Repo().apply { jobs = listOf(job()) }
        val vm = vm(repo, RosterSource.CACHED)
        assertTrue(vm.uiState.value.rosterCached)
        assertEquals(1000L, vm.uiState.value.rosterCapturedAtMillis)
        assertEquals(1, vm.uiState.value.jobs.size)
        assertFalse(vm.uiState.value.rosterUnavailable)
    }

    @Test fun creationRequiresExplicitProfileUnderAllAndPreselectsFilteredProfile() {
        val vm = vm(Repo())
        vm.create()
        assertEquals("", vm.uiState.value.draft!!.profile)
        assertFalse(vm.uiState.value.draft!!.valid())
        vm.back()
        vm.filterProfile("beta")
        vm.create()
        assertEquals("beta", vm.uiState.value.draft!!.profile)
    }

    @Test fun duplicateSaveIsBlockedAndSuccessRefreshesWithoutAnotherWrite() = runBlocking {
        val repo = Repo().apply { pending = CompletableDeferred() }
        val vm = vm(repo)
        draft(vm)
        vm.save(); vm.save(); vm.refresh()
        assertEquals(1, repo.writes)
        assertEquals(1, repo.listCalls)
        repo.pending!!.complete(CronResult.Success(Unit))
        assertEquals(2, repo.listCalls)
        assertNull(vm.uiState.value.draft)
    }

    @Test fun ambiguousWriteLocksActionsUntilExplicitReadAndNeverResends() {
        val repo = Repo().apply { result = CronResult.Failure(CronFailure.UNKNOWN) }
        val vm = vm(repo)
        draft(vm)
        vm.save(); vm.save(); vm.create()
        assertEquals(1, repo.writes)
        assertTrue(vm.uiState.value.needsReconciliation)
        assertFalse(vm.uiState.value.canManage)
        assertNull(vm.uiState.value.draft)
        vm.refresh()
        assertFalse(vm.uiState.value.needsReconciliation)
        assertEquals(1, repo.writes)
    }

    @Test fun validationFailureRetainsDraft() {
        val repo = Repo().apply { result = CronResult.Failure(CronFailure.INVALID) }
        val vm = vm(repo)
        draft(vm)
        val before = vm.uiState.value.draft
        vm.save()
        assertEquals(before, vm.uiState.value.draft)
        assertEquals(CronFailure.INVALID, vm.uiState.value.failure)
    }

    @Test fun deletionRequiresConfirmationAndDoesNotAffectOtherJobs() {
        val repo = Repo().apply { jobs = listOf(job()) }
        val vm = vm(repo)
        vm.open(repo.jobs.first())
        vm.act(CronAction.DELETE)
        assertEquals(0, repo.writes)
        vm.confirmDelete(); vm.act(CronAction.DELETE)
        assertEquals(1, repo.writes)
    }

    @Test fun staleNonCooperativeReadCannotPublishAcrossConnectionSwitch() = runBlocking {
        val repo = Repo()
        val vm = vm(repo)
        repo.listPending = CompletableDeferred()
        vm.refresh()
        val oldRead = repo.listPending!!
        repo.listPending = null
        vm.connectionChanged(true, "other-synthetic-connection")
        oldRead.complete(CronResult.Success(listOf(job())))
        assertTrue(vm.uiState.value.jobs.isEmpty())
        assertFalse(vm.uiState.value.loading)
    }

    @Test fun disconnectDuringWritePreservesUncertaintyButClearsEditor() {
        val repo = Repo().apply { pending = CompletableDeferred() }
        val vm = vm(repo)
        draft(vm); vm.save()
        vm.connectionChanged(false, "synthetic-connection")
        assertTrue(vm.uiState.value.needsReconciliation)
        assertNull(vm.uiState.value.draft)
        assertFalse(vm.uiState.value.canManage)
        assertEquals(1, repo.writes)
    }

    private fun advance(millis: Long) {
        main.testDispatcher.scheduler.advanceTimeBy(millis)
        main.testDispatcher.scheduler.runCurrent()
    }

    @Test fun firstLoadRecoversWithoutNavigationOrConnectionEdge() {
        val repo = Repo().apply {
            jobs = listOf(job())
            listFailure = CronFailure.UNAVAILABLE
            failuresRemaining = 1
        }
        val vm = vm(repo)
        assertEquals(1, repo.listCalls)
        assertTrue(vm.uiState.value.loading)
        assertEquals(1, vm.uiState.value.retryAttempt)
        assertFalse(vm.uiState.value.needsReconciliation)
        assertFalse(vm.uiState.value.canManage)
        advance(999)
        assertEquals(1, repo.listCalls)
        advance(1)
        assertEquals(2, repo.listCalls)
        assertEquals(repo.jobs, vm.uiState.value.jobs)
        assertTrue(vm.uiState.value.hasLoadedJobs)
        assertTrue(vm.uiState.value.canManage)
        assertNull(vm.uiState.value.failure)
        advance(10_000)
        assertEquals(2, repo.listCalls)
    }

    @Test fun exactBackoffExhaustsAndManualRetryStartsNewBudget() {
        val repo = Repo().apply { listFailure = CronFailure.UNAVAILABLE }
        val vm = vm(repo)
        vm.pageStarted(); vm.refresh()
        assertEquals(1, repo.listCalls)
        advance(1_000)
        assertEquals(2, repo.listCalls)
        advance(1_999)
        assertEquals(2, repo.listCalls)
        advance(1)
        assertEquals(3, repo.listCalls)
        advance(3_999)
        assertEquals(3, repo.listCalls)
        advance(1)
        assertEquals(4, repo.listCalls)
        assertFalse(vm.uiState.value.loading)
        assertEquals(0, vm.uiState.value.retryAttempt)
        assertEquals(CronFailure.UNAVAILABLE, vm.uiState.value.failure)
        advance(100_000)
        assertEquals(4, repo.listCalls)
        vm.pageStarted() // Recomposition does not restart an exhausted cycle.
        assertEquals(4, repo.listCalls)
        repo.failuresRemaining = 1
        vm.refresh()
        assertEquals(5, repo.listCalls)
        advance(1_000)
        assertEquals(6, repo.listCalls)
        assertTrue(vm.uiState.value.canManage)
    }

    @Test fun exitOrBackgroundCancelsRetryAndResumeStartsFreshRead() {
        val repo = Repo().apply { listFailure = CronFailure.UNAVAILABLE }
        val vm = vm(repo)
        vm.pageStopped()
        assertFalse(vm.uiState.value.loading)
        advance(10_000)
        assertEquals(1, repo.listCalls)
        repo.listFailure = null
        vm.pageStarted()
        assertEquals(2, repo.listCalls)
        assertTrue(vm.uiState.value.canManage)
        vm.pageStarted()
        assertEquals(2, repo.listCalls)
    }

    @Test fun disconnectCancelsRetryAndReconnectRefreshesOnTheSamePage() {
        val repo = Repo().apply { listFailure = CronFailure.UNAVAILABLE }
        val vm = vm(repo)
        vm.connectionChanged(false, "synthetic-connection")
        advance(10_000)
        assertEquals(1, repo.listCalls)
        assertFalse(vm.uiState.value.canManage)
        repo.listFailure = null
        vm.connectionChanged(true, "synthetic-connection")
        assertEquals(2, repo.listCalls)
        assertTrue(vm.uiState.value.canManage)
    }

    @Test fun switchingConnectionCancelsOldBudgetAndClearsOldData() {
        val repo = Repo().apply { jobs = listOf(job()) }
        val vm = vm(repo)
        repo.listFailure = CronFailure.UNAVAILABLE
        vm.refresh()
        repo.listFailure = null
        repo.jobs = emptyList()
        vm.connectionChanged(true, "other-synthetic-connection")
        assertEquals(3, repo.listCalls)
        advance(10_000)
        assertEquals(3, repo.listCalls)
        assertTrue(vm.uiState.value.jobs.isEmpty())
    }

    @Test fun recoveryPreservesListEditorSelectionAndFilters() {
        val repo = Repo().apply { jobs = listOf(job()) }
        val vm = vm(repo)
        vm.filterProfile("alpha")
        vm.filterStatus(CronStatusFilter.ACTIVE)
        vm.open(repo.jobs.first())
        vm.edit()
        val draft = vm.uiState.value.draft!!.copy(name = "Synthetic retained edit")
        vm.updateDraft(draft)
        repo.listFailure = CronFailure.UNAVAILABLE
        repo.failuresRemaining = 1
        vm.refresh()
        assertEquals(repo.jobs, vm.uiState.value.jobs)
        assertEquals(draft, vm.uiState.value.draft)
        assertFalse(vm.uiState.value.canManage)
        advance(1_000)
        assertEquals(draft, vm.uiState.value.draft)
        assertEquals(repo.jobs.first(), vm.uiState.value.selected)
        assertEquals("alpha", vm.uiState.value.profile)
        assertEquals(CronStatusFilter.ACTIVE, vm.uiState.value.status)
        assertTrue(vm.uiState.value.canManage)
    }

    @Test fun terminalReadFailuresNeverRetryOnResume() {
        for (failure in listOf(CronFailure.AUTH_REQUIRED, CronFailure.UNSUPPORTED, CronFailure.INVALID, CronFailure.NOT_FOUND, CronFailure.STALE, CronFailure.UNKNOWN)) {
            val repo = Repo().apply { listFailure = failure }
            val vm = vm(repo)
            advance(10_000)
            vm.pageStopped(); vm.pageStarted()
            assertEquals(failure.toString(), 1, repo.listCalls)
            assertEquals(failure, vm.uiState.value.failure)
            vm.pageStopped()
        }
    }

    @Test fun ambiguousWriteStaysLockedAcrossReconnectAndReentryUntilExplicitRefresh() {
        val repo = Repo().apply { result = CronResult.Failure(CronFailure.UNKNOWN) }
        val vm = vm(repo)
        draft(vm); vm.save()
        vm.pageStopped(); vm.pageStarted()
        vm.connectionChanged(false, "synthetic-connection")
        vm.connectionChanged(true, "synthetic-connection")
        advance(10_000)
        assertEquals(1, repo.listCalls)
        assertEquals(1, repo.writes)
        assertTrue(vm.uiState.value.needsReconciliation)
        assertFalse(vm.uiState.value.canManage)
        repo.listFailure = CronFailure.UNAVAILABLE
        vm.refresh()
        advance(7_000)
        assertTrue(vm.uiState.value.needsReconciliation)
        assertFalse(vm.uiState.value.canManage)
        repo.listFailure = null
        vm.refresh()
        assertFalse(vm.uiState.value.needsReconciliation)
        assertTrue(vm.uiState.value.canManage)
        assertEquals(1, repo.writes)
    }

    @Test fun stoppedPageRejectsLateNonCooperativeRead() = runBlocking {
        val repo = Repo()
        val vm = vm(repo)
        val oldRead = CompletableDeferred<CronResult<List<CronJob>>>()
        repo.listPending = oldRead
        vm.refresh()
        vm.pageStopped()
        oldRead.complete(CronResult.Success(listOf(job())))
        assertTrue(vm.uiState.value.jobs.isEmpty())
        assertFalse(vm.uiState.value.loading)
    }

    @Test fun backgroundDoesNotStartPostMutationReadsOrRepeatWrites() = runBlocking {
        val repo = Repo().apply { pending = CompletableDeferred() }
        val vm = vm(repo)
        draft(vm); vm.save(); vm.pageStopped()
        repo.pending!!.complete(CronResult.Success(Unit))
        assertEquals(1, repo.writes)
        assertEquals(1, repo.listCalls)
        assertFalse(vm.uiState.value.canManage)
        vm.pageStarted()
        assertEquals(2, repo.listCalls)
        assertTrue(vm.uiState.value.canManage)
    }

    @Test fun immediateMutationSuccessKeepsItsFollowupReadOwnedForCancellation() {
        val repo = Repo()
        val vm = vm(repo)
        draft(vm)
        repo.cooperativeRead = true
        repo.listPending = CompletableDeferred()
        vm.save() // Success starts a new read before the mutation coroutine returns.
        assertEquals(2, repo.listCalls)
        assertTrue(vm.uiState.value.loading)
        vm.pageStopped()
        assertEquals(1, repo.cancelledReads)
        assertFalse(vm.uiState.value.loading)
        assertEquals(1, repo.writes)
    }

    @Test fun presetValidationAndExpressions() {
        val base = CronDraft(profile = "alpha", name = "Synthetic", prompt = "Synthetic")
        assertTrue(base.valid())
        assertEquals("0 9 * * *", base.schedule())
        assertEquals("15 * * * *", base.copy(preset = SchedulePreset.HOURLY, minute = "15").schedule())
        assertEquals("0 9 * * 5", base.copy(preset = SchedulePreset.WEEKLY, day = 5).schedule())
        assertFalse(base.copy(hour = "24").valid())
        assertFalse(base.copy(minute = "60").valid())
        assertFalse(base.copy(preset = SchedulePreset.ADVANCED, custom = "bad").valid())
        assertTrue(base.copy(preset = SchedulePreset.ADVANCED, custom = "*/15 * * * MON-FRI").valid())
    }
}
