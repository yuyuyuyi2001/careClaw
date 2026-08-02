package com.shijing.xomniclaw.core

/**
 * OmniClaw Source Reference:
 * - ../xomniclaw/src/gateway/(all)
 * - ../xomniclaw/src/channels/(all)
 *
 * OmniClaw adaptation: application bootstrap, channel startup, global lifecycle.
 */


import android.app.Activity
import com.shijing.xomniclaw.util.ReasoningTagFilter
import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import com.shijing.xomniclaw.accessibility.AccessibilityProxy
import com.shijing.xomniclaw.accessibility.AccessibilityHealthMonitor
import com.shijing.xomniclaw.util.GlobalExceptionHandler
import com.shijing.xomniclaw.util.WakeLockManager
import com.shijing.xomniclaw.data.model.TaskDataManager
import com.shijing.xomniclaw.util.AppInfoScanner
import com.tencent.mmkv.MMKV
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import com.shijing.xomniclaw.gateway.GatewayService
import com.shijing.xomniclaw.gateway.GatewayServer
import com.shijing.xomniclaw.gateway.GatewayController
import com.shijing.xomniclaw.agent.session.SessionManager
import com.shijing.xomniclaw.agent.skills.SkillsLoader
import com.shijing.xomniclaw.config.ConfigLoader
import com.xiaomo.feishu.FeishuChannel
import com.xiaomo.discord.DiscordChannel
import com.xiaomo.discord.DiscordConfig
import com.xiaomo.discord.ChannelEvent
import com.xiaomo.discord.session.DiscordSessionManager
import com.xiaomo.discord.session.DiscordHistoryManager
import com.xiaomo.discord.session.DiscordDedup
import com.xiaomo.discord.messaging.DiscordTyping
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.Job
import com.shijing.xomniclaw.providers.llm.toNewMessage
import com.shijing.xomniclaw.providers.llm.toLegacyMessage
import com.shijing.xomniclaw.agent.tools.ToolRegistry
import com.shijing.xomniclaw.agent.tools.AndroidToolRegistry
import com.shijing.xomniclaw.agent.context.ContextBuilder
import com.shijing.xomniclaw.agent.loop.AgentLoop
import com.shijing.xomniclaw.agent.loop.ProgressUpdate
import com.shijing.xomniclaw.providers.UnifiedLLMProvider

/**
 */
class MyApplication : Application(), Application.ActivityLifecycleCallbacks {

    companion object {
        private const val TAG = "MyApplication"
        private var activeActivityCount = 0
        private var isChangingConfiguration = false

        lateinit var application: Application

        // Singleton access
        val instance: MyApplication
            get() = application as MyApplication

        // Gateway Server
        private var gatewayServer: GatewayServer? = null

        // Gateway Controller
        private var gatewayController: GatewayController? = null

        // Feishu Channel
        private var feishuChannel: FeishuChannel? = null

        /**
         * Get Feishu Channel (for tool invocation)
         */
        fun getFeishuChannel(): FeishuChannel? = feishuChannel

        // Message Queue Manager: fully aligned with OmniClaw's queue mechanism
        // Supports five modes: interrupt, steer, followup, collect, queue
        private val messageQueueManager = MessageQueueManager()

        // Discord Channel
        private var discordChannel: DiscordChannel? = null
        private val discordSessionManager = DiscordSessionManager()
        private val discordHistoryManager = DiscordHistoryManager(maxHistoryPerChannel = 50)
        private val discordDedup = DiscordDedup()
        private var discordTyping: DiscordTyping? = null
        private val discordProcessingJobs = mutableMapOf<String, Job>()

        // Accessibility Health Monitor
        private var healthMonitor: AccessibilityHealthMonitor? = null

        private fun onAppForeground() {
            Log.d(TAG, "App回到前台")
            // Check if test task is running, if so ensure WakeLock is acquired
            ensureWakeLockForTesting()
        }

        private fun onAppBackground() {
            Log.d(TAG, "App进入后台")
            // Check if test task is running, if so ensure WakeLock is acquired
            ensureWakeLockForTesting()
        }

        /**
         * Check test task status, if test task is running ensure WakeLock is acquired
         * This ensures the app won't lock screen when running in background
         *
         * Called at:
         * 1. App startup (onCreate)
         * 2. App entering background (onAppBackground)
         * 3. App returning to foreground (onAppForeground)
         */
        private fun ensureWakeLockForTesting() {
            try {
                val taskDataManager = TaskDataManager.getInstance()
                val hasTask = taskDataManager.hasCurrentTask()
                
                if (hasTask) {
                    val taskData = taskDataManager.getCurrentTaskData()
                    val isRunning = taskData?.getIsRunning() ?: false

                    if (isRunning) {
                        // Test task is running, ensure WakeLock is acquired
                        // acquireScreenWakeLock has internal duplicate acquisition prevention, safe to call
                        Log.d(TAG, "检测到测试任务在运行，确保 WakeLock 已获取（应用状态: ${if (activeActivityCount == 0) "后台" else "前台"}）")
                        WakeLockManager.acquireScreenWakeLock()
                    } else {
                        // Test task has stopped, release WakeLock
                        Log.d(TAG, "测试任务已停止，释放 WakeLock")
                        WakeLockManager.releaseScreenWakeLock()
                    }
                } else {
                    // No test task, ensure WakeLock is released
                    // releaseScreenWakeLock has internal check, skip if not active
                    if (WakeLockManager.isScreenWakeLockActive()) {
                        Log.d(TAG, "没有测试任务，释放 WakeLock")
                        WakeLockManager.releaseScreenWakeLock()
                    } else {
                        Log.d(TAG, "没有测试任务，WakeLock 未激活，无需释放")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "检查测试任务状态失败: ${e.message}", e)
            }
        }

        /**
         * Handle messages from ChatBroadcastReceiver
         * Send local broadcast for MainActivityCompose to handle
         */
        fun handleChatBroadcast(message: String) {
            Log.d(TAG, "📨 handleChatBroadcast: $message")
            try {
                // Send local broadcast for MainActivityCompose to handle
                val intent = Intent("com.shijing.xomniclaw.CHAT_MESSAGE_FROM_BROADCAST")
                intent.putExtra("message", message)
                androidx.localbroadcastmanager.content.LocalBroadcastManager
                    .getInstance(application)
                    .sendBroadcast(intent)
                Log.d(TAG, "✅ 已发送本地广播")
            } catch (e: Exception) {
                Log.e(TAG, "发送本地广播失败: ${e.message}", e)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        application = this

        // Apply saved language settings
        com.shijing.xomniclaw.util.LocaleHelper.applyLanguage(this)

        MMKV.initialize(this)
        registerActivityLifecycleCallbacks(this)

        // Pre-initialize Chaquopy Python runtime to avoid cold-start latency (~200-500ms)
        initializePython()

        // Initialize file logging system
        initializeFileLogger()

        // Initialize Workspace (aligned with OmniClaw)
        initializeWorkspace()

        // Initialize Cron scheduled tasks
        initializeCronJobs()
        ensureMemoryEvolutionTask()
        rescheduleSystemScheduledTasks()

        // Register global exception handler
        Thread.setDefaultUncaughtExceptionHandler(GlobalExceptionHandler())
        com.shijing.xomniclaw.util.CrashBreadcrumbs.mark("app.onCreate", "default_exception_handler_registered")

        // 启动时把上一次保留下来的“最后一步”重新打到日志里，便于直接在 logcat 中对照。
        com.shijing.xomniclaw.util.CrashBreadcrumbs.readLatest()?.let { latest ->
            Log.i(TAG, "Last breadcrumb before previous death: $latest")
        }

        // Start foreground service keep-alive
        startForegroundServiceKeepAlive()

        // Start Gateway server
        startGatewayServer()

        // ✅ Test config system
        testConfigSystem()

        // Check if test task is running on app startup, if so acquire WakeLock
        // Delayed check to ensure TaskDataManager is initialized
        Handler(Looper.getMainLooper()).postDelayed({
            ensureWakeLockForTesting()
        }, 1000) // 1 second delay

        // 🌐 Start Gateway service
        startGatewayService()

        // 📱 Start Feishu Channel (if enabled)
        startFeishuChannelIfEnabled()
        startDiscordChannelIfEnabled()

        // 🪟 Initialize floating window manager
        com.shijing.xomniclaw.ui.floatwindow.SessionFloatWindow.init(this)

        // 🔌 Start health monitoring (serviceInstance managed by observer lifecycle)
        healthMonitor = AccessibilityHealthMonitor(applicationContext)
        healthMonitor?.startMonitoring()

        // Listen to connection status
        GlobalScope.launch(Dispatchers.Main) {
            AccessibilityProxy.isConnected.observeForever { connected ->
                if (connected) {
                    Log.i(TAG, "✅ 无障碍服务已连接")
                } else {
                    Log.w(TAG, "⚠️ 无障碍服务未连接")
                }
            }
        }

        // Delayed scan and export app info (avoid blocking app startup)
//        Handler(Looper.getMainLooper()).postDelayed({
//            try {
//                AppInfoScanner.scanAndExport(this)
//            } catch (e: Exception) {
//                Log.e(TAG, "扫描应用信息失败: ${e.message}", e)
//            }
//        }, 2000) // 2 second delay to ensure app is fully started
    }

    fun isAppInBackground(): Boolean {
        return activeActivityCount == 0
    }

    /**
     * Start foreground service keep-alive
     */
    private fun startForegroundServiceKeepAlive() {
        try {
            val serviceIntent = Intent(this, ForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            Log.i(TAG, "✅ 前台服务已启动（保活）")
        } catch (e: android.app.ForegroundServiceStartNotAllowedException) {
            // Android 14+: cannot start foreground service from background
            Log.w(TAG, "⚠️ 前台服务启动受限（应用在后台），将在下次回到前台时重试")
        } catch (e: Exception) {
            Log.e(TAG, "❌ 前台服务启动失败", e)
        }
    }

    /**
     * Start Gateway server
     */
    private fun startGatewayServer() {
        try {
            // Stop old instance first (if exists)
            gatewayServer?.stop()
            gatewayServer = null

            // Create and start new instance
            gatewayServer = GatewayServer(this, port = 8080)
            gatewayServer?.start()

            Log.i(TAG, "✅ Gateway Server 启动成功")
            Log.i(TAG, "  - HTTP: http://0.0.0.0:8080")
            Log.i(TAG, "  - WebSocket: ws://0.0.0.0:8080/ws")

            // Get local IP
            GlobalScope.launch(Dispatchers.IO) {
                try {
                    val ip = getLocalIpAddress()
                    if (ip != null) {
                        Log.i(TAG, "  - 局域网访问: http://$ip:8080")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "无法获取本机 IP", e)
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "❌ Gateway Server 启动失败", e)
        }
    }

    /**
     * Get local IP address
     */
    private fun getLocalIpAddress(): String? {
        try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (!address.isLoopbackAddress && address is java.net.Inet4Address) {
                        return address.hostAddress
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "获取 IP 地址失败", e)
        }
        return null
    }

    /**
     * Test config system
     */
    /**
     * Pre-initialize Chaquopy Python runtime on a background thread.
     */
    private fun initializePython() {
        try {
            if (!com.chaquo.python.Python.isStarted()) {
                com.chaquo.python.Python.start(
                    com.chaquo.python.android.AndroidPlatform(this)
                )
                Log.i(TAG, "✅ Chaquopy Python 运行时已初始化")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Chaquopy Python 初始化失败", e)
        }
    }

    /**
     * Initialize file logging system
     */
    private fun initializeFileLogger() {
        try {
            com.shijing.xomniclaw.logging.AppLog.init(this)
            Log.i(TAG, "✅ 文件日志系统已初始化")
        } catch (e: Exception) {
            Log.e(TAG, "初始化文件日志系统失败", e)
        }
    }

    /**
     * Initialize Cron scheduled tasks
     */
    private fun initializeCronJobs() {
        try {
            com.shijing.xomniclaw.cron.CronInitializer.initialize(this)
            Log.i(TAG, "✅ Cron 系统已初始化")
        } catch (e: Exception) {
            Log.e(TAG, "初始化 Cron 系统失败", e)
        }
    }

    /**
     * 重新同步系统级定时任务。
     *
     * 即使进程被重启，只要任务定义仍在本地文件中，就会在应用启动时重新注册到 AlarmManager。
     */
    private fun rescheduleSystemScheduledTasks() {
        try {
            com.shijing.xomniclaw.scheduler.ScheduledTaskManager(this).rescheduleAll()
            Log.i(TAG, "✅ AlarmManager 定时任务已同步")
        } catch (e: Exception) {
            Log.e(TAG, "同步 AlarmManager 定时任务失败", e)
        }
    }

    /**
     * 确保全局记忆进化使用专门的 interval 定时任务批处理，避免普通任务结束时直接改长期记忆。
     */
    private fun ensureMemoryEvolutionTask() {
        try {
            com.shijing.xomniclaw.agent.memory.evolution.MemoryEvolutionAutomationManager(this)
                .ensureDefaultTask()
            Log.i(TAG, "✅ 全局记忆进化定时任务已检查")
        } catch (e: Exception) {
            Log.e(TAG, "初始化全局记忆进化定时任务失败", e)
        }
    }

    /**
     * Initialize Workspace (aligned with OmniClaw)
     */
    private fun initializeWorkspace() {
        try {
            val initializer = com.shijing.xomniclaw.workspace.WorkspaceInitializer(this)

            if (!initializer.isWorkspaceInitialized()) {
                Log.i(TAG, "========================================")
                Log.i(TAG, "📁 首次启动 - 初始化 Workspace...")
                Log.i(TAG, "========================================")

                val success = initializer.initializeWorkspace()

                if (success) {
                    Log.i(TAG, "✅ Workspace 初始化成功")
                    Log.i(TAG, "   路径: ${initializer.getWorkspacePath()}")
                    Log.i(TAG, "   Device ID: ${initializer.getDeviceId()}")
                    Log.i(TAG, "   文件: AGENTS.md, OPS_GUIDE.md, MEMORY.md")
                } else {
                    Log.e(TAG, "❌ Workspace 初始化失败")
                }
            } else {
                Log.d(TAG, "Workspace 已初始化: ${initializer.getWorkspacePath()}")
            }

            // Always ensure bundled skills and memory templates are deployed (copies missing, won't overwrite)
            initializer.ensureBundledSkills()
            initializer.ensureBootstrapMemoryFiles()

        } catch (e: Exception) {
            Log.e(TAG, "初始化 Workspace 失败", e)
        }
    }

    private fun testConfigSystem() {
        try {
            Log.d(TAG, "========================================")
            Log.d(TAG, "🧪 配置系统测试开始")
            Log.d(TAG, "========================================")

            // Run basic config tests
            // com.shijing.xomniclaw.config.ConfigTestRunner.runBasicTests(this)

            // Test LegacyRepository config integration
            // com.shijing.xomniclaw.config.ConfigTestRunner.testLegacyRepository(this)

            Log.d(TAG, "")
            Log.d(TAG, "========================================")
            Log.i(TAG, "✅ 配置系统测试完成!")
            Log.d(TAG, "========================================")

        } catch (e: Exception) {
            Log.e(TAG, "❌ 配置系统测试异常: ${e.message}", e)
        }
    }

    /**
     * Start Gateway service
     */
    private fun startGatewayService() {
        try {
            Log.i(TAG, "========================================")
            Log.i(TAG, "🌐 启动 Gateway 服务 (GatewayController)...")
            Log.i(TAG, "========================================")

            // Initialize TaskDataManager
            val taskDataManager = TaskDataManager.getInstance()

            // Initialize LLM Provider
            val llmProvider = UnifiedLLMProvider(this)

            // Initialize dependencies
            val toolRegistry = ToolRegistry(this, taskDataManager)
            val androidToolRegistry = AndroidToolRegistry(this, taskDataManager)
            val skillsLoader = SkillsLoader(this)
            val workspaceDir = java.io.File("/sdcard/.xomniclaw/workspace")
            val sessionManager = SessionManager(workspaceDir)

            // Create AgentLoop (requires these dependencies)
            val agentLoop = AgentLoop(
                llmProvider = llmProvider,
                toolRegistry = toolRegistry,
                androidToolRegistry = androidToolRegistry,
                contextManager = null,
                maxIterations = 50,
                modelRef = null
            )

            // Create GatewayController
            gatewayController = GatewayController(
                context = this,
                agentLoop = agentLoop,
                sessionManager = sessionManager,
                toolRegistry = toolRegistry,
                androidToolRegistry = androidToolRegistry,
                skillsLoader = skillsLoader,
                port = 8765,
                authToken = null // Temporarily disable auth
            )

            Log.i(TAG, "✅ GatewayController 实例创建成功")

            // Start service
            gatewayController?.start()

            Log.i(TAG, "========================================")
            Log.i(TAG, "✅ Gateway 服务已启动: ws://0.0.0.0:8765")
            Log.i(TAG, "========================================")

        } catch (e: Exception) {
            Log.e(TAG, "========================================")
            Log.e(TAG, "❌ Gateway 初始化失败", e)
            e.printStackTrace()
            Log.e(TAG, "========================================")
        }
    }

    /**
     * Start Feishu Channel (if enabled in config)
     */
    private fun startFeishuChannelIfEnabled() {
        Log.i(TAG, "⏰ startFeishuChannelIfEnabled() 被调用")
        val scope = CoroutineScope(Dispatchers.IO)
        GlobalScope.launch(Dispatchers.IO) {
            try {
                Log.i(TAG, "========================================")
                Log.i(TAG, "📱 检查 Feishu Channel 配置...")
                Log.i(TAG, "========================================")

                val configLoader = ConfigLoader(this@MyApplication)
                val openClawConfig = configLoader.loadOmniClawConfig()
                val feishuConfig = openClawConfig.channels.feishu

                if (!feishuConfig.enabled) {
                    Log.i(TAG, "⏭️  Feishu Channel 未启用，跳过初始化")
                    Log.i(TAG, "   配置路径: /sdcard/.xomniclaw/xomniclaw.json")
                    Log.i(TAG, "   设置 channels.feishu.enabled = true 以启用")
                    Log.i(TAG, "========================================")
                    return@launch
                }

                Log.i(TAG, "✅ Feishu Channel 已启用，准备启动...")
                Log.i(TAG, "   App ID: ${feishuConfig.appId}")
                Log.i(TAG, "   Domain: ${feishuConfig.domain}")
                Log.i(TAG, "   Mode: ${feishuConfig.connectionMode}")
                Log.i(TAG, "   DM Policy: ${feishuConfig.dmPolicy}")
                Log.i(TAG, "   Group Policy: ${feishuConfig.groupPolicy}")

                // 与 FeishuConfigAdapter 对齐，写入 JSON 的白名单、mention、bypass、tools 等字段才会生效；手写 partial 曾导致 groupAllowFrom 丢失、群聊全部被拦截。
                val config = com.shijing.xomniclaw.config.FeishuConfigAdapter.toFeishuConfig(feishuConfig)

                // Create and start FeishuChannel
                feishuChannel = FeishuChannel(config)
                val result = feishuChannel?.start()

                if (result?.isSuccess == true) {
                    // Update MMKV status
                    val mmkv = MMKV.defaultMMKV()
                    mmkv?.encode("channel_feishu_enabled", true)

                    Log.i(TAG, "========================================")
                    Log.i(TAG, "✅ Feishu Channel 启动成功!")
                    Log.i(TAG, "   现在可以接收飞书消息了")
                    Log.i(TAG, "========================================")

                    // Register feishu tools into MainEntryNew's ToolRegistry
                    // (so broadcast/gateway messages also get feishu tools)
                    try {
                        val mainToolRegistry = MainEntryNew.getToolRegistry()
                        val ftr = feishuChannel?.getToolRegistry()
                        if (mainToolRegistry != null && ftr != null) {
                            // Register Feishu tools into function-call ToolRegistry
                            // (skills are guidance; actual tool execution still needs registry entry)
                            val count = com.shijing.xomniclaw.agent.tools.registerFeishuTools(mainToolRegistry, ftr)
                            Log.i(TAG, "🔧 已注册 $count 个飞书工具到 MainEntryNew ToolRegistry")
                        } else {
                            Log.w(TAG, "⚠️ MainEntryNew 未初始化，飞书工具将在首次消息处理时注册")
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "飞书工具注册到 MainEntryNew 失败: ${e.message}")
                    }

                    // Subscribe to event flow, handle received messages
                    scope.launch(Dispatchers.IO) {
                        feishuChannel?.eventFlow?.collect { event ->
                            handleFeishuEvent(event)
                        }
                    }
                } else {
                    // Clear MMKV status
                    val mmkv = MMKV.defaultMMKV()
                    mmkv?.encode("channel_feishu_enabled", false)

                    Log.e(TAG, "========================================")
                    Log.e(TAG, "❌ Feishu Channel 启动失败")
                    Log.e(TAG, "   错误: ${result?.exceptionOrNull()?.message}")
                    Log.e(TAG, "========================================")
                }

            } catch (e: Exception) {
                // Clear MMKV status
                val mmkv = MMKV.defaultMMKV()
                mmkv?.encode("channel_feishu_enabled", false)

                Log.e(TAG, "========================================")
                Log.e(TAG, "❌ Feishu Channel 初始化异常", e)
                Log.e(TAG, "========================================")
            }
        }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {

    }

    override fun onActivityStarted(activity: Activity) {
        activeActivityCount += 1
        if (activeActivityCount == 1 && isChangingConfiguration) {
            isChangingConfiguration = false
        } else if (activeActivityCount == 1) {
            // App returned to foreground from background
            onAppForeground()
        }
    }

    override fun onActivityResumed(activity: Activity) {

    }

    override fun onActivityPaused(activity: Activity) {

    }

    override fun onActivityStopped(activity: Activity) {
        activeActivityCount -= 1
        if (activity.isChangingConfigurations) {
            isChangingConfiguration = true
        } else if (activeActivityCount == 0) {
            // App entered background
            onAppBackground()
        }
    }

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {

    }

    override fun onActivityDestroyed(activity: Activity) {

    }

    /**
     * Get queue mode (aligned with OmniClaw)
     *
     * Reference: omniclaw/src/auto-reply/reply/queue/resolve-settings.ts
     */
    private fun getQueueModeForChat(chatId: String, chatType: String): MessageQueueManager.QueueMode {
        return try {
            val configLoader = ConfigLoader(this@MyApplication)
            val openClawConfig = configLoader.loadOmniClawConfig()

            // Read Feishu queue config
            val queueMode = openClawConfig.channels.feishu.queueMode ?: "followup"

            // Set both queue capacity and drop policy
            val queueKey = "feishu:$chatId"
            messageQueueManager.setQueueSettings(
                key = queueKey,
                cap = openClawConfig.channels.feishu.queueCap,
                dropPolicy = when (openClawConfig.channels.feishu.queueDropPolicy.lowercase()) {
                    "new" -> MessageQueueManager.DropPolicy.NEW
                    "summarize" -> MessageQueueManager.DropPolicy.SUMMARIZE
                    else -> MessageQueueManager.DropPolicy.OLD
                }
            )

            when (queueMode.lowercase()) {
                "interrupt" -> MessageQueueManager.QueueMode.INTERRUPT
                "steer" -> MessageQueueManager.QueueMode.STEER
                "followup" -> MessageQueueManager.QueueMode.FOLLOWUP
                "collect" -> MessageQueueManager.QueueMode.COLLECT
                "queue" -> MessageQueueManager.QueueMode.QUEUE
                else -> {
                    Log.w(TAG, "Unknown queue mode: $queueMode, using FOLLOWUP")
                    MessageQueueManager.QueueMode.FOLLOWUP
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load queue mode, using default FOLLOWUP", e)
            MessageQueueManager.QueueMode.FOLLOWUP
        }
    }

    /**
     * Process Feishu message (with Typing Indicator)
     *
     * Aligned with OmniClaw message processing flow:
     * 1. Add "typing" reaction
     * 2. Process message (call Agent)
     * 3. Remove "typing" reaction
     * 4. Send reply
     */
    private suspend fun processFeishuMessageWithTyping(
        event: com.xiaomo.feishu.FeishuEvent.Message,
        queuedMessage: MessageQueueManager.QueuedMessage
    ) {
        var typingReactionId: String? = null
        try {
            // 1. Add "typing" reaction (Typing Indicator)
            val configLoader = ConfigLoader(this@MyApplication)
            val openClawConfig = configLoader.loadOmniClawConfig()
            val typingIndicatorEnabled = openClawConfig.channels.feishu.typingIndicator

            if (typingIndicatorEnabled) {
                Log.d(TAG, "⌨️  添加输入中表情...")
                val reactionResult = feishuChannel?.addReaction(event.messageId, "Typing")
                if (reactionResult?.isSuccess == true) {
                    typingReactionId = reactionResult.getOrNull()
                    Log.d(TAG, "✅ 输入中表情已添加: $typingReactionId")
                }
            }

            // 2. Call MainEntryNew to process message
            val response = processFeishuMessage(event)

            // 2.5 Check if reply should be skipped (noReply logic)
            if (shouldSkipReply(response, queuedMessage)) {
                Log.d(TAG, "🔕 noReply directive detected, skipping reply")
                // Remove reaction and return immediately
                if (typingReactionId != null) {
                    Log.d(TAG, "🧹 移除输入中表情...")
                    feishuChannel?.removeReaction(event.messageId, typingReactionId)
                }
                return
            }

            // 3. Remove typing reaction
            if (typingReactionId != null) {
                Log.d(TAG, "🧹 移除输入中表情...")
                feishuChannel?.removeReaction(event.messageId, typingReactionId)
            }

            // 4. Send final reply to Feishu (skip if already sent via block reply)
            if (response == "\u0000BLOCK_REPLY_ALREADY_SENT") {
                Log.d(TAG, "✅ Final reply already sent via block reply, skipping")
            } else {
                // Strip leaked model control tokens before sending to user (OmniClaw 2026.3.11)
                val sanitizedResponse = com.shijing.xomniclaw.agent.session.HistorySanitizer
                    .stripControlTokensFromText(response)
                sendFeishuReply(event, sanitizedResponse)
            }
        } catch (e: Exception) {
            Log.e(TAG, "处理飞书消息失败", e)
            // Ensure reaction is removed (even if error occurs)
            if (typingReactionId != null) {
                try {
                    feishuChannel?.removeReaction(event.messageId, typingReactionId)
                } catch (cleanupError: Exception) {
                    Log.w(TAG, "清理输入中表情失败", cleanupError)
                }
            }
        }
    }

    /**
     * Check if reply should be skipped (noReply logic)
     *
     * Aligned with OmniClaw's noReply detection:
     * - Agent can return special directive indicating no reply needed
     * - Certain message types (notifications, status updates) don't need reply
     * - Batch messages may contain noReply flag
     */
    private fun shouldSkipReply(
        response: String,
        queuedMessage: MessageQueueManager.QueuedMessage
    ): Boolean {
        // 1. Check if response is a silent reply (aligned with OmniClaw SILENT_REPLY_TOKEN = "NO_REPLY")
        val trimmed = response.trim()
        if (trimmed.equals(ContextBuilder.SILENT_REPLY_TOKEN, ignoreCase = true) ||
            trimmed.startsWith(ContextBuilder.SILENT_REPLY_TOKEN, ignoreCase = true) ||
            trimmed.endsWith(ContextBuilder.SILENT_REPLY_TOKEN, ignoreCase = true) ||
            trimmed.contains("[noReply]", ignoreCase = true)) {
            Log.d(TAG, "Silent reply detected, skipping: ${trimmed.take(50)}")
            return true
        }

        // 2. Check if response is empty
        if (response.isBlank()) {
            Log.d(TAG, "Response is empty, skipping reply")
            return true
        }

        // 3. Check batch message metadata
        val isBatch = queuedMessage.metadata["isBatch"] as? Boolean ?: false
        if (isBatch) {
            val noReplyFlag = queuedMessage.metadata["noReply"] as? Boolean ?: false
            if (noReplyFlag) {
                return true
            }
        }

        return false
    }

    /**
     * Handle Feishu event
     */
    private fun handleFeishuEvent(event: com.xiaomo.feishu.FeishuEvent) {
        when (event) {
            is com.xiaomo.feishu.FeishuEvent.Message -> {
                Log.i(TAG, "📨 收到飞书消息")
                Log.i(TAG, "   发送者: ${event.senderId}")
                Log.i(TAG, "   内容: ${event.content}")
                Log.i(TAG, "   聊天类型: ${event.chatType}")
                Log.i(TAG, "   Mentions: ${event.mentions}")

                // 🔄 Update current chat context (for Agent tool use)
                feishuChannel?.updateCurrentChatContext(
                    receiveId = event.chatId,
                    receiveIdType = "chat_id",
                    messageId = event.messageId
                )
                Log.d(TAG, "✅ 已更新当前对话上下文: chatId=${event.chatId}")

                // ✅ Check message permissions (aligned with OmniClaw bot.ts)
                try {
                    val configLoader = ConfigLoader(this@MyApplication)
                    val openClawConfig = configLoader.loadOmniClawConfig()
                    val feishuConfig = openClawConfig.channels.feishu

                    // Check DM Policy (private chat permission)
                    if (event.chatType == "p2p") {
                        val dmPolicy = feishuConfig.dmPolicy
                        Log.d(TAG, "   DM Policy: $dmPolicy")

                        when (dmPolicy) {
                            "pairing" -> {
                                // TODO: Implement pairing logic
                                // Temporarily allow all DMs (dev mode)
                                Log.d(TAG, "✅ DM allowed (pairing mode - 暂未实现配对验证)")
                            }
                            "allowlist" -> {
                                // Check allowlist
                                val allowFrom = feishuConfig.allowFrom
                                if (allowFrom.isEmpty() || event.senderId !in allowFrom) {
                                    Log.d(TAG, "❌ DM from ${event.senderId} not in allowlist, sending reject message")

                                    // Send rejection message in coroutine
                                    val sender = feishuChannel?.sender
                                    if (sender != null) {
                                        kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                            try {
                                                val rejectMessage = "⚠️ 抱歉，你的账号不在白名单中，无法使用此机器人。\n\n你的飞书 User ID: `${event.senderId}`\n\n如需使用，请联系管理员将你的 User ID 添加到白名单。"
                                                sender.sendTextMessage(
                                                    receiveId = event.chatId,
                                                    text = rejectMessage,
                                                    receiveIdType = "chat_id",
                                                    renderMode = com.xiaomo.feishu.messaging.RenderMode.AUTO
                                                )
                                                Log.i(TAG, "✅ 已发送白名单拒绝提示")
                                            } catch (e: Exception) {
                                                Log.e(TAG, "❌ 发送白名单拒绝提示失败: ${e.message}")
                                            }
                                        }
                                    }
                                    return
                                }
                                Log.d(TAG, "✅ DM allowed (sender in allowlist)")
                            }
                            "open" -> {
                                Log.d(TAG, "✅ DM allowed (open policy)")
                            }
                            else -> {
                                Log.w(TAG, "⚠️ Unknown DM policy: $dmPolicy, defaulting to open")
                            }
                        }
                    }

                    // Check group messages (aligned with OmniClaw: obey config.requireMention)
                    if (event.chatType == "group") {
                        val requireMention = feishuConfig.requireMention
                        Log.d(TAG, "   requireMention: $requireMention (按配置处理)")

                        if (requireMention) {
                            // Check @_all (aligned with OmniClaw: treat as @ all bots)
                            if (event.content.contains("@_all")) {
                                Log.d(TAG, "✅ 消息包含 @_all")
                            } else if (event.mentions.isEmpty()) {
                                // No @mention at all
                                Log.w(TAG, "❌ 群消息需要 @机器人，但没有任何 @mention，忽略此消息")
                                Log.w(TAG, "   消息内容: ${event.content}")
                                return
                            } else {
                                // Has @mention, check if bot is @mentioned
                                val botOpenId = feishuChannel?.getBotOpenId()
                                if (botOpenId == null) {
                                    // 获取机器人 open_id 失败时不再丢弃消息（否则群聊会「全无响应」）；一般为网络或应用权限未开通 im:message.group_at_msg
                                    Log.w(TAG, "⚠️ 无法获取 bot open_id，跳过 @mention 校验（请检查应用权限与网络）")
                                } else if (botOpenId !in event.mentions) {
                                    // Has bot open_id, but message doesn't @ bot
                                    Log.w(TAG, "❌ 群消息 @了其他人但没有 @机器人(${botOpenId})，忽略此消息")
                                    Log.w(TAG, "   消息内容: ${event.content}")
                                    Log.w(TAG, "   Bot Open ID: $botOpenId")
                                    Log.w(TAG, "   Mentions: ${event.mentions}")
                                    return
                                } else {
                                    Log.d(TAG, "✅ 群消息包含机器人的 @mention")
                                }
                            }
                        } else {
                            Log.d(TAG, "✅ 群消息无需 @机器人（requireMention=false）")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "检查消息权限失败", e)
                    // For safety, ignore message on error
                    return
                }

                // 🔑 Generate queue key (aligned with OmniClaw)
                val queueKey = "feishu:${event.chatId}"

                // 📦 Build queued message
                val queuedMessage = MessageQueueManager.QueuedMessage(
                    messageId = event.messageId,
                    content = event.content,
                    senderId = event.senderId,
                    chatId = event.chatId,
                    chatType = event.chatType,
                    metadata = mapOf(
                        "event" to event
                    )
                )

                // 🎯 Get queue mode (read from config)
                val queueMode = getQueueModeForChat(event.chatId, event.chatType)

                // 🚀 Enqueue message for processing (fully aligned with OmniClaw)
                GlobalScope.launch(Dispatchers.IO) {
                    try {
                        messageQueueManager.enqueue(
                            key = queueKey,
                            message = queuedMessage,
                            mode = queueMode
                        ) { msg ->
                            // Restore original event from metadata
                            val originalEvent = msg.metadata["event"] as? com.xiaomo.feishu.FeishuEvent.Message
                                ?: event
                            processFeishuMessageWithTyping(originalEvent, msg)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "消息队列处理失败", e)
                    }
                }
            }
            is com.xiaomo.feishu.FeishuEvent.Connected -> {
                Log.i(TAG, "✅ Feishu WebSocket 已连接")
            }
            is com.xiaomo.feishu.FeishuEvent.Disconnected -> {
                Log.w(TAG, "⚠️ Feishu WebSocket 已断开")
            }
            is com.xiaomo.feishu.FeishuEvent.Error -> {
                Log.e(TAG, "❌ Feishu 错误: ${event.error.message}")
            }
        }
    }

    /**
     * Process Feishu message - call Agent
     *
     * Create lightweight AgentLoop call and return result directly
     */
    private suspend fun processFeishuMessage(event: com.xiaomo.feishu.FeishuEvent.Message): String {
        return withContext(Dispatchers.IO) {
            try {
                Log.i(TAG, "🤖 开始处理消息: ${event.content}")

                // 🆔 Generate session ID: use chatId_chatType as unique identifier
                // This way different groups/private chats have independent session history
                val sessionId = "${event.chatId}_${event.chatType}"
                Log.i(TAG, "🆔 Session ID: $sessionId (chatType: ${event.chatType})")

                // Execute AgentLoop synchronously and return result
                val sessionManager = MainEntryNew.getSessionManager()
                if (sessionManager == null) {
                    MainEntryNew.initialize(this@MyApplication)
                }

                val session = MainEntryNew.getSessionManager()?.getOrCreate(sessionId)
                if (session == null) {
                    return@withContext "系统错误：无法创建会话"
                }

                Log.i(TAG, "📋 [Session] 加载会话: ${session.messageCount()} 条历史消息")

                // Get history messages and cleanup (ensure tool_use and tool_result are paired)
                val rawHistory = session.getRecentMessages(20)
                val contextHistory = cleanupToolMessages(rawHistory)
                Log.i(TAG, "📋 [Session] 清理后: ${contextHistory.size} 条消息（原始: ${rawHistory.size}）")

                // Initialize components
                val taskDataManager = TaskDataManager.getInstance()
                val toolRegistry = ToolRegistry(
                    context = this@MyApplication,
                    taskDataManager = taskDataManager
                )
                val androidToolRegistry = AndroidToolRegistry(
                    context = this@MyApplication,
                    taskDataManager = taskDataManager
                )

                // Register feishu tools into ToolRegistry (aligned with OmniClaw extension tools)
                val fc = feishuChannel
                if (fc != null) {
                    try {
                        val feishuToolRegistry = fc.getToolRegistry()
                        if (feishuToolRegistry != null) {
                            // Register Feishu tools into function-call ToolRegistry
                            // (skills help routing, but real execution requires tool registration)
                            val feishuToolCount = com.shijing.xomniclaw.agent.tools.registerFeishuTools(toolRegistry, feishuToolRegistry)
                            Log.i(TAG, "🔧 已注册 $feishuToolCount 个飞书工具到 ToolRegistry")
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "飞书工具注册失败: ${e.message}")
                    }
                }

                val configLoader = ConfigLoader(this@MyApplication)
                val contextBuilder = ContextBuilder(
                    context = this@MyApplication,
                    toolRegistry = toolRegistry,
                    androidToolRegistry = androidToolRegistry,
                    configLoader = configLoader
                )
                val llmProvider = com.shijing.xomniclaw.providers.UnifiedLLMProvider(this@MyApplication)
                val contextManager = com.shijing.xomniclaw.agent.context.ContextManager(llmProvider)

                // Load maxIterations from config
                val config = configLoader.loadOmniClawConfig()
                val maxIterations = config.agent.maxIterations

                val agentLoop = AgentLoop(
                    llmProvider = llmProvider,
                    toolRegistry = toolRegistry,
                    androidToolRegistry = androidToolRegistry,
                    contextManager = contextManager,
                    maxIterations = maxIterations,
                    modelRef = null
                )

                // Build system prompt (with channel context for messaging awareness)
                val channelCtx = ContextBuilder.ChannelContext(
                    channel = "feishu",
                    chatId = event.chatId,
                    chatType = event.chatType,
                    senderId = event.senderId,
                    messageId = event.messageId
                )
                val systemPrompt = contextBuilder.buildSystemPrompt(
                    userGoal = event.content,
                    packageName = "",
                    testMode = "chat",
                    loadAgentPolicies = true,
                    channelContext = channelCtx
                )

                // ✅ Block Reply: send intermediate replies as they happen
                // Aligned with OmniClaw's blockReplyBreak="text_end" mechanism
                val blockRepliesSent = mutableListOf<String>()
                val progressJob = kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
                    agentLoop.progressFlow.collect { update ->
                        if (update is ProgressUpdate.BlockReply) {
                            val text = update.text.trim()
                            if (text.isNotEmpty()) {
                                Log.i(TAG, "📤 Block reply (intermediate): ${text.take(100)}...")
                                try {
                                    sendFeishuReply(event, text)
                                    blockRepliesSent.add(text)
                                } catch (e: Exception) {
                                    Log.w(TAG, "发送中间回复失败: ${e.message}")
                                }
                            }
                        }
                    }
                }

                // Run AgentLoop (convert history messages)
                val result = agentLoop.run(
                    systemPrompt = systemPrompt,
                    userMessage = event.content,
                    contextHistory = contextHistory.map { it.toNewMessage() },
                    reasoningEnabled = true
                )

                // Stop progress listener
                progressJob.cancel()

                // Save messages to session (convert back to old format)
                result.messages.forEach { message ->
                    session.addMessage(message.toLegacyMessage())
                }
                MainEntryNew.getSessionManager()?.save(session)
                Log.i(TAG, "💾 [Session] 会话已保存，总消息数: ${session.messageCount()}")

                Log.i(TAG, "✅ Agent 处理完成")
                Log.i(TAG, "   迭代次数: ${result.iterations}")
                Log.i(TAG, "   使用工具: ${result.toolsUsed.joinToString(", ")}")
                Log.i(TAG, "   中间回复: ${blockRepliesSent.size} 条")

                // Return final result
                // If block replies were sent and final content matches last block reply, skip it
                val strippedFinal = com.shijing.xomniclaw.util.ReplyTagFilter.strip(result.finalContent ?: "抱歉，我无法处理这个请求。")
                if (blockRepliesSent.isNotEmpty() && blockRepliesSent.last().trim() == strippedFinal.trim()) {
                    Log.i(TAG, "📤 Final content matches last block reply, marking as already sent")
                    "\u0000BLOCK_REPLY_ALREADY_SENT"  // Sentinel value, caller will check
                } else {
                    MainEntryNew.formatAgentReplyWithTokenUsage(strippedFinal, result.tokenUsage)
                }

            } catch (e: Exception) {
                Log.e(TAG, "Agent 处理失败", e)
                "抱歉，处理消息时出错了：${e.message}"
            }
        }
    }

    /**
     * Send reply to Feishu
     *
     * Features:
     * - Use FeishuSender to auto-detect Markdown and render with cards
     * - Detect screenshot paths and auto-upload send images
     * - Support image + text combined reply
     */
    private suspend fun sendFeishuReply(event: com.xiaomo.feishu.FeishuEvent.Message, content: String) {
        try {
            Log.i(TAG, "📤 发送回复到飞书...")

            // Filter internal reasoning tags (<think>, <final>, etc.)
            val cleanContent = filterReasoningTags(content)

            // Initialize FeishuSender
            val sender = feishuChannel?.sender
            if (sender == null) {
                Log.e(TAG, "❌ FeishuSender 未初始化")
                return
            }

            // Detect if contains screenshot path (supports file path and Content URI)
            // Format 1: 路径: /storage/.../screenshot_xxx.png
            // Format 2: 路径: content://com.shijing.xomniclaw.accessibility.fileprovider/...
            val screenshotPathRegex = Regex("""路径:\s*((?:/storage/|/sdcard/|content://)[^\s\n]+\.png)""")
            val screenshotMatch = screenshotPathRegex.find(cleanContent)

            if (screenshotMatch != null) {
                val screenshotPath = screenshotMatch.groupValues[1]
                Log.i(TAG, "📸 检测到截图路径: $screenshotPath")

                // 1. Upload and send image
                val imageFile = if (screenshotPath.startsWith("content://")) {
                    // Content URI - needs to be converted to temp file via ContentResolver
                    try {
                        val uri = android.net.Uri.parse(screenshotPath)
                        val inputStream = contentResolver.openInputStream(uri)
                        val tempFile = java.io.File(cacheDir, "temp_screenshot_${System.currentTimeMillis()}.png")
                        inputStream?.use { input ->
                            tempFile.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }
                        tempFile
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to convert Content URI to file", e)
                        null
                    }
                } else {
                    java.io.File(screenshotPath)
                }

                if (imageFile != null && imageFile.exists()) {
                    try {
                        Log.i(TAG, "📤 上传图片到飞书...")

                        val imageResult = feishuChannel?.uploadAndSendImage(
                            imageFile = imageFile,
                            receiveId = event.chatId,
                            receiveIdType = "chat_id"
                        )

                        if (imageResult?.isSuccess == true) {
                            Log.i(TAG, "✅ 图片上传并发送成功: ${imageResult.getOrNull()}")
                        } else {
                            Log.e(TAG, "❌ 图片上传失败: ${imageResult?.exceptionOrNull()?.message}")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "上传截图失败", e)
                    }
                } else {
                    Log.w(TAG, "⚠️ 截图文件不存在: $screenshotPath")
                }

                // 2. Send text reply (remove screenshot path info, use Markdown rendering)
                val textContent = cleanContent
                    .replace(screenshotPathRegex, "")
                    .trim()

                if (textContent.isNotEmpty()) {
                    val result = sender.sendTextMessage(
                        receiveId = event.chatId,
                        text = textContent,
                        receiveIdType = "chat_id",
                        renderMode = com.xiaomo.feishu.messaging.RenderMode.AUTO
                    )

                    if (result.isSuccess) {
                        val sendResult = result.getOrNull()
                        Log.i(TAG, "✅ 文本回复发送成功: ${sendResult?.messageId}")
                    } else {
                        Log.e(TAG, "❌ 文本回复发送失败: ${result.exceptionOrNull()?.message}")
                    }
                }
            } else {
                // Auto: markdown content → card, plain text → text message
                var result = sender.sendTextMessage(
                    receiveId = event.chatId,
                    text = cleanContent,
                    receiveIdType = "chat_id",
                    renderMode = com.xiaomo.feishu.messaging.RenderMode.AUTO
                )

                if (result.isSuccess) {
                    val sendResult = result.getOrNull()
                    Log.i(TAG, "✅ 回复发送成功: ${sendResult?.messageId}")
                } else {
                    val errorMsg = result.exceptionOrNull()?.message ?: "未知错误"
                    Log.e(TAG, "❌ 回复发送失败 (Markdown): $errorMsg")

                    // Fallback: 如果 Markdown 卡片失败(如表格过多),降级为纯文本
                    if (errorMsg.contains("table number over limit") || errorMsg.contains("230099") || errorMsg.contains("HTTP 400")) {
                        Log.w(TAG, "⚠️ 降级为纯文本模式重试...")
                        result = sender.sendTextMessage(
                            receiveId = event.chatId,
                            text = "⚠️ 内容格式过于复杂,以下为纯文本版本:\n\n$cleanContent",
                            receiveIdType = "chat_id",
                            renderMode = com.xiaomo.feishu.messaging.RenderMode.TEXT  // 强制纯文本
                        )

                        if (result.isSuccess) {
                            Log.i(TAG, "✅ 纯文本回复发送成功")
                        } else {
                            // 最终兜底:至少告诉用户失败了
                            Log.e(TAG, "❌ 纯文本回复也失败,发送错误提示...")
                            sender.sendTextMessage(
                                receiveId = event.chatId,
                                text = "❌ 回复发送失败: $errorMsg\n\n请检查飞书日志或稍后重试。",
                                receiveIdType = "chat_id",
                                renderMode = com.xiaomo.feishu.messaging.RenderMode.TEXT
                            )
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "发送飞书回复失败", e)
        }
    }

    /**
     * Filter reasoning tags from LLM response.
     * Delegates to ReasoningTagFilter to avoid code duplication.
     */
    private fun filterReasoningTags(content: String): String =
        ReasoningTagFilter.stripReasoningTags(content)

    /**
     * Start Discord Channel (if configured)
     */
    private fun startDiscordChannelIfEnabled() {
        Log.i(TAG, "⏰ startDiscordChannelIfEnabled() 被调用")
        val scope = CoroutineScope(Dispatchers.IO)
        GlobalScope.launch(Dispatchers.IO) {
            try {
                Log.i(TAG, "========================================")
                Log.i(TAG, "🤖 检查 Discord Channel 配置...")
                Log.i(TAG, "========================================")

                val configLoader = ConfigLoader(this@MyApplication)
                val openClawConfig = configLoader.loadOmniClawConfig()
                val discordConfigData = openClawConfig.channels.discord

                if (discordConfigData == null || !discordConfigData.enabled) {
                    Log.i(TAG, "⏭️  Discord Channel 未启用，跳过初始化")
                    Log.i(TAG, "   配置路径: /sdcard/.xomniclaw/xomniclaw.json")
                    Log.i(TAG, "   设置 channels.discord.enabled = true 以启用")
                    Log.i(TAG, "========================================")
                    return@launch
                }

                val token = discordConfigData.token
                if (token.isNullOrBlank()) {
                    Log.w(TAG, "⚠️  Discord Bot Token 未配置，跳过启动")
                    Log.i(TAG, "   请在配置中设置 channels.discord.token")
                    Log.i(TAG, "========================================")
                    return@launch
                }

                Log.i(TAG, "✅ Discord Channel 已启用，准备启动...")
                Log.i(TAG, "   Name: ${discordConfigData.name ?: "default"}")
                Log.i(TAG, "   DM Policy: ${discordConfigData.dm?.policy ?: "pairing"}")
                Log.i(TAG, "   Group Policy: ${discordConfigData.groupPolicy ?: "open"}")
                Log.i(TAG, "   Reply Mode: ${discordConfigData.replyToMode ?: "off"}")

                // Create DiscordConfig
                val config = DiscordConfig(
                    enabled = true,
                    token = token,
                    name = discordConfigData.name,
                    dm = discordConfigData.dm?.let {
                        DiscordConfig.DmConfig(
                            policy = it.policy ?: "pairing",
                            allowFrom = it.allowFrom ?: emptyList()
                        )
                    },
                    groupPolicy = discordConfigData.groupPolicy,
                    guilds = discordConfigData.guilds?.mapValues { (_, guildData) ->
                        DiscordConfig.GuildConfig(
                            channels = guildData.channels,
                            requireMention = guildData.requireMention ?: true,
                            toolPolicy = guildData.toolPolicy
                        )
                    },
                    replyToMode = discordConfigData.replyToMode,
                    accounts = discordConfigData.accounts?.mapValues { (_, accountData) ->
                        DiscordConfig.DiscordAccountConfig(
                            enabled = accountData.enabled ?: true,
                            token = accountData.token,
                            name = accountData.name,
                            dm = accountData.dm?.let {
                                DiscordConfig.DmConfig(
                                    policy = it.policy ?: "pairing",
                                    allowFrom = it.allowFrom ?: emptyList()
                                )
                            },
                            guilds = accountData.guilds?.mapValues { (_, guildData) ->
                                DiscordConfig.GuildConfig(
                                    channels = guildData.channels,
                                    requireMention = guildData.requireMention ?: true,
                                    toolPolicy = guildData.toolPolicy
                                )
                            }
                        )
                    }
                )

                // Start DiscordChannel
                val result = DiscordChannel.start(this@MyApplication, config)

                if (result.isSuccess) {
                    discordChannel = result.getOrNull()

                    // Initialize DiscordTyping
                    discordChannel?.let { channel ->
                        val client = com.xiaomo.discord.DiscordClient(token)
                        discordTyping = DiscordTyping(client)
                    }

                    // Update MMKV status
                    val mmkv = MMKV.defaultMMKV()
                    mmkv?.encode("channel_discord_enabled", true)

                    Log.i(TAG, "========================================")
                    Log.i(TAG, "✅ Discord Channel 启动成功!")
                    Log.i(TAG, "   Bot: ${discordChannel?.getBotUsername()} (${discordChannel?.getBotUserId()})")
                    Log.i(TAG, "   现在可以接收 Discord 消息了")
                    Log.i(TAG, "========================================")

                    // Subscribe to event flow, handle received messages
                    scope.launch(Dispatchers.IO) {
                        discordChannel?.eventFlow?.collect { event ->
                            handleDiscordEvent(event)
                        }
                    }
                } else {
                    // Clear MMKV status
                    val mmkv = MMKV.defaultMMKV()
                    mmkv?.encode("channel_discord_enabled", false)

                    Log.e(TAG, "========================================")
                    Log.e(TAG, "❌ Discord Channel 启动失败")
                    Log.e(TAG, "   错误: ${result.exceptionOrNull()?.message}")
                    Log.e(TAG, "========================================")
                }

            } catch (e: Exception) {
                // Clear MMKV status
                val mmkv = MMKV.defaultMMKV()
                mmkv?.encode("channel_discord_enabled", false)

                Log.e(TAG, "========================================")
                Log.e(TAG, "❌ Discord Channel 初始化异常", e)
                Log.e(TAG, "========================================")
            }
        }
    }

    /**
     * Handle Discord event
     */
    private suspend fun handleDiscordEvent(event: ChannelEvent) {
        try {
            when (event) {
                is ChannelEvent.Connected -> {
                    Log.i(TAG, "🔗 Discord Connected")
                }

                is ChannelEvent.Message -> {
                    Log.i(TAG, "📨 收到 Discord 消息")
                    Log.i(TAG, "   From: ${event.authorName} (${event.authorId})")
                    Log.i(TAG, "   Content: ${event.content}")
                    Log.i(TAG, "   Type: ${event.chatType}")
                    Log.i(TAG, "   Channel: ${event.channelId}")

                    // Send reply
                    sendDiscordReply(event)
                }

                is ChannelEvent.ReactionAdd -> {
                    Log.d(TAG, "👍 Discord Reaction Added: ${event.emoji}")
                }

                is ChannelEvent.ReactionRemove -> {
                    Log.d(TAG, "👎 Discord Reaction Removed: ${event.emoji}")
                }

                is ChannelEvent.TypingStart -> {
                    Log.d(TAG, "⌨️ Discord User Typing: ${event.userId}")
                }

                is ChannelEvent.Error -> {
                    Log.e(TAG, "❌ Discord Error", event.error)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "处理 Discord 事件失败", e)
        }
    }

    /**
     * Send Discord reply (actual implementation)
     */
    private suspend fun sendDiscordReply(event: ChannelEvent.Message) {
        val startTime = System.currentTimeMillis()

        try {
            // Message deduplication check
            if (discordDedup.isDuplicate(event.messageId)) {
                Log.d(TAG, "⏭️  消息已处理，跳过: ${event.messageId}")
                return
            }

            // Cancel previous processing task for this channel
            discordProcessingJobs[event.channelId]?.cancel()

            // Create new processing task
            val job = GlobalScope.launch(Dispatchers.IO) {
                try {
                    processDiscordMessage(event, startTime)
                } finally {
                    discordProcessingJobs.remove(event.channelId)
                }
            }

            discordProcessingJobs[event.channelId] = job

        } catch (e: Exception) {
            Log.e(TAG, "发送 Discord 回复失败", e)
            try {
                discordChannel?.addReaction(event.channelId, event.messageId, "❌")
            } catch (e2: Exception) {
                Log.e(TAG, "添加错误表情失败", e2)
            }
        }
    }

    /**
     * Process Discord message (core logic)
     */
    private suspend fun processDiscordMessage(event: ChannelEvent.Message, startTime: Long) {
        var thinkingReactionAdded = false
        var typingStarted = false

        try {
            Log.i(TAG, "========================================")
            Log.i(TAG, "🤖 开始处理 Discord 消息")
            Log.i(TAG, "   MessageID: ${event.messageId}")
            Log.i(TAG, "   From: ${event.authorName} (${event.authorId})")
            Log.i(TAG, "   Channel: ${event.channelId}")
            Log.i(TAG, "   Content: ${event.content}")
            Log.i(TAG, "========================================")

            // 1. Add thinking reaction
            discordChannel?.addReaction(event.channelId, event.messageId, "🤔")
            thinkingReactionAdded = true

            // 2. Start typing indicator
            discordTyping?.startContinuous(event.channelId)
            typingStarted = true

            // 3. 🆔 Generate session ID: use channelId as unique identifier
            val sessionId = "discord_${event.channelId}"
            Log.i(TAG, "🆔 Session ID: $sessionId")

            // 4. Get or create unified session
            if (MainEntryNew.getSessionManager() == null) {
                MainEntryNew.initialize(this@MyApplication)
            }
            val session = MainEntryNew.getSessionManager()?.getOrCreate(sessionId)
            if (session == null) {
                throw Exception("无法创建会话")
            }

            Log.i(TAG, "📋 [Session] 加载会话: ${session.messageCount()} 条历史消息")

            // 5. Get history messages and cleanup (ensure tool_use and tool_result are paired)
            val rawHistory = session.getRecentMessages(20)
            val contextHistory = cleanupToolMessages(rawHistory)
            Log.i(TAG, "📋 [Session] 清理后: ${contextHistory.size} 条消息（原始: ${rawHistory.size}）")

            // 6. Build system prompt
            val historyContext = ""  // History is in contextHistory
            val systemPrompt = buildDiscordSystemPrompt(event, historyContext)

            // 7. Call AgentLoop
            Log.i(TAG, "🔄 调用 AgentLoop 处理消息...")

            val llmProvider = com.shijing.xomniclaw.providers.UnifiedLLMProvider(this@MyApplication)
            val contextManager = com.shijing.xomniclaw.agent.context.ContextManager(llmProvider)
            val taskDataManager = TaskDataManager.getInstance()

            val toolRegistry = ToolRegistry(this@MyApplication, taskDataManager)
            val androidToolRegistry = AndroidToolRegistry(this@MyApplication, taskDataManager)

            val agentLoop = AgentLoop(
                llmProvider = llmProvider,
                toolRegistry = toolRegistry,
                androidToolRegistry = androidToolRegistry,
                contextManager = contextManager,
                maxIterations = 40,
                modelRef = null
            )

            val result = agentLoop.run(
                systemPrompt = systemPrompt,
                userMessage = event.content,
                contextHistory = contextHistory.map { it.toNewMessage() },
                reasoningEnabled = true
            )

            // 8. Stop typing status
            if (typingStarted) {
                discordTyping?.stopContinuous(event.channelId)
                typingStarted = false
            }

            // 9. Remove thinking reaction
            if (thinkingReactionAdded) {
                discordChannel?.removeReaction(event.channelId, event.messageId, "🤔")
                thinkingReactionAdded = false
            }

            // 9. Save messages to session (convert back to old format)
            result.messages.forEach { message ->
                session.addMessage(message.toLegacyMessage())
            }
            MainEntryNew.getSessionManager()?.save(session)
            Log.i(TAG, "💾 [Session] 会话已保存，总消息数: ${session.messageCount()}")

            // 10. Send reply（附加本回合 LLM token 累加，与主界面一致）
            val replyContent = MainEntryNew.formatAgentReplyWithTokenUsage(
                com.shijing.xomniclaw.util.ReplyTagFilter.strip(result.finalContent ?: "抱歉，我无法处理这个请求。"),
                result.tokenUsage
            )

            // Send in chunks (Discord 2000 character limit)
            val chunks = splitMessageIntoChunks(replyContent, 1900)

            for ((index, chunk) in chunks.withIndex()) {
                val sendResult = discordChannel?.sendMessage(
                    channelId = event.channelId,
                    content = chunk,
                    replyToId = if (index == 0) event.messageId else null
                )

                if (sendResult?.isSuccess == true) {
                    val sentMessageId = sendResult.getOrNull()
                    Log.i(TAG, "✅ 消息块 ${index + 1}/${chunks.size} 发送成功: $sentMessageId")
                } else {
                    Log.e(TAG, "❌ 消息块 ${index + 1}/${chunks.size} 发送失败: ${sendResult?.exceptionOrNull()?.message}")
                }
            }

            // 11. Add completion reaction
            discordChannel?.addReaction(event.channelId, event.messageId, "✅")

            val elapsed = System.currentTimeMillis() - startTime
            Log.i(TAG, "========================================")
            Log.i(TAG, "✅ Discord 消息处理完成")
            Log.i(TAG, "   耗时: ${elapsed}ms")
            Log.i(TAG, "   迭代: ${result.iterations}")
            Log.i(TAG, "   回复长度: ${replyContent.length} 字符")
            Log.i(TAG, "   分块数: ${chunks.size}")
            Log.i(TAG, "========================================")

        } catch (e: Exception) {
            Log.e(TAG, "========================================")
            Log.e(TAG, "❌ Discord 消息处理失败", e)
            Log.e(TAG, "========================================")

            // Cleanup status
            if (typingStarted) {
                discordTyping?.stopContinuous(event.channelId)
            }

            if (thinkingReactionAdded) {
                try {
                    discordChannel?.removeReaction(event.channelId, event.messageId, "🤔")
                } catch (e2: Exception) {
                    Log.e(TAG, "移除思考表情失败", e2)
                }
            }

            // Add error reaction and error message
            try {
                discordChannel?.addReaction(event.channelId, event.messageId, "❌")

                discordChannel?.sendMessage(
                    channelId = event.channelId,
                    content = "抱歉，处理您的消息时遇到错误：${e.message}",
                    replyToId = event.messageId
                )
            } catch (e2: Exception) {
                Log.e(TAG, "发送错误消息失败", e2)
            }
        }
    }

    /**
     * Build Discord system prompt
     */
    private fun buildDiscordSystemPrompt(event: ChannelEvent.Message, historyContext: String): String {
        val botName = discordChannel?.getBotUsername() ?: "OmniClaw Bot"
        val botId = discordChannel?.getBotUserId() ?: ""

        return """
# 身份
你是 **$botName**，一个运行在 Android 设备上的智能助手，通过 Discord 与用户交互。

# 当前上下文
- **平台**: Discord
- **频道类型**: ${event.chatType}
- **频道 ID**: ${event.channelId}
- **用户**: ${event.authorName} (ID: ${event.authorId})
- **Bot ID**: $botId

$historyContext

# 核心能力
你可以通过工具调用来控制 Android 设备：
- 📸 截图观察屏幕
- 👆 点击、滑动、输入
- 🏠 导航、打开应用
- 🔍 获取 UI 信息

# 交互规则
1. **简洁明了**: Discord 消息尽量简洁，重要信息用 Markdown 格式化
2. **主动截图**: 需要观察屏幕时主动使用 screenshot 工具
3. **逐步执行**: 复杂任务分解为多个步骤
4. **反馈进度**: 长时间操作时告知用户当前进度
5. **错误处理**: 遇到问题时说明原因并提供建议

# 响应格式
- 使用 Discord Markdown: **粗体**、*斜体*、`代码`、```代码块```
- 重要操作结果用表情符号: ✅ ❌ ⚠️ 🔄
- 列表使用 - 或数字编号

# 注意事项
- 不要输出过长的消息（建议 1500 字符以内）
- 代码块使用语法高亮
- 链接使用 [文本](URL) 格式

现在，请处理用户的消息。
        """.trimIndent()
    }

    /**
     * Split message into multiple chunks (Discord 2000 character limit)
     */
    private fun splitMessageIntoChunks(message: String, maxChunkSize: Int = 1900): List<String> {
        if (message.length <= maxChunkSize) {
            return listOf(message)
        }

        val chunks = mutableListOf<String>()
        var remaining = message

        while (remaining.length > maxChunkSize) {
            // Try to split at appropriate position (newline, period, space)
            var splitIndex = maxChunkSize

            // Prioritize splitting at newline
            val lastNewline = remaining.substring(0, maxChunkSize).lastIndexOf('\n')
            if (lastNewline > maxChunkSize / 2) {
                splitIndex = lastNewline + 1
            } else {
                // Next try to split at period
                val lastPeriod = remaining.substring(0, maxChunkSize).lastIndexOf('。')
                if (lastPeriod > maxChunkSize / 2) {
                    splitIndex = lastPeriod + 1
                } else {
                    // Finally try to split at space
                    val lastSpace = remaining.substring(0, maxChunkSize).lastIndexOf(' ')
                    if (lastSpace > maxChunkSize / 2) {
                        splitIndex = lastSpace + 1
                    }
                }
            }

            chunks.add(remaining.substring(0, splitIndex))
            remaining = remaining.substring(splitIndex)
        }

        if (remaining.isNotEmpty()) {
            chunks.add(remaining)
        }

        return chunks
    }

    override fun onTerminate() {
        super.onTerminate()

        // Stop Discord related services
        try {
            discordTyping?.cleanup()
            discordTyping = null

            discordProcessingJobs.values.forEach { it.cancel() }
            discordProcessingJobs.clear()

            discordSessionManager.clearAll()
            discordHistoryManager.clearAll()

            DiscordChannel.stop()
            discordChannel = null

            // Clear MMKV status
            val mmkv = MMKV.defaultMMKV()
            mmkv?.encode("channel_discord_enabled", false)

            Log.i(TAG, "Discord 服务已停止")
        } catch (e: Exception) {
            Log.e(TAG, "停止 Discord 服务时出错", e)
        }

        // Stop Feishu Channel
        feishuChannel?.stop()
        feishuChannel = null

        // Stop Gateway Server
        gatewayServer?.stop()
        gatewayServer = null

        Log.i(TAG, "应用终止，所有服务已停止")
    }

    /**
     * Cleanup message history, ensure tool_use and tool_result are paired
     *
     * Problem: When loading history messages from session, there may be orphaned tool_results
     * (corresponding tool_use is in earlier messages, already truncated)
     *
     * Solution: Only keep complete user/assistant messages, remove all tool-related content
     */
    private fun cleanupToolMessages(messages: List<com.shijing.xomniclaw.providers.LegacyMessage>): List<com.shijing.xomniclaw.providers.LegacyMessage> {
        return messages.filter { message ->
            // Only keep text messages from user and assistant
            // Remove all messages containing tool_calls or tool_result
            when (message.role) {
                "user" -> true  // Keep all user messages
                "assistant" -> {
                    // Only keep plain text assistant messages, remove those with tool_calls
                    message.content != null && message.toolCalls == null
                }
                else -> false  // Remove tool role messages
            }
        }
    }
}