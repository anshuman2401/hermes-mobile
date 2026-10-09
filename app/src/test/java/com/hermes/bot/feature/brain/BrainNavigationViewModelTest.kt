package com.hermes.bot.feature.brain

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.hermes.bot.data.brain.BrainInstalledSkill
import com.hermes.bot.data.brain.BrainSkillsFailureReason
import com.hermes.bot.data.brain.BrainSkillsToggleResult
import com.hermes.bot.data.brain.BrainSkillsLoadResult
import com.hermes.bot.data.brain.BrainSkillsSnapshot
import com.hermes.bot.data.local.SelectedContextReadResult
import com.hermes.bot.test.MainDispatcherRule
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Actual production owner/store behavior; no Compose execution is asserted. */
@OptIn(ExperimentalCoroutinesApi::class)
class BrainNavigationViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    @Test fun typedCaptionLoadingSelectedAbsentUnreadableFailedAndThrown() = runTest(main.testDispatcher) {
        val results = listOf(
            SelectedContextReadResult.Selected("demo-profile") to "Profile: demo-profile",
            SelectedContextReadResult.Absent to "Not selected",
            SelectedContextReadResult.Unreadable to "Profile unavailable",
            SelectedContextReadResult.Failed to "Profile unavailable"
        )
        results.forEach { (result, expected) ->
            val store = ViewModelStore()
            val read = CompletableDeferred<SelectedContextReadResult>()
            var calls = 0
            val owner = brainNavigationOwner(store) { calls++; read.await() }
            try {
                assertEquals("Loading", owner.profileCaption.value)
                read.complete(result)
                advanceUntilIdle()
                assertEquals(expected, owner.profileCaption.value)
                assertSame(owner, brainNavigationOwner(store) { error("must not recreate") })
                assertEquals(1, calls)
            } finally { store.clear() }
        }
        val store = ViewModelStore()
        try {
            val owner = brainNavigationOwner(store) { throw IllegalStateException("synthetic failure") }
            advanceUntilIdle()
            assertEquals("Profile unavailable", owner.profileCaption.value)
        } finally { store.clear() }
    }

    @Test fun captionCancellationPropagatesAndDisposalCancelsPendingRead() = runTest(main.testDispatcher) {
        val cancelledStore = ViewModelStore()
        try {
            val owner = brainNavigationOwner(cancelledStore) { throw CancellationException("synthetic") }
            advanceUntilIdle()
            assertEquals("Loading", owner.profileCaption.value)
        } finally { cancelledStore.clear() }
        val store = ViewModelStore()
        val entered = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        val owner = brainNavigationOwner(store) {
            entered.complete(Unit)
            try { awaitCancellation() } finally { stopped.complete(Unit) }
        }
        entered.await()
        store.clear()
        stopped.await()
        assertEquals("Loading", owner.profileCaption.value)
    }

    @Test fun everyPageIsLazyOncePerLiveChildAndBackClearsCoroutineWork() = runTest(main.testDispatcher) {
        val store = ViewModelStore()
        val owner = brainNavigationOwner(store) { SelectedContextReadResult.Absent }
        val calls = IntArray(3)
        val loads = IntArray(3)
        val stopped = IntArray(3)
        val models: () -> BrainMainSettingsViewModel = {
            calls[0]++
            BrainMainSettingsViewModel(
                loadSettings = { loads[0]++; try { awaitCancellation() } finally { stopped[0]++ } },
                applySettings = { _, _ -> error("no writes") },
                confirmMainWarning = { error("no writes") }
            )
        }
        val auxiliary: () -> BrainAuxiliaryModelsViewModel = {
            calls[1]++
            BrainAuxiliaryModelsViewModel(
                loadSettings = { loads[1]++; try { awaitCancellation() } finally { stopped[1]++ } },
                applySettings = { _, _, _ -> error("no writes") },
                confirmAuxiliaryWarning = { error("no writes") }
            )
        }
        val skills: () -> BrainSkillsViewModel = {
            calls[2]++
            BrainSkillsViewModel(
                loadSkills = { loads[2]++; try { awaitCancellation() } finally { stopped[2]++ } },
                toggleSkill = { _, _, _ -> error("no writes") }
            )
        }
        try {
            owner.updateProviders(models, auxiliary, skills)
            assertEquals(listOf(0, 0, 0), calls.toList())
            assertNull(owner.models())
            assertNull(owner.auxiliary())
            assertNull(owner.skills())
            val pages = listOf(BrainDestination.MODELS, BrainDestination.AUXILIARY_MODELS, BrainDestination.SKILLS)
            pages.forEachIndexed { index, page ->
                owner.open(page)
                assertEquals(page, owner.destination.value)
                val first: ViewModel? = when (page) {
                    BrainDestination.MODELS -> owner.models()
                    BrainDestination.AUXILIARY_MODELS -> owner.auxiliary()
                    else -> owner.skills()
                }
                owner.open(page)
                owner.updateProviders(models, auxiliary, skills)
                val second: ViewModel? = when (page) {
                    BrainDestination.MODELS -> owner.models()
                    BrainDestination.AUXILIARY_MODELS -> owner.auxiliary()
                    else -> owner.skills()
                }
                advanceUntilIdle()
                assertSame(first, second)
                assertEquals(1, calls[index])
                assertEquals(1, loads[index])
                assertEquals(BrainBackAction.OVERVIEW, owner.back())
                advanceUntilIdle()
                assertEquals(BrainDestination.OVERVIEW, owner.destination.value)
                assertEquals(1, stopped[index])
                assertEquals(BrainBackAction.SETTINGS, owner.back())
            }
            owner.open(BrainDestination.MODELS)
            owner.models()
            advanceUntilIdle()
            assertEquals(2, calls[0])
            store.clear()
            advanceUntilIdle()
            assertEquals(2, stopped[0])
        } finally { store.clear() }
    }

    @Test fun directPageExitAndProviderReplacementClearOldChildAndNullIsUnavailable() = runTest(main.testDispatcher) {
        val store = ViewModelStore()
        val owner = brainNavigationOwner(store) { SelectedContextReadResult.Absent }
        var calls = 0
        var stopped = 0
        fun provider(): () -> BrainSkillsViewModel = {
            calls++
            BrainSkillsViewModel(
                loadSkills = { try { awaitCancellation() } finally { stopped++ } },
                toggleSkill = { _, _, _ -> error("no writes") }
            )
        }
        val firstProvider = provider()
        try {
            owner.updateProviders(null, null, firstProvider)
            owner.open(BrainDestination.SKILLS)
            val first = owner.skills()
            advanceUntilIdle()
            owner.updateProviders(null, null, firstProvider)
            assertSame(first, owner.skills())
            owner.updateProviders(null, null, provider())
            advanceUntilIdle()
            assertEquals(1, stopped)
            assertNotSame(first, owner.skills())
            advanceUntilIdle()
            assertEquals(2, calls)
            owner.open(BrainDestination.MODELS)
            advanceUntilIdle()
            assertEquals(2, stopped)
            assertNull(owner.models())
            owner.open(BrainDestination.SKILLS)
            owner.skills()
            advanceUntilIdle()
            owner.updateProviders(null, null, null)
            advanceUntilIdle()
            assertEquals(3, stopped)
            assertNull(owner.skills())
            assertEquals(BrainBackAction.OVERVIEW, owner.back())
            assertEquals(BrainBackAction.SETTINGS, owner.back())
        } finally { store.clear() }
    }

    @Test fun ownerIdentityReplacementAndDisposalCancelCaptionAndAllLiveChildren() = runTest(main.testDispatcher) {
        var captionsStopped = 0
        var childrenStopped = 0
        val oldStore = ViewModelStore()
        val old = brainNavigationOwner(oldStore) {
            try { awaitCancellation() } finally { captionsStopped++ }
        }
        old.updateProviders(null, null, {
            BrainSkillsViewModel(
                loadSkills = { try { awaitCancellation() } finally { childrenStopped++ } },
                toggleSkill = { _, _, _ -> error("no writes") }
            )
        })
        old.open(BrainDestination.SKILLS)
        val child = old.skills()
        advanceUntilIdle()
        oldStore.clear()
        advanceUntilIdle()
        assertEquals(1, captionsStopped)
        assertEquals(1, childrenStopped)
        val newStore = ViewModelStore()
        try {
            val replacement = brainNavigationOwner(newStore) { SelectedContextReadResult.Absent }
            assertNotSame(old, replacement)
            assertEquals(BrainDestination.OVERVIEW, replacement.destination.value)
            assertNull(replacement.skills())
            assertNotNull(child)
        } finally { newStore.clear() }
    }

    @Test fun sharedDetailReturnClearsDetailRetainsSkillsWithoutReloadOrToggleRearming() = runTest(main.testDispatcher) {
        val store = ViewModelStore()
        val detailStore = ViewModelStore()
        val owner = brainNavigationOwner(store) { SelectedContextReadResult.Absent }
        var providers = 0
        var loads = 0
        var toggles = 0
        var detailProviders = 0
        var detailStopped = 0
        val snapshot = BrainSkillsSnapshot(7L, "demo-profile", listOf(BrainInstalledSkill("demo-skill", "Synthetic skill", true)))
        owner.updateProviders(null, null, {
            providers++
            BrainSkillsViewModel(
                loadSkills = { loads++; BrainSkillsLoadResult.Available(snapshot) },
                toggleSkill = { _, _, _ ->
                    toggles++
                    BrainSkillsToggleResult.NotConfirmed(BrainSkillsFailureReason.READBACK_UNAVAILABLE, mayHaveSaved = true)
                },
                detailViewModelFactory = { snapshotId, name, profile ->
                    detailProviders++
                    BrainSkillDetailViewModel(
                        BrainSkillDetailIdentity(snapshotId, name, profile),
                        readContent = { _, _ -> try { awaitCancellation() } finally { detailStopped++ } },
                        workDispatcher = main.testDispatcher
                    )
                }
            )
        })
        owner.open(BrainDestination.SKILLS)
        val list = owner.skills()!!
        advanceUntilIdle()
        list.toggle("demo-skill", false)
        advanceUntilIdle()
        assertTrue(list.uiState.value.reloadRequired)
        assertEquals(1, toggles)
        var identity: BrainSkillDetailIdentity? = BrainSkillDetailIdentity(7L, "demo-skill", "demo-profile")
        val detail = ViewModelProvider(detailStore, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val captured = requireNotNull(identity)
                return requireNotNull(list.createDetailViewModel(captured.snapshotId, captured.name, captured.selectedProfile)) as T
            }
        }).get(BrainSkillDetailViewModel::class.java)
        advanceUntilIdle()
        try {
            val returnToSkills = brainDetailReturn(detailStore) { identity = null }
            returnToSkills() // same callback is consumed by toolbar and detail-only system Back
            advanceUntilIdle()
            assertNull(identity)
            assertEquals(1, detailStopped)
            assertEquals(1, detailProviders)
            assertTrue(detail.uiState.value.state is BrainSkillDetailState.Loading)
            assertSame(list, owner.skills())
            assertEquals(BrainDestination.SKILLS, owner.destination.value)
            assertEquals(1, providers)
            assertEquals(1, loads)
            list.toggle("demo-skill", false)
            assertEquals(1, toggles)
            assertTrue(list.uiState.value.reloadRequired)
            assertSame(snapshot, (list.uiState.value.loadState as BrainSkillsLoadState.Ready).snapshot)
            returnToSkills()
            assertEquals(1, detailStopped)
            assertEquals(1, loads)
            assertEquals(BrainBackAction.OVERVIEW, owner.back())
        } finally { detailStore.clear(); store.clear() }
    }
}
