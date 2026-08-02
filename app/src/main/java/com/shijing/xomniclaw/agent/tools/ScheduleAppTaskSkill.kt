package com.shijing.xomniclaw.agent.tools

import android.content.Context
import android.util.Log
import com.shijing.xomniclaw.providers.FunctionDefinition
import com.shijing.xomniclaw.providers.ParametersSchema
import com.shijing.xomniclaw.providers.PropertySchema
import com.shijing.xomniclaw.providers.ToolDefinition
import com.shijing.xomniclaw.scheduler.AlarmTaskScheduler
import com.shijing.xomniclaw.scheduler.NaturalLanguageScheduleParser
import com.shijing.xomniclaw.scheduler.ScheduledTask
import com.shijing.xomniclaw.scheduler.ScheduledTaskManager
import com.shijing.xomniclaw.scheduler.ScheduledTaskTimeResolver
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 面向自然语言调度场景的高层 Skill。
 *
 * 相比 `schedule_task`，这里直接面向“打开某 App 并做某事”建模。
 *
 * 推荐流程：
 * 1. 先由 LLM 从用户整句里提取 `app_name`、`operation`、`schedule_phrase`；
 * 2. 再由规则层只解析 `schedule_phrase`，产出 repeat / dailyTime / daysOfWeek / intervalMinutes；
 * 3. 最终落到系统级定时任务。
 */
class ScheduleAppTaskSkill(private val context: Context) : Skill {
    companion object {
        private const val TAG = "ScheduleAppTaskSkill"
        private val DISPLAY_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        private const val LLM_FUNCTION_DESCRIPTION = "Open an app and run an operation on a schedule (AlarmManager). " +
            "action: create|list|cancel. Extract app_name, operation, and schedule_phrase from the user. " +
            "Do not include the schedule phrase in operation; operation is only what to do after the app opens. " +
            "schedule_phrase (or time_text) drives repeat/days_of_week/interval. cancel uses task_id. " +
            "Fields mirror schedule_task plus app_name/package_name/operation; see schema types."
    }

    override val name = "schedule_app_task"
    override val description = "NL-driven scheduled app open+do. See LLM_FUNCTION_DESCRIPTION in getToolDefinition."

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
                        "app_name" to PropertySchema(type = "string", description = "—"),
                        "package_name" to PropertySchema(type = "string", description = "—"),
                        "operation" to PropertySchema(type = "string", description = "—"),
                        "schedule_phrase" to PropertySchema(type = "string", description = "—"),
                        "repeat" to PropertySchema(
                            type = "string",
                            description = "—",
                            enum = listOf("daily", "once", "weekly", "workday", "interval")
                        ),
                        "time_text" to PropertySchema(type = "string", description = "—"),
                        "days_of_week" to PropertySchema(
                            type = "array",
                            description = "—",
                            items = PropertySchema(type = "string", description = "—")
                        ),
                        "interval_minutes" to PropertySchema(type = "number", description = "—"),
                        "timezone" to PropertySchema(type = "string", description = "—"),
                        "run_at" to PropertySchema(type = "string", description = "—"),
                        "run_at_ms" to PropertySchema(type = "number", description = "—"),
                        "delay_seconds" to PropertySchema(type = "number", description = "—"),
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
            Log.e(TAG, "schedule_app_task execution failed", e)
            SkillResult.error("Failed to execute schedule_app_task: ${e.message}")
        }
    }

    private fun createTask(args: Map<String, Any?>, manager: ScheduledTaskManager): SkillResult {
        val taskName = (args["task_name"] as? String)?.trim()?.ifEmpty { null }
        val appName = (args["app_name"] as? String)?.trim()?.ifEmpty { null }
        val operation = (args["operation"] as? String)?.trim().orEmpty()
        val packageName = (args["package_name"] as? String)?.trim()?.ifEmpty { null }
        val schedulePhrase = (args["schedule_phrase"] as? String)?.trim()?.ifEmpty { null }
            ?: (args["time_text"] as? String)?.trim()?.ifEmpty { null }
        val clarification = ScheduleAppTaskClarifier.inspect(
            appName = appName,
            operation = operation,
            schedulePhrase = schedulePhrase,
            repeat = args["repeat"] as? String,
            daysOfWeekProvided = args["days_of_week"] != null,
            intervalMinutesProvided = args["interval_minutes"] != null,
            runAtProvided = args["run_at"] != null || args["run_at_ms"] != null || args["delay_seconds"] != null
        )
        if (clarification != null) {
            return SkillResult.success(
                content = ScheduleAppTaskClarifier.buildResponse(clarification),
                metadata = mapOf(
                    "needs_clarification" to true,
                    "clarification_questions" to clarification.questions,
                    "clarification_reason_codes" to clarification.reasonCodes,
                    "task_name" to taskName,
                    "app_name" to appName,
                    "operation" to operation,
                    "schedule_phrase" to schedulePhrase
                )
            )
        }
        val resolvedAppName = appName ?: return SkillResult.error("Missing required parameter: app_name")

        // 只有在参数初步明确后，才进入规则层解析 schedule_phrase。
        val parsedNaturalSchedule = try {
            schedulePhrase?.let { NaturalLanguageScheduleParser.parse(it) }
        } catch (e: IllegalArgumentException) {
            return SkillResult.success(
                content = buildString {
                    appendLine("Need clarification before creating the scheduled app task.")
                    appendLine("1. 我还不能精确理解这个时间表达：${schedulePhrase ?: "N/A"}")
                    appendLine("2. 请改成更具体的说法，例如“每周三早上10点”“每个工作日上午10点”或“每隔45分钟”。")
                    append("请先确认后我再继续创建任务。")
                },
                metadata = mapOf(
                    "needs_clarification" to true,
                    "clarification_questions" to listOf(
                        "请把执行时间改成更明确的说法，例如“每周三早上10点”或“每隔45分钟”。"
                    ),
                    "clarification_reason_codes" to listOf("unparseable_schedule_phrase"),
                    "schedule_phrase" to schedulePhrase,
                    "parse_error" to e.message
                )
            )
        }
        val repeat = ((args["repeat"] as? String)?.lowercase()?.trim()).orEmpty()
            .ifEmpty { parsedNaturalSchedule?.repeat ?: "daily" }
        val timezone = (args["timezone"] as? String)?.trim()?.ifEmpty { null }
        val sessionId = (args["session_id"] as? String)?.trim()?.ifEmpty { null }
        val exact = args["exact"] as? Boolean ?: true
        val allowWhileIdle = args["allow_while_idle"] as? Boolean ?: true
        val resolvedTaskName = taskName ?: buildDefaultTaskName(
            appName = resolvedAppName,
            repeat = repeat
        )

        val instruction = buildInstruction(
            appName = resolvedAppName,
            packageName = packageName,
            operation = operation
        )

        val createdTask = when (repeat) {
            "daily" -> {
                val dailyTime = parsedNaturalSchedule?.dailyTime
                    ?: return SkillResult.error("schedule_phrase is required when repeat=daily")
                manager.createDailyTask(
                    name = resolvedTaskName,
                    instruction = instruction,
                    dailyTime = dailyTime,
                    timezone = timezone,
                    sessionId = sessionId,
                    exact = exact,
                    allowWhileIdle = allowWhileIdle
                )
            }

            "weekly" -> {
                val dailyTime = parsedNaturalSchedule?.dailyTime
                    ?: return SkillResult.error("schedule_phrase is required when repeat=weekly")
                val daysOfWeek = parseDaysOfWeek(args["days_of_week"])
                    ?: parsedNaturalSchedule.daysOfWeek
                    ?: return SkillResult.error(
                        "days_of_week is required when repeat=weekly, or provide schedule_phrase like '每周三早上10点'"
                    )
                manager.createWeeklyTask(
                    name = resolvedTaskName,
                    instruction = instruction,
                    dailyTime = dailyTime,
                    daysOfWeek = daysOfWeek,
                    timezone = timezone,
                    sessionId = sessionId,
                    exact = exact,
                    allowWhileIdle = allowWhileIdle
                )
            }

            "workday" -> {
                val dailyTime = parsedNaturalSchedule?.dailyTime
                    ?: return SkillResult.error("schedule_phrase is required when repeat=workday")
                manager.createWorkdayTask(
                    name = resolvedTaskName,
                    instruction = instruction,
                    dailyTime = dailyTime,
                    timezone = timezone,
                    sessionId = sessionId,
                    exact = exact,
                    allowWhileIdle = allowWhileIdle
                )
            }

            "interval" -> {
                val intervalMinutes = (args["interval_minutes"] as? Number)?.toInt()
                    ?: parsedNaturalSchedule?.intervalMinutes
                    ?: return SkillResult.error(
                        "interval_minutes is required when repeat=interval, or provide schedule_phrase like '每隔45分钟'"
                    )
                manager.createIntervalTask(
                    name = resolvedTaskName,
                    instruction = instruction,
                    intervalMinutes = intervalMinutes,
                    sessionId = sessionId,
                    exact = exact,
                    allowWhileIdle = allowWhileIdle
                )
            }

            "once" -> {
                val triggerAtMs = ScheduledTaskTimeResolver.resolveOneTimeTriggerAtMs(
                    runAt = (args["run_at"] as? String)?.trim(),
                    runAtMs = (args["run_at_ms"] as? Number)?.toLong(),
                    delaySeconds = (args["delay_seconds"] as? Number)?.toLong(),
                    timezone = timezone
                )
                manager.createOneTimeTask(
                    name = resolvedTaskName,
                    instruction = instruction,
                    triggerAtMs = triggerAtMs,
                    sessionId = sessionId,
                    exact = exact,
                    allowWhileIdle = allowWhileIdle
                )
            }

            else -> return SkillResult.error("repeat must be one of 'daily', 'once', 'weekly', 'workday', or 'interval'")
        }

        val schedulingMode = AlarmTaskScheduler(context).getSchedulingMode(createdTask)

        return SkillResult.success(
            content = buildString {
                appendLine("Scheduled app task created successfully.")
                appendLine("Task ID: ${createdTask.id}")
                appendLine("App: $resolvedAppName")
                appendLine("Repeat: ${createdTask.repeat}")
                schedulePhrase?.let { appendLine("Schedule phrase: $it") }
                appendLine("Next trigger: ${formatTimestamp(createdTask.nextTriggerAtMs, createdTask.timezone)}")
                appendLine("Scheduling mode: ${schedulingMode.description}")
                parsedNaturalSchedule?.dailyTime?.let { appendLine("Resolved daily time: $it") }
                parsedNaturalSchedule?.daysOfWeek?.let { appendLine("Resolved days of week: ${it.joinToString(",")}") }
                parsedNaturalSchedule?.intervalMinutes?.let { appendLine("Resolved interval minutes: $it") }
                if (createdTask.exact && !schedulingMode.willUseExact) {
                    appendLine("Warning: exact alarm permission is not currently available, so the task may be delayed by minutes.")
                }
                appendLine("Generated instruction: $instruction")
                append("Tip: If the target app has special UI requirements, verify the first run on a real device.")
            },
            metadata = mapOf(
                "task_id" to createdTask.id,
                "repeat" to createdTask.repeat,
                "schedule_phrase" to schedulePhrase,
                "next_trigger_at_ms" to createdTask.nextTriggerAtMs,
                "instruction" to instruction,
                "resolved_daily_time" to parsedNaturalSchedule?.dailyTime,
                "resolved_days_of_week" to parsedNaturalSchedule?.daysOfWeek,
                "resolved_interval_minutes" to parsedNaturalSchedule?.intervalMinutes,
                "scheduling_mode" to schedulingMode.description,
                "will_use_exact_alarm" to schedulingMode.willUseExact
            )
        )
    }

    private fun listTasks(manager: ScheduledTaskManager): SkillResult {
        val tasks = manager.listTasks()
        if (tasks.isEmpty()) {
            return SkillResult.success("No scheduled app tasks found.")
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

    private fun cancelTask(args: Map<String, Any?>, manager: ScheduledTaskManager): SkillResult {
        val taskId = (args["task_id"] as? String)?.trim()
            ?: return SkillResult.error("Missing required parameter: task_id")

        return if (manager.cancelTask(taskId)) {
            SkillResult.success(
                content = "Scheduled app task cancelled successfully: $taskId",
                metadata = mapOf("task_id" to taskId)
            )
        } else {
            SkillResult.error("Task not found: $taskId")
        }
    }

    private fun buildInstruction(appName: String, packageName: String?, operation: String): String {
        // 这里把高层口语参数下沉为 Agent 更容易执行的自然语言指令；执行指令里不能再带时间表达。
        val openInstruction = if (!packageName.isNullOrBlank()) {
            "打开应用 $appName（包名：$packageName）"
        } else {
            "打开$appName"
        }

        return if (operation.isBlank()) {
            openInstruction
        } else {
            "$openInstruction，然后$operation"
        }
    }

    /**
     * task_name 主要是展示用途，若上层未提供则自动生成一个稳定可读的默认值。
     */
    private fun buildDefaultTaskName(appName: String, repeat: String): String {
        return "${repeat.ifBlank { "schedule" }}-$appName-task"
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
     * 兼容 Agent 传入中文或英文星期，避免上层必须自己转 ISO 编号。
     */
    private fun parseDaysOfWeek(raw: Any?): List<Int>? {
        if (raw == null) {
            return null
        }

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
