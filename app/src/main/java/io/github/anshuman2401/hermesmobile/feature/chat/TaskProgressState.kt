package io.github.anshuman2401.hermesmobile.feature.chat

import io.github.anshuman2401.hermesmobile.domain.ProgressTask
import io.github.anshuman2401.hermesmobile.domain.TaskSnapshot
import io.github.anshuman2401.hermesmobile.domain.TaskStatus

/** Presentation only; never persisted and never used to admit/resubmit a prompt. */
data class TaskProgressState(
    val snapshot: TaskSnapshot? = null,
    val expanded: Boolean = true,
    val live: Boolean = false,
    val unavailable: Boolean = false,
    val ended: Boolean = false
) {
    fun accept(next: TaskSnapshot?): TaskProgressState {
        if (next == null) return copy(live = false, unavailable = true)
        val watermark = snapshot?.revision
        if (watermark != null && (next.revision == null || next.revision <= watermark)) return this
        return copy(snapshot = next, live = !ended && next.hasOpenTasks, unavailable = false,
            expanded = if (snapshot?.items.isNullOrEmpty()) !ended else expanded)
    }

    fun settle() = copy(live = false, ended = true)
    fun beginTurn() = copy(live = false, ended = false)

    companion object {
        fun restore(snapshot: TaskSnapshot?, running: Boolean) = TaskProgressState(
            snapshot = snapshot, expanded = running, live = running && snapshot?.hasOpenTasks == true, ended = !running
        )
    }
}

/** Preserve host sibling order, nest real parents, flatten orphan/cyclic references safely. */
object TaskProgressPresentation {
    fun rows(items: List<ProgressTask>): List<Pair<ProgressTask, Int>> {
        val ids = items.map { it.id }.toSet()
        val children = items.filter { it.parentId in ids }.groupBy { it.parentId }
        val seen = HashSet<String>()
        val result = ArrayList<Pair<ProgressTask, Int>>(items.size)
        fun visit(item: ProgressTask, depth: Int) {
            if (!seen.add(item.id)) return
            result.add(item to depth.coerceAtMost(4))
            children[item.id].orEmpty().forEach { visit(it, depth + 1) }
        }
        items.filter { it.parentId !in ids }.forEach { visit(it, 0) }
        items.filter { it.id !in seen }.forEach { visit(it, 0) }
        return result
    }

    fun currentTask(state: TaskProgressState): ProgressTask? = state.snapshot?.items?.firstOrNull {
        it.status == TaskStatus.IN_PROGRESS
    } ?: state.snapshot?.items?.firstOrNull { it.status == TaskStatus.PENDING }

    fun statusLabel(status: TaskStatus, live: Boolean): String = when (status) {
        TaskStatus.COMPLETED -> "Completed"
        TaskStatus.CANCELLED -> "Cancelled"
        TaskStatus.PENDING -> "Pending"
        TaskStatus.IN_PROGRESS -> if (live) "In progress" else "Unfinished"
    }
}
