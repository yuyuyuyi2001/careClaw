package com.shijing.xomniclaw.agent.tools

import android.content.Context
import android.util.Log
import com.shijing.xomniclaw.providers.FunctionDefinition
import com.shijing.xomniclaw.providers.ParametersSchema
import com.shijing.xomniclaw.providers.PropertySchema
import com.shijing.xomniclaw.providers.ToolDefinition
import com.shijing.xomniclaw.scheduler.AlarmTaskScheduler
import com.shijing.xomniclaw.scheduler.ScheduledTask
import com.shijing.xomniclaw.scheduler.ScheduledTaskManager
import com.shijing.xomniclaw.scheduler.ScheduledTaskTimeResolver
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * schedule_task skill
 *
 * 使用 Android AlarmManager 创建系统级定时任务，到点后把指令桥接回现有 Agent 执行入口。
 * 这样既能实现“每天 12 点打开某个 App 并操作”，也能实现“定时做任何当前 Agent 能做的事情”。
 */
class ScheduleTaskSkill(private val context: Context) : Skill {
    companion object {
        private const val TAG = "ScheduleTaskSkill"
        private val DISPLAY_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        private const val LLM_FUNCTION_DESCRIPTION = "Schedule, list, or cancel AlarmManager-based tasks. " +
            "action: create|list|cancel. create: task_name + instruction + repeat(once|daily|weekly|workday|interval) " +
            "and one of run_at (ISO or yyyy-MM-dd HH:mm), run_at_ms, delay_seconds, daily_time (HH:mm), " +
            "or interval_minutes; days_of_week for weekly. list: no extra fields. cancel: task_id. " +
            "Optional: timezone, session_id, exact, allow_while_idle."
    }

    override val name = "schedule_task"
    override val description =
        "AlarmManager task: create/list/cancel. Full field semantics in getToolDefinition LLM block."

    override fun getToolDefinition(): ToolDefinition {
        return ToolDefinition(
            type = "function",
            function = FunctionDefinition(
                name = name,
                description = LLM_FUNCTION_DESCRIPTION,
                parameters = ParametersSchema(
                    type = "object",
                    properties = mapOf(
                        "action" to PropertySchema(
                            type = "string",
                            description = "—",
                            enum = listOf("create", "list", "cancel")
                        ),
                        "task_name" to PropertySchema(type = "string", description = "—"),
                        "instruction" to PropertySchema(type = "string", description = "—"),
                        "repeat" to PropertySchema(
                            type = "string",
                            description = "—",
                            enum = listOf("once", "daily", "weekly", "workday", "interval")
                        ),
                        "run_at" to PropertySchema(type = "string", description = "—"),
                        "run_at_ms" to PropertySchema(type = "number", description = "—"),
                        "delay_seconds" to PropertySchema(type = "number", description = "—"),
                        "daily_time" to PropertySchema(type = "string", description = "—"),
                        "days_of_week" to PropertySchema(
                            type = "array",
                            description = "—",
                            items = PropertySchema(type = "string", description = "—")
                        ),
                        "interval_minutes" to PropertySchema(type = "number", description = "—"),
                        "timezone" to PropertySchema(type = "string", description = "—"),
                        "session_id" to PropertySchema(type = "string", description = "—"),
                        "task_id" to PropertySchema(type = "string", description = "—"),
                        "exact" to PropertySchema(type = "boolean", description = "—"),
                        "allow_while_idle" to PropertySchema(type = "boolean", description = "—")
                    ),
                    required = listOf("action")
                )
            )
        )
    }

    override suspend fun execute(args: Map<String, Any?>): SkillResult {
        val action = (args["action"] as? String)?.lowercase()?.trim()
            ?: return SkillResult.error("Missing required parameter: action")
        val manager = ScheduledTaskManager(context)

        return try {
            when (action) {
                "create" -> createTask(args, manager)
                "list" -> listTasks(manager)
                "cancel" -> cancelTask(args, manager)
                else -> SkillResult.error("Unsupported action: $action")
            }
        } catch (e: Exception) {
            Log.e(TAG, "schedule_task execution failed", e)
            SkillResult.error("Failed to execute schedule_task: ${e.message}")
        }
    }

    private fun createTask(
        args: Map<String, Any?>,
        manager: ScheduledTaskManager
    ): SkillResult {
        val taskName = (args["task_name"] as? String)?.trim()
            ?: return SkillResult.error("Missing required parameter: task_name")
        val instruction = (args["instruction"] as? String)?.trim()
            ?: return SkillResult.error("Missing required parameter: instruction")
        val repeat = ((args["repeat"] as? String)?.lowercase()?.trim()).orEmpty()
            .ifEmpty { if (args["daily_time"] != null) ScheduledTask.REPEAT_DAILY else ScheduledTask.REPEAT_ONCE }
        val sessionId = (args["session_id"] as? String)?.trim()?.ifEmpty { null }
        val exact = args["exact"] as? Boolean ?: true
        val allowWhileIdle = args["allow_while_idle"] as? Boolean ?: true

        val createdTask = when (repeat) {
            ScheduledTask.REPEAT_DAILY -> {
                val dailyTime = (args["daily_time"] as? String)?.trim()
                    ?: return SkillResult.error("daily_time is required when repeat=daily")
                val timezone = (args["timezone"] as? String)?.trim()?.ifEmpty { null }
                manager.createDailyTask(
                    name = taskName,
                    instruction = instruction,
                    dailyTime = dailyTime,
                    timezone = timezone,
                    sessionId = sessionId,
                    exact = exact,
                    allowWhileIdle = allowWhileIdle
                )
            }

            ScheduledTask.REPEAT_WEEKLY -> {
                val dailyTime = (args["daily_time"] as? String)?.trim()
                    ?: return SkillResult.error("daily_time is required when repeat=weekly")
                val daysOfWeek = parseDaysOfWeek(args["days_of_week"])
                val timezone = (args["timezone"] as? String)?.trim()?.ifEmpty { null }
                manager.createWeeklyTask(
                    name = taskName,
                    instruction = instruction,
                    dailyTime = dailyTime,
                    daysOfWeek = daysOfWeek,
                    timezone = timezone,
                    sessionId = sessionId,
                    exact = exact,
                    allowWhileIdle = allowWhileIdle
                )
            }

            ScheduledTask.REPEAT_WORKDAY -> {
                val dailyTime = (args["daily_time"] as? String)?.trim()
                    ?: return SkillResult.error("daily_time is required when repeat=workday")
                val timezone = (args["timezone"] as? String)?.trim()?.ifEmpty { null }
                manager.createWorkdayTask(
                    name = taskName,
                    instruction = instruction,
                    dailyTime = dailyTime,
                    timezone = timezone,
                    sessionId = sessionId,
                    exact = exact,
                    allowWhileIdle = allowWhileIdle
                )
            }

            ScheduledTask.REPEAT_INTERVAL -> {
                val intervalMinutes = (args["interval_minutes"] as? Number)?.toInt()
                    ?: return SkillResult.error("interval_minutes is required when repeat=interval")
                manager.createIntervalTask(
                    name = taskName,
                    instruction = instruction,
                    intervalMinutes = intervalMinutes,
                    sessionId = sessionId,
                    exact = exact,
                    allowWhileIdle = allowWhileIdle
                )
            }

            ScheduledTask.REPEAT_ONCE -> {
                val triggerAtMs = ScheduledTaskTimeResolver.resolveOneTimeTriggerAtMs(
                    runAt = (args["run_at"] as? String)?.trim(),
                    runAtMs = (args["run_at_ms"] as? Number)?.toLong(),
                    delaySeconds = (args["delay_seconds"] as? Number)?.toLong()
                )
                manager.createOneTimeTask(
                    name = taskName,
                    instruction = instruction,
                    triggerAtMs = triggerAtMs,
                    sessionId = sessionId,
                    exact = exact,
                    allowWhileIdle = allowWhileIdle
                )
            }

            else -> return SkillResult.error("repeat must be one of 'once', 'daily', 'weekly', 'workday', or 'interval'")
        }

        val schedulingMode = AlarmTaskScheduler(context).getSchedulingMode(createdTask)

        return SkillResult.success(
            content = buildString {
                appendLine("Scheduled task created successfully.")
                appendLine("Task ID: ${createdTask.id}")
                appendLine("Name: ${createdTask.name}")
                appendLine("Repeat: ${createdTask.repeat}")
                appendLine("Next trigger: ${formatTimestamp(createdTask.nextTriggerAtMs, createdTask.timezone)}")
                appendLine("Scheduling mode: ${schedulingMode.description}")
                if (createdTask.exact && !schedulingMode.willUseExact) {
                    appendLine("Warning: exact alarm permission is not currently available, so the task may be delayed by minutes.")
                }
                append("Instruction: ${createdTask.instruction}")
            },
            metadata = mapOf(
                "task_id" to createdTask.id,
                "repeat" to createdTask.repeat,
                "next_trigger_at_ms" to createdTask.nextTriggerAtMs,
                "scheduling_mode" to schedulingMode.description,
                "will_use_exact_alarm" to schedulingMode.willUseExact
            )
        )
    }

    private fun listTasks(manager: ScheduledTaskManager): SkillResult {
        val tasks = manager.listTasks()
        if (tasks.isEmpty()) {
            return SkillResult.success("No scheduled tasks found.")
        }

        val content = tasks.joinToString("\n\n") { task ->
            val schedulingMode = AlarmTaskScheduler(context).getSchedulingMode(task)
            buildString {
                appendLine("## ${task.name}")
                appendLine("- ID: ${task.id}")
                appendLine("- Enabled: ${task.enabled}")
                appendLine("- Repeat: ${task.repeat}")
                appendLine("- Next trigger: ${formatTimestamp(task.nextTriggerAtMs, task.timezone)}")
                appendLine("- Last triggered: ${formatTimestamp(task.lastTriggeredAtMs, task.timezone)}")
                appendLine("- Scheduling mode: ${schedulingMode.description}")
                task.daysOfWeek?.takeIf { it.isNotEmpty() }?.let {
                    appendLine("- Days of week: ${it.joinToString(",")}")
                }
                task.intervalMinutes?.let { appendLine("- Interval minutes: $it") }
                task.lastTriggerDelayMs?.let { appendLine("- Last alarm delay: ${it}ms") }
                task.lastDispatchLatencyMs?.let { appendLine("- Last dispatch latency: ${it}ms") }
                task.lastTriggerSource?.let { appendLine("- Last trigger source: $it") }
                task.lastWakeSummary?.let { appendLine("- Last wake summary: $it") }
                append("- Instruction: ${task.instruction}")
            }
        }

        return SkillResult.success(
            content = content,
            metadata = mapOf("tasks_count" to tasks.size)
        )
    }

    private fun cancelTask(
        args: Map<String, Any?>,
        manager: ScheduledTaskManager
    ): SkillResult {
        val taskId = (args["task_id"] as? String)?.trim()
            ?: return SkillResult.error("Missing required parameter: task_id")

        val removed = manager.cancelTask(taskId)
        return if (removed) {
            SkillResult.success(
                content = "Scheduled task cancelled successfully: $taskId",
                metadata = mapOf("task_id" to taskId)
            )
        } else {
            SkillResult.error("Task not found: $taskId")
        }
    }

    private fun formatTimestamp(timestampMs: Long?, timezone: String?): String {
        if (timestampMs == null) {
            return "N/A"
        }

        val zoneId = if (timezone.isNullOrBlank()) ZoneId.systemDefault() else ZoneId.of(timezone)
        return Instant.ofEpochMilli(timestampMs)
            .atZone(zoneId)
            .format(DISPLAY_FORMATTER)
    }

    /**
     * 允许 Agent 传入数字、英文缩写或中文星期，降低参数构造门槛。
     */
    private fun parseDaysOfWeek(raw: Any?): List<Int> {
        val values = raw as? List<*>
            ?: throw IllegalArgumentException("days_of_week is required when repeat=weekly")

        val mapping = mapOf(
            "1" to 1, "mon" to 1, "monday" to 1, "周一" to 1, "星期一" to 1,
            "2" to 2, "tue" to 2, "tuesday" to 2, "周二" to 2, "星期二" to 2,
            "3" to 3, "wed" to 3, "wednesday" to 3, "周三" to 3, "星期三" to 3,
            "4" to 4, "thu" to 4, "thursday" to 4, "周四" to 4, "星期四" to 4,
            "5" to 5, "fri" to 5, "friday" to 5, "周五" to 5, "星期五" to 5,
            "6" to 6, "sat" to 6, "saturday" to 6, "周六" to 6, "星期六" to 6,
            "7" to 7, "sun" to 7, "sunday" to 7, "周日" to 7, "星期日" to 7, "周天" to 7, "星期天" to 7
        )

        val parsed = values.map { item ->
            val token = item?.toString()?.trim()?.lowercase(Locale.getDefault())
                ?: throw IllegalArgumentException("days_of_week contains blank value")
            mapping[token] ?: throw IllegalArgumentException("Unsupported weekday token: $token")
        }
        return ScheduledTaskTimeResolver.normalizeDaysOfWeek(parsed)
    }
}
