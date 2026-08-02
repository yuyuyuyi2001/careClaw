package com.shijing.xomniclaw.scheduler

import java.util.Locale

/**
 * 自然语言调度解析器。
 *
 * 目标是把“每周三早上10点”“每个工作日上午10点”“每隔45分钟”这类口语，
 * 转成底层调度系统可直接消费的结构化规则。
 */
object NaturalLanguageScheduleParser {
    data class ParsedSchedule(
        val repeat: String,
        val dailyTime: String? = null,
        val daysOfWeek: List<Int>? = null,
        val intervalMinutes: Int? = null
    )

    private val intervalMinutePattern = Regex("^每隔\\s*(\\d+)\\s*(分钟|分)$")
    private val intervalHourPattern = Regex("^每隔\\s*(\\d+)\\s*(个)?小时$")
    private val workdayPattern = Regex("^(每个?工作日|每逢工作日|工作日)\\s*(.+)$")
    private val dailyPattern = Regex("^(每天|每日)\\s*(.+)$")
    private val weeklyPattern = Regex("^(每周|每星期|周)([一二三四五六日天1-7,，、\\s和及]+)\\s*(.+)$")

    /**
     * 解析自然语言调度短语。
     *
     * 支持：
     * 1. 每天晚上12点
     * 2. 每周三早上10点 / 每周一、三、五早上10点
     * 3. 每个工作日上午10点
     * 4. 每隔45分钟 / 每隔2小时 / 每隔半小时
     * 5. 仅传入“早上10点”时默认按 daily 处理，兼容旧行为
     */
    fun parse(input: String): ParsedSchedule {
        val normalized = normalize(input)
        if (normalized.isBlank()) {
            throw IllegalArgumentException("time_text cannot be blank")
        }

        parseInterval(normalized)?.let { return it }

        weeklyPattern.matchEntire(normalized)?.let { match ->
            val daysOfWeek = parseWeekdayExpression(match.groupValues[2])
            val dailyTime = NaturalLanguageTimeParser.parseToDailyTime(match.groupValues[3])
            return ParsedSchedule(
                repeat = ScheduledTask.REPEAT_WEEKLY,
                dailyTime = dailyTime,
                daysOfWeek = daysOfWeek
            )
        }

        workdayPattern.matchEntire(normalized)?.let { match ->
            val dailyTime = NaturalLanguageTimeParser.parseToDailyTime(match.groupValues[2])
            return ParsedSchedule(
                repeat = ScheduledTask.REPEAT_WORKDAY,
                dailyTime = dailyTime,
                daysOfWeek = listOf(1, 2, 3, 4, 5)
            )
        }

        dailyPattern.matchEntire(normalized)?.let { match ->
            val dailyTime = NaturalLanguageTimeParser.parseToDailyTime(match.groupValues[2])
            return ParsedSchedule(
                repeat = ScheduledTask.REPEAT_DAILY,
                dailyTime = dailyTime
            )
        }

        // 兼容旧入口：只给“早上10点”这类纯时间时，默认理解成 daily。
        return ParsedSchedule(
            repeat = ScheduledTask.REPEAT_DAILY,
            dailyTime = NaturalLanguageTimeParser.parseToDailyTime(normalized)
        )
    }

    private fun parseInterval(normalized: String): ParsedSchedule? {
        if (normalized == "每隔半小时") {
            return ParsedSchedule(
                repeat = ScheduledTask.REPEAT_INTERVAL,
                intervalMinutes = 30
            )
        }

        intervalMinutePattern.matchEntire(normalized)?.let { match ->
            return ParsedSchedule(
                repeat = ScheduledTask.REPEAT_INTERVAL,
                intervalMinutes = match.groupValues[1].toInt()
            )
        }

        intervalHourPattern.matchEntire(normalized)?.let { match ->
            return ParsedSchedule(
                repeat = ScheduledTask.REPEAT_INTERVAL,
                intervalMinutes = match.groupValues[1].toInt() * 60
            )
        }

        return null
    }

    /**
     * 解析“周一、三、五”“135”“一三五”等星期表达。
     */
    private fun parseWeekdayExpression(expression: String): List<Int> {
        val compact = expression.replace("星期", "").replace("周", "")
        val parts = compact.split(Regex("[,，、\\s和及]+")).filter { it.isNotBlank() }
        val tokens = if (parts.size > 1) {
            parts
        } else {
            expandCompactWeekdayTokens(parts.firstOrNull().orEmpty())
        }

        val days = tokens.map { token ->
            when (token) {
                "1", "一" -> 1
                "2", "二" -> 2
                "3", "三" -> 3
                "4", "四" -> 4
                "5", "五" -> 5
                "6", "六" -> 6
                "7", "日", "天" -> 7
                else -> throw IllegalArgumentException("Unsupported weekday expression: $expression")
            }
        }

        return ScheduledTaskTimeResolver.normalizeDaysOfWeek(days)
    }

    private fun expandCompactWeekdayTokens(compact: String): List<String> {
        if (compact.isBlank()) {
            throw IllegalArgumentException("Weekly schedule is missing weekday expression")
        }

        return when {
            compact.all { it in "1234567" } -> compact.map { it.toString() }
            compact.all { it in "一二三四五六日天" } -> compact.map { it.toString() }
            else -> listOf(compact)
        }
    }

    private fun normalize(input: String): String {
        return input.trim()
            .replace("：", ":")
            .replace("个工作日", "工作日")
            .lowercase(Locale.getDefault())
    }
}
