package com.shijing.xomniclaw.scheduler

import java.util.Locale
import java.util.regex.Pattern

/**
 * 自然语言时间解析器。
 *
 * 当前聚焦高频中文表达，优先服务“每天晚上12点”“中午12点”“早上8点半”这类口语时间描述。
 * 输出统一的 `HH:mm`，方便和底层定时系统对接。
 */
object NaturalLanguageTimeParser {
    private val hhmmPattern = Pattern.compile("^([01]?\\d|2[0-3]):([0-5]\\d)$")
    private val chineseTimePattern = Pattern.compile(
        "^(凌晨|早上|早晨|上午|中午|下午|晚上|傍晚|夜里|半夜)?\\s*(\\d{1,2})\\s*点\\s*(半|(\\d{1,2})\\s*分?)?$"
    )

    fun parseToDailyTime(input: String): String {
        val normalized = input.trim().replace("：", ":").lowercase(Locale.getDefault())
        if (normalized.isBlank()) {
            throw IllegalArgumentException("time_text cannot be blank")
        }

        hhmmPattern.matcher(normalized).takeIf { it.matches() }?.let { matcher ->
            val hour = matcher.group(1).toInt()
            val minute = matcher.group(2).toInt()
            return formatHourMinute(hour, minute)
        }

        val matcher = chineseTimePattern.matcher(normalized)
        if (!matcher.matches()) {
            throw IllegalArgumentException(
                "Unsupported time_text. Try formats like '00:00', '晚上12点', '中午12点', '早上8点半'"
            )
        }

        val period = matcher.group(1)
        val rawHour = matcher.group(2).toInt()
        val halfKeyword = matcher.group(3)
        val explicitMinute = matcher.group(4)?.toInt()

        val minute = when {
            !halfKeyword.isNullOrBlank() && halfKeyword.contains("半") -> 30
            explicitMinute != null -> explicitMinute
            else -> 0
        }

        val hour = normalizeHour(period, rawHour)
        return formatHourMinute(hour, minute)
    }

    private fun normalizeHour(period: String?, rawHour: Int): Int {
        if (rawHour !in 0..23) {
            throw IllegalArgumentException("Hour must be between 0 and 23")
        }

        return when (period) {
            "凌晨", "半夜", "夜里" -> if (rawHour == 12) 0 else rawHour
            "早上", "早晨", "上午" -> if (rawHour == 12) 0 else rawHour
            "中午" -> when (rawHour) {
                in 0..10 -> rawHour + 12
                11, 12 -> 12
                else -> rawHour
            }
            "下午", "晚上", "傍晚" -> when (rawHour) {
                in 1..11 -> rawHour + 12
                12 -> if (period == "下午") 12 else 0
                else -> rawHour
            }
            else -> rawHour
        }
    }

    private fun formatHourMinute(hour: Int, minute: Int): String {
        if (minute !in 0..59) {
            throw IllegalArgumentException("Minute must be between 0 and 59")
        }
        return String.format(Locale.US, "%02d:%02d", hour, minute)
    }
}
