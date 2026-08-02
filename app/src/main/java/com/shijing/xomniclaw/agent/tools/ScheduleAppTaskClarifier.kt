package com.shijing.xomniclaw.agent.tools

import com.shijing.xomniclaw.scheduler.ScheduledTask
import java.util.Locale

/**
 * `schedule_app_task` 的歧义识别器。
 *
 * 这层不负责真正创建任务，只负责判断当前参数是否足够明确：
 * 1. App 是否明确；
 * 2. 操作是否仍然停留在占位表达；
 * 3. 调度信息是否完整且可继续解析。
 */
object ScheduleAppTaskClarifier {
    data class ClarificationRequest(
        val questions: List<String>,
        val reasonCodes: List<String>
    )

    private val placeholderAppTokens = setOf(
        "app", "某app", "某个app", "某应用", "应用", "软件", "那个app", "这个app"
    )

    private val placeholderOperationPatterns = listOf(
        Regex("^x+$", RegexOption.IGNORE_CASE),
        Regex("^x\\s*操作$", RegexOption.IGNORE_CASE),
        Regex("^xx+$", RegexOption.IGNORE_CASE),
        Regex("某操作"),
        Regex("某个操作"),
        Regex("什么操作"),
        Regex("待定")
    )

    private val vagueScheduleTokens = listOf(
        "定时", "稍后", "一会", "过会", "有空", "隔一阵子", "上班前", "下班前", "晚点", "早一点"
    )

    /**
     * 根据当前提取结果给出追问建议。
     */
    fun inspect(
        appName: String?,
        operation: String?,
        schedulePhrase: String?,
        repeat: String?,
        daysOfWeekProvided: Boolean,
        intervalMinutesProvided: Boolean,
        runAtProvided: Boolean
    ): ClarificationRequest? {
        val questions = mutableListOf<String>()
        val reasonCodes = mutableListOf<String>()

        if (appName.isNullOrBlank() || looksLikePlaceholderApp(appName)) {
            questions += "你要打开哪个 App？请明确给出应用名，例如“小红书”或“企业微信”。"
            reasonCodes += "ambiguous_app_name"
        }

        if (looksLikePlaceholderOperation(operation)) {
            questions += "打开 App 后具体要做什么？请把操作说清楚，例如“搜索新闻并总结前三条结果”。"
            reasonCodes += "ambiguous_operation"
        }

        val normalizedRepeat = repeat?.trim()?.lowercase(Locale.getDefault())
        val normalizedSchedulePhrase = schedulePhrase?.trim().orEmpty()

        when (normalizedRepeat) {
            null, "" -> {
                if (normalizedSchedulePhrase.isBlank()) {
                    questions += "任务什么时候执行？请提供明确时间，例如“每天晚上10点”或“每隔45分钟”。"
                    reasonCodes += "missing_schedule_phrase"
                }
            }

            ScheduledTask.REPEAT_ONCE -> {
                if (!runAtProvided) {
                    questions += "这是一次性任务，请告诉我具体执行时间，或提供延迟秒数。"
                    reasonCodes += "missing_one_time_trigger"
                }
            }

            ScheduledTask.REPEAT_DAILY,
            ScheduledTask.REPEAT_WORKDAY -> {
                if (normalizedSchedulePhrase.isBlank()) {
                    questions += "请补充明确时间，例如“每天晚上10点”或“每个工作日上午10点”。"
                    reasonCodes += "missing_schedule_phrase"
                }
            }

            ScheduledTask.REPEAT_WEEKLY -> {
                if (normalizedSchedulePhrase.isBlank() && !daysOfWeekProvided) {
                    questions += "请补充每周几和具体时间，例如“每周三早上10点”。"
                    reasonCodes += "missing_weekly_schedule"
                }
            }

            ScheduledTask.REPEAT_INTERVAL -> {
                if (normalizedSchedulePhrase.isBlank() && !intervalMinutesProvided) {
                    questions += "请补充执行间隔，例如“每隔45分钟”或直接提供 interval_minutes。"
                    reasonCodes += "missing_interval_schedule"
                }
            }
        }

        if (normalizedSchedulePhrase.isNotBlank() && looksLikeVagueSchedule(normalizedSchedulePhrase)) {
            questions += "当前时间表达还不够精确，请改成可执行的说法，例如“每周三早上10点”或“每隔45分钟”。"
            reasonCodes += "ambiguous_schedule_phrase"
        }

        if (questions.isEmpty()) {
            return null
        }

        return ClarificationRequest(
            questions = questions.distinct(),
            reasonCodes = reasonCodes.distinct()
        )
    }

    /**
     * 生成给 LLM 和用户都容易理解的追问文本。
     */
    fun buildResponse(request: ClarificationRequest): String {
        return buildString {
            appendLine("Need clarification before creating the scheduled app task.")
            request.questions.forEachIndexed { index, question ->
                appendLine("${index + 1}. $question")
            }
            append("请先确认以上信息，我再继续创建任务。")
        }
    }

    private fun looksLikePlaceholderApp(appName: String): Boolean {
        return appName.trim().lowercase(Locale.getDefault()) in placeholderAppTokens
    }

    private fun looksLikePlaceholderOperation(operation: String?): Boolean {
        val normalized = operation?.trim().orEmpty()
        if (normalized.isBlank()) {
            return false
        }

        return placeholderOperationPatterns.any { it.containsMatchIn(normalized) }
    }

    private fun looksLikeVagueSchedule(schedulePhrase: String): Boolean {
        val normalized = schedulePhrase.trim()
        return vagueScheduleTokens.any { token -> normalized.contains(token) }
    }
}
