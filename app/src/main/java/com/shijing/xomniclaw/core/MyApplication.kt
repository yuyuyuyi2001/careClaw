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
import android.annotation.SuppressLint
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
import com.tencent.mmkv.MMKV
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import com.shijing.xomniclaw.agent.session.SessionManager
import com.shijing.xomniclaw.agent.skills.SkillsLoader
import com.shijing.xomniclaw.config.ConfigLoader
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

        // Gateway Controller

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

        // Initialize file logging system
        initializeFileLogger()

        // Initialize Workspace (aligned with OmniClaw)
        initializeWorkspace()

        // 启动 HTTP 入口（备用远程通道；token 默认 careclaw，可在配置页修改）
        runCatching {
            val token = MMKV.defaultMMKV()
                .decodeString(com.shijing.xomniclaw.util.MMKVKeys.GATEWAY_AUTH_TOKEN.key, "careclaw")
            com.shijing.xomniclaw.remote.GatewayService.start(this, token)
        }

        // 飞书渠道已删（L2 精简）；远程入口现为 HTTP（GatewayService，见上方启动逻辑）。


        // Register global exception handler
        Thread.setDefaultUncaughtExceptionHandler(GlobalExceptionHandler())
        com.shijing.xomniclaw.util.CrashBreadcrumbs.mark("app.onCreate", "default_exception_handler_registered")

        // 启动时把上一次保留下来的“最后一步”重新打到日志里，便于直接在 logcat 中对照。
        com.shijing.xomniclaw.util.CrashBreadcrumbs.readLatest()?.let { latest ->
            Log.i(TAG, "Last breadcrumb before previous death: $latest")
        }

        // Start foreground service keep-alive
        startForegroundServiceKeepAlive()


        // ✅ Test config system
        testConfigSystem()

        // Check if test task is running on app startup, if so acquire WakeLock
        // Delayed check to ensure TaskDataManager is initialized
        Handler(Looper.getMainLooper()).postDelayed({
            ensureWakeLockForTesting()
        }, 1000) // 1 second delay


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
    }

    fun isAppInBackground(): Boolean {
        return activeActivityCount == 0
    }

    /**
     * Start foreground service keep-alive
     */
    @SuppressLint("NewApi")
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
    override fun onTerminate() {
        super.onTerminate()


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
