package io.github.anshuman2401.hermesmobile.feature.chat

import io.github.anshuman2401.hermesmobile.domain.*
import org.junit.Assert.*
import org.junit.Test

class TaskProgressStateTest {
    private fun snapshot(revision: Long?, status: TaskStatus = TaskStatus.IN_PROGRESS) =
        TaskSnapshot(listOf(ProgressTask("a", "Synthetic", status)), revision)

    @Test fun staleDuplicateAndUnversionedUpdatesNeverRollbackVersionedProgress() {
        val current = TaskProgressState().accept(snapshot(5))
        assertTrue(current.live)
        for (revision in listOf(4L, 5L, null)) assertEquals(current, current.accept(snapshot(revision, TaskStatus.COMPLETED)))
        assertEquals(TaskStatus.COMPLETED, current.accept(snapshot(6, TaskStatus.COMPLETED)).snapshot!!.items[0].status)
    }

    @Test fun legacySnapshotsWorkUntilVersionedAuthorityArrives() {
        val old = TaskProgressState().accept(snapshot(null))
        assertEquals(TaskStatus.COMPLETED, old.accept(snapshot(null, TaskStatus.COMPLETED)).snapshot!!.items[0].status)
        assertEquals(1L, old.accept(snapshot(1)).snapshot!!.revision)
    }

    @Test fun terminalAndMalformedUpdatesNeverRestartWorkOrMarkPendingTasksComplete() {
        val ended = TaskProgressState().accept(snapshot(2)).settle()
        val late = ended.accept(snapshot(3))
        assertFalse(late.live)
        assertEquals(TaskStatus.IN_PROGRESS, late.snapshot!!.items[0].status)
        assertEquals("Unfinished", TaskProgressPresentation.statusLabel(TaskStatus.IN_PROGRESS, late.live))
        assertTrue(late.accept(null).unavailable)
        assertEquals(late.snapshot, late.accept(null).snapshot)
        assertTrue(late.beginTurn().accept(snapshot(4)).live)
    }

    @Test fun resumeRestoresOnlyServerProvenRunningPlansAsLive() {
        assertTrue(TaskProgressState.restore(snapshot(3), true).live)
        assertFalse(TaskProgressState.restore(snapshot(3), false).live)
        assertFalse(TaskProgressState.restore(snapshot(3), false).expanded)
        assertNull(TaskProgressState.restore(null, true).snapshot)
        assertFalse(TaskProgressState.restore(snapshot(3, TaskStatus.COMPLETED), true).live)
    }

    @Test fun authoritativeEmptySnapshotClearsThePanelButKeepsWatermark() {
        val cleared = TaskProgressState().accept(snapshot(1)).accept(TaskSnapshot(emptyList(), 2))
        assertTrue(cleared.snapshot!!.items.isEmpty())
        assertFalse(cleared.live)
        assertEquals(cleared, cleared.accept(snapshot(1)))
    }

    @Test fun treeKeepsSiblingOrderAndEveryCyclicOrOrphanTaskExactlyOnce() {
        fun item(id: String, parent: String? = null) = ProgressTask(id, "Synthetic", TaskStatus.PENDING, parent)
        val rows = TaskProgressPresentation.rows(listOf(item("child", "root"), item("root"), item("next"),
            item("orphan", "absent"), item("cycle-a", "cycle-b"), item("cycle-b", "cycle-a")))
        assertEquals(listOf("root", "child", "next", "orphan", "cycle-a", "cycle-b"), rows.map { it.first.id })
        assertEquals(1, rows[1].second)
        val deep = TaskProgressPresentation.rows((0..255).map { item("$it", if (it == 0) null else "${it - 1}") })
        assertEquals(256, deep.size)
        assertEquals(4, deep.last().second)
    }
}
