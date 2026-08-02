package com.shijing.xomniclaw.scheduler

import android.app.Application
import android.content.Context
import android.util.Log
import com.tencent.mmkv.MMKV
import com.shijing.xomniclaw.core.MainEntryNew
import java.util.UUID

/**
 * 定时任务管理器。
 *
 * 统一负责：
 * 1. 任务创建 / 删除 / 查询
 * 2. 计算下一次触发时间
 * 3. 把定时任务桥接成现有 Agent 执行入口
 */
class ScheduledTaskManager(
    context: Context,
    private val store: ScheduledTaskStore = ScheduledTaskStore()
) {
    companion object {
        private const val TAG = "ScheduledTaskManager"
        private const val CATCH_UP_DELAY_MS = 5_000L
    }

    private val appContext = context.applicationContext
    private val alarmScheduler = AlarmTaskScheduler(appContext)

    fun createOneTimeTask(
        name: String,
        instruction: String,
        triggerAtMs: Long,
        sessionId: String? = null,
        exact: Boolean = true,
        allowWhileIdle: Boolean = true
    ): ScheduledTask {
        val now = System.currentTimeMillis()
        val task = ScheduledTask(
            id = UUID.randomUUID().toString(),
            name = name,
            instruction = instruction,
            repeat = ScheduledTask.REPEAT_ONCE,
            runAtMs = triggerAtMs,
            sessionId = sessionId,
            exact = exact,
            allowWhileIdle = allowWhileIdle,
            createdAtMs = now,
            updatedAtMs = now,
            nextTriggerAtMs = triggerAtMs
        )

        store.upsert(task)
        alarmScheduler.schedule(task)
        return task
    }

    fun createDailyTask(
        name: String,
        instruction: String,
        dailyTime: String,
        timezone: String? = null,
        sessionId: String? = null,
        exact: Boolean = true,
        allowWhileIdle: Boolean = true
    ): ScheduledTask {
        val now = System.currentTimeMillis()
        val nextTriggerAtMs = ScheduledTaskTimeResolver.computeNextDailyTriggerAtMs(
            dailyTime = dailyTime,
            timezone = timezone,
            nowMs = now
        )

        val task = ScheduledTask(
            id = UUID.randomUUID().toString(),
            name = name,
            instruction = instruction,
            repeat = ScheduledTask.REPEAT_DAILY,
            dailyTime = dailyTime,
            timezone = timezone,
            sessionId = sessionId,
            exact = exact,
            allowWhileIdle = allowWhileIdle,
            createdAtMs = now,
            updatedAtMs = now,
            nextTriggerAtMs = nextTriggerAtMs
        )

        store.upsert(task)
        alarmScheduler.schedule(task)
        return task
    }

    fun createWeeklyTask(
        name: String,
        instruction: String,
        dailyTime: String,
        daysOfWeek: List<Int>,
        timezone: String? = null,
        sessionId: String? = null,
        exact: Boolean = true,
        allowWhileIdle: Boolean = true
    ): ScheduledTask {
        val now = System.currentTimeMillis()
        val normalizedDays = ScheduledTaskTimeResolver.normalizeDaysOfWeek(daysOfWeek)
        val nextTriggerAtMs = ScheduledTaskTimeResolver.computeNextWeeklyTriggerAtMs(
            dailyTime = dailyTime,
            daysOfWeek = normalizedDays,
            timezone = timezone,
            nowMs = now
        )

        val task = ScheduledTask(
            id = UUID.randomUUID().toString(),
            name = name,
            instruction = instruction,
            repeat = ScheduledTask.REPEAT_WEEKLY,
            dailyTime = dailyTime,
            daysOfWeek = normalizedDays,
            timezone = timezone,
            sessionId = sessionId,
            exact = exact,
            allowWhileIdle = allowWhileIdle,
            createdAtMs = now,
            updatedAtMs = now,
            nextTriggerAtMs = nextTriggerAtMs
        )

        store.upsert(task)
        alarmScheduler.schedule(task)
        return task
    }

    fun createWorkdayTask(
        name: String,
        instruction: String,
        dailyTime: String,
        timezone: String? = null,
        sessionId: String? = null,
        exact: Boolean = true,
        allowWhileIdle: Boolean = true
    ): ScheduledTask {
        val now = System.currentTimeMillis()
        val nextTriggerAtMs = ScheduledTaskTimeResolver.computeNextWorkdayTriggerAtMs(
            dailyTime = dailyTime,
            timezone = timezone,
            nowMs = now
        )

        val task = ScheduledTask(
            id = UUID.randomUUID().toString(),
            name = name,
            instruction = instruction,
            repeat = ScheduledTask.REPEAT_WORKDAY,
            dailyTime = dailyTime,
            daysOfWeek = listOf(1, 2, 3, 4, 5),
            timezone = timezone,
            sessionId = sessionId,
            exact = exact,
            allowWhileIdle = allowWhileIdle,
            createdAtMs = now,
            updatedAtMs = now,
            nextTriggerAtMs = nextTriggerAtMs
        )

        store.upsert(task)
        alarmScheduler.schedule(task)
        return task
    }

    fun createIntervalTask(
        name: String,
        instruction: String,
        intervalMinutes: Int,
        sessionId: String? = null,
        exact: Boolean = true,
        allowWhileIdle: Boolean = true
    ): ScheduledTask {
        val now = System.currentTimeMillis()
        val nextTriggerAtMs = ScheduledTaskTimeResolver.computeNextIntervalTriggerAtMs(
            intervalMinutes = intervalMinutes,
            nowMs = now
        )

        val task = ScheduledTask(
            id = UUID.randomUUID().toString(),
            name = name,
            instruction = instruction,
            repeat = ScheduledTask.REPEAT_INTERVAL,
            intervalMinutes = intervalMinutes,
            sessionId = sessionId,
            exact = exact,
            allowWhileIdle = allowWhileIdle,
            createdAtMs = now,
            updatedAtMs = now,
            nextTriggerAtMs = nextTriggerAtMs
        )

        store.upsert(task)
        alarmScheduler.schedule(task)
        return task
    }

    fun listTasks(): List<ScheduledTask> {
        return store.list().sortedBy { it.nextTriggerAtMs ?: Long.MAX_VALUE }
    }

    fun getTask(taskId: String): ScheduledTask? {
        return store.get(taskId)
    }

    /**
     * 更新已有定时任务。
     *
     * 保存前统一做格式校验；若校验失败，则不覆盖原任务，也不重新注册闹钟。
     */
    fun updateTask(draft: ScheduledTaskEditDraft): ScheduledTaskEditResult {
        val existingTask = store.get(draft.taskId)
            ?: return ScheduledTaskEditResult(errorMessage = "任务不存在：${draft.taskId}")

        val result = ScheduledTaskEditValidator.applyDraft(existingTask, draft)
        val updatedTask = result.task ?: return result

        alarmScheduler.cancel(existingTask.id)
        store.upsert(updatedTask)

        if (updatedTask.enabled && updatedTask.nextTriggerAtMs != null) {
            alarmScheduler.schedule(updatedTask)
        }

        return result
    }

    /**
     * 快速启用或停用任务。
     *
     * 复用统一的编辑校验链路，确保启用后下一次触发时间被正确重算。
     */
    fun setTaskEnabled(taskId: String, enabled: Boolean): ScheduledTaskEditResult {
        val existingTask = store.get(taskId)
            ?: return ScheduledTaskEditResult(errorMessage = "任务不存在：$taskId")

        val draft = ScheduledTaskEditDraft.fromTask(existingTask).copy(enabled = enabled)
        return updateTask(draft)
    }

    fun cancelTask(taskId: String): Boolean {
        alarmScheduler.cancel(taskId)
        return store.delete(taskId)
    }

    /**
     * 重新把所有已启用任务注册到系统闹钟。
     *
     * 典型场景：
     * - 设备重启
     * - 应用升级后 PendingIntent 丢失
     * - 应用进程重新启动，希望和落盘状态重新对齐
     */
    fun rescheduleAll() {
        val now = System.currentTimeMillis()
        val updatedTasks = store.list().map { task ->
            if (!task.enabled) {
                return@map task
            }

            val refreshedNextTrigger = when (task.repeat) {
                ScheduledTask.REPEAT_ONCE -> {
                    val oneTimeTrigger = task.runAtMs ?: task.nextTriggerAtMs
                    if (oneTimeTrigger == null) {
                        null
                    } else if (oneTimeTrigger <= now && task.lastTriggeredAtMs == null) {
                        // 如果错过了一次性触发，给一个短暂补偿窗口，让任务尽快补跑。
                        now + CATCH_UP_DELAY_MS
                    } else {
                        oneTimeTrigger
                    }
                }

                else -> computeNextRecurringTriggerAtMs(task, now)
            }

            task.copy(
                nextTriggerAtMs = refreshedNextTrigger,
                updatedAtMs = now
            )
        }

        store.replaceAll(updatedTasks)
        updatedTasks.filter { it.enabled && it.nextTriggerAtMs != null }
            .forEach { alarmScheduler.schedule(it) }
    }

    fun handleTriggeredTask(
        taskId: String,
        expectedTriggerAtMs: Long? = null,
        alarmReceivedAtMs: Long? = null,
        triggerSource: String = "alarm",
        wakeSummary: String? = null
    ) {
        val task = store.get(taskId)
        if (task == null) {
            Log.w(TAG, "Triggered task not found: $taskId")
            return
        }
        if (!task.enabled) {
            Log.w(TAG, "Triggered task is disabled: $taskId")
            return
        }

        val expectedAtMs = expectedTriggerAtMs ?: task.nextTriggerAtMs
        val alarmAtMs = alarmReceivedAtMs ?: System.currentTimeMillis()
        val dispatchStartedAtMs = System.currentTimeMillis()
        val triggerDelayMs = expectedAtMs?.let { (alarmAtMs - it).coerceAtLeast(0L) }
        val dispatchLatencyMs = (dispatchStartedAtMs - alarmAtMs).coerceAtLeast(0L)

        dispatchAgentInstruction(task)

        val updatedTask = when (task.repeat) {
            ScheduledTask.REPEAT_DAILY,
            ScheduledTask.REPEAT_WEEKLY,
            ScheduledTask.REPEAT_WORKDAY,
            ScheduledTask.REPEAT_INTERVAL -> {
                val nextTriggerAtMs = computeNextRecurringTriggerAtMs(
                    task = task,
                    nowMs = alarmAtMs + 1000L
                )
                task.copy(
                    updatedAtMs = dispatchStartedAtMs,
                    lastTriggeredAtMs = alarmAtMs,
                    lastExpectedTriggerAtMs = expectedAtMs,
                    lastAlarmReceivedAtMs = alarmAtMs,
                    lastExecutionStartedAtMs = dispatchStartedAtMs,
                    lastTriggerDelayMs = triggerDelayMs,
                    lastDispatchLatencyMs = dispatchLatencyMs,
                    lastTriggerSource = triggerSource,
                    lastWakeSummary = wakeSummary,
                    nextTriggerAtMs = nextTriggerAtMs
                )
            }

            else -> {
                task.copy(
                    enabled = false,
                    updatedAtMs = dispatchStartedAtMs,
                    lastTriggeredAtMs = alarmAtMs,
                    lastExpectedTriggerAtMs = expectedAtMs,
                    lastAlarmReceivedAtMs = alarmAtMs,
                    lastExecutionStartedAtMs = dispatchStartedAtMs,
                    lastTriggerDelayMs = triggerDelayMs,
                    lastDispatchLatencyMs = dispatchLatencyMs,
                    lastTriggerSource = triggerSource,
                    lastWakeSummary = wakeSummary,
                    nextTriggerAtMs = null
                )
            }
        }

        store.upsert(updatedTask)

        if (updatedTask.enabled && updatedTask.nextTriggerAtMs != null) {
            alarmScheduler.schedule(updatedTask)
        } else {
            alarmScheduler.cancel(updatedTask.id)
        }
    }

    private fun dispatchAgentInstruction(task: ScheduledTask) {
        // 直接调用主执行入口，减少“闹钟 -> 广播 -> Receiver -> Agent”的额外一跳。
        val application = appContext as? Application
            ?: throw IllegalStateException("Application context is required for scheduled execution")
        val resolvedSessionId = task.sessionId ?: MMKV.defaultMMKV()?.decodeString("last_session_id")

        MainEntryNew.initialize(application)
        MainEntryNew.runWithSession(
            userInput = buildImmediateExecutionInstruction(task.instruction),
            sessionId = resolvedSessionId,
            application = application,
            keepScreenAwake = true
        )
        Log.i(TAG, "Dispatched scheduled instruction directly for task=${task.id}")
    }

    private fun buildImmediateExecutionInstruction(rawInstruction: String): String {
        val instruction = stripSchedulePrefix(rawInstruction)
        // 触发阶段必须表达成“现在执行”，避免模型把历史创建语句再次理解成新建定时任务。
        return "现在立即执行以下手机操作，只执行一次：$instruction"
    }

    private fun stripSchedulePrefix(rawInstruction: String): String {
        val trimmed = rawInstruction.trim()
        if (trimmed.isBlank()) {
            return trimmed
        }

        val cleaned = trimmed
            .replace(
                Regex(
                    pattern = """^(请|帮我|麻烦你)?\s*(每天|每晚|每早|每周[一二三四五六日天]?|每个工作日|工作日|今晚|明早|明天|后天)?\s*(早上|上午|中午|下午|晚上|凌晨)?\s*\d{1,2}\s*([:：点时]\s*\d{1,2}\s*分?)?\s*(点|时)?\s*""",
                    options = setOf(RegexOption.IGNORE_CASE)
                ),
                ""
            )
            .replace(Regex("""^(定时|到点|自动)\s*"""), "")
            .trimStart('，', ',', '。', '、', ' ')
            .trim()

        return cleaned.ifBlank { trimmed }
    }

    /**
     * 统一计算重复型任务的下一次触发时间，避免 daily/weekly/workday/interval 各处复制逻辑。
     */
    private fun computeNextRecurringTriggerAtMs(task: ScheduledTask, nowMs: Long): Long {
        return when (task.repeat) {
            ScheduledTask.REPEAT_DAILY -> ScheduledTaskTimeResolver.computeNextDailyTriggerAtMs(
                dailyTime = task.dailyTime
                    ?: throw IllegalStateException("Daily task missing dailyTime: ${task.id}"),
                timezone = task.timezone,
                nowMs = nowMs
            )

            ScheduledTask.REPEAT_WEEKLY -> ScheduledTaskTimeResolver.computeNextWeeklyTriggerAtMs(
                dailyTime = task.dailyTime
                    ?: throw IllegalStateException("Weekly task missing dailyTime: ${task.id}"),
                daysOfWeek = task.daysOfWeek
                    ?: throw IllegalStateException("Weekly task missing daysOfWeek: ${task.id}"),
                timezone = task.timezone,
                nowMs = nowMs
            )

            ScheduledTask.REPEAT_WORKDAY -> ScheduledTaskTimeResolver.computeNextWorkdayTriggerAtMs(
                dailyTime = task.dailyTime
                    ?: throw IllegalStateException("Workday task missing dailyTime: ${task.id}"),
                timezone = task.timezone,
                nowMs = nowMs
            )

            ScheduledTask.REPEAT_INTERVAL -> ScheduledTaskTimeResolver.computeNextIntervalTriggerAtMs(
                intervalMinutes = task.intervalMinutes
                    ?: throw IllegalStateException("Interval task missing intervalMinutes: ${task.id}"),
                nowMs = nowMs,
                baseTriggerAtMs = task.nextTriggerAtMs
            )

            else -> throw IllegalStateException("Unsupported recurring task repeat=${task.repeat}")
        }
    }
}
