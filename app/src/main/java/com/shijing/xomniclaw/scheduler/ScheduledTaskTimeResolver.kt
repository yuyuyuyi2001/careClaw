package com.shijing.xomniclaw.scheduler

import java.time.Instant
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * 定时任务时间解析工具。
 *
 * 这里把所有“字符串时间 -> 触发时间戳”的逻辑收口，方便测试和后续扩展更多周期规则。
 */
object ScheduledTaskTimeResolver {
    private val workdayDaysOfWeek = listOf(
        DayOfWeek.MONDAY.value,
        DayOfWeek.TUESDAY.value,
        DayOfWeek.WEDNESDAY.value,
        DayOfWeek.THURSDAY.value,
        DayOfWeek.FRIDAY.value
    )

    private val localDateTimeFormatters = listOf(
        DateTimeFormatter.ISO_LOCAL_DATE_TIME,
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"),
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    )

    fun resolveOneTimeTriggerAtMs(
        runAt: String?,
        runAtMs: Long?,
        delaySeconds: Long?,
        nowMs: Long = System.currentTimeMillis(),
        timezone: String? = null
    ): Long {
        runAtMs?.let { return it }

        if (!runAt.isNullOrBlank()) {
            return parseDateTime(runAt, timezone)
        }

        delaySeconds?.let { return nowMs + (it * 1000L) }

        throw IllegalArgumentException("Must provide one of run_at, run_at_ms, or delay_seconds")
    }

    fun computeNextDailyTriggerAtMs(
        dailyTime: String,
        timezone: String? = null,
        nowMs: Long = System.currentTimeMillis()
    ): Long {
        return computeNextWeeklyTriggerAtMs(
            dailyTime = dailyTime,
            daysOfWeek = DayOfWeek.values().map { it.value },
            timezone = timezone,
            nowMs = nowMs
        )
    }

    /**
     * 计算下一次“每周几固定时间”触发时间。
     *
     * `daysOfWeek` 使用 ISO-8601 编码：1=周一 ... 7=周日。
     */
    fun computeNextWeeklyTriggerAtMs(
        dailyTime: String,
        daysOfWeek: List<Int>,
        timezone: String? = null,
        nowMs: Long = System.currentTimeMillis()
    ): Long {
        val zoneId = resolveZoneId(timezone)
        val now = Instant.ofEpochMilli(nowMs).atZone(zoneId)
        val localTime = parseLocalTime(dailyTime)
        val normalizedDays = normalizeDaysOfWeek(daysOfWeek)
        val today = now.dayOfWeek.value

        for (offset in 0..7) {
            val candidateDay = ((today - 1 + offset) % 7) + 1
            if (candidateDay !in normalizedDays) {
                continue
            }

            val candidate = now.plusDays(offset.toLong())
                .withHour(localTime.hour)
                .withMinute(localTime.minute)
                .withSecond(0)
                .withNano(0)

            if (candidate.isAfter(now)) {
                return candidate.toInstant().toEpochMilli()
            }
        }

        throw IllegalStateException("Failed to compute next weekly trigger for days=$normalizedDays")
    }

    /**
     * 计算下一个工作日固定时间触发时间。
     */
    fun computeNextWorkdayTriggerAtMs(
        dailyTime: String,
        timezone: String? = null,
        nowMs: Long = System.currentTimeMillis()
    ): Long {
        return computeNextWeeklyTriggerAtMs(
            dailyTime = dailyTime,
            daysOfWeek = workdayDaysOfWeek,
            timezone = timezone,
            nowMs = nowMs
        )
    }

    /**
     * 计算固定分钟间隔任务的下一次触发时间。
     *
     * 这里默认按“上一次理论/实际触发时刻继续往后滚动”，避免因单次执行变慢导致周期持续漂移。
     */
    fun computeNextIntervalTriggerAtMs(
        intervalMinutes: Int,
        nowMs: Long = System.currentTimeMillis(),
        baseTriggerAtMs: Long? = null
    ): Long {
        require(intervalMinutes > 0) { "interval_minutes must be greater than 0" }
        val intervalMs = intervalMinutes * 60_000L
        val base = baseTriggerAtMs ?: nowMs
        if (base > nowMs) {
            return base
        }

        val elapsed = nowMs - base
        val steps = (elapsed / intervalMs) + 1
        return base + (steps * intervalMs)
    }

    private fun parseDateTime(value: String, timezone: String?): Long {
        try {
            return Instant.parse(value).toEpochMilli()
        } catch (_: DateTimeParseException) {
            // 优先支持标准 ISO-8601；解析失败后再尝试本地时间格式。
        }

        val zoneId = resolveZoneId(timezone)
        val localDateTime = localDateTimeFormatters.firstNotNullOfOrNull { formatter ->
            try {
                LocalDateTime.parse(value, formatter)
            } catch (_: DateTimeParseException) {
                null
            }
        } ?: throw IllegalArgumentException(
            "Unsupported run_at format. Use ISO-8601 or 'yyyy-MM-dd HH:mm[:ss]'"
        )

        return localDateTime.atZone(zoneId).toInstant().toEpochMilli()
    }

    private fun parseLocalTime(value: String): LocalTime {
        return try {
            LocalTime.parse(value, DateTimeFormatter.ofPattern("HH:mm"))
        } catch (e: DateTimeParseException) {
            throw IllegalArgumentException("daily_time must use HH:mm format, e.g. 12:00")
        }
    }

    /**
     * 标准化星期列表并去重排序。
     */
    fun normalizeDaysOfWeek(daysOfWeek: List<Int>): List<Int> {
        val normalized = daysOfWeek.distinct().sorted()
        if (normalized.isEmpty()) {
            throw IllegalArgumentException("days_of_week cannot be empty")
        }
        if (normalized.any { it !in 1..7 }) {
            throw IllegalArgumentException("days_of_week must use ISO values 1..7 (Mon..Sun)")
        }
        return normalized
    }

    private fun resolveZoneId(timezone: String?): ZoneId {
        return try {
            if (timezone.isNullOrBlank()) ZoneId.systemDefault() else ZoneId.of(timezone)
        } catch (e: Exception) {
            throw IllegalArgumentException("Invalid timezone: $timezone")
        }
    }
}
