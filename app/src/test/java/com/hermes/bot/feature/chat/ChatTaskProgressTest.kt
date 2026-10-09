package com.hermes.bot.feature.chat

import com.hermes.bot.data.gateway.*
import com.hermes.bot.data.session.FakeGatewayProtocolAdapter
import com.hermes.bot.domain.*
import com.hermes.bot.test.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatTaskProgressTest {
    @get:Rule val main = MainDispatcherRule()
    private fun snapshot(revision: Long, status: TaskStatus = TaskStatus.IN_PROGRESS) =
        TaskSnapshot(listOf(ProgressTask("synthetic-task", "Synthetic step", status)), revision)
    private fun vm(adapter: FakeGatewayProtocolAdapter, repo: ChatRuntimeAdmissionTest.FakeAdmissionSessionRepository =
        ChatRuntimeAdmissionTest.FakeAdmissionSessionRepository()) = ChatViewModel("sess-100", sessionRepository = repo, gatewayProtocolAdapter = adapter)

    @Test fun onlyCurrentRuntimeSnapshotsAreAdmittedAndTheyNeverSubmitOrFabricateHistory() = runTest {
        val adapter = FakeGatewayProtocolAdapter()
        val model = vm(adapter)
        for (id in listOf(null, "", "other", "sess-100", "stale-runtime")) adapter.eventFlow.emit(GatewayEvent.TasksUpdated(id, 1, snapshot(1)))
        assertNull(model.uiState.value.taskProgress.snapshot)
        adapter.eventFlow.emit(GatewayEvent.TasksUpdated("runtime-100", 2, snapshot(2)))
        assertEquals(2L, model.uiState.value.taskProgress.snapshot!!.revision)
        assertTrue(model.uiState.value.timeline.isEmpty())
        assertFalse(model.uiState.value.isStreaming)
        assertEquals(0, adapter.submitPromptCallCount)
        adapter.eventFlow.emit(GatewayEvent.TasksUpdated("runtime-100", 3, snapshot(1, TaskStatus.COMPLETED)))
        assertEquals(2L, model.uiState.value.taskProgress.snapshot!!.revision)
    }

    @Test fun toolResultsShareThePanelWithoutReplacingToolActivity() = runTest {
        val adapter = FakeGatewayProtocolAdapter()
        val model = vm(adapter)
        adapter.eventFlow.emit(GatewayEvent.ToolComplete("runtime-100", 1, "synthetic-call", "todo_list", taskSnapshot = snapshot(1)))
        assertNotNull(model.uiState.value.taskProgress.snapshot)
        assertEquals(1, model.uiState.value.timeline.filterIsInstance<ToolActivity>().size)
        adapter.eventFlow.emit(GatewayEvent.ToolStart("runtime-100", 2, "synthetic-start", "todo_list", args = mapOf("todos" to emptyList<Any>())))
        assertEquals(1L, model.uiState.value.taskProgress.snapshot!!.revision)
    }

    @Test fun terminalEventsAndLateSnapshotsLeaveUnfinishedWorkNonLive() = runTest {
        for (status in listOf("complete", "interrupted", "error", null)) {
            val adapter = FakeGatewayProtocolAdapter()
            val model = vm(adapter)
            adapter.eventFlow.emit(GatewayEvent.MessageStart("runtime-100", null))
            adapter.eventFlow.emit(GatewayEvent.TasksUpdated("runtime-100", 1, snapshot(1)))
            assertTrue(model.uiState.value.taskProgress.live)
            adapter.eventFlow.emit(GatewayEvent.MessageComplete("runtime-100", 2, null, status))
            adapter.eventFlow.emit(GatewayEvent.TasksUpdated("runtime-100", 3, snapshot(2)))
            assertFalse(model.uiState.value.taskProgress.live)
            assertEquals(TaskStatus.IN_PROGRESS, model.uiState.value.taskProgress.snapshot!!.items[0].status)
            assertTrue(model.uiState.value.timeline.isEmpty())
        }
    }

    @Test fun disconnectFreezesProgressAndUnavailableUpdateRetainsLastKnownTasks() = runTest {
        val adapter = FakeGatewayProtocolAdapter()
        val model = vm(adapter)
        adapter.eventFlow.emit(GatewayEvent.MessageStart("runtime-100", null))
        adapter.eventFlow.emit(GatewayEvent.TasksUpdated("runtime-100", 1, snapshot(1)))
        assertTrue(model.uiState.value.taskProgress.live)
        adapter.connectionStatus.value = ConnectionStatus.OFFLINE
        assertFalse(model.uiState.value.taskProgress.live)
        assertEquals(1L, model.uiState.value.taskProgress.snapshot!!.revision)
        adapter.eventFlow.emit(GatewayEvent.TasksUpdated("runtime-100", 2, snapshot(2)))
        assertFalse(model.uiState.value.taskProgress.live)
        adapter.eventFlow.emit(GatewayEvent.TasksUpdated("runtime-100", 3, null))
        assertTrue(model.uiState.value.taskProgress.unavailable)
        assertNotNull(model.uiState.value.taskProgress.snapshot)
    }

    @Test fun finishedPlanCollapsesAfterLingerWithoutLosingConfirmedTasks() = runTest {
        val adapter = FakeGatewayProtocolAdapter()
        val model = vm(adapter)
        adapter.eventFlow.emit(GatewayEvent.MessageStart("runtime-100", null))
        adapter.eventFlow.emit(GatewayEvent.TasksUpdated("runtime-100", 1, snapshot(1, TaskStatus.COMPLETED)))
        assertTrue(model.uiState.value.taskProgress.expanded)
        advanceTimeBy(3999); runCurrent()
        assertTrue(model.uiState.value.taskProgress.expanded)
        advanceTimeBy(1); runCurrent()
        assertFalse(model.uiState.value.taskProgress.expanded)
        model.toggleTaskProgress()
        assertTrue(model.uiState.value.taskProgress.expanded)
        assertEquals(1, model.uiState.value.taskProgress.snapshot!!.completedCount)
    }

    @Test fun userExpansionChoiceCancelsAutomaticCollapse() = runTest {
        val adapter = FakeGatewayProtocolAdapter()
        val model = vm(adapter)
        adapter.eventFlow.emit(GatewayEvent.MessageStart("runtime-100", null))
        adapter.eventFlow.emit(GatewayEvent.TasksUpdated("runtime-100", 1, snapshot(1, TaskStatus.COMPLETED)))
        model.toggleTaskProgress()
        model.toggleTaskProgress()
        advanceTimeBy(4000); runCurrent()
        assertTrue(model.uiState.value.taskProgress.expanded)
    }

    @Test fun resumeReplacesTaskAuthorityAndDoesNotResurrectIdlePlansAsRunning() = runTest {
        val adapter = FakeGatewayProtocolAdapter()
        val repo = ChatRuntimeAdmissionTest.FakeAdmissionSessionRepository()
        repo.resumeOutcome = Result.success(SessionResumeResult("runtime-100", "sess-100", 0,
            taskSnapshot = snapshot(7), activityEvidence = ResumeActivityEvidence.NON_RUNNING))
        val model = vm(adapter, repo)
        assertEquals(7L, model.uiState.value.taskProgress.snapshot!!.revision)
        assertFalse(model.uiState.value.taskProgress.live)
        assertFalse(model.uiState.value.taskProgress.expanded)
        repo.resumeOutcome = Result.success(SessionResumeResult("synthetic-fresh-runtime", "sess-100", 0,
            taskSnapshot = snapshot(1), running = true, activityEvidence = ResumeActivityEvidence.RUNNING))
        model.loadSession()
        assertEquals(1L, model.uiState.value.taskProgress.snapshot!!.revision)
        assertTrue(model.uiState.value.taskProgress.live)
        adapter.eventFlow.emit(GatewayEvent.TasksUpdated("runtime-100", 20, snapshot(20)))
        assertEquals(1L, model.uiState.value.taskProgress.snapshot!!.revision)
        repo.resumeOutcome = Result.success(SessionResumeResult("synthetic-fresh-runtime", "sess-100", 0))
        model.loadSession()
        assertNull(model.uiState.value.taskProgress.snapshot)
    }
}
