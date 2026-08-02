package com.shijing.xomniclaw.ui.floatwindow

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.View.INVISIBLE
import android.view.View.VISIBLE
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ImageButton
import android.widget.TextView
import androidx.cardview.widget.CardView
import com.shijing.xomniclaw.R
import com.lzf.easyfloat.EasyFloat
import com.lzf.easyfloat.enums.ShowPattern
import com.shijing.xomniclaw.voice.ScreenCompanionController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * 屏内替身模式的独立悬浮层。
 *
 * UI 保持极简：一个短消息区、一个长按说话按钮、一个停止按钮，
 * 并支持拖动，减少对当前页面的遮挡。
 */
object ScreenCompanionFloatWindow {
    private const val TAG = "ScreenCompanionFloat"
    private const val FLOAT_TAG = "screen_companion_float"
    private const val DEFAULT_WIDTH_DP = 196
    private const val DEFAULT_HEIGHT_DP = 72
    private const val MIN_WIDTH_DP = 172
    private const val MIN_HEIGHT_DP = 72
    /** 允许用户把替身条拖得更宽/更高一些（仍受屏幕与 applySize 上限约束） */
    private const val MAX_WIDTH_DP = 380
    private const val MAX_HEIGHT_DP = 280

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var stateJob: Job? = null

    private var rootCardView: CardView? = null
    private var resizeHandleView: View? = null
    private var statusTextView: TextView? = null
    private var messageTextView: TextView? = null
    private var voiceButton: ImageButton? = null
    private var stopButton: ImageButton? = null

    private data class ResizeSession(
        val startWidth: Int,
        val startHeight: Int,
        val startRawX: Float,
        val startRawY: Float
    )

    private var resizeSession: ResizeSession? = null

    fun show(context: Context) {
        if (EasyFloat.isShow(FLOAT_TAG)) {
            applyPersistedSize(context)
            bindStateCollector()
            render(ScreenCompanionController.uiState.value)
            return
        }

        try {
            val topOffset = getStatusBarHeight(context) + dpToPx(context, 4)
            EasyFloat.with(context)
                .setTag(FLOAT_TAG)
                .setLayout(R.layout.layout_screen_companion_float) { view ->
                    bindViews(view)
                    applyPersistedSize(context)
                    bindInteractions()
                    bindStateCollector()
                    render(ScreenCompanionController.uiState.value)
                }
                .setGravity(Gravity.TOP or Gravity.CENTER_HORIZONTAL, 0, topOffset)
                .setShowPattern(ShowPattern.ALL_TIME)
                .setDragEnable(true)
                .show()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show companion float window", e)
        }
    }

    fun dismiss() {
        // 避免在缩放中途关闭后仍保持 dragEnable=false
        if (EasyFloat.isShow(FLOAT_TAG)) {
            EasyFloat.dragEnable(true, FLOAT_TAG)
        }
        stateJob?.cancel()
        stateJob = null
        rootCardView = null
        resizeHandleView = null
        statusTextView = null
        messageTextView = null
        voiceButton = null
        stopButton = null
        resizeSession = null
        try {
            if (EasyFloat.isShow(FLOAT_TAG)) {
                EasyFloat.dismiss(FLOAT_TAG)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to dismiss companion float window", e)
        }
    }

    private fun bindViews(root: View) {
        rootCardView = root.findViewById(R.id.card_companion_root)
        resizeHandleView = root.findViewById(R.id.view_companion_resize_handle)
        statusTextView = root.findViewById(R.id.tv_companion_status)
        messageTextView = root.findViewById(R.id.tv_companion_message)
        voiceButton = root.findViewById<ImageButton>(R.id.btn_companion_voice).apply {
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            isHapticFeedbackEnabled = true
        }
        stopButton = root.findViewById<ImageButton>(R.id.btn_companion_stop).apply {
            scaleType = ImageView.ScaleType.CENTER_INSIDE
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun bindInteractions() {
        voiceButton?.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    ScreenCompanionController.startVoiceCapture()
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    ScreenCompanionController.stopVoiceCapture()
                    true
                }
                else -> false
            }
        }
        stopButton?.setOnClickListener {
            ScreenCompanionController.exitCompanionMode(stopAgent = true)
        }
        // 右下角缩放：须暂时关闭 EasyFloat 全局拖拽，否则首次滑动会被当成拖窗，无法改尺寸。
        resizeHandleView?.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val card = rootCardView ?: return@setOnTouchListener false
                    setFloatDragEnabled(false)
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                    resizeSession = ResizeSession(
                        startWidth = card.width.coerceAtLeast(dpToPx(card.context, DEFAULT_WIDTH_DP)),
                        startHeight = card.height.coerceAtLeast(dpToPx(card.context, DEFAULT_HEIGHT_DP)),
                        startRawX = event.rawX,
                        startRawY = event.rawY
                    )
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val card = rootCardView ?: return@setOnTouchListener false
                    val current = resizeSession ?: return@setOnTouchListener false
                    val width = (current.startWidth + (event.rawX - current.startRawX)).toInt()
                    val height = (current.startHeight + (event.rawY - current.startRawY)).toInt()
                    applySize(
                        context = card.context,
                        widthPx = width,
                        heightPx = height,
                        persist = false
                    )
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    setFloatDragEnabled(true)
                    v.parent?.requestDisallowInterceptTouchEvent(false)
                    persistCurrentSize()
                    resizeSession = null
                    true
                }

                else -> false
            }
        }
    }

    private fun bindStateCollector() {
        stateJob?.cancel()
        stateJob = scope.launch {
            ScreenCompanionController.uiState.collectLatest { state ->
                render(state)
            }
        }
    }

    private fun render(state: ScreenCompanionController.ScreenCompanionUiState) {
        statusTextView?.text = state.statusText
        messageTextView?.let { messageView ->
            messageView.text = state.transientMessage
            // 固定保留消息行，避免替身悬浮窗在长条/短条之间来回跳变。
            messageView.visibility = if (state.transientMessage.isBlank()) INVISIBLE else VISIBLE
        }

        voiceButton?.let { button ->
            val backgroundColor = when {
                state.isListening -> Color.parseColor("#E53935")
                state.isAgentRunning -> Color.parseColor("#555555")
                else -> Color.parseColor("#1E88E5")
            }
            button.isEnabled = !state.isAgentRunning || state.isListening
            button.alpha = if (button.isEnabled) 1.0f else 0.6f
            button.imageTintList = ColorStateList.valueOf(Color.WHITE)
            button.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(backgroundColor)
            }
        }

        stopButton?.let { button ->
            button.imageTintList = ColorStateList.valueOf(Color.WHITE)
            button.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#7B1FA2"))
            }
        }
    }

    private fun applyPersistedSize(context: Context) {
        val defaultWidth = dpToPx(context, DEFAULT_WIDTH_DP)
        val defaultHeight = dpToPx(context, DEFAULT_HEIGHT_DP)
        val saved = ScreenCompanionFloatPrefs.loadSize(
            defaultWidthPx = defaultWidth,
            defaultHeightPx = defaultHeight
        )
        applySize(context, saved.widthPx, saved.heightPx, persist = false)
    }

    private fun applySize(context: Context, widthPx: Int, heightPx: Int, persist: Boolean) {
        val card = rootCardView ?: return
        val maxWidth = context.resources.displayMetrics.widthPixels - dpToPx(context, 24)
        val maxHeight = (context.resources.displayMetrics.heightPixels * 0.45f).toInt()
            .coerceAtLeast(dpToPx(context, MAX_HEIGHT_DP))
        val targetWidth = widthPx.coerceIn(
            dpToPx(context, MIN_WIDTH_DP),
            minOf(dpToPx(context, MAX_WIDTH_DP), maxWidth)
        )
        val targetHeight = heightPx.coerceIn(
            dpToPx(context, MIN_HEIGHT_DP),
            minOf(maxHeight, dpToPx(context, MAX_HEIGHT_DP))
        )
        val params = (card.layoutParams ?: ViewGroup.LayoutParams(targetWidth, targetHeight)).apply {
            width = targetWidth
            height = targetHeight
        }
        card.layoutParams = params
        card.requestLayout()
        // 同步更新 EasyFloat 宿主窗口尺寸，避免部分机型仅改子 View 时看起来“不实时生效”。
        EasyFloat.updateFloat(
            tag = FLOAT_TAG,
            width = targetWidth,
            height = targetHeight
        )
        if (persist) {
            ScreenCompanionFloatPrefs.saveSize(
                ScreenCompanionFloatPrefs.FloatSize(
                    widthPx = targetWidth,
                    heightPx = targetHeight
                )
            )
        }
    }

    private fun persistCurrentSize() {
        val card = rootCardView ?: return
        if (card.width <= 0 || card.height <= 0) return
        ScreenCompanionFloatPrefs.saveSize(
            ScreenCompanionFloatPrefs.FloatSize(
                widthPx = card.width,
                heightPx = card.height
            )
        )
    }

    private fun setFloatDragEnabled(enabled: Boolean) {
        // 兼容 EasyFloat 不同内部查找路径：优先按 tag，其次退化到默认实例。
        EasyFloat.dragEnable(enabled, FLOAT_TAG)
        EasyFloat.dragEnable(enabled)
    }

    private fun getStatusBarHeight(context: Context): Int {
        val resourceId = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resourceId > 0) context.resources.getDimensionPixelSize(resourceId) else 0
    }

    private fun dpToPx(context: Context, dp: Int): Int {
        return (dp * context.resources.displayMetrics.density).toInt()
    }
}
