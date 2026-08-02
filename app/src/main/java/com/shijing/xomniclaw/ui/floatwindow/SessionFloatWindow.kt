/**
 * OmniClaw Source Reference:
 * - ../xomniclaw/src/gateway/(all)
 *
 * OmniClaw adaptation: Android UI layer.
 */
package com.shijing.xomniclaw.ui.floatwindow

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.TextView
import com.lzf.easyfloat.EasyFloat
import com.lzf.easyfloat.enums.ShowPattern
import com.shijing.xomniclaw.R
import com.shijing.xomniclaw.util.MMKVKeys
import com.tencent.mmkv.MMKV

/**
 * Compact session floating bar manager.
 *
 * Features:
 * - Only shown when main page is not visible
 * - Only displays step progress text
 * - Placed at the top status area to avoid blocking page buttons
 * - Disabled by default, controlled by in-app switch
 */
object SessionFloatWindow {
    private const val TAG = "SessionFloatWindow"
    private const val FLOAT_TAG = "session_float"
    private val STEP_FULL_RE = Regex("""步骤\s*(\d+)\s*/\s*(\d+)""")
    private val STEP_ONLY_RE = Regex("""步骤\s*(\d+)""")
    private val STEP_CN_RE = Regex("""第\s*(\d+)\s*步""")

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private var isEnabled = false
    private var isMainActivityVisible = true
    private var stepIndicatorTextView: TextView? = null
    private var latestStepText: String = ""

    // Agent 运行时自动显示浮动窗口（无需用户手动开启）
    @Volatile
    private var isAgentRunning = false

    // 任务已进入最终完成/失败态后，忽略迟到的进度更新，避免悬浮窗被重新刷出。
    @Volatile
    private var isTaskTerminal = false

    /**
     * Initialize floating window configuration
     */
    fun init(@Suppress("UNUSED_PARAMETER") context: Context) {
        val mmkv = MMKV.defaultMMKV()
        isEnabled = mmkv.decodeBool(MMKVKeys.FLOAT_WINDOW_ENABLED.key, false)
        Log.d(TAG, "SessionFloatWindow initialized, enabled=$isEnabled")
    }

    /**
     * Agent 开始/结束执行时调用，自动控制浮动窗口显隐。
     * running=true 时，即使用户未手动开启浮动窗口也会自动显示。
     */
    fun setAgentRunning(running: Boolean, context: Context) {
        isAgentRunning = running
        Log.d(TAG, "Agent running=$running, mainVisible=$isMainActivityVisible, enabled=$isEnabled")

        if (running && !isMainActivityVisible) {
            isTaskTerminal = false
            createFloatWindow(context.applicationContext)
        } else if (!running) {
            resetStepIndicator()
            // Agent 结束 → 无条件关闭悬浮窗（任务完成后始终隐藏）
            dismissFloatWindow()
        }
    }

    /**
     * 根据真实任务完成状态关闭悬浮窗，而不是依赖文案关键词推断。
     */
    fun finishTask() {
        isTaskTerminal = true
        resetStepIndicator()
        dismissFloatWindow()
        Log.d(TAG, "Float window finished by terminal task state")
    }

    /**
     * Set floating window switch status
     */
    fun setEnabled(context: Context, enabled: Boolean) {
        isEnabled = enabled

        val mmkv = MMKV.defaultMMKV()
        mmkv.encode(MMKVKeys.FLOAT_WINDOW_ENABLED.key, enabled)

        Log.d(TAG, "Float window enabled=$enabled")

        if (enabled) {
            if (!isMainActivityVisible) {
                createFloatWindow(context)
            }
        } else if (!isAgentRunning) {
            dismissFloatWindow()
        }
    }

    /**
     * Get floating window switch status
     */
    fun isEnabled(): Boolean {
        return isEnabled
    }

    /**
     * Set main activity visibility
     */
    fun setMainActivityVisible(visible: Boolean, context: Context) {
        isMainActivityVisible = visible
        Log.d(TAG, "Main activity visible=$visible, enabled=$isEnabled, agentRunning=$isAgentRunning")

        // 需要显示浮动窗口的条件：用户手动开启 或 Agent 正在执行
        val shouldShow = isEnabled || isAgentRunning

        if (!shouldShow) return

        if (visible) {
            dismissFloatWindow()
        } else {
            createFloatWindow(context)
        }
    }

    /**
     * Update session info
     */
    @SuppressLint("SetTextI18n")
    fun updateSessionInfo(title: String, content: String) {
        if (isTaskTerminal) return
        val extracted = extractStepText(title, content)
        when {
            extracted != null -> latestStepText = extracted
            shouldClearStepText(title, content) -> latestStepText = ""
        }
        mainHandler.post { syncStepIndicatorView() }
        Log.d(TAG, "Updated session info: $title — ${content.take(30)}")
    }

    /**
     * For compact top bar, latest message text is intentionally ignored.
     */
    fun updateLatestMessage(message: String) {
        if (message.isEmpty()) return
        if (isTaskTerminal) return
    }

    /**
     * Create floating window
     */
    @SuppressLint("InflateParams")
    private fun createFloatWindow(context: Context) {
        // 定时任务执行时不应被悬浮窗权限页卡住；无权限时直接跳过进度浮窗。
        if (!Settings.canDrawOverlays(context)) {
            Log.w(TAG, "Skip creating float window because overlay permission is not granted")
            return
        }

        // Check if already exists
        if (EasyFloat.isShow(FLOAT_TAG)) {
            Log.d(TAG, "Float window already exists")
            mainHandler.post { syncStepIndicatorView() }
            return
        }

        try {
            val topOffset = getStatusBarHeight(context) + dpToPx(context, 4)
            EasyFloat.with(context)
                .setTag(FLOAT_TAG)
                .setLayout(R.layout.layout_session_float) { view ->
                    stepIndicatorTextView = view.findViewById(R.id.tv_step_indicator)
                    syncStepIndicatorView()
                }
                .setGravity(Gravity.TOP or Gravity.CENTER_HORIZONTAL, 0, topOffset)
                .setShowPattern(ShowPattern.ALL_TIME)
                .setDragEnable(false)
                .show()

            Log.d(TAG, "Float window created")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create float window", e)
        }
    }

    /**
     * Destroy floating window
     */
    private fun dismissFloatWindow() {
        try {
            if (EasyFloat.isShow(FLOAT_TAG)) {
                EasyFloat.dismiss(FLOAT_TAG)
                stepIndicatorTextView = null
                Log.d(TAG, "Float window dismissed")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to dismiss float window", e)
        }
    }

    /**
     * 任务完成后需要立即清空步骤文案，避免悬浮窗复用上一次的“步骤 x/y”。
     */
    private fun resetStepIndicator() {
        latestStepText = ""
        mainHandler.post { syncStepIndicatorView() }
    }

    private fun syncStepIndicatorView() {
        val textView = stepIndicatorTextView ?: return
        textView.text = latestStepText
        textView.visibility = if (latestStepText.isBlank()) View.GONE else View.VISIBLE
    }

    private fun extractStepText(title: String, content: String): String? {
        val candidates = listOf(title, content)

        // Highest priority: explicit "步骤 x/y"
        for (raw in candidates) {
            val full = STEP_FULL_RE.find(raw)
            if (full != null) {
                val cur = full.groupValues[1]
                val max = full.groupValues[2]
                return "步骤 $cur/$max"
            }
        }

        val rememberedMax = STEP_FULL_RE.find(latestStepText)?.groupValues?.getOrNull(2)
        for (raw in candidates) {
            val stepOnly = STEP_ONLY_RE.find(raw)?.groupValues?.getOrNull(1)
                ?: STEP_CN_RE.find(raw)?.groupValues?.getOrNull(1)
            if (!stepOnly.isNullOrBlank()) {
                val maxPart = rememberedMax ?: "--"
                return "步骤 $stepOnly/$maxPart"
            }
        }
        return null
    }

    /**
     * 当进度进入结束态/非步骤态时，不应继续保留上一轮的步骤编号。
     */
    private fun shouldClearStepText(title: String, content: String): Boolean {
        val normalized = "$title\n$content"
        if (normalized.contains("步骤")) {
            return false
        }

        val clearMarkers = listOf(
            "完成",
            "已完成",
            "已成功",
            "成功",
            "执行完成",
            "思考完成",
            "中间回复",
            "错误",
            "上下文已恢复",
            "上下文超限",
            "循环检测"
        )
        return clearMarkers.any { normalized.contains(it) }
    }

    private fun dpToPx(context: Context, dp: Int): Int {
        val density = context.resources.displayMetrics.density
        return (dp * density).toInt()
    }

    private fun getStatusBarHeight(context: Context): Int {
        val resId = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resId > 0) context.resources.getDimensionPixelSize(resId) else 0
    }
}
