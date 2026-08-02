package com.shijing.xomniclaw.voice

import android.Manifest
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.shijing.xomniclaw.accessibility.AccessibilityProxy
import com.shijing.xomniclaw.vision.ScreenFrameSampler
import com.shijing.xomniclaw.config.ConfigLoader
import com.shijing.xomniclaw.config.VisionConfig
import com.shijing.xomniclaw.core.MainEntryNew
import com.shijing.xomniclaw.gateway.GatewayServer
import com.shijing.xomniclaw.providers.LegacyMessage
import com.shijing.xomniclaw.ui.floatwindow.ScreenCompanionFloatWindow
import com.shijing.xomniclaw.ui.floatwindow.SessionFloatWindow
import com.shijing.xomniclaw.util.CrashBreadcrumbs
import com.shijing.xomniclaw.util.MediaProjectionHelper
import com.shijing.xomniclaw.vision.VisionFrameBuffer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * 屏内替身模式控制器。
 *
 * 这个控制器把“屏幕采样、语音结果处理、悬浮层状态、主会话写回、多步任务完成后返回原页面”
 * 收敛到同一个模块，避免把跨 App 生命周期继续堆在 Compose 页面里。
 */
object ScreenCompanionController {
    private const val TAG = "ScreenCompanionCtrl"
    private const val AGENT_WAIT_TIMEOUT_MS = 3_600_000L
    private const val SAMPLER_START_DELAY_MS = 800L
    /** 替身悬浮窗消息默认停留 1 分钟后再自动清除。 */
    private const val TRANSIENT_MESSAGE_CLEAR_MS = 60_000L

    data class ScreenCompanionUiState(
        val isActive: Boolean = false,
        val isListening: Boolean = false,
        val isProcessing: Boolean = false,
        val isAgentRunning: Boolean = false,
        val statusText: String = "按住说话",
        val transientMessage: String = ""
    )

    /**
     * 标记最近一次已经提前展示过的语音识别文本，
     * 避免最终完成事件到来时再把同一句用户语音重复写入会话。
     */
    private data class VoiceRecognitionMarker(
        val pressStartTimestampMs: Long?,
        val recognizedText: String
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _uiState = MutableStateFlow(ScreenCompanionUiState())
    val uiState = _uiState.asStateFlow()

    private var application: Application? = null
    private var currentSessionId: String = "default"
    private var visionConfig: VisionConfig = VisionConfig()
    private var screenSampler: ScreenFrameSampler? = null
    private var voiceManager: VoiceRecorderManager? = null
    private var voiceEventJob: Job? = null
    private var voiceListeningJob: Job? = null
    private var voiceProcessingJob: Job? = null
    private var clearMessageJob: Job? = null
    private var agentWatcherJob: Job? = null
    private var samplerStartJob: Job? = null
    private var pendingOriginPackageName: String? = null
    private var companionTtsManager: CompanionTtsManager? = null
    private var lastDisplayedVoiceRecognition: VoiceRecognitionMarker? = null

    fun isActive(): Boolean {
        return _uiState.value.isActive
    }

    fun enterCompanionMode(
        application: Application,
        sessionId: String,
        visionConfig: VisionConfig
    ): Boolean {
        CrashBreadcrumbs.mark(
            stage = "companion.enter.request",
            detail = "sessionId=$sessionId, projectionGranted=${MediaProjectionHelper.isMediaProjectionGranted()}"
        )
        if (!MediaProjectionHelper.isMediaProjectionGranted()) {
            Log.w(TAG, "MediaProjection not granted, cannot enter companion mode")
            CrashBreadcrumbs.mark("companion.enter.reject", "media_projection_not_granted")
            return false
        }
        this.application = application
        this.currentSessionId = sessionId
        this.visionConfig = resolveEffectiveVisionConfig(application, visionConfig)
        MainEntryNew.initialize(application)

        // 切入替身模式前清空历史屏幕帧，避免上一轮页面残留影响这次问答。
        VisionFrameBuffer.clear()
        lastDisplayedVoiceRecognition = null
        companionTtsManager?.shutdown()
        companionTtsManager = CompanionTtsManager(application.applicationContext).also {
            it.resetDedup()
        }
        rebuildVoiceManager(application)
        rebuildScreenSampler(application)
        CrashBreadcrumbs.mark(
            stage = "companion.enter.components_ready",
            detail = "hasVoiceManager=${voiceManager != null}, hasScreenSampler=${screenSampler != null}"
        )

        updateState {
            it.copy(
                isActive = true,
                isListening = false,
                isProcessing = false,
                isAgentRunning = false,
                statusText = "按住说话",
                transientMessage = ""
            )
        }
        ScreenCompanionFloatWindow.show(application.applicationContext)
        CrashBreadcrumbs.mark("companion.enter.float_window", "show_called")
        scheduleScreenSamplerStart()
        showTransientMessage("屏内替身已开启")
        CrashBreadcrumbs.mark("companion.enter.success", "companion_mode_active")
        return true
    }

    /**
     * 替身模式进入时再兜底读取一次配置，避免 Compose 里还拿着默认空对象。
     *
     * 常见场景：
     * 1. 页面刚启动就立刻点进替身模式，LaunchedEffect 尚未把配置灌进 currentVisionConfig
     * 2. 运行期间用户更新了 /sdcard/.xomniclaw/xomniclaw.json，但页面内存态还没刷新
     */
    private fun resolveEffectiveVisionConfig(
        application: Application,
        candidate: VisionConfig
    ): VisionConfig {
        return try {
            val latest = ConfigLoader(application).loadOmniClawConfig().vision ?: candidate
            Log.i(
                TAG,
                "Reload vision config for companion mode: hasSttProvider=${hasSttProviderConfig(application)}"
            )
            latest
        } catch (e: Exception) {
            Log.w(TAG, "Failed to reload vision config, fallback to candidate: ${e.message}")
            candidate
        }
    }

    fun updateSessionId(sessionId: String) {
        currentSessionId = sessionId
        CrashBreadcrumbs.mark("companion.session.update", "sessionId=$sessionId")
    }

    fun startVoiceCapture() {
        val application = application ?: return
        val manager = voiceManager ?: return
        companionTtsManager?.stop(resetDedup = true)
        CrashBreadcrumbs.mark(
            stage = "companion.voice.start",
            detail = "agentRunning=${_uiState.value.isAgentRunning}, hasRecordPermission=${
                ContextCompat.checkSelfPermission(
                    application,
                    Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED
            }"
        )
        if (_uiState.value.isAgentRunning) {
            showTransientMessage("任务执行中，请稍候再问")
            return
        }
        val hasRecordPermission = ContextCompat.checkSelfPermission(
            application,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasRecordPermission) {
            showTransientMessage("请先在主界面授予录音权限", durationMs = TRANSIENT_MESSAGE_CLEAR_MS)
            return
        }
        val latestVisionConfig = resolveEffectiveVisionConfig(application, visionConfig)
        visionConfig = latestVisionConfig
        manager.visionConfig = latestVisionConfig
        // 屏内替身语音属于“视觉模式语音”：
        // 即使用户在 ASR 返回前关闭了预览/切页，也应按按下瞬间的视觉态走 VLM 路由。
        VoiceRoundDisclosureHint.visionOverlayActiveForNextRecording = true
        Log.i(
            TAG,
            "Start voice capture: hasSttProvider=${hasSttProviderConfig(application)}"
        )
        val pressStartTs = System.currentTimeMillis()
        scope.launch {
            val currentPackage = runCatching { AccessibilityProxy.getCurrentPackageName() }
                .getOrDefault("")
            pendingOriginPackageName = currentPackage
        }
        manager.startListening(pressStartTs)
    }

    fun stopVoiceCapture() {
        CrashBreadcrumbs.mark("companion.voice.stop", "stop_listening_requested")
        updateState {
            it.copy(
                isListening = false,
                isProcessing = true,
                statusText = "正在识别，等待结果"
            )
        }
        showTransientMessage("已收到语音，正在识别并等待回答", durationMs = TRANSIENT_MESSAGE_CLEAR_MS)
        voiceManager?.stopListening()
    }

    fun exitCompanionMode(stopAgent: Boolean = true) {
        CrashBreadcrumbs.mark(
            stage = "companion.exit",
            detail = "stopAgent=$stopAgent, isActive=${_uiState.value.isActive}"
        )
        if (stopAgent) {
            MainEntryNew.cancelCurrentJob(false)
        }
        agentWatcherJob?.cancel()
        samplerStartJob?.cancel()
        clearMessageJob?.cancel()
        voiceEventJob?.cancel()
        voiceListeningJob?.cancel()
        voiceProcessingJob?.cancel()
        screenSampler?.stop()
        screenSampler = null
        voiceManager?.destroy()
        voiceManager = null
        companionTtsManager?.shutdown()
        companionTtsManager = null
        lastDisplayedVoiceRecognition = null
        pendingOriginPackageName = null
        updateState { ScreenCompanionUiState() }
        ScreenCompanionFloatWindow.dismiss()
        syncFloatWindowVisibilityToRealForeground()
    }

    fun clearTransientMessage() {
        updateState { it.copy(transientMessage = "") }
    }

    private fun rebuildScreenSampler(application: Application) {
        samplerStartJob?.cancel()
        screenSampler?.stop()
        screenSampler = ScreenFrameSampler().apply {
            fps = visionConfig.fps
            jpegQuality = visionConfig.quality
        }
        CrashBreadcrumbs.mark(
            stage = "companion.rebuild.screen_sampler",
            detail = "fps=${visionConfig.fps}, quality=${visionConfig.quality}"
        )
    }

    private fun rebuildVoiceManager(application: Application) {
        voiceManager?.destroy()
        voiceManager = VoiceRecorderManager(application.applicationContext).apply {
            this.visionConfig = visionConfig
        }
        CrashBreadcrumbs.mark(
            stage = "companion.rebuild.voice_manager",
            detail = "hasSttProvider=${hasSttProviderConfig(application)}"
        )

        voiceEventJob?.cancel()
        voiceListeningJob?.cancel()
        voiceProcessingJob?.cancel()
        val manager = voiceManager ?: return

        voiceEventJob = scope.launch {
            manager.voiceResultEvents.collect { result ->
                handleVoiceProcessingResult(result)
            }
        }
        voiceListeningJob = scope.launch {
            manager.isListening.collect { listening ->
                updateState {
                    it.copy(
                        isListening = listening,
                        statusText = when {
                            it.isAgentRunning -> "正在执行"
                            it.isProcessing -> "正在识别，等待结果"
                            listening -> "松开结束"
                            else -> "按住说话"
                        }
                    )
                }
            }
        }
        voiceProcessingJob = scope.launch {
            manager.isProcessing.collect { processing ->
                updateState {
                    it.copy(
                        isProcessing = processing,
                        statusText = when {
                            it.isAgentRunning -> "正在执行"
                            it.isListening -> "松开结束"
                            processing -> "正在识别，等待结果"
                            else -> "按住说话"
                        }
                    )
                }
            }
        }
    }

    /**
     * 进入替身模式后稍等一小段时间再启动连续采样，
     * 避免悬浮窗和 VirtualDisplay 在同一时刻初始化导致机型相关时序问题。
     */
    private fun scheduleScreenSamplerStart() {
        samplerStartJob?.cancel()
        samplerStartJob = scope.launch {
            CrashBreadcrumbs.mark(
                stage = "companion.enter.screen_sampler",
                detail = "delayed_start_scheduled=${SAMPLER_START_DELAY_MS}ms"
            )
            delay(SAMPLER_START_DELAY_MS)
            if (!_uiState.value.isActive) {
                CrashBreadcrumbs.mark("companion.enter.screen_sampler", "skip_start_because_inactive")
                return@launch
            }
            screenSampler?.start(scope)
            CrashBreadcrumbs.mark("companion.enter.screen_sampler", "start_called_after_delay")
        }
    }

    private suspend fun handleVoiceProcessingResult(result: VoiceRecorderManager.VoiceProcessingResult) {
        if (!_uiState.value.isActive) return

        result.errorMessage?.takeIf { it.isNotBlank() }?.let { error ->
            Log.w(TAG, "Voice processing error: $error")
            showTransientMessage(error, durationMs = TRANSIENT_MESSAGE_CLEAR_MS)
            return
        }

        val recognizedText = result.recognizedText.trim()
        val aiReply = result.aiReply.trim()
        val directAction = result.directAction
        val recognitionMarker = VoiceRecognitionMarker(
            pressStartTimestampMs = result.pressStartTimestampMs,
            recognizedText = recognizedText
        )

        if (result.stage == VoiceRecorderManager.VoiceProcessingStage.TRANSCRIPTION_READY) {
            // 方案A：仅浮层/状态提示，不写 session。注意：不要在这里更新 lastDisplayedVoiceRecognition，
            // 否则 COMPLETED 会认为「已展示过」而跳过 appendMessage，主界面会没有 ASR 文本。
            updateState {
                it.copy(
                    isProcessing = true,
                    statusText = "已识别，等待回答"
                )
            }
            showTransientMessage("已识别：$recognizedText", durationMs = TRANSIENT_MESSAGE_CLEAR_MS)
            return
        }

        val followUpTask = resolveFollowUpTask(directAction, recognizedText)
        // 只要后续会走 AgentLoop（runWithSession），会话里的用户句只应由 MainEntry 写一次；
        // 仅在「纯本地 VLM 即时回复、不启动 Agent」时用 🎤 补用户气泡（与主界面语音同理）。
        val voiceOnlyLocalReply =
            followUpTask.isNullOrBlank() && aiReply.isNotBlank() && recognizedText.isNotBlank()
        if (recognizedText.isNotBlank() && lastDisplayedVoiceRecognition != recognitionMarker) {
            if (voiceOnlyLocalReply) {
                appendMessage(currentSessionId, "user", "🎤 $recognizedText")
            }
            lastDisplayedVoiceRecognition = recognitionMarker
        }
        if (aiReply.isNotBlank()) {
            appendAssistantMessage(currentSessionId, aiReply)
        }
        clearTransientMessage()
        if (aiReply.isNotBlank()) {
            companionTtsManager?.speak(aiReply)
            showTransientMessage(aiReply)
        }

        if (followUpTask.isNullOrBlank()) {
            if (recognizedText.isNotBlank() && aiReply.isBlank()) {
                Log.i(TAG, "Voice result has STT text but no aiReply/directAction, fallback to AgentLoop")
                appendAssistantMessage(currentSessionId, "已收到，正在继续处理")
                launchAgentTask(recognizedText)
            }
            return
        }

        launchAgentTask(
            task = followUpTask
        )
    }

    private fun resolveFollowUpTask(directAction: JSONObject?, recognizedText: String): String? {
        if (directAction == null) {
            return null
        }
        val actionType = directAction.optString("action", "")
        return when (actionType) {
            "agent_task" -> directAction.optString("task", "").ifBlank { recognizedText }
            else -> recognizedText.ifBlank { null }
        }
    }

    private fun launchAgentTask(task: String) {
        val application = application ?: return
        agentWatcherJob?.cancel()
        agentWatcherJob = scope.launch {
            var taskCompleted = false
            try {
                // 多步执行期间兜底重新展示替身悬浮层，避免页面切换或系统重绘导致悬浮层意外消失。
                ensureCompanionFloatVisible()
                // 替身模式已经有自己的悬浮层，这里把普通步骤条临时视作“主界面可见”来抑制其弹出。
                SessionFloatWindow.setMainActivityVisible(true, application)
                updateState {
                    it.copy(
                        isAgentRunning = true,
                        statusText = "正在执行"
                    )
                }
                showTransientMessage("已收到，正在继续处理")

                MainEntryNew.runWithSession(
                    userInput = task,
                    sessionId = currentSessionId,
                    application = application,
                    returnToMainOnFinish = true
                )
                withTimeoutOrNull(AGENT_WAIT_TIMEOUT_MS) {
                    MainEntryNew.agentSessionRunning.filter { !it }.first()
                }
                taskCompleted = true

                val finalReply = readLatestAssistantMessage(currentSessionId)
                if (finalReply.isNotBlank()) {
                    companionTtsManager?.speak(finalReply)
                    showTransientMessage(finalReply)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to launch companion agent task", e)
                showTransientMessage("任务执行失败: ${e.message}", durationMs = TRANSIENT_MESSAGE_CLEAR_MS)
            } finally {
                if (taskCompleted) {
                    finalizeCompletedTask()
                } else {
                    pendingOriginPackageName = null
                    updateState {
                        it.copy(
                            isAgentRunning = false,
                            statusText = when {
                                it.isListening -> "松开结束"
                                it.isProcessing -> "正在识别，等待结果"
                                else -> "按住说话"
                            }
                        )
                    }
                    ensureCompanionFloatVisible()
                    syncFloatWindowVisibilityToRealForeground()
                }
            }
        }
    }

    /**
     * 自动任务完成后，替身模式应当立即退出，避免悬浮窗继续留在其他 App 顶层。
     * 主界面切回由 MainEntryNew.runWithSession(returnToMainOnFinish=true) 负责。
     */
    private fun finalizeCompletedTask() {
        pendingOriginPackageName = null
        clearMessageJob?.cancel()
        voiceEventJob?.cancel()
        voiceListeningJob?.cancel()
        samplerStartJob?.cancel()
        screenSampler?.stop()
        screenSampler = null
        voiceManager?.destroy()
        voiceManager = null
        companionTtsManager?.shutdown()
        companionTtsManager = null
        lastDisplayedVoiceRecognition = null
        updateState { ScreenCompanionUiState() }
        ScreenCompanionFloatWindow.dismiss()
    }

    private fun appendAssistantMessage(sessionId: String, content: String) {
        appendMessage(sessionId, "assistant", content)
    }

    private fun appendMessage(sessionId: String, role: String, content: String) {
        if (content.isBlank()) return
        val sessionManager = MainEntryNew.getSessionManager() ?: return
        try {
            val session = sessionManager.getOrCreate(sessionId)
            session.addMessage(LegacyMessage(role = role, content = content))
            sessionManager.save(session)
            GatewayServer.broadcastChatMessage(sessionId, role, content)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to append companion message role=$role", e)
        }
    }

    private fun readLatestAssistantMessage(sessionId: String): String {
        return try {
            val session = MainEntryNew.getSessionManager()?.get(sessionId) ?: return ""
            session.messages.asReversed().firstOrNull { it.role == "assistant" }
                ?.content
                ?.toString()
                .orEmpty()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read latest assistant message", e)
            ""
        }
    }

    private fun showTransientMessage(message: String, durationMs: Long = TRANSIENT_MESSAGE_CLEAR_MS) {
        if (message.isBlank()) return
        updateState { it.copy(transientMessage = message) }
        clearMessageJob?.cancel()
        clearMessageJob = scope.launch {
            delay(durationMs)
            if (_uiState.value.transientMessage == message) {
                clearTransientMessage()
            }
        }
    }

    private fun syncFloatWindowVisibilityToRealForeground() {
        val application = application ?: return
        scope.launch {
            val currentPackage = runCatching { AccessibilityProxy.getCurrentPackageName() }
                .getOrDefault("")
            val appVisible = currentPackage == application.packageName
            SessionFloatWindow.setMainActivityVisible(appVisible, application)
            if (_uiState.value.isActive) {
                ensureCompanionFloatVisible()
            }
        }
    }

    private fun ensureCompanionFloatVisible() {
        val application = application ?: return
        if (_uiState.value.isActive) {
            ScreenCompanionFloatWindow.show(application.applicationContext)
        }
    }

    private fun updateState(transform: (ScreenCompanionUiState) -> ScreenCompanionUiState) {
        _uiState.value = transform(_uiState.value)
    }

    /**
     * 替身模式仅做状态展示，STT 真正的可用性由 LocalVoiceVisionHub 在执行时校验。
     */
    private fun hasSttProviderConfig(application: Application): Boolean {
        return runCatching {
            val stt = ConfigLoader(application).loadOmniClawConfig().resolveProviders()["stt"]
            stt != null &&
                !stt.apiKey.isNullOrBlank() &&
                stt.baseUrl.isNotBlank() &&
                stt.models.isNotEmpty()
        }.getOrDefault(false)
    }
}
