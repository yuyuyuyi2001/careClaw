package com.shijing.xomniclaw.ui.floatwindow

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.Log
import android.view.Gravity
import android.widget.ImageButton
import android.widget.TextView
import com.shijing.xomniclaw.R
import com.lzf.easyfloat.EasyFloat
import com.lzf.easyfloat.enums.ShowPattern
import com.shijing.xomniclaw.behavior.BehaviorRecordingController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * 轨迹录制模式悬浮窗。
 *
 * root 可用时会额外显示“收藏当前页”按钮，把当前页面沉淀到 Deeplink 收藏列表。
 */
object BehaviorRecordingFloatWindow {
    private const val TAG = "BehaviorRecordingFloat"
    private const val FLOAT_TAG = "behavior_recording_float"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var stateJob: Job? = null

    private var statusTextView: TextView? = null
    private var messageTextView: TextView? = null
    private var bookmarkButton: ImageButton? = null
    private var stopButton: ImageButton? = null

    fun show(context: Context) {
        if (EasyFloat.isShow(FLOAT_TAG)) {
            bindStateCollector()
            render(BehaviorRecordingController.uiState.value)
            return
        }
        try {
            val topOffset = getStatusBarHeight(context) + dpToPx(context, 4)
            EasyFloat.with(context)
                .setTag(FLOAT_TAG)
                .setLayout(R.layout.layout_behavior_recording_float) { view ->
                    statusTextView = view.findViewById(R.id.tv_behavior_status)
                    messageTextView = view.findViewById(R.id.tv_behavior_message)
                    bookmarkButton = view.findViewById<ImageButton>(R.id.btn_behavior_bookmark).apply {
                        setOnClickListener {
                            BehaviorRecordingController.bookmarkCurrentPage()
                        }
                    }
                    stopButton = view.findViewById<ImageButton>(R.id.btn_behavior_stop).apply {
                        setOnClickListener {
                            BehaviorRecordingController.stop()
                        }
                    }
                    bindStateCollector()
                    render(BehaviorRecordingController.uiState.value)
                }
                .setGravity(Gravity.TOP or Gravity.CENTER_HORIZONTAL, 0, topOffset)
                .setShowPattern(ShowPattern.ALL_TIME)
                .setDragEnable(true)
                .show()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show behavior float window", e)
        }
    }

    fun dismiss() {
        stateJob?.cancel()
        stateJob = null
        statusTextView = null
        messageTextView = null
        bookmarkButton = null
        stopButton = null
        try {
            if (EasyFloat.isShow(FLOAT_TAG)) {
                EasyFloat.dismiss(FLOAT_TAG)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to dismiss behavior float window", e)
        }
    }

    private fun bindStateCollector() {
        stateJob?.cancel()
        stateJob = scope.launch {
            BehaviorRecordingController.uiState.collectLatest { state ->
                render(state)
            }
        }
    }

    private fun render(state: BehaviorRecordingController.UiState) {
        statusTextView?.text = state.statusText
        messageTextView?.text = state.latestMessage
        bookmarkButton?.let { button ->
            button.visibility = if (state.canBookmarkCurrentPage) android.view.View.VISIBLE else android.view.View.GONE
            button.imageTintList = ColorStateList.valueOf(Color.WHITE)
            button.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#2E7D32"))
            }
        }
        stopButton?.let { button ->
            button.imageTintList = ColorStateList.valueOf(Color.WHITE)
            button.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#D32F2F"))
            }
        }
    }

    private fun getStatusBarHeight(context: Context): Int {
        val resourceId = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resourceId > 0) context.resources.getDimensionPixelSize(resourceId) else 0
    }

    private fun dpToPx(context: Context, dp: Int): Int {
        return (dp * context.resources.displayMetrics.density).toInt()
    }
}
