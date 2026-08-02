package com.shijing.xomniclaw.core

/**
 * X-OmniClaw Source Reference:
 * - ../xomniclaw/src/agents/(all)
 * - ../xomniclaw/src/gateway/(all)
 *
 * X-OmniClaw adaptation: main agent execution entry for Android runtime.
 */


import android.app.Application
import android.content.Intent
import android.os.Build
import android.text.TextUtils
import android.util.Log
import com.shijing.xomniclaw.agent.context.ContextBuilder
import com.shijing.xomniclaw.agent.memory.MemoryManager
import com.shijing.xomniclaw.agent.memory.evolution.MemoryEvolutionManager
import com.shijing.xomniclaw.agent.tools.AndroidToolRegistry
import com.shijing.xomniclaw.agent.tools.ToolRegistry
import com.shijing.xomniclaw.agent.loop.AgentLoop
import com.shijing.xomniclaw.agent.loop.LlmTokenUsage
import com.shijing.xomniclaw.agent.loop.ProgressUpdate
import com.shijing.xomniclaw.agent.session.SessionManager
import com.shijing.xomniclaw.config.ConfigLoader
import com.shijing.xomniclaw.data.model.TaskDataManager
import kotlinx.coroutines.flow.asSharedFlow
import com.shijing.xomniclaw.ext.mmkv
import com.shijing.xomniclaw.ext.simpleSafeLaunch
import com.shijing.xomniclaw.providers.llm.toNewMessage
import com.shijing.xomniclaw.providers.llm.toLegacyMessage
import com.shijing.xomniclaw.accessibility.service.AccessibilityBinderService
import com.shijing.xomniclaw.util.LayoutExceptionLogger
import com.shijing.xomniclaw.util.MMKVKeys
import com.shijing.xomniclaw.util.WakeLockManager
import com.shijing.xomniclaw.util.ReasoningTagFilter
import com.shijing.xomniclaw.util.CallerAwareLog
import com.shijing.xomniclaw.providers.LegacyMessage
import com.shijing.xomniclaw.ui.floatwindow.SessionFloatWindow
import com.shijing.xomniclaw.BuildConfig
import java.text.Normalizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import android.os.Environment
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * New MainEntry - Refactored version based on Nanobot architecture
 *
 * Core changes:
 * 1. Use AgentLoop instead of fixed process
 * 2. Use LLM Provider (Claude Opus 4.6 + Reasoning)
 * 3. Toolize all operations
 * 4. Dynamic decision-making instead of hardcoded flow
 */
object MainEntryNew {
    private const val TAG = "MainEntryNew"
    const val ACTION_AGENT_PROGRESS = "com.shijing.xomniclaw.ACTION_AGENT_PROGRESS"
    const val EXTRA_PROGRESS_TYPE = "type"
    const val EXTRA_PROGRESS_TITLE = "title"
    const val EXTRA_PROGRESS_CONTENT = "content"

    // ================ Core Components ================
    private lateinit var application: Application
    private lateinit var toolRegistry: ToolRegistry
    private lateinit var androidToolRegistry: AndroidToolRegistry
    private lateinit var llmProvider: com.shijing.xomniclaw.providers.UnifiedLLMProvider
    private lateinit var agentLoop: AgentLoop
    private lateinit var contextBuilder: ContextBuilder
    private lateinit var sessionManager: SessionManager
    private lateinit var configLoader: ConfigLoader

    // ================ State Management ================
    var user: String = ""
    private var currentTaskId: String? = null
    private var currentDocId: String? = null
    private var job: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val taskDataManager: TaskDataManager = TaskDataManager.getInstance()
    private val progressEventCounter = AtomicLong(0)

    /**
     * 统一日志格式：输出 [文件名:行号] 方法 -> 消息，便于定位 Android 侧日志来源。
     */
    private fun logd(message: String) {
        CallerAwareLog.d(TAG, message)
    }

    private fun logw(message: String) {
        CallerAwareLog.w(TAG, message)
    }

    /**
     * 在最终回复后附加「本回合」LLM API token 累加（多轮迭加总）；不并入 session 落盘原文，仅用于浮窗与聊天广播。
     * 若上游未返回 usage，Python 仍传 0。
     */
    private fun appendTokenUsageForDisplay(plain: String, usage: LlmTokenUsage?): String {
        if (usage == null) return plain
        return plain + "\n\n—\n" + "本回合 LLM tokens：输入 ${usage.promptTokens}，输出 ${usage.completionTokens}，合计 ${usage.totalTokens}（多轮请求累加；未返回时可能为 0）"
    }

    /**
     * 供飞书/Discord 等与主界面一致的最终展示文案（带 token 行）；不落盘 session。
     */
    fun formatAgentReplyWithTokenUsage(plain: String, usage: LlmTokenUsage?) =
        appendTokenUsageForDisplay(plain, usage)

    private fun loge(message: String, error: Throwable? = null) {
        CallerAwareLog.e(TAG, message, error)
    }

    // Agent 最大迭代次数，用于浮动窗口进度显示
    private var agentMaxIterations: Int = 40

    // Document sync completion state
    private val _docSyncFinished = MutableStateFlow(false)
    val docSyncFinished = _docSyncFinished.asStateFlow()

    // Test summary completion state
    private val _summaryFinished = MutableStateFlow(false)
    val summaryFinished = _summaryFinished.asStateFlow()

    data class UiProgressEvent(
        val id: String,
        val sessionId: String,
        val type: String,
        val title: String,
        val content: String,
        val timestamp: Long
    )

    /**
     * 每次 runWithSession 各自持有的 UI 回写上下文。
     *
     * 关键点：
     * 1. 进度事件必须绑死到发起这次运行的 session，不能依赖全局变量
     * 2. block reply 去重也必须按 run 隔离，避免 A 会话影响 B 会话的最终回复判断
     */
    private data class SessionUiContext(
        val sessionId: String,
        val currentUserInput: String,
        val lastBlockReplyText: AtomicReference<String?> = AtomicReference(null),
        // 记录本轮 user 是否已提前落盘，避免 thinking 抢在 user 前面。
        val userMessagePersisted: AtomicReference<Boolean> = AtomicReference(false),
        /** 当前会话最近一次迭代步号，用于给思考过程打上 Step 索引。 */
        val currentStepIndex: AtomicLong = AtomicLong(0)
    )

    /**
     * Token 累计值（统一用 Long，避免长期累计后 Int 溢出）。
     */
    data class TokenUsageCounter(
        val promptTokens: Long = 0L,
        val completionTokens: Long = 0L,
        val totalTokens: Long = 0L
    ) {
        operator fun plus(other: TokenUsageCounter): TokenUsageCounter {
            return TokenUsageCounter(
                promptTokens = promptTokens + other.promptTokens,
                completionTokens = completionTokens + other.completionTokens,
                totalTokens = totalTokens + other.totalTokens
            )
        }
    }

    /**
     * 状态页 Token 展示快照：本会话累计 + 全局累计。
     */
    data class TokenUsageStatus(
        val sessionId: String? = null,
        val sessionCounter: TokenUsageCounter = TokenUsageCounter(),
        val globalCounter: TokenUsageCounter = TokenUsageCounter()
    )

    private val _uiProgressFlow = MutableSharedFlow<UiProgressEvent>(extraBufferCapacity = 64)
    val uiProgressFlow: SharedFlow<UiProgressEvent> = _uiProgressFlow

    /**
     * 会话 Agent 是否仍在执行（与「首条回复是否已写入」无关）。
     * 用于聊天页停止按钮：避免 runWithSession 立即返回后误把 isLoading 置 false。
     */
    private val _agentSessionRunning = MutableStateFlow(false)
    val agentSessionRunning: StateFlow<Boolean> = _agentSessionRunning.asStateFlow()
    /** 当前正在执行的会话集合（用于按会话隔离 loading/停止逻辑）。 */
    private val _runningSessionIds = MutableStateFlow<Set<String>>(emptySet())
    val runningSessionIds: StateFlow<Set<String>> = _runningSessionIds.asStateFlow()
    private val activeSessionJobs = ConcurrentHashMap<String, Job>()
    /** 每个会话独立 AgentLoop 引用：用于 stop 时显式下发停止信号。 */
    private val activeSessionLoops = ConcurrentHashMap<String, AgentLoop>()
    private val activeSessionRunTokens = ConcurrentHashMap<String, String>()
    private val sessionTokenCounters = ConcurrentHashMap<String, TokenUsageCounter>()
    private val _tokenUsageStatus = MutableStateFlow(TokenUsageStatus())
    val tokenUsageStatus: StateFlow<TokenUsageStatus> = _tokenUsageStatus.asStateFlow()

    /**
     * Get SessionManager (for Gateway use)
     */
    fun getSessionManager(): SessionManager? {
        return if (::sessionManager.isInitialized) sessionManager else null
    }

    /**
     * Get ToolRegistry (for registering extension tools like feishu)
     */
    fun getToolRegistry(): ToolRegistry? {
        return if (::toolRegistry.isInitialized) toolRegistry else null
    }

    /**
     * Initialize - Must be called before use
     */
    fun initialize(app: Application) {
        if (::application.isInitialized) {
            Log.w(TAG, "Already initialized")
            return
        }

        application = app
        Log.d(TAG, "Initializing MainEntryNew...")

        try {
            // 启动时先恢复全局累计，保证状态页首帧即可显示历史总量。
            val persistedGlobalCounter = loadGlobalTokenCounterFromStorage()
            _tokenUsageStatus.value = _tokenUsageStatus.value.copy(globalCounter = persistedGlobalCounter)

            // 0. Initialize ConfigLoader
            configLoader = ConfigLoader(application)
            Log.d(TAG, "✓ ConfigLoader initialized")

            // 1. Initialize LLM Provider (unified Provider - supports all X-OmniClaw-compatible APIs)
            llmProvider = com.shijing.xomniclaw.providers.UnifiedLLMProvider(application)
            Log.d(TAG, "✓ UnifiedLLMProvider initialized (supports multi-model APIs)")

            // 2. Initialize ToolRegistry (universal tools - from Pi Coding Agent)
            toolRegistry = ToolRegistry(
                context = application,
                taskDataManager = taskDataManager
            )
            Log.d(TAG, "✓ ToolRegistry initialized (${toolRegistry.getToolCount()} universal tools)")

            // 3. Initialize MemoryManager (memory management + hybrid search index)
            val workspacePath = "/sdcard/.xomniclaw/workspace"
            val openClawCfg = configLoader.loadOmniClawConfig()
            val embeddingProviders = openClawCfg.resolveProviders()
            // Try to find an OpenAI-compatible provider for embeddings
            val embeddingBaseUrl = embeddingProviders.values.firstOrNull()?.baseUrl ?: ""
            val embeddingApiKey = embeddingProviders.values.firstOrNull()?.apiKey ?: ""
            val memoryManager = com.shijing.xomniclaw.agent.memory.MemoryManager(
                workspacePath = workspacePath,
                context = application,
                embeddingBaseUrl = embeddingBaseUrl,
                embeddingApiKey = embeddingApiKey
            )

            // 4. Initialize AndroidToolRegistry (Android platform tools)
            androidToolRegistry = AndroidToolRegistry(
                context = application,
                taskDataManager = taskDataManager,
                memoryManager = memoryManager,
                workspacePath = workspacePath
            )
            Log.d(TAG, "✓ AndroidToolRegistry initialized (${androidToolRegistry.getToolCount()} Android tools)")

            // 5. Initialize context builder (X-OmniClaw style)
            contextBuilder = ContextBuilder(
                context = application,
                toolRegistry = toolRegistry,
                androidToolRegistry = androidToolRegistry,
                configLoader = configLoader
            )
            Log.d(TAG, "✓ ContextBuilder initialized")

            // 5. Initialize session manager (统一使用当前工作目录，避免继续创建旧版 .omniclaw 路径)
            val workspaceDir = File(Environment.getExternalStorageDirectory(), ".xomniclaw/workspace")
            if (!workspaceDir.exists()) {
                workspaceDir.mkdirs()
                Log.d(TAG, "Created workspace directory: ${workspaceDir.absolutePath}")
            }
            sessionManager = SessionManager(
                workspace = workspaceDir
            )
            Log.d(TAG, "✓ SessionManager initialized (workspace: ${workspaceDir.absolutePath})")

            // 6. Initialize context manager (X-OmniClaw-aligned context overflow handling)
            val contextManager = com.shijing.xomniclaw.agent.context.ContextManager(llmProvider)
            Log.d(TAG, "✓ ContextManager initialized")

            // Load maxIterations from config
            val config = configLoader.loadOmniClawConfig()
            val maxIterations = config.agent.maxIterations
            agentMaxIterations = maxIterations

            // 7. Initialize AgentLoop
            agentLoop = AgentLoop(
                llmProvider = llmProvider,
                toolRegistry = toolRegistry,
                androidToolRegistry = androidToolRegistry,
                contextManager = contextManager,
                maxIterations = maxIterations,
                modelRef = null,  // Use default model
                configLoader = configLoader  // Gap 2: context window resolution
            )
            Log.d(TAG, "✓ AgentLoop initialized (maxIterations: $maxIterations)")

            Log.d(TAG, "========== Initialization Complete ==========")

        } catch (e: Exception) {
            Log.e(TAG, "Initialization failed", e)
            throw RuntimeException("Failed to initialize MainEntryNew", e)
        }
    }

    /** 为每次会话运行创建独立 AgentLoop，避免并发会话共享 progressFlow 导致串线。 */
    private fun createSessionScopedAgentLoop(application: Application): AgentLoop {
        // 快速修复：每次会话都创建“新鲜” Provider/ConfigLoader，避免单例缓存持有旧 API Key。
        val sessionConfigLoader = ConfigLoader(application.applicationContext)
        val sessionLlmProvider = com.shijing.xomniclaw.providers.UnifiedLLMProvider(application.applicationContext)
        return AgentLoop(
            llmProvider = sessionLlmProvider,
            toolRegistry = toolRegistry,
            androidToolRegistry = androidToolRegistry,
            contextManager = com.shijing.xomniclaw.agent.context.ContextManager(sessionLlmProvider),
            maxIterations = agentMaxIterations,
            modelRef = null,
            configLoader = sessionConfigLoader
        )
    }

    private fun updateSessionRunningState(sessionId: String, running: Boolean, runToken: String) {
        if (running) {
            activeSessionRunTokens[sessionId] = runToken
            _runningSessionIds.value = activeSessionRunTokens.keys.toSet()
            _agentSessionRunning.value = _runningSessionIds.value.isNotEmpty()
            return
        }

        val tokenInMap = activeSessionRunTokens[sessionId]
        if (tokenInMap == runToken) {
            activeSessionRunTokens.remove(sessionId)
            _runningSessionIds.value = activeSessionRunTokens.keys.toSet()
            _agentSessionRunning.value = _runningSessionIds.value.isNotEmpty()
        }
    }

    /**
     * 前后台切换后对运行态进行一次自愈：
     * 以“仍处于活跃状态的 Job”为准，回填 runningSessionIds，避免 UI 状态条误清空。
     */
    fun reconcileRunningSessionsFromActiveJobs(): Set<String> {
        val activeJobIds = activeSessionJobs.entries
            .filter { (_, job) -> job.isActive && !job.isCancelled && !job.isCompleted }
            .map { it.key }
            .toSet()
        val staleTokenIds = activeSessionRunTokens.keys - activeJobIds
        staleTokenIds.forEach { sid -> activeSessionRunTokens.remove(sid) }
        val repairedIds = activeJobIds + activeSessionRunTokens.keys
        if (_runningSessionIds.value != repairedIds) {
            Log.d(TAG, "reconcileRunningSessionsFromActiveJobs: old=${_runningSessionIds.value}, new=$repairedIds")
            _runningSessionIds.value = repairedIds
        }
        _agentSessionRunning.value = repairedIds.isNotEmpty()
        return repairedIds
    }

    /**
     * 将单次 Agent 运行的 token 使用量累加到：
     * 1) 本会话（内存态）
     * 2) 全局累计（内存 + MMKV 持久化）
     */
    private fun accumulateTokenUsage(sessionId: String, usage: LlmTokenUsage?) {
        if (usage == null) return
        val delta = TokenUsageCounter(
            promptTokens = usage.promptTokens.toLong(),
            completionTokens = usage.completionTokens.toLong(),
            totalTokens = usage.totalTokens.toLong()
        )
        val previousSessionCounter = sessionTokenCounters[sessionId] ?: TokenUsageCounter()
        val updatedSessionCounter = previousSessionCounter + delta
        sessionTokenCounters[sessionId] = updatedSessionCounter

        val updatedGlobalCounter = _tokenUsageStatus.value.globalCounter + delta
        _tokenUsageStatus.value = TokenUsageStatus(
            sessionId = sessionId,
            sessionCounter = updatedSessionCounter,
            globalCounter = updatedGlobalCounter
        )
        persistGlobalTokenCounter(updatedGlobalCounter)
    }

    /**
     * 若 UI 切到其它会话，可主动切换“本会话累计”展示对象（不影响累计值本身）。
     */
    fun syncTokenUsageForSession(sessionId: String) {
        val sessionCounter = sessionTokenCounters[sessionId] ?: TokenUsageCounter()
        _tokenUsageStatus.value = _tokenUsageStatus.value.copy(
            sessionId = sessionId,
            sessionCounter = sessionCounter
        )
    }

    /**
     * 给非 AgentLoop 入口（如语音视觉直连链路）上报 token 增量。
     * 若未显式传入 sessionId，则优先沿用状态页当前展示会话。
     */
    fun recordExternalTokenUsage(
        usage: LlmTokenUsage?,
        sessionId: String? = null
    ) {
        if (usage == null) return
        val targetSessionId = sessionId
            ?: _tokenUsageStatus.value.sessionId
            ?: "voice_live"
        accumulateTokenUsage(targetSessionId, usage)
    }

    private fun loadGlobalTokenCounterFromStorage(): TokenUsageCounter {
        return TokenUsageCounter(
            promptTokens = mmkv.decodeLong(MMKVKeys.GLOBAL_TOKEN_PROMPT_TOTAL.key, 0L),
            completionTokens = mmkv.decodeLong(MMKVKeys.GLOBAL_TOKEN_COMPLETION_TOTAL.key, 0L),
            totalTokens = mmkv.decodeLong(MMKVKeys.GLOBAL_TOKEN_TOTAL.key, 0L)
        )
    }

    private fun persistGlobalTokenCounter(counter: TokenUsageCounter) {
        mmkv.encode(MMKVKeys.GLOBAL_TOKEN_PROMPT_TOTAL.key, counter.promptTokens)
        mmkv.encode(MMKVKeys.GLOBAL_TOKEN_COMPLETION_TOTAL.key, counter.completionTokens)
        mmkv.encode(MMKVKeys.GLOBAL_TOKEN_TOTAL.key, counter.totalTokens)
    }

    // registerAllTools() removed
    // Tools are now divided into:
    // - ToolRegistry: Universal tools (read, write, exec, web_fetch)
    // - AndroidToolRegistry: Android platform tools (tap, screenshot, open_app)

    /**
     * Run Agent with session management - Supports multi-turn conversations
     */
    fun runWithSession(
        userInput: String,
        sessionId: String?,
        application: Application,
        returnToMainOnFinish: Boolean = false,
        keepScreenAwake: Boolean = false
    ) {
        // Ensure initialized
        if (!::agentLoop.isInitialized) {
            initialize(application)
        }

        val effectiveSessionId = sessionId ?: "default"
        val sessionUiContext = SessionUiContext(
            sessionId = effectiveSessionId,
            currentUserInput = userInput
        )
        Log.d(TAG, "🆔 [Session] Session ID: $effectiveSessionId")

        // Get or create session
        val session = sessionManager.getOrCreate(effectiveSessionId)
        Log.d(TAG, "📋 [Session] History message count: ${session.messageCount()}")

        // Get history messages (recent 20) and convert to new format
        // Aligned with X-OmniClaw: limitHistoryTurns (by user turn count)
        // 1. Fetch all session messages
        // 2. Apply limitHistoryTurns with configurable dmHistoryLimit
        // 3. Context pruning in AgentLoop handles the rest
        // Aligned with X-OmniClaw: getHistoryLimitFromSessionKey → limitHistoryTurns
        // 1. Read dmHistoryLimit from config (per-channel, per-user)
        // 2. If not configured → no truncation (undefined → limitHistoryTurns returns all)
        // 3. AgentLoop's context pruning (soft trim / hard clear) handles oversized context
        val dmHistoryLimit: Int? = try {
            val openClawConfig = configLoader?.loadOmniClawConfig()
            // Check channels.feishu.dmHistoryLimit (or channels.android.dmHistoryLimit)
            openClawConfig?.channels?.feishu?.dmHistoryLimit
        } catch (_: Exception) { null }

        // Aligned with X-OmniClaw: if dmHistoryLimit not configured, send all history
        // AgentLoop's context pruning (pruneHistoryForContextShare-aligned) handles oversized context
        val allMessages = if (dmHistoryLimit != null && dmHistoryLimit > 0) {
            val raw = session.getRecentMessages(dmHistoryLimit * 4).map { it.toNewMessage() }
            com.shijing.xomniclaw.agent.session.HistorySanitizer
                .limitHistoryTurns(raw.toMutableList(), dmHistoryLimit)
        } else {
            session.getRecentMessages(session.messages.size).map { it.toNewMessage() }
        }
        val contextHistory = allMessages
        Log.d(TAG, "📥 [History] total=${session.messages.size} raw=${allMessages.size} → context=${contextHistory.size} (dmHistoryLimit=${dmHistoryLimit ?: "unlimited"})")
        Log.d(TAG, "📥 [Session] Loaded context: ${contextHistory.size} messages")

        if (TextUtils.isEmpty(user)) {
            user = Build.MODEL
        }

        if (keepScreenAwake) {
            // 定时自动化在息屏场景下需要持续保持屏幕和 CPU 活跃，避免亮屏后又快速熄灭。
            WakeLockManager.acquireScreenWakeLock()
        }

        val runToken = UUID.randomUUID().toString()
        val previousSessionJob = activeSessionJobs.remove(effectiveSessionId)
        previousSessionJob?.cancel()
        activeSessionLoops.remove(effectiveSessionId)?.stop()
        val sessionAgentLoop = createSessionScopedAgentLoop(application)
        activeSessionLoops[effectiveSessionId] = sessionAgentLoop
        updateSessionRunningState(effectiveSessionId, running = true, runToken = runToken)

        // Start coroutine execution
        val launchedJob = scope.simpleSafeLaunch(
            {
                var progressJob: Job? = null
                try {
                    Log.d(TAG, "========== Agent Session Execution Start ==========")
                    Log.d(TAG, "🆔 Session ID: $effectiveSessionId")
                    Log.d(TAG, "💬 User input: $userInput")
                    Log.d(TAG, "📋 Context messages: ${contextHistory.size}")

                    // Agent 开始执行 → 自动显示浮动窗口
                    SessionFloatWindow.setAgentRunning(true, application)
                    SessionFloatWindow.updateSessionInfo(
                        title = "Agent 启动中",
                        content = "任务: ${userInput.take(60)}"
                    )

                    // 1. Build system prompt
                    Log.d(TAG, "💬 Building system prompt...")
                    val stateTransitionTrajectory = buildStateTransitionTrajectory(
                        history = contextHistory,
                        userGoal = userInput
                    )
                    val systemPrompt = contextBuilder.buildSystemPrompt(
                        userGoal = userInput,
                        packageName = "",
                        testMode = "chat",
                        loadAgentPolicies = true,
                        stateTransitionTrajectory = stateTransitionTrajectory
                    )
                    Log.d(TAG, "✅ System prompt built (${systemPrompt.length} chars)")

                    // 2. Broadcast user message
                    Log.d(TAG, "📤 [Broadcast] Broadcasting user message...")
                    com.shijing.xomniclaw.gateway.GatewayServer.broadcastChatMessage(
                        effectiveSessionId, "user", userInput
                    )
                    // 先把本轮 user 落盘，保证后续 thinking 永远在 user 之后。
                    ensureUserMessagePersisted(session, sessionUiContext)

                    // 3. Start progress listening
                    progressJob = launch {
                        sessionAgentLoop.progressFlow.collect { update ->
                            handleProgressUpdate(
                                update = update,
                                sessionUiContext = sessionUiContext
                            )
                        }
                    }

                    // 4. Run AgentLoop (with context history)
                    val result = sessionAgentLoop.run(
                        systemPrompt = systemPrompt,
                        userMessage = userInput,
                        contextHistory = contextHistory,
                        reasoningEnabled = false
                    )
                    val shouldReturnToMain = shouldReturnToMainAfterRun(
                        result = result,
                        explicitRequest = returnToMainOnFinish
                    )

                    val cleanFinalContent = com.shijing.xomniclaw.util.ReplyTagFilter.strip(
                        ReasoningTagFilter.stripReasoningTags(result.finalContent)
                    )
                    val displayForUser = appendTokenUsageForDisplay(cleanFinalContent, result.tokenUsage)
                    Log.d(TAG, "========== AgentLoop Complete ==========")
                    Log.d(TAG, "Iterations: ${result.iterations}")
                    Log.d(TAG, "Final result: ${cleanFinalContent}")
                    result.tokenUsage?.let {
                        Log.d(TAG, "Token usage: prompt=${it.promptTokens} completion=${it.completionTokens} total=${it.totalTokens}")
                    }

                    // 任务已经得到最终结果：按真实完成状态立即关闭步骤悬浮窗，
                    // 不再依赖最终回复文案中的关键词来判断。
                    SessionFloatWindow.finishTask()

                    // 5. Broadcast AI response (skip if already sent via block reply)
                    if (cleanFinalContent.isNotEmpty()) {
                        // Update floating window with latest AI response
                        com.shijing.xomniclaw.ui.floatwindow.SessionFloatWindow.updateLatestMessage(displayForUser)

                        if (sessionUiContext.lastBlockReplyText.get()?.trim() == cleanFinalContent.trim()) {
                            Log.d(TAG, "✅ Final content matches last block reply, skipping broadcast")
                        } else {
                            Log.d(TAG, "📤 [Broadcast] Broadcasting AI response...")
                            com.shijing.xomniclaw.gateway.GatewayServer.broadcastChatMessage(
                                effectiveSessionId, "assistant", displayForUser
                            )
                        }
                    }
                    sessionUiContext.lastBlockReplyText.set(null)

                    // 6. Save only run delta messages to session (convert back to legacy format)
                    // Skip system messages: they are ephemeral per-run and re-generated each time.
                    // Persisting full result.messages (which may contain contextHistory) would replay
                    // old assistant outputs and cause cross-round duplicated bubbles in main timeline.
                    Log.d(TAG, "💾 [Session] Saving messages to session...")
                    val deltaMessages = extractRunDeltaMessages(
                        contextHistory = contextHistory,
                        resultMessages = result.messages,
                        currentUserInput = userInput
                    )
                    Log.d(
                        TAG,
                        "💾 [Session] Delta messages: result=${result.messages.size}, context=${contextHistory.size}, delta=${deltaMessages.size}"
                    )
                    deltaMessages.filter { it.role != "system" }.forEach { message ->
                        if (message.role == "user" && sessionUiContext.userMessagePersisted.get()) {
                            // 已在起跑阶段落盘过本轮 user，收尾时跳过重复 user。
                            return@forEach
                        }
                        val sanitizedMessage = if (message.role == "assistant") {
                            message.copy(content = ReasoningTagFilter.stripReasoningTags(message.content))
                        } else message
                        session.addMessage(sanitizedMessage.toLegacyMessage())
                    }
                    sessionManager.save(session)
                    Log.d(TAG, "✅ [Session] Session saved, total messages: ${session.messageCount()}")

                    queueMemoryEvolutionEvent(
                        application = application,
                        sessionId = effectiveSessionId,
                        userInput = userInput,
                        finalContent = cleanFinalContent,
                        success = true,
                        errorMessage = null,
                        toolsUsed = result.toolsUsed
                    )

                    if (shouldReturnToMain) {
                        bringMainUiToFrontAndNotify(
                            sessionId = effectiveSessionId,
                            notice = "✅ 复杂任务已完成，已返回主界面。"
                        )
                    }
                } finally {
                    progressJob?.cancel()
                    if (activeSessionRunTokens[effectiveSessionId] == runToken) {
                        activeSessionJobs.remove(effectiveSessionId)
                        activeSessionLoops.remove(effectiveSessionId)
                    }
                    updateSessionRunningState(effectiveSessionId, running = false, runToken = runToken)
                    if (_runningSessionIds.value.isEmpty()) {
                        SessionFloatWindow.setAgentRunning(false, application)
                    }
                    if (keepScreenAwake) {
                        WakeLockManager.releaseScreenWakeLock()
                    }
                }

            },
            { exception ->
                val cancelledByUser = exception is CancellationException
                if (cancelledByUser) {
                    Log.i(TAG, "🛑 Agent session cancelled: ${exception.message}")
                } else {
                    Log.e(TAG, "❌ Agent session execution failed", exception)
                }
                if (activeSessionRunTokens[effectiveSessionId] == runToken) {
                    activeSessionJobs.remove(effectiveSessionId)
                    activeSessionLoops.remove(effectiveSessionId)
                }
                updateSessionRunningState(effectiveSessionId, running = false, runToken = runToken)
                SessionFloatWindow.finishTask()
                // Agent 异常结束 → 也隐藏浮动窗口
                if (_runningSessionIds.value.isEmpty()) {
                    SessionFloatWindow.setAgentRunning(false, application)
                }
                if (keepScreenAwake) {
                    WakeLockManager.releaseScreenWakeLock()
                }

                // 用户主动取消与真正异常使用不同文案，避免误导为代码故障。
                val errorMessage = if (cancelledByUser) {
                    buildString {
                        append("🛑 已停止执行\n\n")
                        append("你已手动取消当前任务，这不是系统错误。\n\n")
                        append("你可以：\n")
                        append("- 直接发送新指令继续\n")
                        append("- 或点击“新对话”开始新的会话")
                    }
                } else {
                    buildString {
                        append("❌ 执行出错:\n\n")
                        append("**错误**: ${exception.message}\n\n")

                        // 如果是 LLM 异常，添加更详细的信息
                        if (exception is com.shijing.xomniclaw.providers.LLMException) {
                            append("**类型**: API 调用失败\n")
                            append("**建议**: 请检查模型配置和 API key\n\n")
                        }

                        // 添加堆栈跟踪 (前500字符)
                        append("**堆栈跟踪**:\n```\n")
                        append(exception.stackTraceToString().take(500))
                        append("\n```")
                    }
                }

                // 广播错误消息到聊天界面
                try {
                    com.shijing.xomniclaw.gateway.GatewayServer.broadcastChatMessage(
                        effectiveSessionId, "assistant", errorMessage
                    )
                    Log.d(TAG, "📤 [Broadcast] Error message sent to user")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to broadcast error message", e)
                }

                // 保存错误到 session
                try {
                    session.addMessage(com.shijing.xomniclaw.providers.LegacyMessage(
                        role = "assistant",
                        content = errorMessage
                    ))
                    sessionManager.save(session)
                    Log.d(TAG, "💾 [Session] Error saved to session")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to save error to session", e)
                }

                scope.launch(Dispatchers.IO) {
                    queueMemoryEvolutionEvent(
                        application = application,
                        sessionId = effectiveSessionId,
                        userInput = userInput,
                        finalContent = errorMessage,
                        success = false,
                        errorMessage = exception.message,
                        toolsUsed = emptyList()
                    )
                }

                if (returnToMainOnFinish) {
                    bringMainUiToFrontAndNotify(
                        sessionId = effectiveSessionId,
                        notice = if (cancelledByUser) {
                            "🛑 当前任务已停止，已返回主界面。"
                        } else {
                            "⚠️ 任务执行异常，已返回主界面，请查看对话详情。"
                        }
                    )
                }
            }
        )
        activeSessionJobs[effectiveSessionId] = launchedJob
        job = launchedJob
    }

    private fun queueMemoryEvolutionEvent(
        application: Application,
        sessionId: String,
        userInput: String,
        finalContent: String,
        success: Boolean,
        errorMessage: String?,
        toolsUsed: List<String>
    ) {
        if (MemoryEvolutionManager.shouldSkipRunRecording(userInput)) {
            return
        }
        scope.launch(Dispatchers.IO) {
            try {
                val openClawCfg = configLoader.loadOmniClawConfig()
                val embeddingProviders = openClawCfg.resolveProviders()
                val memoryManager = MemoryManager(
                    workspacePath = "/sdcard/.xomniclaw/workspace",
                    context = application,
                    embeddingBaseUrl = embeddingProviders.values.firstOrNull()?.baseUrl ?: "",
                    embeddingApiKey = embeddingProviders.values.firstOrNull()?.apiKey ?: ""
                )
                MemoryEvolutionManager(
                    context = application,
                    memoryManager = memoryManager
                ).recordAgentRun(
                    sessionId = sessionId,
                    userInput = userInput,
                    finalContent = finalContent,
                    success = success,
                    errorMessage = errorMessage,
                    toolsUsed = toolsUsed
                )
            } catch (e: Exception) {
                Log.w(TAG, "Failed to queue memory evolution event", e)
            }
        }
    }

    /**
     * Run test task - New architecture version
     */
    fun run(
        userInput: String,
        application: Application,
        existingRecordId: String? = null,
        existingPackageName: String? = null,
        onSummaryFinished: (() -> Job)? = null
    ) {
        // 确保已初始化
        if (!::agentLoop.isInitialized) {
            initialize(application)
        }

        // 重置状态
        _summaryFinished.value = false

        if (TextUtils.isEmpty(user)) {
            user = Build.MODEL
        }

        // 先回到桌面
        safePressHome()

        // 创建新任务
        val newTaskId = generateTaskId()
        taskDataManager.startNewTask(newTaskId, existingPackageName ?: "")
        currentTaskId = newTaskId
        Log.d(TAG, "========== 新测试任务: $newTaskId ==========")

        // Read mode from xomniclaw.json instead of MMKV
        val openClawConfig = configLoader.loadOmniClawConfig()
        val testMode = openClawConfig.agent.mode
        Log.d(TAG, "测试模式: $testMode (from xomniclaw.json)")

        // Cancel old task
        cancelCurrentJobWithoutClearingTaskData()

        // Set new task as running
        val newTaskData = taskDataManager.getCurrentTaskData()
        newTaskData?.setIsRunning(true)

        // Acquire screen wake lock
        WakeLockManager.acquireScreenWakeLock()
        Log.d(TAG, "已获取屏幕唤醒锁")

        // Start coroutine to execute test
        Log.d(TAG, "🚀 About to start coroutine for test task...")
        _agentSessionRunning.value = true
        job = scope.simpleSafeLaunch(
            {
                var progressJob: Job? = null
                try {
                    Log.d(TAG, "✅ Coroutine started, executing test task...")

                    SessionFloatWindow.setAgentRunning(true, application)
                    SessionFloatWindow.updateSessionInfo(
                        title = "Agent 启动中",
                        content = "任务: ${userInput.take(60)}"
                    )

                    Log.d(TAG, "💬 Step 1: Building system prompt...")
                    val packageName = existingPackageName ?: ""
                    val systemPrompt = contextBuilder.buildSystemPrompt(
                        userGoal = userInput,
                        packageName = packageName,
                        testMode = testMode,
                        loadAgentPolicies = true
                    )

                    Log.d(TAG, "✅ System prompt built (${systemPrompt.length} chars)")
                    Log.d(TAG, "✅ Estimated Tokens: ~${systemPrompt.length / 4}")

                    val skillsStats = contextBuilder.getSkillsStatistics()
                    if (skillsStats.isNotEmpty()) {
                        Log.d(TAG, "📊 Skills statistics:")
                        skillsStats.lines().forEach { line ->
                            Log.d(TAG, "   $line")
                        }
                    }

                    Log.d(TAG, "👂 Step 2: Starting progress listening...")
                    progressJob = launch {
                        Log.d(TAG, "✅ Progress listening coroutine started")
                        agentLoop.progressFlow.collect { update ->
                            Log.d(TAG, "📥 Received progress update: ${update.javaClass.simpleName}")
                            handleProgressUpdate(update = update, sessionUiContext = null)
                        }
                    }
                    Log.d(TAG, "✅ Progress listening set up")

                    Log.d(TAG, "========== Starting AgentLoop ==========")
                    Log.d(TAG, "System prompt length: ${systemPrompt.length}")
                    Log.d(TAG, "User input: $userInput")
                    Log.d(TAG, "Universal tools: ${toolRegistry.getToolCount()}")
                    Log.d(TAG, "Android tools: ${androidToolRegistry.getToolCount()}")

                    val result = agentLoop.run(
                        systemPrompt = systemPrompt,
                        userMessage = userInput,
                        reasoningEnabled = false
                    )

                    Log.d(TAG, "========== AgentLoop Complete ==========")
                    Log.d(TAG, "Iterations: ${result.iterations}")
                    Log.d(TAG, "Tools used: ${result.toolsUsed.joinToString(", ")}")
                    Log.d(TAG, "Final result: ${result.finalContent}")
                    result.tokenUsage?.let {
                        Log.d(
                            TAG,
                            "Token usage: prompt=${it.promptTokens} completion=${it.completionTokens} total=${it.totalTokens}"
                        )
                    }

                    // 测试任务也使用真实完成态来收起步骤悬浮窗。
                    SessionFloatWindow.finishTask()

                    _summaryFinished.value = true
                    onSummaryFinished?.invoke()

                    Log.d(TAG, "测试任务执行完成")
                } finally {
                    progressJob?.cancel()
                    WakeLockManager.releaseScreenWakeLock()
                    SessionFloatWindow.setAgentRunning(false, application)
                    _agentSessionRunning.value = false
                }
            },
            { error ->
                Log.e(TAG, "测试任务执行失败", error)
                LayoutExceptionLogger.log("MainEntryNew#run", error)

                SessionFloatWindow.finishTask()
                WakeLockManager.releaseScreenWakeLock()
                SessionFloatWindow.setAgentRunning(false, application)
                _summaryFinished.value = true
                _agentSessionRunning.value = false
            }
        )
    }

    private fun emitProgressToUi(
        sessionUiContext: SessionUiContext,
        type: String,
        title: String,
        content: String
    ) {
        val sessionId = sessionUiContext.sessionId
        val sequence = progressEventCounter.incrementAndGet()
        _uiProgressFlow.tryEmit(
            UiProgressEvent(
                id = "progress_${sessionId}_$sequence",
                sessionId = sessionId,
                type = type,
                title = title,
                content = content,
                timestamp = System.currentTimeMillis()
            )
        )
    }

    /**
     * Handle progress update - Only update floating window display
     */
    private suspend fun handleProgressUpdate(
        update: ProgressUpdate,
        sessionUiContext: SessionUiContext?
    ) {
        logd("handleProgressUpdate called: ${update.javaClass.simpleName}")
        when (update) {
            is ProgressUpdate.Iteration -> {
                logd("========== Iteration ${update.number} ==========")
                sessionUiContext?.currentStepIndex?.set(update.number.toLong())
                SessionFloatWindow.updateSessionInfo(
                    title = "🤖 步骤 ${update.number}/$agentMaxIterations",
                    content = "正在思考..."
                )
            }

            is ProgressUpdate.Thinking -> {
                logd("💭 Thinking: 正在处理第 ${update.iteration} 步...")
                sessionUiContext?.currentStepIndex?.set(update.iteration.toLong())
                SessionFloatWindow.updateSessionInfo(
                    title = "🤖 步骤 ${update.iteration}/$agentMaxIterations",
                    content = "正在思考..."
                )
            }

            is ProgressUpdate.Reasoning -> {
                logd("🧠 Reasoning (${update.content.length} chars, ${update.llmDuration}ms)")
                sessionUiContext?.let { ctx ->
                    val step = ctx.currentStepIndex.get().takeIf { it > 0 } ?: -1L
                    val stepTag = if (step > 0) "Step ${step.toInt()}" else "Step ?"
                    val indexedReasoning = buildString {
                        append("[$stepTag] ")
                        append(update.content)
                    }
                    SessionFloatWindow.updateSessionInfo(
                        title = "思考完成（$stepTag）",
                        content = indexedReasoning.take(100) + if (indexedReasoning.length > 100) "..." else ""
                    )
                    // 落盘到 Agent JSONL，冷启动后 sync 才能恢复主对话中的「思考过程」
                    try {
                        val s = sessionManager.getOrCreate(ctx.sessionId)
                        ensureUserMessagePersisted(s, ctx)
                        s.addMessage(
                            LegacyMessage(
                                role = "thinking",
                                content = indexedReasoning
                            )
                        )
                        sessionManager.save(s)
                    } catch (e: Exception) {
                        loge("Failed to persist reasoning to agent session", e)
                    }
                    emitProgressToUi(
                        ctx,
                        type = "reasoning",
                        title = "思考过程（$stepTag）",
                        content = indexedReasoning
                    )
                }
                if (sessionUiContext == null) {
                    SessionFloatWindow.updateSessionInfo(
                        title = "思考完成",
                        content = update.content.take(100) + if (update.content.length > 100) "..." else ""
                    )
                }
            }

            is ProgressUpdate.ToolCall -> {
                logd("🔧 Tool: ${update.name}")

                val argsText = if (update.arguments.isEmpty()) {
                    "无参数"
                } else {
                    update.arguments.entries.joinToString("\n") { (key, value) ->
                        "  • $key: $value"
                    }
                }

                SessionFloatWindow.updateSessionInfo(
                    title = "🔧 执行: ${update.name}",
                    content = argsText.take(100)
                )
                sessionUiContext?.let {
                    emitProgressToUi(it, "tool_call", "执行: ${update.name}", argsText)
                }
            }

            is ProgressUpdate.ToolResult -> {
                logd("✅ Result: ${update.result.take(100)}, ${update.execDuration}ms")
                SessionFloatWindow.updateSessionInfo(
                    title = "执行完成",
                    content = update.result.take(100) + if (update.result.length > 100) "..." else ""
                )
                sessionUiContext?.let {
                    emitProgressToUi(it, "tool_result", "执行完成", update.result)
                }
            }

            is ProgressUpdate.IterationComplete -> {
                logd("🏁 Iteration ${update.number} complete: total=${update.iterationDuration}ms, llm=${update.llmDuration}ms, exec=${update.execDuration}ms")
                SessionFloatWindow.updateSessionInfo(
                    title = "✅ 步骤 ${update.number}/$agentMaxIterations 完成",
                    content = "耗时: ${update.iterationDuration / 1000}s"
                )
            }

            is ProgressUpdate.ContextOverflow -> {
                logw("🔄 Context overflow: ${update.message}")
                SessionFloatWindow.updateSessionInfo(
                    title = "上下文超限",
                    content = update.message
                )
            }

            is ProgressUpdate.ContextRecovered -> {
                logd("✅ Context recovered: ${update.strategy} (attempt ${update.attempt})")
                SessionFloatWindow.updateSessionInfo(
                    title = "上下文已恢复",
                    content = "策略: ${update.strategy}"
                )
            }

            is ProgressUpdate.LoopDetected -> {
                val logLevel = if (update.critical) "🚨" else "⚠️"
                logw("$logLevel Loop detected: ${update.detector} (count: ${update.count})")
                SessionFloatWindow.updateSessionInfo(
                    title = "${if (update.critical) "严重" else "警告"}: 循环检测",
                    content = "${update.detector}: ${update.count} 次"
                )
            }

            is ProgressUpdate.LlmUsage -> {
                // 实时累计 token：有 session 上下文则记到该会话，否则归到测试入口。
                val targetSessionId = sessionUiContext?.sessionId ?: "test_run"
                accumulateTokenUsage(targetSessionId, update.usage)
            }

            is ProgressUpdate.Error -> {
                loge("❌ Error: ${update.message}")
                SessionFloatWindow.updateSessionInfo(
                    title = "错误",
                    content = update.message.take(100)
                )
                sessionUiContext?.let {
                    emitProgressToUi(it, "error", "错误", update.message)
                }
            }

            is ProgressUpdate.BlockReply -> {
                logd("📤 Block reply: ${update.text.take(200)}")
                SessionFloatWindow.updateSessionInfo(
                    title = "中间回复",
                    content = update.text.take(100) + if (update.text.length > 100) "..." else ""
                )
                sessionUiContext?.let {
                    emitProgressToUi(it, "block_reply", "中间回复", update.text)
                    // 中间回复需要绑定到本次 run 的 session，不能再走全局 activeSessionId。
                    it.lastBlockReplyText.set(update.text)
                    com.shijing.xomniclaw.gateway.GatewayServer.broadcastChatMessage(
                        it.sessionId, "assistant", update.text
                    )
                }
            }
        }
    }

    /**
     * 确保本轮用户输入已写入会话，避免 thinking 抢先入库导致“思考在用户前”。
     */
    private fun ensureUserMessagePersisted(
        session: com.shijing.xomniclaw.agent.session.Session,
        sessionUiContext: SessionUiContext
    ) {
        if (sessionUiContext.userMessagePersisted.get()) return
        val input = sessionUiContext.currentUserInput.trim()
        if (input.isBlank()) return
        val alreadyLastUser = session.messages.lastOrNull()?.let { last ->
            val lastText = when (val c = last.content) {
                is String -> c.trim()
                else -> c?.toString()?.trim().orEmpty()
            }
            last.role == "user" && lastText == input
        } ?: false
        if (!alreadyLastUser) {
            session.addMessage(
                LegacyMessage(
                    role = "user",
                    content = input
                )
            )
            sessionManager.save(session)
        }
        sessionUiContext.userMessagePersisted.set(true)
    }


    /**
     * Cancel current task
     */
    fun cancelCurrentJob(isRunning: Boolean) {
        Log.d(TAG, "cancelCurrentJob")

        _agentSessionRunning.value = false
        _runningSessionIds.value = emptySet()
        activeSessionRunTokens.clear()
        activeSessionLoops.values.forEach { loop -> loop.stop() }
        activeSessionLoops.clear()
        activeSessionJobs.values.forEach { it.cancel() }
        activeSessionJobs.clear()

        WakeLockManager.releaseScreenWakeLock()

        currentTaskId = null
        taskDataManager.clearCurrentTask()
        job?.cancel()

        val currentTaskData = taskDataManager.getCurrentTaskData()
        currentTaskData?.setIsRunning(isRunning)

        _summaryFinished.value = true

        // Stop AgentLoop
        if (::agentLoop.isInitialized) {
            agentLoop.stop()
        }

        // 用户手动停止 → 隐藏浮动窗口
        if (::application.isInitialized) {
            SessionFloatWindow.setAgentRunning(false, application)
        }
    }

    /**
     * Cancel current task without clearing TaskData
     */
    private fun cancelCurrentJobWithoutClearingTaskData() {
        Log.d(TAG, "cancelCurrentJobWithoutClearingTaskData")

        _agentSessionRunning.value = false
        _runningSessionIds.value = emptySet()
        activeSessionRunTokens.clear()
        activeSessionLoops.values.forEach { loop -> loop.stop() }
        activeSessionLoops.clear()
        activeSessionJobs.values.forEach { it.cancel() }
        activeSessionJobs.clear()

        WakeLockManager.releaseScreenWakeLock()
        job?.cancel()

        val currentTaskData = taskDataManager.getCurrentTaskData()
        currentTaskData?.setIsRunning(false)
    }

    /**
     * 仅停止指定会话的运行，不影响其它会话。
     */
    fun cancelSessionJob(sessionId: String, isRunning: Boolean = false) {
        Log.d(TAG, "cancelSessionJob: sessionId=$sessionId")
        activeSessionLoops.remove(sessionId)?.stop()
        activeSessionJobs.remove(sessionId)?.cancel()
        activeSessionRunTokens.remove(sessionId)
        _runningSessionIds.value = activeSessionRunTokens.keys.toSet()
        _agentSessionRunning.value = _runningSessionIds.value.isNotEmpty()
        if (_runningSessionIds.value.isEmpty() && ::application.isInitialized) {
            SessionFloatWindow.setAgentRunning(false, application)
        }
        val currentTaskData = taskDataManager.getCurrentTaskData()
        currentTaskData?.setIsRunning(isRunning)
    }

    /**
     * 停止所有仍在执行的会话任务（红色停止按钮走这里，避免 runningSessionIds 漏记导致停不掉）。
     */
    fun cancelAllSessionJobs() {
        val ids = (activeSessionJobs.keys + activeSessionLoops.keys + activeSessionRunTokens.keys).toSet()
        Log.d(TAG, "cancelAllSessionJobs: ids=$ids")
        ids.forEach { sid -> cancelSessionJob(sid, false) }
    }

    // ================ Helper Methods ================

    private fun generateTaskId(): String {
        return "task_${System.currentTimeMillis()}"
    }

    /**
     * 仅提取本轮新增消息，避免把历史上下文重复落盘。
     *
     * 兼容两种情况：
     * 1) resultMessages 以 contextHistory 为前缀 -> 直接 drop(history.size)
     * 2) 前缀不完全一致（某些 provider 会改写）-> 按“公共前缀”长度回退
     *
     * 注意：Python 侧 [messages] 始终以 **system** 开头，而 Kotlin 传入的 contextHistory **不含**
     * system。旧实现用 resultMessages[0]==system 与 contextHistory[0]==user 比对，前缀长度恒为 0，
     * 进而 drop(0)==整段回放，会话里会像「把上一轮历史又追加一遍」（OpenRouter/Gemma 等更易触发）。
     */
    private fun extractRunDeltaMessages(
        contextHistory: List<com.shijing.xomniclaw.providers.llm.Message>,
        resultMessages: List<com.shijing.xomniclaw.providers.llm.Message>,
        currentUserInput: String
    ): List<com.shijing.xomniclaw.providers.llm.Message> {
        if (resultMessages.isEmpty()) return emptyList()
        // 与发给 Python 的对话数组对齐：去掉仅存在于完整 payload 里的首条 system。
        val resultTail = resultMessages.dropWhile { it.role == "system" }
        if (contextHistory.isEmpty()) return resultTail

        // 优先按「本轮用户句」锚定：取最后一次与当前输入一致的用户消息之后的增量（含本条 user 之后的所有轮次）。
        val currentAnchor = normalizeUserInputAnchor(currentUserInput)
        if (currentAnchor.isNotEmpty()) {
            val lastUserIdx = resultTail.indexOfLast { msg ->
                msg.role == "user" && normalizeUserInputAnchor(msg.content) == currentAnchor
            }
            // 不要求 lastUserIdx < lastIndex：若仅剩 user、尚无 assistant（异常短回包），drop 为空即可。
            if (lastUserIdx >= 0) {
                return resultTail.drop(lastUserIdx + 1)
            }
        }

        val maxPrefix = minOf(contextHistory.size, resultTail.size)
        var samePrefixCount = 0
        while (samePrefixCount < maxPrefix) {
            val h = contextHistory[samePrefixCount]
            val r = resultTail[samePrefixCount]
            val sameRole = h.role == r.role
            val sameContent = h.content.trim() == r.content.trim()
            if (!sameRole || !sameContent) break
            samePrefixCount += 1
        }
        if (samePrefixCount == 0 && contextHistory.isNotEmpty()) {
            Log.w(
                TAG,
                "extractRunDeltaMessages: 前缀仍为 0（history=${contextHistory.size}, tail=${resultTail.size}），" +
                    "可能造成重复落盘；请检查 provider 是否改写了历史句内容。"
            )
        }
        return resultTail.drop(samePrefixCount)
    }

    /** 用户句锚点归一化：NFKC + trim，减轻 Gemma/OpenRouter 与输入法间 Unicode 差异导致的锚定失败 */
    private fun normalizeUserInputAnchor(raw: String): String {
        return Normalizer.normalize(raw.trim(), Normalizer.Form.NFKC)
    }

    private fun safePressHome() {
        try {
            AccessibilityBinderService.serviceInstance?.pressHomeButton()
        } catch (e: Exception) {
            LayoutExceptionLogger.log("MainEntryNew#safePressHome", e)
        }
    }

    private fun shouldReturnToMainAfterRun(
        result: com.shijing.xomniclaw.agent.loop.AgentResult,
        explicitRequest: Boolean
    ): Boolean {
        if (explicitRequest) return true
        val multiStep = result.iterations >= 3
        val hasDeviceOps = result.toolsUsed.any { tool ->
            tool.equals("device", ignoreCase = true) ||
                tool.equals("tap", ignoreCase = true) ||
                tool.equals("swipe", ignoreCase = true) ||
                tool.equals("open_app", ignoreCase = true) ||
                tool.equals("input_text", ignoreCase = true)
        }
        return multiStep && hasDeviceOps
    }

    private data class PendingExpectedAction(
        val toolCallId: String?,
        val toolName: String,
        val action: String,
        val intent: String,
        val expectation: String
    )

    private data class TrajectoryStep(
        val intent: String,
        val action: String,
        val expectation: String,
        val outcome: String,
        val fingerprint: String
    )

    /**
     * 构造低 token 的“状态变化轨迹”上下文：
     * - Summary Memory：已完成子目标摘要
     * - Short-term Trajectory：最近 3~5 步 I/A/E/O
     * - Self-check Guidance：避免重复 snapshot / 重复动作
     */
    private fun buildStateTransitionTrajectory(
        history: List<com.shijing.xomniclaw.providers.llm.Message>,
        userGoal: String
    ): String {
        if (history.isEmpty()) return ""

        val pendingById = LinkedHashMap<String, PendingExpectedAction>()
        val pendingQueue = ArrayDeque<PendingExpectedAction>()
        val steps = mutableListOf<TrajectoryStep>()

        history.takeLast(40).forEach { msg ->
            when (msg.role) {
                "assistant" -> {
                    val toolCalls = msg.toolCalls ?: emptyList()
                    toolCalls.forEach { tc ->
                        val normalizedArgs = tc.arguments.replace("\n", " ").trim().take(120)
                        val action = if (normalizedArgs.isBlank()) {
                            tc.name
                        } else {
                            "${tc.name}($normalizedArgs)"
                        }
                        val pending = PendingExpectedAction(
                            toolCallId = tc.id.takeIf { it.isNotBlank() },
                            toolName = tc.name,
                            action = action,
                            intent = inferIntentFromToolName(tc.name, userGoal),
                            expectation = inferExpectationFromToolName(tc.name)
                        )
                        pending.toolCallId?.let { pendingById[it] = pending }
                        pendingQueue.addLast(pending)
                    }
                }

                "tool" -> {
                    val resolved = msg.toolCallId?.let { pendingById.remove(it) } ?: pendingQueue.removeFirstOrNull()
                    val toolName = resolved?.toolName ?: (msg.name ?: "unknown_tool")
                    val action = resolved?.action ?: toolName
                    val intent = resolved?.intent ?: inferIntentFromToolName(toolName, userGoal)
                    val expectation = resolved?.expectation ?: inferExpectationFromToolName(toolName)
                    val outcome = summarizeToolOutcome(msg.content)
                    val fingerprint = extractUiFingerprint(msg.content)
                    steps += TrajectoryStep(
                        intent = intent,
                        action = action,
                        expectation = expectation,
                        outcome = outcome,
                        fingerprint = fingerprint
                    )
                }
            }
        }

        if (steps.isEmpty()) return ""

        val shortTerm = steps.takeLast(5)
        val summaryMemory = shortTerm
            .filter { it.outcome.startsWith("success") || it.outcome.startsWith("partial") }
            .map { it.action.substringBefore("(").trim() }
            .distinct()
            .take(3)

        val summaryLines = if (summaryMemory.isEmpty()) {
            listOf("任务目标：${userGoal.take(60)}")
        } else {
            summaryMemory.map { "已执行：$it" }
        }

        return buildString {
            appendLine("Summary Memory:")
            summaryLines.forEach { appendLine("- $it") }
            appendLine()
            appendLine("Short-term Trajectory (latest ${shortTerm.size}):")
            shortTerm.forEachIndexed { index, s ->
                appendLine(
                    "- Step ${index + 1} | Intent: ${s.intent} | Action: ${s.action} | " +
                        "Expectation: ${s.expectation} | Outcome: ${s.outcome} | UI: ${s.fingerprint}"
                )
            }
            appendLine()
            appendLine("Validation:")
            appendLine("- If current UI already matches last expectation, do NOT repeat same action.")
            appendLine("- If same UI fingerprint repeats with same action, switch strategy instead of re-snapshot.")
        }.trim()
    }

    private fun inferIntentFromToolName(toolName: String, userGoal: String): String {
        val lower = toolName.lowercase()
        return when {
            lower.contains("open") || lower.contains("launch") -> "打开目标应用并进入可操作页面"
            lower.contains("tap") || lower.contains("click") -> "定位并触发页面元素"
            lower.contains("input") || lower.contains("type") -> "输入查询词或表单内容"
            lower.contains("swipe") || lower.contains("scroll") -> "浏览页面并发现目标区域"
            lower.contains("back") -> "返回上一层并重新定位流程"
            lower.contains("snapshot") || lower.contains("screenshot") || lower == "device" -> "观察当前页面状态以判断下一步"
            else -> "推进用户目标：${userGoal.take(40)}"
        }
    }

    private fun inferExpectationFromToolName(toolName: String): String {
        val lower = toolName.lowercase()
        return when {
            lower.contains("open") || lower.contains("launch") -> "看到目标应用首页或搜索入口"
            lower.contains("tap") || lower.contains("click") -> "页面发生可见变化或进入下一层"
            lower.contains("input") || lower.contains("type") -> "输入框内容更新并可继续提交"
            lower.contains("swipe") || lower.contains("scroll") -> "可见区域变化，出现新候选信息"
            lower.contains("back") -> "返回上一页并出现预期导航层级"
            lower.contains("snapshot") || lower.contains("screenshot") || lower == "device" -> "获得可判断下一步的页面证据"
            else -> "得到推进任务的可验证反馈"
        }
    }

    private fun summarizeToolOutcome(raw: String): String {
        val oneLine = raw.replace("\n", " ").trim()
        if (oneLine.isBlank()) return "unknown: empty result"
        val lower = oneLine.lowercase()
        return when {
            lower.contains("success") || lower.contains("ok") || lower.contains("true") ->
                "success: ${oneLine.take(90)}"
            lower.contains("error") || lower.contains("fail") || lower.contains("false") ->
                "fail: ${oneLine.take(90)}"
            else -> "partial: ${oneLine.take(90)}"
        }
    }

    private fun extractUiFingerprint(raw: String): String {
        val oneLine = raw.replace("\n", " ").trim()
        if (oneLine.isBlank()) return "unknown_ui"
        val packageMatch = Regex("""(?:package|pkg)[=:]\s*([A-Za-z0-9\._]+)""")
            .find(oneLine)?.groupValues?.getOrNull(1)
        val activityMatch = Regex("""(?:activity|screen|page)[=:]\s*([A-Za-z0-9\._/]+)""")
            .find(oneLine)?.groupValues?.getOrNull(1)
        val joined = listOfNotNull(packageMatch, activityMatch).joinToString("/")
        return if (joined.isNotBlank()) joined.take(60) else oneLine.take(60)
    }

    private fun bringMainUiToFrontAndNotify(sessionId: String, notice: String) {
        try {
            val launchIntent = application.packageManager.getLaunchIntentForPackage(application.packageName)
            if (launchIntent != null) {
                launchIntent.addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
                application.startActivity(launchIntent)
                Log.d(TAG, "📲 Brought X-OmniClaw main UI to foreground")
            } else {
                Log.w(TAG, "Launch intent not found for package ${application.packageName}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to bring main UI to foreground", e)
        }

        try {
            com.shijing.xomniclaw.gateway.GatewayServer.broadcastChatMessage(
                sessionId,
                "assistant",
                notice
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to broadcast return-to-main notice", e)
        }
    }
}
