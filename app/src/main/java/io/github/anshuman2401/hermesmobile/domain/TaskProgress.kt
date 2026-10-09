package io.github.anshuman2401.hermesmobile.domain

/** Server-authored, bounded, memory-only task information. Never a prompt or turn authority. */
enum class TaskStatus { PENDING, IN_PROGRESS, COMPLETED, CANCELLED }

data class ProgressTask(val id: String, val content: String, val status: TaskStatus, val parentId: String? = null) {
    override fun toString() = "ProgressTask([REDACTED])"
}

data class TaskSnapshot(val items: List<ProgressTask>, val revision: Long? = null, val descriptionsTruncated: Boolean = false) {
    val completedCount: Int get() = items.count { it.status == TaskStatus.COMPLETED }
    val countedCount: Int get() = items.count { it.status != TaskStatus.CANCELLED }
    val hasOpenTasks: Boolean get() = items.any { it.status == TaskStatus.PENDING || it.status == TaskStatus.IN_PROGRESS }
    override fun toString() = "TaskSnapshot([REDACTED])"
}
