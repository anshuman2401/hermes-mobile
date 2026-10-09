package com.hermes.bot.feature.sessions

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import com.hermes.bot.data.local.SessionPinScope
import com.hermes.bot.data.local.SessionPins
import com.hermes.bot.data.session.SessionRepository
import com.hermes.bot.data.session.SessionListOutcome
import com.hermes.bot.test.MainDispatcherRule
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class SessionsPinViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private class PinsRepo(
        val delegate: SessionsViewModelTest.FakeSessionRepository = SessionsViewModelTest.FakeSessionRepository()
    ) : SessionRepository by delegate {
        var pins = SessionPins(SessionPinScope("synthetic-profile", "synthetic-origin-digest", null))
        var failRead = false
        var failWrite = false
        var writeGate: CompletableDeferred<Unit>? = null
        var readGate: CompletableDeferred<Unit>? = null
        var writes = 0
        override suspend fun getSessionPins(): SessionPins {
            val captured = pins
            readGate?.await()
            if (failRead) error("Synthetic failure")
            return captured
        }
        override suspend fun setSessionPinned(scope: SessionPinScope, sessionId: String, pinned: Boolean): SessionPins {
            writes++
            writeGate?.await()
            if (failWrite) error("Synthetic failure")
            return pins.copy(sessionIds = if (pinned) pins.sessionIds + sessionId else pins.sessionIds - sessionId).also { pins = it }
        }
    }
    private fun cleanup(vm: SessionsViewModel) { ViewModelStore().apply { put("sessions", vm); clear() } }

    @Test fun successfulPinAndUnpinAreLocal_andSurviveViewModelRecreation() {
        val repo = PinsRepo()
        val vm = SessionsViewModel(repo)
        val calls = repo.delegate.listSessionsCallCount
        vm.toggleSessionPin("s-1")
        assertEquals(setOf("s-1"), vm.uiState.value.pinnedSessionIds)
        assertEquals(calls, repo.delegate.listSessionsCallCount)
        cleanup(vm)
        val restored = SessionsViewModel(repo)
        assertEquals(setOf("s-1"), restored.uiState.value.pinnedSessionIds)
        restored.toggleSessionPin("s-1")
        assertTrue(restored.uiState.value.pinnedSessionIds.isEmpty())
        cleanup(restored)
    }

    @Test fun pendingDuplicateTapIsIgnored_andFailedSaveKeepsPriorState() {
        val repo = PinsRepo().apply { writeGate = CompletableDeferred(); failWrite = true }
        val vm = SessionsViewModel(repo)
        vm.toggleSessionPin("s-1")
        vm.toggleSessionPin("s-1")
        assertEquals(1, repo.writes)
        assertEquals(setOf("s-1"), vm.uiState.value.savingPinIds)
        assertTrue(vm.uiState.value.pinnedSessionIds.isEmpty())
        repo.writeGate!!.complete(Unit)
        assertEquals("Couldn't save pin", vm.uiState.value.pinFailure)
        assertTrue(vm.uiState.value.pinnedSessionIds.isEmpty())
        assertTrue(vm.uiState.value.savingPinIds.isEmpty())
        vm.dismissPinFailure()
        assertNull(vm.uiState.value.pinFailure)
        cleanup(vm)
    }

    @Test fun workspaceResetRejectsOldWritePublication_andClearsWorkspaceFilter() {
        val repo = PinsRepo().apply { writeGate = CompletableDeferred() }
        val saved = SavedStateHandle()
        val vm = SessionsViewModel(repo, saved)
        vm.onPinnedFilterChange(true)
        vm.toggleSessionPin("s-1")
        vm.resetWorkspace()
        repo.writeGate!!.complete(Unit)
        assertTrue(vm.uiState.value.pinnedSessionIds.isEmpty())
        assertTrue(vm.uiState.value.savingPinIds.isEmpty())
        assertFalse(vm.uiState.value.showPinnedOnly)
        cleanup(vm)
        val restored = SessionsViewModel(null, saved)
        assertFalse(restored.uiState.value.showPinnedOnly)
        cleanup(restored)
    }

    @Test fun refreshCannotPublishAnOldPinSnapshotOverCompletedWrite() {
        val repo = PinsRepo()
        val vm = SessionsViewModel(repo)
        repo.readGate = CompletableDeferred()
        vm.refreshSessions()
        vm.toggleSessionPin("s-1")
        repo.readGate!!.complete(Unit)
        assertEquals(setOf("s-1"), vm.uiState.value.pinnedSessionIds)
        cleanup(vm)
    }

    @Test fun readFailureIsVisible_andMissingSessionDoesNotErasePin() {
        val repo = PinsRepo().apply { pins = pins.copy(sessionIds = setOf("not-loaded")) }
        val vm = SessionsViewModel(repo)
        assertEquals(setOf("not-loaded"), vm.uiState.value.pinnedSessionIds)
        vm.toggleSessionPin("not-loaded")
        assertEquals(0, repo.writes)
        repo.failRead = true
        vm.refreshSessions()
        assertEquals("Couldn't load pins", vm.uiState.value.pinFailure)
        assertFalse(vm.uiState.value.pinsAvailable)
        assertEquals(setOf("not-loaded"), vm.uiState.value.pinnedSessionIds)
        assertEquals(1, vm.uiState.value.sessions.size)
        cleanup(vm)
    }
}
