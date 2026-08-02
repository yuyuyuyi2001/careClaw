package com.shijing.xomniclaw.scheduler

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 定时任务编辑草稿。
 *
 * 这里保留字符串形态字段，方便直接承接 UI 表单输入；
 * 真正写回调度层前，再统一做解析与校验。
 */
data class ScheduledTaskEditDraft(
    val taskId: String,
    val name: String,
    val instruction: String,
    val repeat: String,
    val runAtText: String? = null,
    val dailyTime: String? = null,
    val daysOfWeekText: String? = null,
    val intervalMinutesText: String? = null,
    val timezone: String? = null,
    val enabled: Boolean = true,
    val exact: Boolean = true,
    val allowWhileIdle: Boolean = true
) {
    companion object {
        /**
         * 从已有任务生成编辑草稿，便于启停切换或复用现有配置。
         */
        fun fromTask(task: ScheduledTask): ScheduledTaskEditDraft {
            return ScheduledTaskEditDraft(
                taskId = task.id,
                name = task.name,
                instruction = task.instruction,
                repeat = task.repeat,
                runAtText = task.runAtMs?.let { formatRunAtForEdit(it) },
                dailyTime = task.dailyTime,
                daysOfWeekText = task.daysOfWeek?.joinToString(","),
                intervalMinutesText = task.intervalMinutes?.toString(),
                timezone = task.timezone,
                enabled = task.enabled,
                exact = task.exact,
                allowWhileIdle = task.allowWhileIdle
            )
        }

        private fun formatRunAtForEdit(timestampMs: Long): String {
            return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(timestampMs))
        }
    }
}

/**
 * 编辑结果。
 */
data class ScheduledTaskEditResult(
    val task: ScheduledTask? = null,
    val errorMessage: String? = null
) {
    val success: Boolean get() = task != null && errorMessage == null
}

/**
 * 定时任务编辑校验器。
 *
 * 目标：
 * 1. 让 UI 侧可以直接拿到清晰错误信息；
 * 2. 保证写回存储前任务一定是合法的；
 * 3. 避免无效配置被注册到 AlarmManager。
 */
object ScheduledTaskEditValidator {
    fun applyDraft(
        existingTask: ScheduledTask,
        draft: ScheduledTaskEditDraft,
        nowMs: Long = System.currentTimeMillis()
    ): ScheduledTaskEditResult {
        return try {
            val normalizedName = draft.name.trim()
            require(normalizedName.isNotBlank()) { "任务名称不能为空" }

            val normalizedInstruction = draft.instruction.trim()
            require(normalizedInstruction.isNotBlank()) { "任务指令不能为空" }

            val normalizedRepeat = draft.repeat.trim().lowercase()
            require(
                normalizedRepeat in setOf(
                    ScheduledTask.REPEAT_ONCE,
                    ScheduledTask.REPEAT_DAILY,
                    ScheduledTask.REPEAT_WEEKLY,
                    ScheduledTask.REPEAT_WORKDAY,
                    ScheduledTask.REPEAT_INTERVAL
                )
            ) { "重复类型不合法：$normalizedRepeat" }

            val normalizedTimezone = draft.timezone?.trim()?.ifBlank { null }
            val updatedTask = when (normalizedRepeat) {
                ScheduledTask.REPEAT_ONCE -> buildOneTimeTask(
                    existingTask = existingTask,
                    draft = draft,
                    nowMs = nowMs,
                    normalizedName = normalizedName,
                    normalizedInstruction = normalizedInstruction,
                    normalizedTimezone = normalizedTimezone
                )

                ScheduledTask.REPEAT_DAILY -> buildDailyTask(
                    existingTask = existingTask,
                    draft = draft,
                    nowMs = nowMs,
                    normalizedName = normalizedName,
                    normalizedInstruction = normalizedInstruction,
                    normalizedTimezone = normalizedTimezone
                )

                ScheduledTask.REPEAT_WEEKLY -> buildWeeklyTask(
                    existingTask = existingTask,
                    draft = draft,
                    nowMs = nowMs,
                    normalizedName = normalizedName,
                    normalizedInstruction = normalizedInstruction,
                    normalizedTimezone = normalizedTimezone
                )

                ScheduledTask.REPEAT_WORKDAY -> buildWorkdayTask(
                    existingTask = existingTask,
                    draft = draft,
                    nowMs = nowMs,
                    normalizedName = normalizedName,
                    normalizedInstruction = normalizedInstruction,
                    normalizedTimezone = normalizedTimezone
                )

                ScheduledTask.REPEAT_INTERVAL -> buildIntervalTask(
                    existingTask = existingTask,
                    draft = draft,
                    nowMs = nowMs,
                    normalizedName = normalizedName,
                    normalizedInstruction = normalizedInstruction,
                    normalizedTimezone = normalizedTimezone
                )

                else -> error("Unsupported repeat: $normalizedRepeat")
            }

            ScheduledTaskEditResult(task = updatedTask)
        } catch (e: Exception) {
            ScheduledTaskEditResult(errorMessage = e.message ?: "修改定时任务失败")
        }
    }

    private fun buildOneTimeTask(
        existingTask: ScheduledTask,
        draft: ScheduledTaskEditDraft,
        nowMs: Long,
        normalizedName: String,
        normalizedInstruction: String,
        normalizedTimezone: String?
    ): ScheduledTask {
        val runAtText = draft.runAtText?.trim()
        require(!runAtText.isNullOrBlank()) { "一次性任务必须填写执行时间" }
        val triggerAtMs = ScheduledTaskTimeResolver.resolveOneTimeTriggerAtMs(
            runAt = runAtText,
            runAtMs = null,
            delaySeconds = null,
            nowMs = nowMs,
            timezone = normalizedTimezone
        )

        return existingTask.copy(
            name = normalizedName,
            instruction = normalizedInstruction,
            repeat = ScheduledTask.REPEAT_ONCE,
            runAtMs = triggerAtMs,
            dailyTime = null,
            daysOfWeek = null,
            intervalMinutes = null,
            timezone = normalizedTimezone,
            exact = draft.exact,
            allowWhileIdle = draft.allowWhileIdle,
            enabled = draft.enabled,
            updatedAtMs = nowMs,
            nextTriggerAtMs = if (draft.enabled) triggerAtMs else null
        )
    }

    private fun buildDailyTask(
        existingTask: ScheduledTask,
        draft: ScheduledTaskEditDraft,
        nowMs: Long,
        normalizedName: String,
        normalizedInstruction: String,
        normalizedTimezone: String?
    ): ScheduledTask {
        val dailyTime = draft.dailyTime?.trim()
        require(!dailyTime.isNullOrBlank()) { "每天任务必须填写时间，格式如 21:30" }
        val nextTriggerAtMs = ScheduledTaskTimeResolver.computeNextDailyTriggerAtMs(
            dailyTime = dailyTime,
            timezone = normalizedTimezone,
            nowMs = nowMs
        )

        return existingTask.copy(
            name = normalizedName,
            instruction = normalizedInstruction,
            repeat = ScheduledTask.REPEAT_DAILY,
            runAtMs = null,
            dailyTime = dailyTime,
            daysOfWeek = null,
            intervalMinutes = null,
            timezone = normalizedTimezone,
            exact = draft.exact,
            allowWhileIdle = draft.allowWhileIdle,
            enabled = draft.enabled,
            updatedAtMs = nowMs,
            nextTriggerAtMs = if (draft.enabled) nextTriggerAtMs else null
        )
    }

    private fun buildWeeklyTask(
        existingTask: ScheduledTask,
        draft: ScheduledTaskEditDraft,
        nowMs: Long,
        normalizedName: String,
        normalizedInstruction: String,
        normalizedTimezone: String?
    ): ScheduledTask {
        val dailyTime = draft.dailyTime?.trim()
        require(!dailyTime.isNullOrBlank()) { "每周任务必须填写时间，格式如 21:30" }
        val daysOfWeekText = draft.daysOfWeekText?.trim()
        require(!daysOfWeekText.isNullOrBlank()) { "每周任务必须填写周几，例如 周三 或 1,3,5" }
        val daysOfWeek = ScheduledTaskWeekdayParser.parseFlexible(daysOfWeekText)
        val nextTriggerAtMs = ScheduledTaskTimeResolver.computeNextWeeklyTriggerAtMs(
            dailyTime = dailyTime,
            daysOfWeek = daysOfWeek,
            timezone = normalizedTimezone,
            nowMs = nowMs
        )

        return existingTask.copy(
            name = normalizedName,
            instruction = normalizedInstruction,
            repeat = ScheduledTask.REPEAT_WEEKLY,
            runAtMs = null,
            dailyTime = dailyTime,
            daysOfWeek = daysOfWeek,
            intervalMinutes = null,
            timezone = normalizedTimezone,
            exact = draft.exact,
            allowWhileIdle = draft.allowWhileIdle,
            enabled = draft.enabled,
            updatedAtMs = nowMs,
            nextTriggerAtMs = if (draft.enabled) nextTriggerAtMs else null
        )
    }

    private fun buildWorkdayTask(
        existingTask: ScheduledTask,
        draft: ScheduledTaskEditDraft,
        nowMs: Long,
        normalizedName: String,
        normalizedInstruction: String,
        normalizedTimezone: String?
    ): ScheduledTask {
        val dailyTime = draft.dailyTime?.trim()
        require(!dailyTime.isNullOrBlank()) { "工作日任务必须填写时间，格式如 21:30" }
        val nextTriggerAtMs = ScheduledTaskTimeResolver.computeNextWorkdayTriggerAtMs(
            dailyTime = dailyTime,
            timezone = normalizedTimezone,
            nowMs = nowMs
        )

        return existingTask.copy(
            name = normalizedName,
            instruction = normalizedInstruction,
            repeat = ScheduledTask.REPEAT_WORKDAY,
            runAtMs = null,
            dailyTime = dailyTime,
            daysOfWeek = listOf(1, 2, 3, 4, 5),
            intervalMinutes = null,
            timezone = normalizedTimezone,
            exact = draft.exact,
            allowWhileIdle = draft.allowWhileIdle,
            enabled = draft.enabled,
            updatedAtMs = nowMs,
            nextTriggerAtMs = if (draft.enabled) nextTriggerAtMs else null
        )
    }

    private fun buildIntervalTask(
        existingTask: ScheduledTask,
        draft: ScheduledTaskEditDraft,
        nowMs: Long,
        normalizedName: String,
        normalizedInstruction: String,
        normalizedTimezone: String?
    ): ScheduledTask {
        val intervalMinutes = draft.intervalMinutesText?.trim()?.toIntOrNull()
            ?: throw IllegalArgumentException("固定间隔任务必须填写有效的分钟数")
        require(intervalMinutes > 0) { "固定间隔分钟数必须大于 0" }
        val nextTriggerAtMs = ScheduledTaskTimeResolver.computeNextIntervalTriggerAtMs(
            intervalMinutes = intervalMinutes,
            nowMs = nowMs
        )

        return existingTask.copy(
            name = normalizedName,
            instruction = normalizedInstruction,
            repeat = ScheduledTask.REPEAT_INTERVAL,
            runAtMs = null,
            dailyTime = null,
            daysOfWeek = null,
            intervalMinutes = intervalMinutes,
            timezone = normalizedTimezone,
            exact = draft.exact,
            allowWhileIdle = draft.allowWhileIdle,
            enabled = draft.enabled,
            updatedAtMs = nowMs,
            nextTriggerAtMs = if (draft.enabled) nextTriggerAtMs else null
        )
    }
}
