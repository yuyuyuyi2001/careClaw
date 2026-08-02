package com.shijing.xomniclaw.ui.activity

import com.shijing.xomniclaw.scheduler.ScheduledTask
import com.shijing.xomniclaw.agent.memory.evolution.MemoryEvolutionStatus

/**
 * 状态页里的 Memory 概览。
 */
data class MemoryStatusSnapshot(
    val longTermMemoryExists: Boolean,
    val longTermMemoryLength: Int,
    val imageMemoriesExists: Boolean,
    val imageMemoriesLength: Int,
    val userProfileExists: Boolean,
    val userProfileLength: Int,
    val knowledgeFiles: List<String>,
    val dailyLogs: List<String>,
    val evolutionStatus: MemoryEvolutionStatus
)

/**
 * 相册记忆与画像设置的状态页快照。
 */
data class GalleryMemorySettingsState(
    val featureEnabled: Boolean,
    val profileLoadingEnabled: Boolean,
    val scanIntervalMinutes: Int,
    val manualSyncMaxImages: Int,
    val automationTaskSummary: String
)

/**
 * Memory 文件详情。
 */
data class MemoryDetailState(
    val title: String,
    val content: String
)

/**
 * 定时任务排序方式。
 */
enum class ScheduledTaskSortOption {
    NEXT_TRIGGER_ASC,
    LAST_TRIGGER_DESC,
    UPDATED_DESC,
    NAME_ASC
}

/**
 * 定时任务编辑表单状态。
 *
 * 保留字符串字段，便于直接映射到输入框。
 */
data class ScheduledTaskEditorState(
    val taskId: String,
    val name: String,
    val instruction: String,
    val repeat: String,
    val runAtText: String,
    val dailyTime: String,
    val daysOfWeekText: String,
    val intervalMinutesText: String,
    val timezone: String,
    val enabled: Boolean,
    val exact: Boolean,
    val allowWhileIdle: Boolean
) {
    companion object {
        fun fromTask(task: ScheduledTask): ScheduledTaskEditorState {
            return ScheduledTaskEditorState(
                taskId = task.id,
                name = task.name,
                instruction = task.instruction,
                repeat = task.repeat,
                runAtText = task.runAtMs?.let { formatTimestamp(it) }.orEmpty(),
                dailyTime = task.dailyTime.orEmpty(),
                daysOfWeekText = task.daysOfWeek?.joinToString(",") ?: "",
                intervalMinutesText = task.intervalMinutes?.toString().orEmpty(),
                timezone = task.timezone.orEmpty(),
                enabled = task.enabled,
                exact = task.exact,
                allowWhileIdle = task.allowWhileIdle
            )
        }
    }
}
