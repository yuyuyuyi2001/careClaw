package com.shijing.xomniclaw.scheduler

import java.util.Locale

/**
 * 定时任务星期解析器。
 *
 * 统一处理 UI / Skill / 后续接口层传入的星期表达，减少多处重复映射逻辑。
 * 支持：
 * - `1,3,5`
 * - `mon,wed,fri`
 * - `周三`
 * - `周一,周三,周五`
 */
object ScheduledTaskWeekdayParser {
    private val tokenMapping = mapOf(
        "1" to 1, "mon" to 1, "monday" to 1, "周一" to 1, "星期一" to 1,
        "2" to 2, "tue" to 2, "tuesday" to 2, "周二" to 2, "星期二" to 2,
        "3" to 3, "wed" to 3, "wednesday" to 3, "周三" to 3, "星期三" to 3,
        "4" to 4, "thu" to 4, "thursday" to 4, "周四" to 4, "星期四" to 4,
        "5" to 5, "fri" to 5, "friday" to 5, "周五" to 5, "星期五" to 5,
        "6" to 6, "sat" to 6, "saturday" to 6, "周六" to 6, "星期六" to 6,
        "7" to 7, "sun" to 7, "sunday" to 7, "周日" to 7, "星期日" to 7, "周天" to 7, "星期天" to 7
    )

    /**
     * 解析弹性星期输入。
     */
    fun parseFlexible(input: String): List<Int> {
        val raw = input.trim()
        require(raw.isNotBlank()) { "days_of_week cannot be blank" }

        val tokens = raw.split(Regex("[,，、\\s]+"))
            .map { it.trim() }
            .filter { it.isNotBlank() }

        val parsed = tokens.map { token ->
            val normalized = token.lowercase(Locale.getDefault())
            tokenMapping[normalized] ?: throw IllegalArgumentException("Unsupported weekday token: $token")
        }

        return ScheduledTaskTimeResolver.normalizeDaysOfWeek(parsed)
    }
}
