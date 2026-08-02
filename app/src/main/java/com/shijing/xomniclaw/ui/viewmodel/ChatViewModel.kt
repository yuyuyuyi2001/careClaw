/**
 * OmniClaw Source Reference:
 * - ../xomniclaw/src/gateway/(all)
 *
 * OmniClaw adaptation: Android UI layer.
 */
package com.shijing.xomniclaw.ui.viewmodel

import android.app.Application
import com.shijing.xomniclaw.core.MainEntryNew
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.shijing.xomniclaw.agent.context.ConversationMemoryPolicy
import com.shijing.xomniclaw.channel.ChannelManager
import com.shijing.xomniclaw.ui.compose.ChatMessage
import com.shijing.xomniclaw.ui.compose.ChatMessageKind
import com.shijing.xomniclaw.ui.compose.MessageStatus
import com.shijing.xomniclaw.ui.compose.RunningTaskStatus
import com.shijing.xomniclaw.ui.session.ChatTimelineMapper
import com.shijing.xomniclaw.ui.session.SessionManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Chat interface ViewModel - Single Source of Truth architecture
 *
 * Architecture principles:
 * 1. SessionManager is the single source of truth for messages
 * 2. Periodically sync messages from backend, only update UI when there are new messages
 * 3. Avoid duplicate messages and complex merge logic
 */
class ChatViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "ChatViewModel"
        private const val SYNC_INTERVAL = 3000L // 3 seconds
        /** 等待 Agent 协程结束的上限（提前在 agentSessionRunning=false 时结束） */
        private const val AGENT_COMPLETION_WAIT_MS = 3_600_000L
    }

    // Single data source: SessionManager
    private val uiSessionManager = SessionManager()
    private val channelManager = ChannelManager(application)

    // Expose session-related flows
    val sessions: StateFlow<List<SessionManager.Session>> = uiSessionManager.sessions
    val currentSession: StateFlow<SessionManager.Session> = uiSessionManager.currentSession

    /** 与 [currentSession] 单一数据源一致，避免 _messages 镜像滞后导致串会话展示 */
    val messages: StateFlow<List<ChatMessage>> = currentSession
        .map { it.messages }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            emptyList()
        )

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _memoryWindowInfo = MutableStateFlow(
        "当前保底可记忆会话轮数：0（保底${ConversationMemoryPolicy.HARD_KEEP_RECENT_DIALOG_TURNS}轮）"
    )
    val memoryWindowInfo: StateFlow<String> = _memoryWindowInfo.asStateFlow()
    private val _runningTasks = MutableStateFlow<List<RunningTaskStatus>>(emptyList())
    val runningTasks: StateFlow<List<RunningTaskStatus>> = _runningTasks.asStateFlow()

    // Track sync state for each session
    private val sessionSyncState = mutableMapOf<String, Int>() // sessionId -> lastMessageCount

    init {
        // 保留原有记忆窗口信息行（当前会话维度）。
        viewModelScope.launch {
            currentSession.collect { session ->
                updateMemoryWindowInfo(session.messages)
            }
        }

        // Initialize on startup
        viewModelScope.launch {
            initialize()
        }

        observeAgentProgress()

        // 新增一行“执行中任务”状态：只显示未完成任务，完成后立即移除。
        // 注意：红色停止按钮是“当前会话级”，不应被其它会话的运行态点亮。
        viewModelScope.launch {
            combine(currentSession, sessions, MainEntryNew.runningSessionIds) { current, sessionList, runningIds ->
                Triple(current, sessionList, runningIds)
            }.collect { (current, sessionList, runningIds) ->
                _isLoading.value = runningIds.contains(current.id)
                _runningTasks.value = buildRunningTasks(sessionList, runningIds)
            }
        }

        // Periodically sync messages from backend (smart sync, only update when new messages arrive)
        viewModelScope.launch {
            while (true) {
                delay(SYNC_INTERVAL)
                syncFromBackend()
            }
        }
    }

    private fun observeAgentProgress() {
        viewModelScope.launch {
            MainEntryNew.uiProgressFlow.collect { event ->
                val message = ChatTimelineMapper.fromProgressEvent(event) ?: return@collect
                uiSessionManager.addMessageToSession(event.sessionId, message)
            }
        }
    }

    /**
     * Initialize - Load history on startup
     */
    private suspend fun initialize() {
        try {
            Log.d(TAG, "🚀 [Initialize] Starting...")

            // Ensure MainEntryNew is initialized
            com.shijing.xomniclaw.core.MainEntryNew.initialize(getApplication())

            // Load all backend sessions (Feishu/Discord/WebSocket)
            uiSessionManager.loadSessionsFromBackend()

            // Load current session messages
            syncFromBackend()

            Log.d(TAG, "✅ [Initialize] Completed")
        } catch (e: Exception) {
            Log.e(TAG, "❌ [Initialize] Failed", e)
        }
    }

    /**
     * Sync messages from backend - Smart sync, only update when new messages arrive
     */
    private suspend fun syncFromBackend(targetSessionId: String? = null) {
        try {
            val sessionId = targetSessionId ?: currentSession.value.id
            Log.d(TAG, "🔍 [Sync Check] Session: $sessionId")

            // Use current session ID as backend session key
            val agentSessionManager = com.shijing.xomniclaw.core.MainEntryNew.getSessionManager()
            if (agentSessionManager == null) {
                Log.w(TAG, "⚠️ [Sync] SessionManager not initialized")
                return
            }

            // Get corresponding agent session using current session ID
            val agentSession = agentSessionManager.get(sessionId)
            if (agentSession == null) {
                Log.d(TAG, "ℹ️ [Sync] Session $sessionId has no backend data")
                return
            }

            val newMessageCount = agentSession.messageCount()
            val lastSyncedCount = sessionSyncState[sessionId] ?: 0
            Log.d(TAG, "📊 [Sync] last=$lastSyncedCount, new=$newMessageCount")

            // Check if there are new messages
            if (newMessageCount <= lastSyncedCount) {
                Log.d(TAG, "⏭️ [Sync Skip] No new messages")
                return
            }

            Log.d(TAG, "🔄 [Sync] New messages: $lastSyncedCount -> $newMessageCount")

            // Convert all messages（锚点时间取自 Agent Session，保证多次同步顺序稳定）
            val chatMessages = convertMessages(sessionId, agentSession.messages, agentSession)

            // Update SessionManager (single source of truth)
            uiSessionManager.mergeSessionMessages(sessionId, chatMessages)

            sessionSyncState[sessionId] = newMessageCount
            Log.d(TAG, "✅ [Sync] Completed: ${chatMessages.size} messages")
        } catch (e: Exception) {
            Log.e(TAG, "❌ [Sync] Failed", e)
        }
    }

    /**
     * Convert backend messages to UI messages
     */
    private fun convertMessages(
        sessionId: String,
        messages: List<com.shijing.xomniclaw.providers.LegacyMessage>,
        agentSession: com.shijing.xomniclaw.agent.session.Session
    ): List<ChatMessage> {
        val baseTimeMs = parseAgentSessionAnchorMs(agentSession)
        return messages.mapIndexedNotNull { index, message ->
            ChatTimelineMapper.fromBackendMessage(
                sessionId = sessionId,
                index = index,
                message = message,
                baseTimeMs = baseTimeMs
            )
        }
    }

    private fun parseAgentSessionAnchorMs(agentSession: com.shijing.xomniclaw.agent.session.Session): Long {
        return try {
            java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US)
                .parse(agentSession.createdAt)?.time ?: System.currentTimeMillis()
        } catch (_: Exception) {
            System.currentTimeMillis()
        }
    }

    private fun updateMemoryWindowInfo(messages: List<ChatMessage>) {
        val totalTurns = messages.count { it.isUser }
        val rememberable = ConversationMemoryPolicy.rememberableTurns(totalTurns)
        _memoryWindowInfo.value = buildString {
            append("当前保底可记忆会话轮数：")
            append(rememberable)
            append("（会话")
            append(totalTurns)
            append("轮，保底")
            append(ConversationMemoryPolicy.HARD_KEEP_RECENT_DIALOG_TURNS)
            append("轮）")
        }
    }

    /**
     * 仅拼接当前“执行中”任务名称；已完成任务不应继续显示在状态栏。
     */
    private fun buildRunningTasks(
        sessionList: List<SessionManager.Session>,
        runningIds: Set<String>
    ): List<RunningTaskStatus> {
        if (runningIds.isEmpty()) return emptyList()
        val inSessionOrder = sessionList
            .filter { it.id in runningIds }
            .map { session ->
                val title = session.title.trim().ifEmpty { session.id.takeLast(8) }
                RunningTaskStatus(sessionId = session.id, title = title)
            }
        val missing = (runningIds - inSessionOrder.map { it.sessionId }.toSet()).map { sid ->
            RunningTaskStatus(sessionId = sid, title = sid.takeLast(8))
        }
        return inSessionOrder + missing
    }

    /**
     * Check if it's a backend session
     */
    private fun isBackendSession(sessionId: String): Boolean {
        return sessionId.startsWith("discord_") ||
               sessionId.contains("_p2p") ||
               sessionId.contains("_group") ||
               sessionId.startsWith("session_")
    }

    /**
     * Send user message
     *
     * @param userBubbleAlreadyFromVoice 为 true 时表示语音识别阶段已通过 [addVoiceUserMessage] 插入带 🎤 的气泡，
     * 此处勿再追加一条纯文本用户消息，否则会与语音管线「先落屏」重复一条（轮次也会翻倍）。
     */
    fun sendMessage(
        content: String,
        returnToMainOnFinish: Boolean = false,
        userBubbleAlreadyFromVoice: Boolean = false,
    ) {
        if (content.isBlank()) return

        Log.d(TAG, "💬 [Send] $content")

        // Record inbound
        channelManager.recordInbound()

        val sessionId = currentSession.value.id

        // Add user message to UI（语音跟进 Agent 时跳过，避免与 🎤 气泡双条）
        if (!userBubbleAlreadyFromVoice) {
            val userMessage = ChatMessage(
                content = content,
                isUser = true,
                status = MessageStatus.SENT,
                kind = ChatMessageKind.USER
            )
            uiSessionManager.addMessageToSession(sessionId, userMessage)
        }

        // Call MainEntryNew to execute（isLoading 由 agentSessionRunning 驱动，避免首条回复后误关停止按钮）
        viewModelScope.launch {
            Log.d(TAG, "🚀 [MainEntryNew] Execute (session: $sessionId)...")

            try {
                MainEntryNew.runWithSession(
                    userInput = content,
                    sessionId = sessionId,
                    application = getApplication(),
                    returnToMainOnFinish = returnToMainOnFinish
                )
                // 等待当前会话完成；不再等待“所有会话”都结束，避免跨会话互相影响。
                withTimeoutOrNull(AGENT_COMPLETION_WAIT_MS) {
                    MainEntryNew.runningSessionIds.filter { runningIds ->
                        !runningIds.contains(sessionId)
                    }.first()
                }
            } finally {
                syncFromBackend(sessionId)
            }
        }

        // Auto-generate session title
        if (currentSession.value.title == "新对话") {
            uiSessionManager.autoGenerateCurrentSessionTitle()
        }
    }

    /**
     * 停止当前正在执行的 Agent 任务
     */
    fun stopAgent() {
        Log.d(TAG, "🛑 [Stop Agent] cancel all running sessions")
        MainEntryNew.cancelAllSessionJobs()
    }

    /**
     * 从后台回到前台时主动校准运行态，避免状态栏误丢失“执行中任务”。
     */
    fun refreshRunningTaskStatusOnResume() {
        MainEntryNew.reconcileRunningSessionsFromActiveJobs()
    }

    /**
     * 语音识别文本先行落屏，避免用户必须等 AI 回复回来才看到自己的话。
     */
    fun addVoiceUserMessage(userText: String) {
        if (userText.isNotBlank()) {
            uiSessionManager.addMessageToSession(
                currentSession.value.id,
                ChatMessage(
                    content = "🎤 $userText",
                    isUser = true,
                    status = MessageStatus.SENT,
                    kind = ChatMessageKind.USER
                )
            )
        }
    }

    /**
     * 语音回复单独追加，便于与识别结果分阶段展示。
     */
    fun addVoiceAssistantMessage(aiReply: String) {
        if (aiReply.isNotBlank()) {
            uiSessionManager.addMessageToSession(
                currentSession.value.id,
                ChatMessage(
                    content = aiReply,
                    isUser = false,
                    status = MessageStatus.SENT,
                    kind = ChatMessageKind.ASSISTANT
                )
            )
        }
    }

    /**
     * 把语音链路用于“按下说话时刻对齐”的截图直接写入主对话。
     * 文本中保留绝对路径，供 [ChatMediaParser] 自动渲染成图片缩略图。
     */
    fun addVoiceAlignedFrameMessage(alignedFramePath: String) {
        if (alignedFramePath.isBlank()) return
        val content = buildString {
            append("本轮思考对齐截图：\n")
            append(alignedFramePath)
        }
        uiSessionManager.addMessageToSession(
            currentSession.value.id,
            ChatMessage(
                content = content,
                isUser = false,
                status = MessageStatus.SENT,
                kind = ChatMessageKind.SYSTEM
            )
        )
    }

    /**
     * 兼容旧调用方：内部委托到分阶段方法。
     */
    fun addVoiceRoundTrip(userText: String, aiReply: String) {
        Log.d(TAG, "🎤 [Voice RoundTrip] user=$userText, reply=${aiReply.take(80)}")
        addVoiceUserMessage(userText)
        addVoiceAssistantMessage(aiReply)
    }

    // === Session Management ===

    fun createNewSession() {
        // 仅创建新会话，不影响其它会话中的执行中任务（全局状态条会持续展示）。
        uiSessionManager.createSession()
        // New session automatically initializes sync state to 0
    }

    fun switchSession(sessionId: String) {
        val prev = currentSession.value.id
        Log.d(TAG, "🔀 [Switch Session] $sessionId (from=$prev)")
        uiSessionManager.switchSession(sessionId)
        // Immediately sync when switching sessions
        viewModelScope.launch {
            syncFromBackend(sessionId)
        }
    }

    fun deleteSession(sessionId: String) {
        MainEntryNew.cancelSessionJob(sessionId, false)
        uiSessionManager.deleteSession(sessionId)
        // Clean up sync state
        sessionSyncState.remove(sessionId)

        // Clean up backend session
        val agentSessionManager = com.shijing.xomniclaw.core.MainEntryNew.getSessionManager()
        agentSessionManager?.clear(sessionId)
    }

    fun clearCurrentSession() {
        val sessionId = currentSession.value.id
        Log.d(TAG, "🗑️ [Clear Session] $sessionId")

        uiSessionManager.clearCurrentSession()

        // Also clear Agent Session
        val agentSessionManager = com.shijing.xomniclaw.core.MainEntryNew.getSessionManager()
        agentSessionManager?.clear(if (isBackendSession(sessionId)) sessionId else sessionId)

        // Reset sync state
        sessionSyncState[sessionId] = 0
    }
}
