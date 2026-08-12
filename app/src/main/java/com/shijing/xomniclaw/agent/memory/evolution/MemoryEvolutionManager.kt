package com.shijing.xomniclaw.agent.memory.evolution

import android.content.Context
import android.util.Log
import com.shijing.xomniclaw.agent.memory.MemoryManager
import com.shijing.xomniclaw.agent.memory.gallery.GalleryMemoryRepository
import com.shijing.xomniclaw.agent.memory.gallery.UserProfileGenerator
import com.shijing.xomniclaw.providers.UnifiedLLMProvider
import com.shijing.xomniclaw.providers.llm.Message
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
        private const val DEFAULT_MEMORY_HEADER = "# CareClaw 全局记忆"
        private val TIME_FORMAT = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        private val JSON_BLOCK_RE = Regex("\\{[\\s\\S]*?\\}")

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
    private val llmProvider = UnifiedLLMProvider(context.applicationContext)

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

        val accepted = batch.mapNotNull { judgeMemory(it) }
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
            skippedCandidates = batch.size - accepted.size,
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

    /**
     * 用 LLM 评判单个任务事件是否值得沉淀为长期记忆，并按四类（用户偏好/任务经验/失败教训/项目上下文）归类。
     * 返回 null 表示「不值得记忆」或 LLM 调用失败（跳过该事件，不阻断整轮进化）。
     */
    private suspend fun judgeMemory(event: MemoryEvolutionEvent): MemoryCandidate? {
        val text = "${event.userInput}\n${event.finalContent}".trim()
        if (text.isBlank()) return null
        val systemPrompt = """
            你是 CareClaw 的「长期记忆提炼器」，判断一段 Agent 任务记录是否值得沉淀为长期记忆。
            只输出一个 JSON 对象，结构：
            {"valuable":true/false,"category":"...","title":"...","content":"..."}
            四类记忆（category 只取其一）：
            - USER_PREFERENCE：用户偏好、习惯、长期约束（如「以后装软件用应用商店」）
            - TASK_WORKFLOW：可复用的任务流程、工具组合经验（如「查攻略=打开小红书→搜索→看地点」）
            - FAILURE_LESSON：失败原因与绕过方式（如「小红书打开失败，改走浏览器」）
            - PROJECT_CONTEXT：用户持续推进的项目/长期上下文
            规则：
            - valuable=true 仅当内容对长期理解用户或复用任务有真实价值；琐碎、一次性、无意义内容返回 false
            - 绝不输出密码、验证码、token、api key、银行卡号等敏感信息，必要时模糊化
            - title 简短（≤20 字）；content 简洁（≤80 字），用中文
        """.trimIndent()
        val userPrompt = "请判断以下 Agent 任务记录是否有值得长期沉淀的记忆：\n---\n$text"
        return try {
            val response = llmProvider.chatWithTools(
                messages = listOf(
                    Message(role = "system", content = systemPrompt),
                    Message(role = "user", content = userPrompt)
                ),
                tools = null,
                temperature = 0.2,
                maxTokens = 256,
                reasoningEnabled = false
            )
            parseJudgeResult(response.content, event)
        } catch (e: Exception) {
            Log.w(TAG, "Memory judge failed for event ${event.id}: ${e.message}")
            null
        }
    }

    private fun parseJudgeResult(content: String?, event: MemoryEvolutionEvent): MemoryCandidate? {
        val text = content?.trim().orEmpty()
        if (text.isBlank() || text.startsWith("Error:", ignoreCase = true)) return null
        val json = runCatching {
            if (text.startsWith("{")) JSONObject(text) else JSONObject(JSON_BLOCK_RE.find(text)?.value.orEmpty())
        }.getOrNull() ?: return null
        if (!json.optBoolean("valuable", false)) return null
        val category = runCatching { MemoryCategory.valueOf(json.optString("category")) }.getOrNull() ?: return null
        val title = json.optString("title").trim().take(20).ifBlank { return null }
        val content = json.optString("content").trim().take(MAX_CANDIDATE_TEXT_CHARS).ifBlank { return null }
        return MemoryCandidate(
            category = category,
            title = title,
            content = content,
            confidence = 1.0,
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
            appendLine("此文件保存用户使用 CareClaw 执行任务过程中沉淀的长期记忆。")
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

}
