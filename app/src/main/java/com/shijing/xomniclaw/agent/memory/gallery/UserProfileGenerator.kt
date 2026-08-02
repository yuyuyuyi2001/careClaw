package com.shijing.xomniclaw.agent.memory.gallery

import com.shijing.xomniclaw.agent.memory.MemoryManager
import java.text.SimpleDateFormat
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 用户画像生成器。
 *
 * v2 版本继续保持“规则聚合、可解释”的路线，但把画像拆成：
 * 1. 稳定画像：身份、偏好、节奏、风险；
 * 2. 近期摘要：最近 7 天主题与最近同步概况；
 * 3. 近期细节：仅保存在文件中，不建议默认全部注入上下文。
 */
class UserProfileGenerator(
    private val memoryManager: MemoryManager,
    private val repository: GalleryMemoryRepository
) {
    companion object {
        private val PROFILE_TIME_FORMAT =
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        private val MEMORY_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

        private val topicMapping = mapOf(
            "work" to "工作",
            "learning" to "学习",
            "shopping" to "购物",
            "travel" to "出行",
            "social" to "社交",
            "screenshot" to "截图整理",
            "camera" to "拍照记录",
            "uncategorized" to "日常内容"
        )

        private val recurringNeedMapping = mapOf(
            "work" to "信息总结",
            "learning" to "知识整理",
            "shopping" to "商品比较",
            "travel" to "行程规划",
            "social" to "消息整理",
            "screenshot" to "截图归档"
        )

        private val roleMapping = mapOf(
            "work" to "知识工作者",
            "shopping" to "内容消费者",
            "travel" to "旅行规划者",
            "learning" to "持续学习者",
            "social" to "社交沟通者"
        )
    }

    suspend fun buildUserProfile(): String {
        val imageMemories = repository.readImageMemories()
        // 显式用户偏好与交互约定以 AGENTS.md（执行手册）中「全局交互准则」等为参考
        val userContext = memoryManager.readWorkspaceMarkdownFile("AGENTS.md")
        val longTermMemory = memoryManager.readMemory()
        val recentLogs = loadRecentLogs(limit = 3)
        val parsedEntries = parseImageMemories(imageMemories)
        val taskMemorySignals = parseTaskMemorySignals(longTermMemory, recentLogs)

        val topTags = parsedEntries.flatMap { it.tags }
            .groupingBy { it }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            .take(5)

        val topBuckets = parsedEntries.mapNotNull { it.bucketName }
            .groupingBy { it }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            .take(5)

        val profile = UserProfileV2(
            lastUpdated = PROFILE_TIME_FORMAT.format(Date()),
            profileVersion = "v2",
            confidenceNote = buildConfidenceNote(parsedEntries.size, recentLogs.size),
            identitySnapshot = IdentitySnapshot(
                preferredName = extractPreferredName(userContext, longTermMemory),
                likelyRoles = deriveLikelyRoles(topTags),
                primaryLanguage = extractPrimaryLanguage(userContext),
                timezone = TimeZone.getDefault().id
            ),
            stablePreferences = StablePreferences(
                responseStyle = deriveResponseStyle(userContext, taskMemorySignals),
                preferredTopics = derivePreferredTopics(topTags, taskMemorySignals),
                contentPreferences = deriveContentPreferences(parsedEntries, topBuckets, taskMemorySignals),
                recurringNeeds = deriveRecurringNeeds(topTags, taskMemorySignals)
            ),
            lifestyleAndRoutine = LifestyleAndRoutine(
                activeTimePattern = deriveActiveTimePattern(parsedEntries),
                likelyWorkLifeMode = deriveWorkLifeMode(topTags, topBuckets),
                planningStyle = derivePlanningStyle(parsedEntries, topTags)
            ),
            interestDistribution = InterestDistribution(
                topInterestTags = topTags.map { it.key to it.value },
                topGallerySources = topBuckets.map { it.key to it.value }
            ),
            importantContextHints = deriveImportantContextHints(parsedEntries, topTags, topBuckets, taskMemorySignals),
            privacyAndRiskNotes = derivePrivacyAndRiskNotes(parsedEntries, taskMemorySignals),
            recentSignalsSummary = RecentSignalsSummary(
                last7dTopics = deriveRecentTopics(parsedEntries),
                lastSyncSummary = buildLastSyncSummary(parsedEntries)
            ),
            provenance = Provenance(
                imageMemoriesCount = parsedEntries.size,
                longTermMemoryAvailable = longTermMemory.isNotBlank(),
                recentLogsLoaded = recentLogs.size,
                generatedFrom = listOf(
                    "memory/IMAGE-MEMORY.md",
                    "MEMORY.md",
                    "memory/YYYY-MM-DD.md"
                )
            ),
            recentSignalsDetails = RecentSignalsDetails(
                recentFocuses = deriveRecentFocuses(parsedEntries, recentLogs, taskMemorySignals),
                latestMemoryHighlights = (
                    parsedEntries.takeLast(5).map { it.summary }.filter { it.isNotBlank() } +
                        taskMemorySignals.recentTaskHighlights
                    ).distinct().take(8)
            )
        )

        return UserProfileMarkdownFormatter.toMarkdown(profile)
    }

    private suspend fun loadRecentLogs(limit: Int): List<String> {
        return memoryManager.listLogs()
            .take(limit)
            .mapNotNull { date ->
                memoryManager.getLogByDate(date).takeIf { it.isNotBlank() }
            }
    }

    private fun parseImageMemories(markdown: String): List<ParsedImageMemory> {
        if (markdown.isBlank()) {
            return emptyList()
        }

        val chunks = markdown.split(Regex("(?m)^## "))
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.startsWith("# Image Memories") }

        return chunks.mapNotNull { chunk ->
            val lines = chunk.lines()
            if (lines.isEmpty()) {
                return@mapNotNull null
            }
            val displayName = lines.first().removePrefix("## ").trim()
            val timestampText = extractField(lines, "time")
                ?: extractField(lines, "timestamp")
                .orEmpty()
            val summary = extractField(lines, "summary").orEmpty()
            val albumOrBucket = extractField(lines, "album")
                ?: extractField(lines, "bucket")
            val tags = inferCompactTags(displayName, summary, albumOrBucket)
            ParsedImageMemory(
                displayName = displayName,
                bucketName = albumOrBucket?.takeIf { it.isNotBlank() },
                tags = tags,
                summary = summary,
                sensitivity = "low",
                extractedText = "",
                timestamp = parseTimestamp(timestampText)
            )
        }
    }

    private fun extractField(lines: List<String>, field: String): String? {
        val prefix = "- $field:"
        return lines.firstOrNull { it.startsWith(prefix) }
            ?.substringAfter(prefix)
            ?.trim()
    }

    private fun parseTimestamp(value: String): LocalDateTime? {
        if (value.isBlank()) {
            return null
        }
        return runCatching {
            LocalDateTime.parse(value, MEMORY_TIME_FORMATTER)
        }.getOrNull()
    }

    /**
     * image-memories 极简条目不保存独立 tags 字段。
     * 从文件名、summary 以及 `album`（图片来源目录）做 best effort 推断，让 user-profile 继续可用。
     */
    private fun inferCompactTags(
        displayName: String,
        summary: String,
        albumOrBucket: String?
    ): List<String> {
        val normalized = "$displayName $summary ${albumOrBucket.orEmpty()}".lowercase(Locale.getDefault())
        val tags = linkedSetOf<String>()
        if (normalized.contains("截图") || normalized.contains("screen") || normalized.contains("screenshot")) {
            tags += "screenshot"
        }
        if (normalized.contains("工作") || normalized.contains("文档") || normalized.contains("表格") || normalized.contains("电脑")) {
            tags += "work"
        }
        if (normalized.contains("学习") || normalized.contains("课程") || normalized.contains("书") || normalized.contains("笔记")) {
            tags += "learning"
        }
        if (normalized.contains("购物") || normalized.contains("小票") || normalized.contains("发票") || normalized.contains("订单")) {
            tags += "shopping"
        }
        if (normalized.contains("聊天") || normalized.contains("消息") || normalized.contains("社交")) {
            tags += "social"
        }
        if (normalized.contains("出行") || normalized.contains("旅行") || normalized.contains("酒店") || normalized.contains("机票")) {
            tags += "travel"
        }
        if (normalized.contains("相机") || normalized.contains("拍摄") || normalized.contains("照片")) {
            tags += "camera"
        }
        if (tags.isEmpty()) {
            tags += "uncategorized"
        }
        return tags.toList()
    }

    private fun buildConfidenceNote(imageMemoryCount: Int, recentLogsCount: Int): String {
        val confidence = when {
            imageMemoryCount >= 30 || recentLogsCount >= 5 -> "high"
            imageMemoryCount >= 10 || recentLogsCount >= 2 -> "medium"
            else -> "low"
        }
        return "This profile is inferred from gallery memories, long-term memory, and recent logs. Evidence confidence: $confidence."
    }

    private fun extractPreferredName(userContext: String, longTermMemory: String): String {
        val candidateTexts = listOf(userContext, longTermMemory)
        val patterns = listOf(
            Regex("(?im)^\\s*preferred_name\\s*[:：]\\s*(.+)$"),
            Regex("(?im)^\\s*name\\s*[:：]\\s*(.+)$"),
            Regex("(?im)^\\s*称呼\\s*[:：]\\s*(.+)$"),
            Regex("(?im)^\\s*昵称\\s*[:：]\\s*(.+)$")
        )
        patterns.forEach { pattern ->
            candidateTexts.forEach { text ->
                val match = pattern.find(text)
                if (match != null) {
                    return match.groupValues[1].trim()
                }
            }
        }
        return "unknown"
    }

    private fun extractPrimaryLanguage(userContext: String): String {
        val normalized = userContext.lowercase(Locale.getDefault())
        return when {
            normalized.contains("use chinese") || normalized.contains("中文") -> "zh-CN"
            normalized.contains("english") -> "en-US"
            else -> Locale.getDefault().toLanguageTag()
        }
    }

    private fun deriveResponseStyle(userContext: String, taskSignals: TaskMemorySignals): String {
        val normalized = userContext.lowercase(Locale.getDefault())
        val traits = mutableListOf<String>()
        if (normalized.contains("brief")) traits += "简洁"
        if (normalized.contains("clear")) traits += "结构化"
        if (normalized.contains("success confirmations")) traits += "偏执行导向"
        traits += taskSignals.responseStyleHints
        return traits.distinct().joinToString(" / ").ifBlank { "结构化 / 偏执行导向" }
    }

    private fun deriveLikelyRoles(topTags: List<Map.Entry<String, Int>>): List<String> {
        val roles = topTags.mapNotNull { roleMapping[it.key] }.distinct().toMutableList()
        if (roles.isEmpty()) {
            roles += "个人设备用户"
        }
        return roles.take(4)
    }

    private fun derivePreferredTopics(
        topTags: List<Map.Entry<String, Int>>,
        taskSignals: TaskMemorySignals
    ): List<String> {
        val topics = topTags.map { topicMapping[it.key] ?: it.key }
            .filter { it.isNotBlank() }
            .plus(taskSignals.preferredTaskTopics)
            .distinct()
        return if (topics.isEmpty()) listOf("日常内容") else topics.take(5)
    }

    private fun deriveContentPreferences(
        entries: List<ParsedImageMemory>,
        topBuckets: List<Map.Entry<String, Int>>,
        taskSignals: TaskMemorySignals
    ): List<String> {
        val preferences = mutableListOf<String>()
        if (entries.isNotEmpty()) {
            val screenshotRatio = entries.count { it.tags.contains("screenshot") } / entries.size.toDouble()
            val extractedTextRatio = entries.count { it.extractedText.isNotBlank() } / entries.size.toDouble()
            val cameraBucketRatio = entries.count { (it.bucketName ?: "").contains("camera", ignoreCase = true) } / entries.size.toDouble()

            if (screenshotRatio >= 0.35 || topBuckets.firstOrNull()?.key?.contains("screenshot", ignoreCase = true) == true) {
                preferences += "截图信息密集"
            }
            if (extractedTextRatio >= 0.30) {
                preferences += "偏文字内容"
            }
            if (cameraBucketRatio >= 0.25) {
                preferences += "偏拍照记录"
            }
            if (entries.any { it.summary.contains("新闻") || it.summary.contains("热点") || it.summary.contains("资讯") }) {
                preferences += "偏资讯型内容"
            }
        }
        preferences += taskSignals.contentPreferences

        return preferences.distinct().ifEmpty { listOf("内容偏好多样") }.take(4)
    }

    private fun deriveRecurringNeeds(
        topTags: List<Map.Entry<String, Int>>,
        taskSignals: TaskMemorySignals
    ): List<String> {
        val needs = topTags.mapNotNull { recurringNeedMapping[it.key] }.distinct().toMutableList()
        needs += taskSignals.recurringNeeds
        if (needs.isEmpty()) {
            needs += "上下文补充"
        }
        if ("信息总结" in needs && "截图归档" !in needs) {
            needs += "下一步建议"
        }
        return needs.take(5)
    }

    private fun deriveActiveTimePattern(entries: List<ParsedImageMemory>): String {
        val timestamps = entries.mapNotNull { it.timestamp }
        if (timestamps.isEmpty()) {
            return "unknown"
        }

        val averageHour = timestamps.map { it.hour }.average()
        val workdayRatio = timestamps.count { it.dayOfWeek in listOf(
            DayOfWeek.MONDAY,
            DayOfWeek.TUESDAY,
            DayOfWeek.WEDNESDAY,
            DayOfWeek.THURSDAY,
            DayOfWeek.FRIDAY
        ) } / timestamps.size.toDouble()

        val period = when {
            averageHour >= 19 || averageHour < 6 -> "晚间活跃"
            averageHour in 6.0..11.9 -> "上午活跃"
            else -> "白天活跃"
        }
        val cadence = if (workdayRatio >= 0.65) "工作日高频" else "周内外均衡"
        return "$period / $cadence"
    }

    private fun deriveWorkLifeMode(
        topTags: List<Map.Entry<String, Int>>,
        topBuckets: List<Map.Entry<String, Int>>
    ): String {
        val tagKeys = topTags.map { it.key }
        return when {
            "work" in tagKeys && "screenshot" in tagKeys -> "工作与工具类截图较多"
            "travel" in tagKeys || "camera" in tagKeys -> "生活记录与出行内容较多"
            topBuckets.any { it.key.contains("screenshot", ignoreCase = true) } -> "偏截图驱动的信息留存"
            else -> "工作生活混合型使用"
        }
    }

    private fun derivePlanningStyle(
        entries: List<ParsedImageMemory>,
        topTags: List<Map.Entry<String, Int>>
    ): String {
        val tagKeys = topTags.map { it.key }
        val hasTaskSignals = entries.any {
            it.summary.contains("安排") || it.summary.contains("待办") || it.summary.contains("提醒")
        }
        return when {
            "work" in tagKeys || hasTaskSignals -> "偏任务驱动"
            "travel" in tagKeys || "camera" in tagKeys -> "偏生活记录"
            else -> "偏临时记录"
        }
    }

    private fun deriveImportantContextHints(
        entries: List<ParsedImageMemory>,
        topTags: List<Map.Entry<String, Int>>,
        topBuckets: List<Map.Entry<String, Int>>,
        taskSignals: TaskMemorySignals
    ): List<String> {
        val hints = mutableListOf<String>()
        val tagKeys = topTags.map { it.key }

        if ("work" in tagKeys || "screenshot" in tagKeys) {
            hints += "常保存工作截图与待处理信息，适合在任务执行时提供总结与下一步建议。"
        }
        if ("shopping" in tagKeys) {
            hints += "若任务涉及购物或比价，可优先结合近期图片记忆判断关注点。"
        }
        if ("social" in tagKeys) {
            hints += "近期有社交或聊天类内容时，任务执行前可优先检索相关图片记忆。"
        }
        if (hints.isEmpty() && entries.isNotEmpty()) {
            val dominantBucket = topBuckets.firstOrNull()?.key ?: "相册"
            hints += "当前画像主要来自 $dominantBucket 内容，建议在图片相关任务中结合 IMAGE-MEMORY.md 使用。"
        }
        hints += taskSignals.importantHints
        return hints.distinct().take(6)
    }

    private fun derivePrivacyAndRiskNotes(
        entries: List<ParsedImageMemory>,
        taskSignals: TaskMemorySignals
    ): PrivacyAndRiskNotes {
        val avoidTopics = (listOf("证件号", "支付细节", "验证码") + taskSignals.riskNotes).distinct().take(8)
        if (entries.isEmpty()) {
            return PrivacyAndRiskNotes(
                highSensitivityRatio = "unknown",
                avoidTopics = avoidTopics,
                profileGenerationMode = "filtered_evidence_based_v2"
            )
        }

        val mediumOrHighCount = entries.count { it.sensitivity == "medium" || it.sensitivity == "high" }
        val ratio = mediumOrHighCount / entries.size.toDouble()
        val label = when {
            ratio >= 0.45 -> "high"
            ratio >= 0.20 -> "medium"
            else -> "low"
        }
        return PrivacyAndRiskNotes(
            highSensitivityRatio = label,
            avoidTopics = avoidTopics,
            profileGenerationMode = "filtered_evidence_based_v2"
        )
    }

    private fun deriveRecentTopics(entries: List<ParsedImageMemory>): List<String> {
        val latestTimestamp = entries.mapNotNull { it.timestamp }.maxOrNull() ?: return listOf("unknown")
        val recentEntries = entries.filter { entry ->
            entry.timestamp != null && entry.timestamp.isAfter(latestTimestamp.minusDays(7))
        }
        if (recentEntries.isEmpty()) {
            return listOf("unknown")
        }
        val topicCounts = recentEntries.flatMap { it.tags }
            .groupingBy { topicMapping[it] ?: it }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            .take(4)
            .map { it.key }
        return if (topicCounts.isEmpty()) listOf("unknown") else topicCounts
    }

    private fun buildLastSyncSummary(entries: List<ParsedImageMemory>): String {
        if (entries.isEmpty()) {
            return "暂无图片记忆同步结果。"
        }
        val latestEntry = entries.maxByOrNull { it.timestamp ?: LocalDateTime.MIN }
        val latestTimeText = latestEntry?.timestamp?.format(MEMORY_TIME_FORMATTER) ?: "unknown"
        return "最近同步后共纳入 ${entries.size} 条图片记忆，最新图片时间为 $latestTimeText。"
    }

    private fun deriveRecentFocuses(
        entries: List<ParsedImageMemory>,
        recentLogs: List<String>,
        taskSignals: TaskMemorySignals
    ): List<String> {
        val focuses = entries.takeLast(3).map { it.summary }.filter { it.isNotBlank() }.toMutableList()
        if (recentLogs.isNotEmpty()) {
            focuses += "近期日志存在 ${recentLogs.size} 份更新，可在需要时结合 daily log 获取更细上下文。"
        }
        focuses += taskSignals.recentTaskHighlights
        return focuses.distinct().take(4)
    }

    private fun parseTaskMemorySignals(
        longTermMemory: String,
        recentLogs: List<String>
    ): TaskMemorySignals {
        val combined = (longTermMemory + "\n" + recentLogs.joinToString("\n")).lowercase(Locale.getDefault())
        val preferredTopics = mutableListOf<String>()
        val recurringNeeds = mutableListOf<String>()
        val contentPreferences = mutableListOf<String>()
        val responseStyleHints = mutableListOf<String>()
        val importantHints = mutableListOf<String>()
        val riskNotes = mutableListOf<String>()
        val highlights = longTermMemory.lines()
            .filter { it.startsWith("- ") && !it.contains("暂无稳定记忆") }
            .takeLast(6)
            .map { it.removePrefix("- ").take(180) }

        if (combined.contains("小红书") || combined.contains("新闻") || combined.contains("资讯")) {
            preferredTopics += "资讯总结"
            recurringNeeds += "App 内搜索与总结"
            contentPreferences += "偏资讯型内容"
        }
        if (combined.contains("定时") || combined.contains("全局记忆进化")) {
            recurringNeeds += "定时自动化"
            importantHints += "用户会使用定时任务长期运行自动化，应优先保持任务指令可重复、可诊断。"
        }
        if (combined.contains("相册") || combined.contains("image-memories")) {
            recurringNeeds += "相册记忆维护"
        }
        if (combined.contains("简洁") || combined.contains("中文")) {
            responseStyleHints += "中文 / 简洁"
        }
        if (combined.contains("coloros") || combined.contains("息屏") || combined.contains("权限")) {
            riskNotes += "Android 权限、息屏和 ROM 后台限制"
            importantHints += "涉及后台、息屏或跨 App 自动化时，需要优先检查权限和系统限制。"
        }

        return TaskMemorySignals(
            preferredTaskTopics = preferredTopics.distinct(),
            recurringNeeds = recurringNeeds.distinct(),
            contentPreferences = contentPreferences.distinct(),
            responseStyleHints = responseStyleHints.distinct(),
            importantHints = importantHints.distinct(),
            riskNotes = riskNotes.distinct(),
            recentTaskHighlights = highlights
        )
    }

    private data class ParsedImageMemory(
        val displayName: String,
        val bucketName: String?,
        val tags: List<String>,
        val summary: String,
        val sensitivity: String,
        val extractedText: String,
        val timestamp: LocalDateTime?
    )

    private data class TaskMemorySignals(
        val preferredTaskTopics: List<String>,
        val recurringNeeds: List<String>,
        val contentPreferences: List<String>,
        val responseStyleHints: List<String>,
        val importantHints: List<String>,
        val riskNotes: List<String>,
        val recentTaskHighlights: List<String>
    )
}
