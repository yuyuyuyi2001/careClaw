package com.shijing.xomniclaw.accessibility.service

import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import java.util.concurrent.CopyOnWriteArraySet

/**
 * 轻量无障碍事件分发器。
 *
 * 让 app 主模块可以订阅用户真实操作事件，而不需要直接把录制逻辑塞进无障碍服务类本身。
 */
object AccessibilityEventDispatcher {
    data class RecordedAccessibilityEvent(
        val timestampMs: Long,
        val eventType: Int,
        val eventTypeName: String,
        val packageName: String,
        val className: String,
        val text: String,
        val contentDescription: String,
        val actionSummary: String
    )

    private val listeners = CopyOnWriteArraySet<(RecordedAccessibilityEvent) -> Unit>()

    fun addListener(listener: (RecordedAccessibilityEvent) -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: (RecordedAccessibilityEvent) -> Unit) {
        listeners.remove(listener)
    }

    fun clearListeners() {
        listeners.clear()
    }

    fun dispatch(event: AccessibilityEvent) {
        if (listeners.isEmpty()) return
        val mapped = RecordedAccessibilityEvent(
            // AccessibilityEvent.eventTime 是开机后相对时间（uptime），
            // 这里统一换算成真实墙上时间，避免记录里出现 1970 年。
            timestampMs = toWallClockTimeMs(event.eventTime),
            eventType = event.eventType,
            eventTypeName = eventTypeToName(event.eventType),
            packageName = event.packageName?.toString().orEmpty(),
            className = event.className?.toString().orEmpty(),
            text = event.text?.joinToString(" ") { it?.toString().orEmpty() }.orEmpty().trim(),
            contentDescription = event.contentDescription?.toString().orEmpty(),
            actionSummary = buildActionSummary(event)
        )
        listeners.forEach { listener ->
            listener(mapped)
        }
    }

    private fun toWallClockTimeMs(eventTimeMs: Long): Long {
        if (eventTimeMs <= 0L) {
            return System.currentTimeMillis()
        }
        val offset = System.currentTimeMillis() - SystemClock.uptimeMillis()
        val candidate = offset + eventTimeMs
        // 防御性处理：若换算异常落在 2000 年前，则回退当前时间。
        return if (candidate >= 946684800000L) candidate else System.currentTimeMillis()
    }

    private fun buildActionSummary(event: AccessibilityEvent): String {
        val text = event.text?.joinToString(" ") { it?.toString().orEmpty() }.orEmpty().trim()
        val contentDesc = event.contentDescription?.toString().orEmpty()
        val className = event.className?.toString().orEmpty()
        return when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED ->
                "点击 ${contentDesc.ifBlank { text }.ifBlank { className }}"
            AccessibilityEvent.TYPE_VIEW_LONG_CLICKED ->
                "长按 ${contentDesc.ifBlank { text }.ifBlank { className }}"
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED ->
                "输入文本 ${text.ifBlank { "(空)" }}"
            AccessibilityEvent.TYPE_VIEW_SCROLLED ->
                "滚动 ${className.ifBlank { "列表" }}"
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ->
                "切换到 ${event.packageName?.toString().orEmpty()}"
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ->
                "页面内容变化 ${className.ifBlank { "" }}".trim()
            else ->
                eventTypeToName(event.eventType)
        }
    }

    private fun eventTypeToName(eventType: Int): String {
        return when (eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED -> "TYPE_VIEW_CLICKED"
            AccessibilityEvent.TYPE_VIEW_LONG_CLICKED -> "TYPE_VIEW_LONG_CLICKED"
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> "TYPE_VIEW_TEXT_CHANGED"
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> "TYPE_VIEW_SCROLLED"
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> "TYPE_WINDOW_STATE_CHANGED"
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> "TYPE_WINDOW_CONTENT_CHANGED"
            else -> "TYPE_$eventType"
        }
    }
}
