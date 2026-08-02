package com.shijing.xomniclaw.agent.memory.evolution

import android.content.Context
import android.util.Log
import com.shijing.xomniclaw.agent.memory.MemoryManager
import com.shijing.xomniclaw.agent.memory.gallery.GalleryMemoryRepository
import com.shijing.xomniclaw.agent.memory.gallery.UserProfileGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * 全局记忆进化管理器。
 *
 * 普通 Agent 任务结束时只写入 pending 队列；真正更新 MEMORY.md / USER-PROFILE.md
 * 由专门的 memory_evolution 定时任务批量执行，避免每轮任务直接污染长期记忆。
 */
class MemoryEvolutionManager(
    private val context: Context,
    private val memoryManager: MemoryManager,
    private val settingsStore: MemoryEvolutionSettingsStore = MemoryEvolutionSettingsStore(),
    private val statusStore: MemoryEvolutionStatusStore = MemoryEvolutionStatusStore()
) {
    companion object {
        private const val TAG = "MemoryEvolutionManager"
        private const val PENDING_EVENTS_FILE = ".memory-evolution-pending.jsonl"
        private const val MAX_EVENT_TEXT_CHARS = 1200
        private const val MAX_CANDIDATE_TEXT_CHARS = 280
        private const val DEFAULT_MEMORY_HEADER = "# X-OmniClaw 全局记忆"
        private val TIME_FORMAT = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

        fun shouldSkipRunRecording(userInput: String): Boolean {
            val normalized = userInput.lowercase(Locale.getDefault())
            return normalized.contains("memory_evolution") ||
                normalized.contains("全局记忆进化") ||
                normalized.contains("更新全局记忆") ||
                ((normalized.contains("更新") || normalized.contains("刷新") || normalized.contains("整理")) &&
                    normalized.contains("记忆"))
        }
    }

    private val workspaceDir = File("/sdcard/.xomniclaw/workspace")
    private val memoryDir = File(workspaceDir, "memory")
    private val pendingFile = File(memoryDir, PENDING_EVENTS_FILE)
    private val repository = GalleryMemoryRepository(memoryManager)
    private val userProfileGenerator = UserProfileGenerator(memoryManager, repository)

    suspend fun recordAgentRun(
        sessionId: String,
        userInput: String,
        finalContent: String,
        success: Boolean,
        errorMessage: String?,
        toolsUsed: List<String>
    ) = withContext(Dispatchers.IO) {
        if (shouldSkipRunRecording(userInput)) {
            Log.d(TAG, "Skip recording memory evolution task itself")
            return@withContext
        }
        memoryDir.mkdirs()
        val event = MemoryEvolutionEvent(
            id = UUID.randomUUID().toString(),
            sessionId = sessionId,
            userInput = userInput.take(MAX_EVENT_TEXT_CHARS),
            finalContent = finalContent.take(MAX_EVENT_TEXT_CHARS),
            success = success,
            errorMessage = errorMessage?.take(MAX_EVENT_TEXT_CHARS),
            toolsUsed = toolsUsed.distinct().take(12),
            triggeredAtMs = System.currentTimeMillis()
        )
        pendingFile.appendText(event.toJson().toString() + "\n")
        Log.d(TAG, "Recorded pending memory event: ${event.id}")
    }

    suspend fun runEvolution(): MemoryEvolutionReport = withContext(Dispatchers.IO) {
        val settings = settingsStore.load()
        if (!settings.enabled) {
            return@withContext MemoryEvolutionReport(
                processedEvents = 0,
                acceptedCandidates = 0,
                skippedCandidates = 0,
                globalMemoryUpdated = false,
                profileUpdated = false,
                pendingEventsRemaining = countPendingEvents(),
                message = "Memory evolution is disabled."
            )
        }

        val allEvents = readPendingEvents()
        val batch = allEvents.take(settings.maxPendingEventsPerRun)
        if (batch.isEmpty()) {
            val profile = rebuildUserProfile()
            saveStatus(
                processedEvents = 0,
                acceptedCandidates = 0,
                message = "No pending task memories. User profile refreshed.",
                pendingEvents = 0,
                userProfileChars = profile.length
            )
            return@withContext MemoryEvolutionReport(
                processedEvents = 0,
                acceptedCandidates = 0,
                skippedCandidates = 0,
                globalMemoryUpdated = false,
                profileUpdated = true,
                pendingEventsRemaining = 0,
                message = "No pending task memories. User profile refreshed."
            )
        }

        batch.forEach { event ->
            memoryManager.appendToToday(formatDailyEvent(event))
        }

        val candidates = batch.flatMap(::extractCandidates)
        val accepted = candidates
            .filter { it.confidence >= 0.55 }
            .filterNot { MemoryEvolutionPolicy.isSensitive(it.content) }
            .distinctBy { it.category to MemoryEvolutionPolicy.normalizeForDedupe(it.title + it.content) }

        val globalUpdated = if (accepted.isNotEmpty()) {
            val merged = mergeGlobalMemory(
                current = memoryManager.readMemory(),
                candidates = accepted,
                maxChars = settings.maxGlobalChars
            )
            memoryManager.writeMemory(merged)
            true
        } else {
            false
        }

        val profile = rebuildUserProfile()
        val remaining = allEvents.drop(batch.size)
        writePendingEvents(remaining)
        saveStatus(
            processedEvents = batch.size,
            acceptedCandidates = accepted.size,
            message = "Processed ${batch.size} task memory events, accepted ${accepted.size} candidates.",
            pendingEvents = remaining.size,
            userProfileChars = profile.length
        )

        MemoryEvolutionReport(
            processedEvents = batch.size,
            acceptedCandidates = accepted.size,
            skippedCandidates = candidates.size - accepted.size,
            globalMemoryUpdated = globalUpdated,
            profileUpdated = true,
            pendingEventsRemaining = remaining.size,
            message = "Processed ${batch.size} task memory events, accepted ${accepted.size} candidates."
        )
    }

    fun getStatus(): MemoryEvolutionStatus {
        val status = statusStore.load()
        val currentPending = countPendingEvents()
        return status.copy(pendingEvents = currentPending)
    }

    private suspend fun rebuildUserProfile(): String {
        val profile = userProfileGenerator.buildUserProfile()
        repository.writeUserProfile(profile)
        return profile
    }

    private fun extractCandidates(event: MemoryEvolutionEvent): List<MemoryCandidate> {
        val candidates = mutableListOf<MemoryCandidate>()
        val combined = "${event.userInput}\n${event.finalContent}".trim()

        extractPreference(event)?.let { candidates += it }
        if (event.success && looksLikeReusableTask(event.userInput, event.toolsUsed)) {
            candidates += MemoryCandidate(
                category = MemoryCategory.TASK_WORKFLOW,
                title = summarizeTitle(event.userInput),
                content = "用户曾使用 X-OmniClaw 完成：${event.userInput.take(MAX_CANDIDATE_TEXT_CHARS)}。结果摘要：${event.finalContent.take(MAX_CANDIDATE_TEXT_CHARS)}",
                confidence = 0.68,
                sourceEventId = event.id
            )
        }
        if (!event.success || !event.errorMessage.isNullOrBlank() || combined.contains("失败") || combined.contains("权限")) {
            val reason = event.errorMessage ?: event.finalContent
            candidates += MemoryCandidate(
                category = MemoryCategory.FAILURE_LESSON,
                title = summarizeTitle(event.userInput),
                content = "任务相关经验：${event.userInput.take(160)}；现象/结论：${reason.take(MAX_CANDIDATE_TEXT_CHARS)}",
                confidence = if (event.errorMessage.isNullOrBlank()) 0.58 else 0.75,
                sourceEventId = event.id
            )
        }
        if (looksLikeProjectContext(event.userInput)) {
            candidates += MemoryCandidate(
                category = MemoryCategory.PROJECT_CONTEXT,
                title = summarizeTitle(event.userInput),
                content = "用户持续推进的上下文：${event.userInput.take(MAX_CANDIDATE_TEXT_CHARS)}",
                confidence = 0.6,
                sourceEventId = event.id
            )
        }

        return candidates
    }

    private fun extractPreference(event: MemoryEvolutionEvent): MemoryCandidate? {
        val text = event.userInput
        val preferenceMarkers = listOf("以后", "记住", "我希望", "我喜欢", "不要", "总是", "默认")
        if (preferenceMarkers.none { text.contains(it) }) {
            return null
        }
        return MemoryCandidate(
            category = MemoryCategory.USER_PREFERENCE,
            title = summarizeTitle(text),
            content = "用户偏好/约束：${text.take(MAX_CANDIDATE_TEXT_CHARS)}",
            confidence = 0.72,
            sourceEventId = event.id
        )
    }

    private fun mergeGlobalMemory(
        current: String,
        candidates: List<MemoryCandidate>,
        maxChars: Int
    ): String {
        val base = normalizeGlobalMemory(current)
        val lines = base.lines().toMutableList()
        candidates.groupBy { it.category }.forEach { (category, items) ->
            ensureSection(lines, category.sectionTitle)
            val insertIndex = lines.indexOf(category.sectionTitle) + 1
            val existingText = lines.joinToString("\n")
            val newLines = items.mapNotNull { candidate ->
                val line = "- ${TIME_FORMAT.format(Date())} | ${candidate.title}: ${candidate.content}"
                if (existingText.contains(candidate.title) || existingText.contains(candidate.content.take(60))) {
                    null
                } else {
                    line
                }
            }
            if (newLines.isNotEmpty()) {
                lines.addAll(insertIndex, newLines)
            }
        }
        return MemoryEvolutionPolicy.compactGlobalMemory(lines.joinToString("\n"), maxChars)
    }

    private fun normalizeGlobalMemory(current: String): String {
        if (current.isBlank() || current.startsWith("# Long-term Memory")) {
            return buildGlobalMemoryTemplate()
        }
        val lines = current.lines().toMutableList()
        if (lines.firstOrNull()?.startsWith("#") != true) {
            lines.add(0, DEFAULT_MEMORY_HEADER)
        }
        MemoryCategory.values().forEach { ensureSection(lines, it.sectionTitle) }
        return lines.joinToString("\n")
    }

    private fun buildGlobalMemoryTemplate(): String {
        return buildString {
            appendLine(DEFAULT_MEMORY_HEADER)
            appendLine()
            appendLine("此文件保存用户使用 X-OmniClaw 执行任务过程中沉淀的长期记忆。")
            appendLine("相册内容写入 memory/IMAGE-MEMORY.md；用户画像写入 memory/USER-PROFILE.md。")
            appendLine()
            MemoryCategory.values().forEach { category ->
                appendLine(category.sectionTitle)
                appendLine("- 暂无稳定记忆。")
                appendLine()
            }
        }.trim()
    }

    private fun ensureSection(lines: MutableList<String>, sectionTitle: String) {
        if (lines.none { it.trim() == sectionTitle }) {
            if (lines.lastOrNull()?.isNotBlank() == true) {
                lines.add("")
            }
            lines.add(sectionTitle)
            lines.add("- 暂无稳定记忆。")
        }
    }

    private fun formatDailyEvent(event: MemoryEvolutionEvent): String {
        return buildString {
            appendLine("- type: agent_task")
            appendLine("- event_id: ${event.id}")
            appendLine("- session_id: ${event.sessionId}")
            appendLine("- success: ${event.success}")
            appendLine("- tools: ${event.toolsUsed.joinToString(",").ifBlank { "none" }}")
            appendLine("- user_input: ${event.userInput}")
            if (event.errorMessage != null) {
                appendLine("- error: ${event.errorMessage}")
            }
            append("- result_summary: ${event.finalContent.take(300)}")
        }
    }

    private fun readPendingEvents(): List<MemoryEvolutionEvent> {
        if (!pendingFile.exists()) {
            return emptyList()
        }
        return pendingFile.readLines()
            .mapNotNull { line ->
                runCatching { JSONObject(line).toEvent() }.getOrNull()
            }
    }

    private fun writePendingEvents(events: List<MemoryEvolutionEvent>) {
        pendingFile.parentFile?.mkdirs()
        if (events.isEmpty()) {
            pendingFile.writeText("")
            return
        }
        pendingFile.writeText(events.joinToString("\n") { it.toJson().toString() } + "\n")
    }

    private fun countPendingEvents(): Int {
        return runCatching {
            if (!pendingFile.exists()) 0 else pendingFile.readLines().count { it.isNotBlank() }
        }.getOrDefault(0)
    }

    private suspend fun saveStatus(
        processedEvents: Int,
        acceptedCandidates: Int,
        message: String,
        pendingEvents: Int,
        userProfileChars: Int
    ) {
        val globalMemoryChars = runCatching { memoryManager.readMemory().length }.getOrDefault(0)
        statusStore.save(
            MemoryEvolutionStatus(
                lastRunAtMs = System.currentTimeMillis(),
                processedEvents = processedEvents,
                acceptedCandidates = acceptedCandidates,
                globalMemoryChars = globalMemoryChars,
                userProfileChars = userProfileChars,
                pendingEvents = pendingEvents,
                lastMessage = message
            )
        )
    }

    private fun MemoryEvolutionEvent.toJson(): JSONObject {
        return JSONObject()
            .put("id", id)
            .put("sessionId", sessionId)
            .put("userInput", userInput)
            .put("finalContent", finalContent)
            .put("success", success)
            .put("errorMessage", errorMessage)
            .put("toolsUsed", JSONArray(toolsUsed))
            .put("triggeredAtMs", triggeredAtMs)
    }

    private fun JSONObject.toEvent(): MemoryEvolutionEvent {
        val tools = optJSONArray("toolsUsed")?.let { array ->
            (0 until array.length()).map { index -> array.optString(index) }
        } ?: emptyList()
        return MemoryEvolutionEvent(
            id = optString("id"),
            sessionId = optString("sessionId"),
            userInput = optString("userInput"),
            finalContent = optString("finalContent"),
            success = optBoolean("success"),
            errorMessage = optString("errorMessage").takeIf { it.isNotBlank() && it != "null" },
            toolsUsed = tools,
            triggeredAtMs = optLong("triggeredAtMs")
        )
    }

    private fun looksLikeReusableTask(userInput: String, toolsUsed: List<String>): Boolean {
        val keywords = listOf("打开", "搜索", "总结", "发送", "定时", "同步", "扫描", "整理", "分析", "发布")
        return keywords.any { userInput.contains(it) } || toolsUsed.any { it in setOf("device", "gallery_memory", "schedule_task", "schedule_app_task") }
    }

    private fun looksLikeProjectContext(userInput: String): Boolean {
        val keywords = listOf("项目", "代码", "模块", "方案", "设计", "实现", "优化", "修复")
        return keywords.any { userInput.contains(it) }
    }

    private fun summarizeTitle(text: String): String {
        return text.replace(Regex("\\s+"), " ").trim().take(36).ifBlank { "未命名任务" }
    }

}
