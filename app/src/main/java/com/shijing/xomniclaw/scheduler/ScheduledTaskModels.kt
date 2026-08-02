package com.shijing.xomniclaw.scheduler

/**
 * 定时任务数据模型。
 *
 * 这里保持模型扁平化，便于 JSON 持久化和后续在 Receiver / Skill / Scheduler 之间复用。
 */
data class ScheduledTaskStoreFile(
    val version: Int = 1,
    val tasks: List<ScheduledTask> = emptyList()
)

/**
 * 定时任务实体。
 *
 * - `instruction` 是真正要交给 Agent 执行的指令，因此可以泛化成“到点后做任何当前 Agent 能做的事情”。
 * - `repeat` 目前支持一次性、每天固定时间、每周几、工作日和固定分钟间隔几种模式。
 * - 诊断字段用于区分“闹钟本身延迟”和“到点后 Agent 链路延迟”，便于后续在真机上排查低时延问题。
 */
data class ScheduledTask(
    val id: String,
    val name: String,
    val instruction: String,
    val repeat: String = REPEAT_ONCE,
    val runAtMs: Long? = null,
    val dailyTime: String? = null,
    val daysOfWeek: List<Int>? = null,
    val intervalMinutes: Int? = null,
    val timezone: String? = null,
    val sessionId: String? = null,
    val exact: Boolean = true,
    val allowWhileIdle: Boolean = true,
    var enabled: Boolean = true,
    val createdAtMs: Long,
    var updatedAtMs: Long,
    var nextTriggerAtMs: Long? = null,
    var lastTriggeredAtMs: Long? = null,
    var lastExpectedTriggerAtMs: Long? = null,
    var lastAlarmReceivedAtMs: Long? = null,
    var lastExecutionStartedAtMs: Long? = null,
    var lastTriggerDelayMs: Long? = null,
    var lastDispatchLatencyMs: Long? = null,
    var lastTriggerSource: String? = null,
    var lastWakeSummary: String? = null
) {
    companion object {
        const val REPEAT_ONCE = "once"
        const val REPEAT_DAILY = "daily"
        const val REPEAT_WEEKLY = "weekly"
        const val REPEAT_WORKDAY = "workday"
        const val REPEAT_INTERVAL = "interval"
    }
}
