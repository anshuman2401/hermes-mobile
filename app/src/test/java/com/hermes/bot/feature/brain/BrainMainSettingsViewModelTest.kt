package com.hermes.bot.feature.brain

import com.hermes.bot.data.brain.BrainMainModel
import com.hermes.bot.data.brain.BrainMainSettingsApplyResult
import com.hermes.bot.data.brain.BrainMainSettingsDraft
import com.hermes.bot.data.brain.BrainMainSettingsFailure
import com.hermes.bot.data.brain.BrainMainSettingsFailureReason
import com.hermes.bot.data.brain.BrainMainSettingsLoadResult
import com.hermes.bot.data.brain.BrainMainSettingsSnapshot
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
class BrainMainSettingsViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    @Test
    fun cancelDraftNoWrite() = runTest(main.testDispatcher) {
        val writes = mutableListOf<BrainMainSettingsDraft>()
        val snapshot = snapshot()
        val viewModel = viewModel(
            snapshot = snapshot,
            apply = { _, draft ->
                writes += draft
                BrainMainSettingsApplyResult.Saved(snapshot)
            }
        )
        advanceUntilIdle()

        viewModel.openModelDialog()
        viewModel.selectModel("provider-b", "model-b")
        viewModel.selectReasoning("high")
        viewModel.dismissModelDialog()
        viewModel.openModelDialog()

        val dialog = viewModel.uiState.value.dialog ?: error("dialog missing")
        assertEquals("provider-a", dialog.draft.provider)
        assertEquals("model-a", dialog.draft.model)
        assertEquals("medium", dialog.draft.reasoningEffort)
        assertTrue(writes.isEmpty())
    }

    @Test
    fun savingBlocksDismissEditAndDuplicateApply() = runTest(main.testDispatcher) {
        val result = CompletableDeferred<BrainMainSettingsApplyResult>()
        var applyCalls = 0
        val snapshot = snapshot()
        val viewModel = viewModel(
            snapshot = snapshot,
            apply = { _, _ ->
                applyCalls++
                result.await()
            }
        )
        advanceUntilIdle()
        viewModel.openModelDialog()
        viewModel.selectModel("provider-b", "model-b")
        val draftBeforeSave = viewModel.uiState.value.dialog!!.draft

        viewModel.apply()
        viewModel.selectModel("provider-a", "model-a")
        viewModel.dismissModelDialog()
        viewModel.apply()

        assertEquals(1, applyCalls)
        assertTrue(viewModel.uiState.value.dialog!!.saving)
        assertEquals(draftBeforeSave, viewModel.uiState.value.dialog!!.draft)

        result.complete(BrainMainSettingsApplyResult.Saved(snapshot))
        advanceUntilIdle()
        assertNull(viewModel.uiState.value.dialog)
        assertEquals("Model settings saved", viewModel.uiState.value.feedback)
    }

    @Test
    fun uncertainApplyCannotRetryOldSnapshotAfterApplyOrCancelReopen() = runTest(main.testDispatcher) {
        val loaded = snapshot()

        listOf(
            BrainMainSettingsFailureReason.READBACK_UNCONFIRMED,
            BrainMainSettingsFailureReason.STALE
        ).forEach { reason ->
            var applyCalls = 0
            val attemptedSnapshotIds = mutableListOf<Long>()
            val viewModel = viewModel(
                snapshot = loaded,
                apply = { snapshotId, _ ->
                    applyCalls++
                    attemptedSnapshotIds += snapshotId
                    BrainMainSettingsApplyResult.Failed(
                        BrainMainSettingsFailure(reason)
                    )
                }
            )
            advanceUntilIdle()

            viewModel.openModelDialog()
            viewModel.selectModel("provider-b", "model-b")
            viewModel.apply()
            advanceUntilIdle()

            viewModel.apply()
            advanceUntilIdle()
            assertEquals(1, applyCalls)

            viewModel.dismissModelDialog()
            viewModel.openModelDialog()
            viewModel.apply()
            advanceUntilIdle()

            assertEquals(1, applyCalls)
            assertEquals(listOf(loaded.snapshotId), attemptedSnapshotIds)
        }
    }

    @Test
    fun failedReloadBlocksStaleApplyThenSuccessfulReloadUsesFreshSnapshot() = runTest(main.testDispatcher) {
        val loaded = snapshot()
        val refreshed = loaded.copy(
            snapshotId = 8L,
            selectedProfile = "personal",
            main = BrainMainModel("provider-a", "model-a", null)
        )
        val loadResults = mutableListOf<BrainMainSettingsLoadResult>(
            BrainMainSettingsLoadResult.Available(loaded),
            BrainMainSettingsLoadResult.Unavailable(
                BrainMainSettingsFailure(BrainMainSettingsFailureReason.STALE)
            ),
            BrainMainSettingsLoadResult.Available(refreshed)
        )
        val attemptedSnapshotIds = mutableListOf<Long>()
        val viewModel = viewModel(
            snapshot = loaded,
            load = { loadResults.removeAt(0) },
            apply = { snapshotId, _ ->
                attemptedSnapshotIds += snapshotId
                if (attemptedSnapshotIds.size == 1) {
                    BrainMainSettingsApplyResult.Failed(
                        BrainMainSettingsFailure(BrainMainSettingsFailureReason.READBACK_UNCONFIRMED)
                    )
                } else {
                    BrainMainSettingsApplyResult.Saved(refreshed)
                }
            }
        )
        advanceUntilIdle()

        viewModel.openModelDialog()
        viewModel.selectModel("provider-b", "model-b")
        viewModel.apply()
        advanceUntilIdle()

        viewModel.load()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.loadState is BrainMainSettingsLoadState.Error)
        viewModel.openModelDialog()
        viewModel.apply()
        advanceUntilIdle()
        assertEquals(listOf(loaded.snapshotId), attemptedSnapshotIds)

        viewModel.load()
        advanceUntilIdle()
        val ready = viewModel.uiState.value.loadState as BrainMainSettingsLoadState.Ready
        assertEquals(refreshed.snapshotId, ready.snapshot.snapshotId)
        assertEquals(refreshed.selectedProfile, ready.snapshot.selectedProfile)

        viewModel.openModelDialog()
        viewModel.selectModel("provider-b", "model-b")
        viewModel.apply()
        advanceUntilIdle()

        assertEquals(listOf(loaded.snapshotId, refreshed.snapshotId), attemptedSnapshotIds)
        assertNull(viewModel.uiState.value.dialog)
    }

    @Test
    fun uncertainConfirmationFailureAlsoBlocksAnotherApply() = runTest(main.testDispatcher) {
        val loaded = snapshot()
        var applyCalls = 0
        var confirmationCalls = 0
        val viewModel = viewModel(
            snapshot = loaded,
            apply = { _, _ ->
                applyCalls++
                BrainMainSettingsApplyResult.ConfirmationRequired(
                    snapshotId = loaded.snapshotId,
                    warning = "Synthetic host warning"
                )
            },
            confirm = { _ ->
                confirmationCalls++
                BrainMainSettingsApplyResult.Failed(
                    BrainMainSettingsFailure(BrainMainSettingsFailureReason.READBACK_UNCONFIRMED)
                )
            }
        )
        advanceUntilIdle()

        viewModel.openModelDialog()
        viewModel.selectModel("provider-b", "model-b")
        viewModel.apply()
        advanceUntilIdle()
        viewModel.confirmWarning()
        advanceUntilIdle()

        viewModel.apply()
        advanceUntilIdle()

        assertEquals(1, applyCalls)
        assertEquals(1, confirmationCalls)
    }

    @Test
    fun partialOutcomeUpdatesConfirmedSnapshotAndKeepsFailedDraftEditable() = runTest(main.testDispatcher) {
        val loaded = snapshot()
        val confirmedMain = loaded.copy(
            snapshotId = 8L,
            main = BrainMainModel("provider-b", "model-b", null),
            reasoningEffort = "medium"
        )
        var applyCalls = 0
        val attemptedSnapshotIds = mutableListOf<Long>()
        val viewModel = viewModel(
            snapshot = loaded,
            apply = { snapshotId, _ ->
                applyCalls++
                attemptedSnapshotIds += snapshotId
                if (applyCalls == 1) {
                    BrainMainSettingsApplyResult.Partial(
                        snapshot = confirmedMain,
                        mainSaved = true,
                        reasoningSaved = false
                    )
                } else {
                    BrainMainSettingsApplyResult.Saved(confirmedMain)
                }
            }
        )
        advanceUntilIdle()
        viewModel.openModelDialog()
        viewModel.selectModel("provider-b", "model-b")
        viewModel.selectReasoning("high")
        viewModel.apply()
        advanceUntilIdle()

        val dialog = viewModel.uiState.value.dialog ?: error("partial save must keep dialog open")
        assertEquals("provider-b", dialog.snapshot.main?.provider)
        assertEquals("model-b", dialog.snapshot.main?.model)
        assertEquals("high", dialog.draft.reasoningEffort)
        assertEquals("Model saved; reasoning not saved", viewModel.uiState.value.feedback)

        viewModel.selectReasoning("medium")
        viewModel.apply()
        advanceUntilIdle()

        assertEquals(2, applyCalls)
        assertEquals(listOf(loaded.snapshotId, confirmedMain.snapshotId), attemptedSnapshotIds)
        assertNull(viewModel.uiState.value.dialog)
    }

    @Test
    fun warningRequiresExplicitOneUseConfirmationAndCancelDoesNotWrite() = runTest(main.testDispatcher) {
        val loaded = snapshot()
        var confirmationCalls = 0
        val viewModel = viewModel(
            snapshot = loaded,
            apply = { _, _ ->
                BrainMainSettingsApplyResult.ConfirmationRequired(
                    snapshotId = loaded.snapshotId,
                    warning = "Synthetic host warning"
                )
            },
            confirm = { _ ->
                confirmationCalls++
                BrainMainSettingsApplyResult.Saved(loaded)
            }
        )
        advanceUntilIdle()
        viewModel.openModelDialog()
        viewModel.selectModel("provider-b", "model-b")
        viewModel.apply()
        advanceUntilIdle()

        assertEquals("Synthetic host warning", viewModel.uiState.value.dialog?.confirmationWarning)
        viewModel.declineWarning()
        assertNull(viewModel.uiState.value.dialog?.confirmationWarning)
        assertEquals(0, confirmationCalls)

        viewModel.apply()
        advanceUntilIdle()
        viewModel.confirmWarning()
        advanceUntilIdle()
        assertEquals(1, confirmationCalls)
        assertNull(viewModel.uiState.value.dialog)
    }

    private fun viewModel(
        snapshot: BrainMainSettingsSnapshot,
        apply: suspend (Long, BrainMainSettingsDraft) -> BrainMainSettingsApplyResult,
        load: suspend () -> BrainMainSettingsLoadResult = {
            BrainMainSettingsLoadResult.Available(snapshot)
        },
        confirm: suspend (Long) -> BrainMainSettingsApplyResult = {
            BrainMainSettingsApplyResult.Failed(
                BrainMainSettingsFailure(BrainMainSettingsFailureReason.CONFIRMATION_REJECTED)
            )
        }
    ) = BrainMainSettingsViewModel(
        loadSettings = load,
        applySettings = apply,
        confirmMainWarning = confirm,
        readSelectedProfile = { "work" }
    )

    private fun snapshot(): BrainMainSettingsSnapshot = BrainMainSettingsSnapshot(
        snapshotId = 7L,
        selectedProfile = "work",
        main = BrainMainModel("provider-a", "model-a", null),
        providers = listOf(
            BrainModelProvider(
                slug = "provider-a",
                models = listOf("model-a"),
                authenticated = true,
                capabilities = mapOf("model-a" to BrainModelCapability(true, true))
            ),
            BrainModelProvider(
                slug = "provider-b",
                models = listOf("model-b"),
                authenticated = true,
                capabilities = mapOf("model-b" to BrainModelCapability(null, null))
            )
        ),
        reasoningEffort = "medium"
    )
}
