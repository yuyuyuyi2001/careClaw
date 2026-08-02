package com.shijing.xomniclaw.behavior

import android.app.Application
import android.util.Log
import android.widget.Toast
import com.shijing.xomniclaw.accessibility.service.AccessibilityEventDispatcher
import com.shijing.xomniclaw.agent.skills.BehaviorSkillWriter
import com.shijing.xomniclaw.deeplink.DeeplinkBookmarkSession
import com.shijing.xomniclaw.ui.floatwindow.BehaviorRecordingFloatWindow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 用户轨迹录制控制器。
 *
 * 目标很单一：订阅无障碍事件、收集用户行为、允许在录制期间收藏当前页，停止时生成 skill 文件。
 */
object BehaviorRecordingController {
    private const val TAG = "BehaviorRecordingCtrl"
    private const val MAX_RECORDED_EVENTS = 200
    private const val DEFAULT_IDLE_MESSAGE = "请开始操作，结束后点停止"

    data class UiState(
        val isRecording: Boolean = false,
        val statusText: String = "正在轨迹录制",
        val latestMessage: String = DEFAULT_IDLE_MESSAGE,
        val canBookmarkCurrentPage: Boolean = false
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState = _uiState.asStateFlow()
    private val controllerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var application: Application? = null
    private val recordedEvents = mutableListOf<AccessibilityEventDispatcher.RecordedAccessibilityEvent>()

    private val recordingListener: (AccessibilityEventDispatcher.RecordedAccessibilityEvent) -> Unit = { event ->
        synchronized(recordedEvents) {
            if (recordedEvents.size >= MAX_RECORDED_EVENTS) {
                recordedEvents.removeAt(0)
            }
            val previous = recordedEvents.lastOrNull()
            // 过滤非常近且完全相同的重复事件，避免 skill 文件被窗口刷新刷爆。
            val isDuplicate = previous != null &&
                previous.eventType == event.eventType &&
                previous.packageName == event.packageName &&
                previous.className == event.className &&
                previous.text == event.text &&
                previous.contentDescription == event.contentDescription &&
                (event.timestampMs - previous.timestampMs) < 300L
            if (!isDuplicate) {
                recordedEvents.add(event)
            }
        }
        val application = application
        if (application != null && _uiState.value.isRecording) {
            val sessionState = DeeplinkBookmarkSession.onAccessibilityEvent(application, event)
            val latestMessage = buildLatestMessage(
                latestAction = event.actionSummary,
                currentPageTitle = sessionState.currentPage?.let { "${it.appName} / ${it.displayTitle}" }
            )
            _uiState.value = _uiState.value.copy(
                latestMessage = latestMessage,
                canBookmarkCurrentPage = sessionState.rootAvailable
            )
        }
    }

    fun start(application: Application): Boolean {
        if (_uiState.value.isRecording) {
            return true
        }
        this.application = application
        synchronized(recordedEvents) {
            recordedEvents.clear()
        }
        val deeplinkSession = DeeplinkBookmarkSession.start(application.applicationContext)
        AccessibilityEventDispatcher.addListener(recordingListener)
        _uiState.value = UiState(
            isRecording = true,
            statusText = "正在轨迹录制",
            latestMessage = buildLatestMessage(
                latestAction = "已开始轨迹录制",
                currentPageTitle = deeplinkSession.currentPage?.let { "${it.appName} / ${it.displayTitle}" }
            ),
            canBookmarkCurrentPage = deeplinkSession.rootAvailable
        )
        BehaviorRecordingFloatWindow.show(application.applicationContext)
        return true
    }

    fun bookmarkCurrentPage() {
        val application = application ?: return
        controllerScope.launch {
            val result = DeeplinkBookmarkSession.bookmarkCurrentPage(application.applicationContext)
            _uiState.value = _uiState.value.copy(latestMessage = result.message)
            withContext(Dispatchers.Main) {
                Toast.makeText(application, result.message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun stop() {
        val application = application
        this.application = null
        AccessibilityEventDispatcher.removeListener(recordingListener)
        DeeplinkBookmarkSession.stop()
        val snapshot = synchronized(recordedEvents) {
            recordedEvents.toList().also { recordedEvents.clear() }
        }
        _uiState.value = UiState()
        BehaviorRecordingFloatWindow.dismiss()
        if (application == null) {
            return
        }
        try {
            val result = BehaviorSkillWriter.writeBehaviorSkill(snapshot)
            Toast.makeText(
                application,
                "轨迹记录已写入 ${result.skillName}",
                Toast.LENGTH_LONG
            ).show()
            Log.i(TAG, "Behavior skill written: ${result.skillFile.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write behavior skill", e)
            Toast.makeText(
                application,
                "轨迹记录写入失败: ${e.message}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun buildLatestMessage(
        latestAction: String,
        currentPageTitle: String?
    ): String {
        return if (currentPageTitle.isNullOrBlank()) {
            latestAction.ifBlank { DEFAULT_IDLE_MESSAGE }
        } else {
            "$latestAction | 当前页：$currentPageTitle"
        }
    }
}
