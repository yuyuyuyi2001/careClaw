package com.shijing.xomniclaw.ui.activity

import com.shijing.xomniclaw.scheduler.ScheduledTask
import java.util.Locale

/**
 * 定时任务状态页文案。
 */
fun formatTaskSortOptionLabel(option: ScheduledTaskSortOption): String {
    return when (option) {
        ScheduledTaskSortOption.NEXT_TRIGGER_ASC -> "按下次触发时间"
        ScheduledTaskSortOption.LAST_TRIGGER_DESC -> "按最近触发时间"
        ScheduledTaskSortOption.UPDATED_DESC -> "按最近更新时间"
        ScheduledTaskSortOption.NAME_ASC -> "按任务名称"
    }
}

/**
 * 状态页里的任务筛选与排序逻辑。
 *
 * 先搜索再排序，保证用户看到的是命中的结果集。
 */
fun filterAndSortScheduledTasks(
    tasks: List<ScheduledTask>,
    searchQuery: String,
    sortOption: ScheduledTaskSortOption
): List<ScheduledTask> {
    val normalizedQuery = searchQuery.trim().lowercase(Locale.getDefault())
    val filtered = if (normalizedQuery.isBlank()) {
        tasks
    } else {
        tasks.filter { task ->
            val searchTarget = listOf(
                task.name,
                task.instruction,
                task.repeat,
                formatRepeatLabel(task.repeat)
            ).joinToString(" ").lowercase(Locale.getDefault())
            searchTarget.contains(normalizedQuery)
        }
    }

    return when (sortOption) {
        ScheduledTaskSortOption.NEXT_TRIGGER_ASC -> filtered.sortedWith(
            compareBy<ScheduledTask> { it.nextTriggerAtMs ?: Long.MAX_VALUE }
                .thenBy { it.name.lowercase(Locale.getDefault()) }
        )

        ScheduledTaskSortOption.LAST_TRIGGER_DESC -> filtered.sortedWith(
            compareByDescending<ScheduledTask> { it.lastTriggeredAtMs ?: Long.MIN_VALUE }
                .thenBy { it.name.lowercase(Locale.getDefault()) }
        )

        ScheduledTaskSortOption.UPDATED_DESC -> filtered.sortedWith(
            compareByDescending<ScheduledTask> { it.updatedAtMs }
                .thenBy { it.name.lowercase(Locale.getDefault()) }
        )

        ScheduledTaskSortOption.NAME_ASC -> filtered.sortedBy { it.name.lowercase(Locale.getDefault()) }
    }
}
