package io.github.anshuman2401.hermesmobile.feature.brain

import io.github.anshuman2401.hermesmobile.data.brain.BrainInstalledSkill
import io.github.anshuman2401.hermesmobile.data.brain.BrainSkillsFailureReason
import io.github.anshuman2401.hermesmobile.data.brain.BrainSkillsLoadFailure
import io.github.anshuman2401.hermesmobile.data.brain.BrainSkillsLoadResult
import io.github.anshuman2401.hermesmobile.data.brain.BrainSkillsSnapshot
import io.github.anshuman2401.hermesmobile.data.brain.BrainSkillsToggleResult
import io.github.anshuman2401.hermesmobile.test.MainDispatcherRule
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BrainSkillsViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    @Test
    fun profileAndDisabledInventoryRemainVisibleAndRowCallbackIsNotAWrite() = runTest(main.testDispatcher) {
        val snapshot = snapshot(selectedProfile = "worker_alpha", enabled = false)
        var toggles = 0
        val viewModel = BrainSkillsViewModel(
            loadSkills = { BrainSkillsLoadResult.Available(snapshot) },
            toggleSkill = { _, _, _ -> toggles++; BrainSkillsToggleResult.Saved(snapshot) },
            readSelectedProfile = { "worker_alpha" }
        )
        advanceUntilIdle()

        val ready = viewModel.uiState.value.loadState as BrainSkillsLoadState.Ready
        assertEquals("worker_alpha", viewModel.uiState.value.selectedProfile)
        assertEquals(false, ready.snapshot.skills.single().enabled)
        assertEquals(0, toggles)
    }

    @Test
    fun oneBusyMutationKeepsConfirmedStateAndIgnoresDuplicateCallbacks() = runTest(main.testDispatcher) {
        val initial = snapshot(enabled = true)
        val saved = snapshot(id = 2, enabled = false)
        val result = CompletableDeferred<BrainSkillsToggleResult>()
        var calls = 0
        val viewModel = BrainSkillsViewModel(
            loadSkills = { BrainSkillsLoadResult.Available(initial) },
            toggleSkill = { _, _, _ -> calls++; result.await() }
        )
        advanceUntilIdle()

        viewModel.toggle("demo-one", false)
        viewModel.toggle("demo-one", true)
        assertEquals(1, calls)
        assertEquals("demo-one", viewModel.uiState.value.busySkill)
        assertEquals(true, ready(viewModel).snapshot.skills.single().enabled)

        result.complete(BrainSkillsToggleResult.Saved(saved))
        advanceUntilIdle()
        assertEquals(null, viewModel.uiState.value.busySkill)
        assertEquals(false, ready(viewModel).snapshot.skills.single().enabled)
        assertEquals("Skill saved", viewModel.uiState.value.feedback)
    }

    @Test
    fun ordinaryRejectionPreservesConfirmedStateAndNoOpDoesNotNeedAnotherMutation() = runTest(main.testDispatcher) {
        val initial = snapshot(enabled = true)
        var calls = 0
        val viewModel = BrainSkillsViewModel(
            loadSkills = { BrainSkillsLoadResult.Available(initial) },
            toggleSkill = { _, _, _ ->
                calls++
                if (calls == 1) {
                    BrainSkillsToggleResult.Rejected(BrainSkillsFailureReason.WRITE_REJECTED)
                } else {
                    BrainSkillsToggleResult.Saved(initial)
                }
            }
        )
        advanceUntilIdle()
        viewModel.toggle("demo-one", false)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.feedback!!.contains("rejected"))
        assertTrue(ready(viewModel).snapshot.skills.single().enabled)

        viewModel.toggle("demo-one", true)
        advanceUntilIdle()
        assertEquals(2, calls)
        assertFalse(viewModel.uiState.value.reloadRequired)
    }

    @Test
    fun notConfirmedIsDisplayOnlyAndRetryNeedsFreshSnapshot() = runTest(main.testDispatcher) {
        val initial = snapshot(id = 1, enabled = true)
        val observed = snapshot(id = 1, enabled = false)
        val fresh = snapshot(id = 2, enabled = false)
        var loadCalls = 0
        var toggleCalls = 0
        val viewModel = BrainSkillsViewModel(
            loadSkills = {
                loadCalls++
                if (loadCalls == 1) BrainSkillsLoadResult.Available(initial)
                else BrainSkillsLoadResult.Available(fresh)
            },
            toggleSkill = { _, _, _ ->
                toggleCalls++
                BrainSkillsToggleResult.NotConfirmed(
                    BrainSkillsFailureReason.READBACK_MISMATCH,
                    mayHaveSaved = true,
                    authoritativeSnapshot = observed
                )
            }
        )
        advanceUntilIdle()
        viewModel.toggle("demo-one", false)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.reloadRequired)
        assertFalse(ready(viewModel).snapshot.skills.single().enabled)
        viewModel.toggle("demo-one", true)
        assertEquals(1, toggleCalls)

        viewModel.load()
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.reloadRequired)
        assertEquals(2, ready(viewModel).snapshot.snapshotId)
        viewModel.toggle("demo-one", true)
        advanceUntilIdle()
        assertEquals(2, toggleCalls)
    }

    @Test
    fun initialErrorHasReachableRetryAndCancellationClearsBusyAndEligibility() = runTest(main.testDispatcher) {
        var loadCalls = 0
        var cancelToggle = true
        val snapshot = snapshot()
        val viewModel = BrainSkillsViewModel(
            loadSkills = {
                loadCalls++
                if (loadCalls == 1) {
                    BrainSkillsLoadResult.Unavailable(
                        BrainSkillsLoadFailure(BrainSkillsFailureReason.UNAVAILABLE)
                    )
                } else BrainSkillsLoadResult.Available(snapshot)
            },
            toggleSkill = { _, _, _ ->
                if (cancelToggle) {
                    cancelToggle = false
                    throw CancellationException("synthetic cancellation")
                }
                BrainSkillsToggleResult.Saved(snapshot)
            }
        )
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.loadState is BrainSkillsLoadState.Error)
        viewModel.load()
        advanceUntilIdle()
        viewModel.toggle("demo-one", false)
        advanceUntilIdle()
        assertEquals(null, viewModel.uiState.value.busySkill)
        assertTrue(viewModel.uiState.value.reloadRequired)
    }

    private fun ready(viewModel: BrainSkillsViewModel) =
        viewModel.uiState.value.loadState as BrainSkillsLoadState.Ready

    private fun snapshot(
        id: Long = 1,
        enabled: Boolean = true,
        selectedProfile: String = "default"
    ) = BrainSkillsSnapshot(
        snapshotId = id,
        selectedProfile = selectedProfile,
        skills = listOf(BrainInstalledSkill("demo-one", "Demo skill", enabled))
    )
}
