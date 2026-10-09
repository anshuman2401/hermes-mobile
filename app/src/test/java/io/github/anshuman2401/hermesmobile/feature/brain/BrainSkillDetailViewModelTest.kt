package io.github.anshuman2401.hermesmobile.feature.brain

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import io.github.anshuman2401.hermesmobile.data.brain.BrainSkillsContentFailureReason
import io.github.anshuman2401.hermesmobile.data.brain.BrainSkillsContentResult
import io.github.anshuman2401.hermesmobile.test.MainDispatcherRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BrainSkillDetailViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private val identity = BrainSkillDetailIdentity(7L, "demo-one", "default")

    @Test
    fun initialReadDistinguishesEmptyAndWhitespaceDocuments() = runTest(main.testDispatcher) {
        val empty = BrainSkillDetailViewModel(
            identity,
            readContent = { _, _ -> BrainSkillsContentResult.Available("default", "demo-one", "") },
            workDispatcher = main.testDispatcher
        )
        advanceUntilIdle()
        assertTrue(empty.uiState.value.state is BrainSkillDetailState.Empty)

        val whitespace = BrainSkillDetailViewModel(
            identity,
            readContent = { _, _ -> BrainSkillsContentResult.Available("default", "demo-one", " \n") },
            workDispatcher = main.testDispatcher
        )
        advanceUntilIdle()
        assertTrue(whitespace.uiState.value.state is BrainSkillDetailState.Document)
    }

    @Test
    fun staleIdentityIsUnavailableAndDoesNotRetryObsoleteSnapshot() = runTest(main.testDispatcher) {
        var calls = 0
        val viewModel = BrainSkillDetailViewModel(
            identity,
            readContent = { _, _ ->
                calls++
                BrainSkillsContentResult.Unavailable(BrainSkillsContentFailureReason.STALE)
            },
            workDispatcher = main.testDispatcher
        )
        advanceUntilIdle()
        viewModel.retry()
        advanceUntilIdle()
        assertEquals(1, calls)
        assertTrue(viewModel.uiState.value.state is BrainSkillDetailState.Unavailable)
    }

    @Test
    fun cancellationAndLateResultCannotPublishDocument() = runTest(main.testDispatcher) {
        val result = CompletableDeferred<BrainSkillsContentResult>()
        val detailStore = ViewModelStore()
        val detailFactory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = BrainSkillDetailViewModel(
                identity,
                readContent = { _, _ -> result.await() },
                workDispatcher = main.testDispatcher
            ) as T
        }
        val viewModel = ViewModelProvider(detailStore, detailFactory).get(
            "test-skill-detail",
            BrainSkillDetailViewModel::class.java
        )
        detailStore.clear()
        result.complete(BrainSkillsContentResult.Available("default", "demo-one", "late"))
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.state is BrainSkillDetailState.Loading)
    }
}
