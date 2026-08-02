/**
 * OmniClaw Source Reference:
 * - ../xomniclaw/src/gateway/(all)
 *
 * OmniClaw adaptation: Android UI layer.
 */
package com.shijing.xomniclaw.ui.session

import android.util.Log
import com.shijing.xomniclaw.providers.LegacyMessage
import com.shijing.xomniclaw.ui.compose.ChatMessage
import com.shijing.xomniclaw.ui.compose.ChatMessageKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.LinkedHashMap
import java.util.UUID

/**
 * Session Manager - Multi-session management
 *
 * Features:
 * - Create/delete sessions
 * - Switch sessions
 * - Independent message history for each session
 * - Session metadata (title, creation time, etc.)
 */
class SessionManager {

    data class Session(
        val id: String = UUID.randomUUID().toString(),
        val title: String = "新对话",
        val createdAt: Long = System.currentTimeMillis(),
        val messages: List<ChatMessage> = emptyList(),
        val isActive: Boolean = false
    ) {
        /**
         * Generate title based on first user message
         */
        fun generateTitle(): String {
            val firstUserMessage = messages.firstOrNull { it.isUser }
            return if (firstUserMessage != null) {
                val content = firstUserMessage.content
                if (content.length > 20) {
                    content.take(20) + "..."
                } else {
                    content
                }
            } else {
                "新对话 ${createdAt}"
            }
        }
    }

    companion object {
        private const val TAG = "SessionManager"
        private const val PREF_LAST_SESSION_ID = "last_session_id"
    }

    // MMKV for persistent storage
    private val mmkv by lazy {
        com.tencent.mmkv.MMKV.defaultMMKV()
    }

    private val _sessions = MutableStateFlow<List<Session>>(listOf(createDefaultSession()))
    val sessions: StateFlow<List<Session>> = _sessions.asStateFlow()

    private val _currentSession = MutableStateFlow<Session>(_sessions.value.first())
    val currentSession: StateFlow<Session> = _currentSession.asStateFlow()

    /**
     * Create default session
     */
    private fun createDefaultSession(): Session {
        // Check if it's first run - X-OmniClaw style
        val welcomeMessage = getWelcomeMessage()

        return Session(
            title = "新对话",
            messages = listOf(
                ChatMessage(
                    content = welcomeMessage,
                    isUser = false,
                    kind = ChatMessageKind.SYSTEM
                )
            ),
            isActive = true
        )
    }

    /**
     * Whether a session is just the startup placeholder with no real conversation yet.
     */
    private fun isEphemeralDefaultSession(session: Session): Boolean {
        return session.title == "新对话" &&
            session.messages.size == 1 &&
            session.messages.firstOrNull()?.isUser == false
    }

    /**
     * Get welcome message - X-OmniClaw style
     *
     * If core workspace bootstrap files are missing, treat it as first run.
     */
    private fun getWelcomeMessage(): String {
        val workspaceDir = java.io.File("/sdcard/.xomniclaw/workspace")
        val agentsFile = java.io.File(workspaceDir, "AGENTS.md")

        val isFirstRun = !workspaceDir.exists() || !agentsFile.exists()

        return if (isFirstRun) {
            """
你好，我是你的 **Android 自动化助手**。

我可以理解文本、语音、图片和当前屏幕内容，帮你完成应用操作、信息检索、文档处理和自动化任务。

这是你第一次使用这套助手能力，我们先一起完成基础配置，让后续执行更稳定。

## 📝 需要配置的文件

你的 workspace 位于：`/sdcard/.xomniclaw/workspace/`

### 1. **AGENTS.md** - Agent 大模型专属策略（仅 Agent 调用时生效）
### 2. **OPS_GUIDE.md** - 通用执行手册（交互规范与设备执行规则）

完成这些配置后，我们就可以正式开始协作。

你可以试着对我说：`帮我打开小红书搜美食`，或者 `监控飞书消息并总结重点`
            """.trimIndent()
        } else {
            """
你好，我是你的 **Android 自动化助手**。

我可以帮你：
- 控制和测试 Android 应用
- 理解当前屏幕、图片与语音输入
- 浏览网页、搜索信息和执行自动化任务
- 处理设备操作、文件与工作流协作

告诉我你的目标，我会直接开始处理。
            """.trimIndent()
        }
    }

    /**
     * Load sessions from backend SessionManager
     * (sessions created by Feishu, Discord, WebSocket)
     */
    fun loadSessionsFromBackend() {
        try {
            val backendSessionManager = com.shijing.xomniclaw.core.MainEntryNew.getSessionManager()
            if (backendSessionManager == null) {
                android.util.Log.w("SessionManager", "Backend SessionManager not initialized")
                return
            }

            val backendSessionKeys = backendSessionManager.getAllKeys()
            if (backendSessionKeys.isEmpty()) {
                android.util.Log.d("SessionManager", "No backend sessions found")
                return
            }

            // Convert backend sessions to UI sessions
            val backendSessions = backendSessionKeys.mapNotNull { key ->
                val backendSession = backendSessionManager.get(key)
                if (backendSession != null) {
                    val type = when {
                        key.startsWith("discord_") -> "Discord"
                        key.contains("_p2p") || key.contains("_group") -> "飞书"
                        key.startsWith("session_") -> "WebSocket"
                        else -> "其他"
                    }

                    // Generate title
                    val title = if (backendSession.messages.isNotEmpty()) {
                        val firstUserMsg = backendSession.messages.firstOrNull {
                            it.role == "user"
                        }
                        if (firstUserMsg != null && firstUserMsg.content != null) {
                            val content = when (val c = firstUserMsg.content) {
                                is String -> c
                                else -> c.toString()
                            }
                            if (content.length > 15) {
                                "[$type] ${content.take(15)}..."
                            } else {
                                "[$type] $content"
                            }
                        } else {
                            "[$type] ${key.take(10)}..."
                        }
                    } else {
                        "[$type] ${key.take(10)}..."
                    }

                    val sessionAnchorMs = try {
                        java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US)
                            .parse(backendSession.createdAt)?.time ?: System.currentTimeMillis()
                    } catch (e: Exception) {
                        System.currentTimeMillis()
                    }

                    // Convert message format and keep assistant raw text intact for full timeline replay.
                    val normalizedBackendMessages = normalizeThinkingUserOrder(backendSession.messages)
                    val uiMessages = normalizedBackendMessages.mapIndexedNotNull { index, msg ->
                        ChatTimelineMapper.fromBackendMessage(
                            sessionId = key,
                            index = index,
                            message = msg,
                            baseTimeMs = sessionAnchorMs
                        )
                    }
                    val openingMessage = buildSessionOpeningMessage(key, sessionAnchorMs)
                    val uiMessagesWithOpening = listOf(openingMessage) + uiMessages

                    Session(
                        id = key,
                        title = title,
                        createdAt = sessionAnchorMs,
                        messages = uiMessagesWithOpening,
                        isActive = false
                    )
                } else {
                    null
                }
            }

            if (backendSessions.isNotEmpty()) {
                // Keep real UI-only sessions, but drop the startup placeholder session
                val uiOnlySessions = _sessions.value.filter { session ->
                    !backendSessionKeys.contains(session.id) && !isEphemeralDefaultSession(session)
                }

                // Merge: backend sessions + real UI sessions
                val allSessions = (backendSessions + uiOnlySessions).sortedByDescending { it.createdAt }

                val lastSessionId = mmkv.decodeString(PREF_LAST_SESSION_ID)
                val restoredSession = lastSessionId?.let { id -> allSessions.find { it.id == id } }
                val targetSession = restoredSession ?: allSessions.firstOrNull()

                if (targetSession != null) {
                    _sessions.value = allSessions.map {
                        it.copy(isActive = it.id == targetSession.id)
                    }
                    _currentSession.value = targetSession.copy(isActive = true)
                    mmkv.encode(PREF_LAST_SESSION_ID, targetSession.id)
                    android.util.Log.d("SessionManager", "✅ Restored session: ${targetSession.id}")
                } else {
                    val defaultSession = createDefaultSession()
                    _sessions.value = listOf(defaultSession)
                    _currentSession.value = defaultSession
                    mmkv.encode(PREF_LAST_SESSION_ID, defaultSession.id)
                }

                android.util.Log.d("SessionManager", "✅ Loaded ${backendSessions.size} sessions from backend")
            }

        } catch (e: Exception) {
            android.util.Log.e("SessionManager", "Failed to load backend sessions", e)
        }
    }

    /**
     * Create new session
     */
    fun createSession(): Session {
        val newSession = createDefaultSession()

        // Set all current sessions to inactive
        val updatedSessions = _sessions.value.map { it.copy(isActive = false) }

        // 新会话置顶：按创建时间降序排列，与后端加载逻辑一致。
        _sessions.value = (updatedSessions + newSession.copy(isActive = true))
            .sortedByDescending { it.createdAt }
        _currentSession.value = newSession

        // 💾 Save current session ID to MMKV
        mmkv.encode(PREF_LAST_SESSION_ID, newSession.id)
        android.util.Log.d("SessionManager", "💾 Saved new session ID: ${newSession.id}")

        return newSession
    }

    /**
     * Switch to specified session
     */
    fun switchSession(sessionId: String) {
        val session = _sessions.value.find { it.id == sessionId }
        if (session != null) {
            // Update active status
            _sessions.value = _sessions.value.map {
                it.copy(isActive = it.id == sessionId)
            }
            _currentSession.value = session.copy(isActive = true)

            // 💾 Save current session ID to MMKV
            mmkv.encode(PREF_LAST_SESSION_ID, sessionId)
            android.util.Log.d("SessionManager", "💾 Saved session ID: $sessionId")
        }
    }

    /**
     * Delete session
     */
    fun deleteSession(sessionId: String) {
        val currentSessions = _sessions.value
        if (currentSessions.size <= 1) {
            // Keep at least one session
            return
        }

        val remainingSessions = currentSessions
            .filter { it.id != sessionId }
            .sortedByDescending { it.createdAt }

        val wasCurrent = _currentSession.value.id == sessionId
        val newCurrent = if (wasCurrent) remainingSessions.first() else _currentSession.value

        _sessions.value = remainingSessions.map { it.copy(isActive = it.id == newCurrent.id) }
        _currentSession.value = newCurrent.copy(isActive = true)
        if (wasCurrent) {
            mmkv.encode(PREF_LAST_SESSION_ID, newCurrent.id)
        }
    }

    /**
     * Add message to current session
     */
    fun addMessageToCurrentSession(message: ChatMessage) {
        addMessageToSession(_currentSession.value.id, message)
    }

    /**
     * Replace all messages in current session (for syncing from backend)
     */
    fun replaceCurrentSessionMessages(messages: List<ChatMessage>) {
        mergeSessionMessages(_currentSession.value.id, messages)
    }

    /**
     * Remove message from current session
     */
    fun removeMessageFromCurrentSession(messageId: String) {
        removeMessageFromSession(_currentSession.value.id, messageId)
    }

    /**
     * Update current session title
     */
    fun updateCurrentSessionTitle(title: String) {
        val current = _currentSession.value
        val updatedSession = current.copy(title = title)

        _sessions.value = _sessions.value.map {
            if (it.id == current.id) updatedSession else it
        }
        _currentSession.value = updatedSession
    }

    /**
     * Auto-generate current session title (based on first user message)
     */
    fun autoGenerateCurrentSessionTitle() {
        val current = _currentSession.value
        val generatedTitle = current.generateTitle()
        if (generatedTitle != current.title) {
            updateCurrentSessionTitle(generatedTitle)
        }
    }

    /**
     * Clear current session messages
     */
    fun clearCurrentSession() {
        val current = _currentSession.value
        val updatedSession = current.copy(
            messages = listOf(
                ChatMessage(
                    content = "聊天记录已清空。有什么可以帮到你的吗？",
                    isUser = false,
                    kind = ChatMessageKind.SYSTEM
                )
            )
        )

        _sessions.value = _sessions.value.map {
            if (it.id == current.id) updatedSession else it
        }
        _currentSession.value = updatedSession
    }

    /**
     * Get all sessions
     */
    fun getAllSessions(): List<Session> = _sessions.value

    fun addMessageToSession(sessionId: String, message: ChatMessage) {
        updateSessionById(sessionId) { session ->
            if (session.messages.any { it.id == message.id }) {
                session
            } else {
                session.copy(messages = session.messages + message)
            }
        }
    }

    /**
     * 合并指定会话的消息列表。
     *
     * 这里按消息 id 合并而不是按文本合并，避免完整时间线里的重复文本被错误吞掉。
     */
    fun mergeSessionMessages(sessionId: String, incomingMessages: List<ChatMessage>) {
        // 仅合并属于本会话的后端同步消息，防止 sync 竞态或错误 targetSessionId 把 A 会话文案写入 B。
        val safeIncoming = incomingMessages.filter { m ->
            filterBackendMergeRow(sessionId, m)
        }
        updateSessionById(sessionId) { session ->
            val mergedById = LinkedHashMap<String, ChatMessage>()
            session.messages.forEach { mergedById[it.id] = it }
            safeIncoming.forEach { mergedById[it.id] = it }
            // 保持插入顺序，避免后端回放时间锚点与实时 UI 时间混用时把“执行轨迹”排到用户消息前面。
            // LinkedHashMap 的 value 顺序即：原有顺序 + 新增消息顺序；同 id 覆盖不改变原有位置。
            var mergedMessages = mergedById.values.toList()
            // progress 与 JSONL 双路径写入时，同文案思考条会重复，按正文去重保留一条
            mergedMessages = dedupeThinkingByContent(mergedMessages)
            // 先发本地用户气泡 + 后端同步再合并同一条 user，会产生两条相同文案（id 不同）
            mergedMessages = dedupeBackendUserEchoOfLocalSend(mergedMessages)
            session.copy(messages = mergedMessages)
        }
    }

    /**
     * [ChatTimelineMapper] 里 backend 行使用 eventKey=`backend:<sessionId>:...`；
     * 合并到 [sessionId] 时校验首段 session，不一致则丢弃。
     */
    private fun filterBackendMergeRow(sessionId: String, m: ChatMessage): Boolean {
        val ek = m.eventKey ?: return true
        if (!ek.startsWith("backend:")) return true
        val parts = ek.split(":")
        if (parts.size < 4) return true
        val keySid = parts[1]
        if (keySid != sessionId) {
            Log.w(TAG, "merge drop cross-session backend row: target=$sessionId eventKeySid=$keySid id=${m.id}")
            return false
        }
        return true
    }

    /**
     * 去掉「后端同步那条」重复用户气泡：本地发送已有一条（随机 id），
     * sync 又用 backend_* id 合并同文案，界面会显示两次。
     */
    private fun dedupeBackendUserEchoOfLocalSend(messages: List<ChatMessage>): List<ChatMessage> {
        val localTexts = messages.asSequence()
            .filter { it.isUser && !it.id.startsWith("backend_") }
            .map { normalizeUserEchoText(it.content) }
            .filter { it.isNotEmpty() }
            .toHashSet()
        return messages.filter { m ->
            if (!m.isUser || !m.id.startsWith("backend_")) return@filter true
            val t = normalizeUserEchoText(m.content)
            !(t.isNotEmpty() && t in localTexts)
        }
    }

    /**
     * 归一化用户消息文本，避免「🎤 你好」与「你好」在回放去重时被当成两条。
     */
    private fun normalizeUserEchoText(content: String): String {
        return content
            .trim()
            .removePrefix("🎤")
            .trim()
    }

    /** 相同内容的思考气泡只保留一条，避免实时进度与同步合并重复 */
    private fun dedupeThinkingByContent(messages: List<ChatMessage>): List<ChatMessage> {
        val seen = HashSet<String>()
        return messages.filter { m ->
            if (m.kind != ChatMessageKind.THINKING) return@filter true
            val key = m.content.trim()
            if (key.isEmpty()) return@filter true
            if (key in seen) false else {
                seen.add(key)
                true
            }
        }
    }

    /**
     * 修复历史异常：若出现 thinking 紧跟 user 的反向顺序（thinking,user），交换为（user,thinking）。
     * 仅做局部邻接修复，避免大范围重排改变历史语义。
     */
    private fun normalizeThinkingUserOrder(messages: List<LegacyMessage>): List<LegacyMessage> {
        if (messages.size < 2) return messages
        val out = ArrayList<LegacyMessage>(messages.size)
        var i = 0
        while (i < messages.size) {
            val cur = messages[i]
            val next = messages.getOrNull(i + 1)
            if (cur.role == "thinking" && next?.role == "user") {
                out.add(next)
                out.add(cur)
                i += 2
                continue
            }
            out.add(cur)
            i += 1
        }
        return out
    }

    /**
     * 历史会话统一补回系统开场语，避免“开头语缺失”。
     */
    private fun buildSessionOpeningMessage(sessionId: String, baseTimeMs: Long): ChatMessage {
        return ChatMessage(
            id = "system_opening_$sessionId",
            content = getWelcomeMessage(),
            isUser = false,
            timestamp = baseTimeMs - 1,
            kind = ChatMessageKind.SYSTEM,
            eventKey = "system_opening:$sessionId"
        )
    }

    fun removeMessageFromSession(sessionId: String, messageId: String) {
        updateSessionById(sessionId) { session ->
            session.copy(messages = session.messages.filter { it.id != messageId })
        }
    }

    private fun updateSessionById(sessionId: String, transform: (Session) -> Session): Session? {
        val existing = _sessions.value.find { it.id == sessionId } ?: return null
        val updated = transform(existing).copy(isActive = existing.isActive)
        _sessions.value = _sessions.value.map { session ->
            if (session.id == sessionId) updated else session
        }
        if (_currentSession.value.id == sessionId) {
            _currentSession.value = updated.copy(isActive = true)
        }
        return updated
    }

    /**
     * Get session count
     */
    fun getSessionCount(): Int = _sessions.value.size
}
