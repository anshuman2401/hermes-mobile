package com.hermes.bot.feature.brain

import com.hermes.bot.data.brain.BrainAuxiliaryAssignment
import com.hermes.bot.data.brain.BrainAuxiliaryDraft
import com.hermes.bot.data.brain.BrainAuxiliaryModelsApplyFailureReason
import com.hermes.bot.data.brain.BrainAuxiliaryModelsApplyResult
import com.hermes.bot.data.brain.BrainAuxiliaryModelsLoadResult
import com.hermes.bot.data.brain.BrainAuxiliaryModelsSnapshot
import com.hermes.bot.data.brain.BrainAuxiliaryTask
import com.hermes.bot.data.brain.BrainAuxiliaryTaskRow
import com.hermes.bot.data.brain.BrainModelCapability
import com.hermes.bot.data.brain.BrainModelProvider
import com.hermes.bot.test.MainDispatcherRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BrainAuxiliaryModelsViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    @Test
    fun allElevenRowsAndPartialAssignmentsRemainHonest() = runTest(main.testDispatcher) {
        val snapshot = snapshot(partial = true)
        val viewModel = viewModel(snapshot = snapshot)
        advanceUntilIdle()

        val state = viewModel.uiState.value.loadState as BrainAuxiliaryModelsLoadState.Ready
        assertTrue(state.partial)
        assertEquals(BrainAuxiliaryTask.entries, state.snapshot.tasks.map { it.task })
        assertNull(state.snapshot.tasks.first { it.task == BrainAuxiliaryTask.Curator }.assignment)
        assertEquals("profile-one", viewModel.uiState.value.selectedProfile)
    }

    @Test
    fun selectionAutoAndDismissDoNotWriteBeforeApply() = runTest(main.testDispatcher) {
        val writes = mutableListOf<Triple<Long, BrainAuxiliaryTask, BrainAuxiliaryDraft>>()
        val snapshot = snapshot()
        val viewModel = viewModel(
            snapshot = snapshot,
            apply = { id, task, draft ->
                writes += Triple(id, task, draft)
                BrainAuxiliaryModelsApplyResult.Saved(snapshot)
            }
        )
        advanceUntilIdle()

        viewModel.openTaskDialog(BrainAuxiliaryTask.Vision)
        viewModel.selectModel("provider-two", "model-two")
        assertEquals(
            BrainAuxiliaryDraft.Concrete("provider-two", "model-two"),
            viewModel.uiState.value.dialog?.draft
        )
        viewModel.returnToAuto()
        viewModel.dismissModelDialog()
        assertTrue(writes.isEmpty())

        viewModel.openTaskDialog(BrainAuxiliaryTask.Vision)
        assertEquals(BrainAuxiliaryDraft.Concrete("provider-one", "model-one"), viewModel.uiState.value.dialog?.draft)
        viewModel.returnToAuto()
        viewModel.apply()
        advanceUntilIdle()

        assertEquals(
            listOf(Triple(snapshot.snapshotId, BrainAuxiliaryTask.Vision, BrainAuxiliaryDraft.Auto)),
            writes
        )
        assertNull(viewModel.uiState.value.dialog)
    }

    @Test
    fun savedAutoWithUnavailableCatalogKeepsPartialStateAndDisablesConcreteChoices() = runTest(main.testDispatcher) {
        val snapshot = snapshot(catalogAvailable = false)
        var applyCalls = 0
        val viewModel = viewModel(
            snapshot = snapshot,
            apply = { _, _, _ ->
                applyCalls++
                BrainAuxiliaryModelsApplyResult.Saved(snapshot)
            }
        )
        advanceUntilIdle()

        val loaded = viewModel.uiState.value.loadState as BrainAuxiliaryModelsLoadState.Ready
        assertTrue(loaded.partial)
        assertFalse(loaded.snapshot.tasks.any { it.assignment == null })
        assertFalse(loaded.snapshot.catalogAvailable)

        viewModel.openTaskDialog(BrainAuxiliaryTask.Vision)
        val initialDraft = viewModel.uiState.value.dialog!!.draft
        viewModel.selectModel("provider-two", "model-two")
        assertEquals(initialDraft, viewModel.uiState.value.dialog!!.draft)

        viewModel.returnToAuto()
        viewModel.apply()
        advanceUntilIdle()

        val saved = viewModel.uiState.value.loadState as BrainAuxiliaryModelsLoadState.Ready
        assertEquals(1, applyCalls)
        assertTrue(saved.partial)
        assertFalse(saved.snapshot.catalogAvailable)
        assertEquals("Auxiliary model saved", viewModel.uiState.value.feedback)
    }

    @Test
    fun savingBlocksDuplicateApplyEditAndDismiss() = runTest(main.testDispatcher) {
        val result = CompletableDeferred<BrainAuxiliaryModelsApplyResult>()
        var calls = 0
        val snapshot = snapshot()
        val viewModel = viewModel(
            snapshot = snapshot,
            apply = { _, _, _ ->
                calls++
                result.await()
            }
        )
        advanceUntilIdle()
        viewModel.openTaskDialog(BrainAuxiliaryTask.Vision)
        viewModel.selectModel("provider-two", "model-two")
        val draft = viewModel.uiState.value.dialog!!.draft

        viewModel.apply()
        viewModel.selectModel("provider-one", "model-one")
        viewModel.dismissModelDialog()
        viewModel.apply()

        assertEquals(1, calls)
        assertTrue(viewModel.uiState.value.dialog!!.saving)
        assertEquals(draft, viewModel.uiState.value.dialog!!.draft)

        result.complete(BrainAuxiliaryModelsApplyResult.Saved(snapshot))
        advanceUntilIdle()
        assertNull(viewModel.uiState.value.dialog)
        assertEquals("Auxiliary model saved", viewModel.uiState.value.feedback)
    }

    @Test
    fun warningIsOneUseAndCancelDoesNotConfirm() = runTest(main.testDispatcher) {
        var confirms = 0
        val snapshot = snapshot()
        val viewModel = viewModel(
            snapshot = snapshot,
            apply = { _, _, _ ->
                BrainAuxiliaryModelsApplyResult.ConfirmationRequired(snapshot.snapshotId, "Synthetic warning")
            },
            confirm = {
                confirms++
                BrainAuxiliaryModelsApplyResult.Saved(snapshot)
            }
        )
        advanceUntilIdle()
        viewModel.openTaskDialog(BrainAuxiliaryTask.Vision)
        viewModel.selectModel("provider-two", "model-two")
        viewModel.apply()
        advanceUntilIdle()

        assertEquals("Synthetic warning", viewModel.uiState.value.dialog?.confirmationWarning)
        viewModel.declineWarning()
        assertEquals(0, confirms)
        assertNull(viewModel.uiState.value.dialog?.confirmationWarning)

        viewModel.apply()
        advanceUntilIdle()
        viewModel.confirmWarning()
        advanceUntilIdle()
        assertEquals(1, confirms)
        assertNull(viewModel.uiState.value.dialog)
    }

    @Test
    fun notConfirmedWithAuthoritativeDisplayInvalidatesUntilSuccessfulReload() = runTest(main.testDispatcher) {
        val loaded = snapshot()
        val observed = loaded.copy(
            snapshotId = 9L,
            tasks = loaded.tasks.map { row ->
                if (row.task == BrainAuxiliaryTask.Vision) {
                    row.copy(assignment = BrainAuxiliaryAssignment(row.task, "provider-two", "model-two"))
                } else row
            }
        )
        val fresh = loaded.copy(snapshotId = 10L)
        val loadResults = ArrayDeque<BrainAuxiliaryModelsLoadResult>()
        loadResults += BrainAuxiliaryModelsLoadResult.Available(loaded)
        loadResults += BrainAuxiliaryModelsLoadResult.Unavailable(
            com.hermes.bot.data.brain.BrainAuxiliaryModelsLoadFailure(
                com.hermes.bot.data.brain.BrainAuxiliaryModelsLoadFailureReason.STALE
            )
        )
        loadResults += BrainAuxiliaryModelsLoadResult.Available(fresh)
        val attempted = mutableListOf<Long>()
        val viewModel = viewModel(
            snapshot = loaded,
            load = { loadResults.removeFirst() },
            apply = { id, _, _ ->
                attempted += id
                BrainAuxiliaryModelsApplyResult.NotConfirmed(
                    BrainAuxiliaryModelsApplyFailureReason.READBACK_MISMATCH,
                    mayHaveSaved = true,
                    authoritativeSnapshot = observed
                )
            }
        )
        advanceUntilIdle()
        viewModel.openTaskDialog(BrainAuxiliaryTask.Vision)
        viewModel.selectModel("provider-two", "model-two")
        viewModel.apply()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.reloadRequired)
        assertEquals(9L, (viewModel.uiState.value.loadState as BrainAuxiliaryModelsLoadState.Ready).snapshot.snapshotId)
        viewModel.dismissModelDialog()
        val afterCancel = viewModel.uiState.value
        assertTrue(afterCancel.reloadRequired)
        assertEquals(
            9L,
            (afterCancel.loadState as BrainAuxiliaryModelsLoadState.Ready).snapshot.snapshotId
        )
        viewModel.openTaskDialog(BrainAuxiliaryTask.Vision)
        assertNull(viewModel.uiState.value.dialog)
        viewModel.apply()
        assertEquals(listOf(loaded.snapshotId), attempted)

        viewModel.load()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.loadState is BrainAuxiliaryModelsLoadState.Error)
        assertTrue(viewModel.uiState.value.reloadRequired)
        viewModel.load()
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.reloadRequired)
        assertEquals(10L, (viewModel.uiState.value.loadState as BrainAuxiliaryModelsLoadState.Ready).snapshot.snapshotId)
    }

    @Test
    fun definiteRejectionLeavesDraftEditable() = runTest(main.testDispatcher) {
        var calls = 0
        val snapshot = snapshot()
        val viewModel = viewModel(
            snapshot = snapshot,
            apply = { _, _, _ ->
                calls++
                if (calls == 1) {
                    BrainAuxiliaryModelsApplyResult.Rejected(
                        BrainAuxiliaryModelsApplyFailureReason.WRITE_REJECTED
                    )
                } else {
                    BrainAuxiliaryModelsApplyResult.Saved(snapshot)
                }
            }
        )
        advanceUntilIdle()
        viewModel.openTaskDialog(BrainAuxiliaryTask.Vision)
        viewModel.selectModel("provider-two", "model-two")
        viewModel.apply()
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.reloadRequired)
        assertTrue(viewModel.uiState.value.dialog != null)
        viewModel.apply()
        advanceUntilIdle()
        assertEquals(2, calls)
        assertNull(viewModel.uiState.value.dialog)
    }

    private fun viewModel(
        snapshot: BrainAuxiliaryModelsSnapshot,
        load: suspend () -> BrainAuxiliaryModelsLoadResult = {
            if (snapshot.catalogAvailable && snapshot.tasks.all { it.assignment != null }) {
                BrainAuxiliaryModelsLoadResult.Available(snapshot)
            } else {
                BrainAuxiliaryModelsLoadResult.Partial(snapshot)
            }
        },
        apply: suspend (Long, BrainAuxiliaryTask, BrainAuxiliaryDraft) -> BrainAuxiliaryModelsApplyResult = { _, _, _ ->
            BrainAuxiliaryModelsApplyResult.Saved(snapshot)
        },
        confirm: suspend (Long) -> BrainAuxiliaryModelsApplyResult = {
            BrainAuxiliaryModelsApplyResult.Rejected(
                BrainAuxiliaryModelsApplyFailureReason.CONFIRMATION_REJECTED
            )
        }
    ) = BrainAuxiliaryModelsViewModel(
        loadSettings = load,
        applySettings = apply,
        confirmAuxiliaryWarning = confirm,
        readSelectedProfile = { "profile-one" }
    )

    private fun snapshot(
        partial: Boolean = false,
        snapshotId: Long = 7L,
        catalogAvailable: Boolean = !partial
    ): BrainAuxiliaryModelsSnapshot {
        val tasks = BrainAuxiliaryTask.entries.map { task ->
            if (partial && task == BrainAuxiliaryTask.Curator) {
                BrainAuxiliaryTaskRow(task, null)
            } else {
                BrainAuxiliaryTaskRow(
                    task,
                    BrainAuxiliaryAssignment(task, "provider-one", "model-one")
                )
            }
        }
        return BrainAuxiliaryModelsSnapshot(
            snapshotId = snapshotId,
            selectedProfile = "profile-one",
            tasks = tasks,
            providers = listOf(
                BrainModelProvider(
                    slug = "provider-one",
                    models = listOf("model-one"),
                    authenticated = true,
                    capabilities = mapOf("model-one" to BrainModelCapability(true, true))
                ),
                BrainModelProvider(
                    slug = "provider-two",
                    models = listOf("model-two"),
                    authenticated = true,
                    capabilities = mapOf("model-two" to BrainModelCapability(true, true))
                )
            ),
            catalogAvailable = catalogAvailable
        )
    }
}
