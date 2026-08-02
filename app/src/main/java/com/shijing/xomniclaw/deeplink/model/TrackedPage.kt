package com.shijing.xomniclaw.deeplink.model

/**
 * 录制会话里当前跟踪到的页面。
 *
 * 这里不直接依赖 UI，方便在悬浮窗、收藏逻辑、列表页之间复用。
 */
data class TrackedPage(
    val packageName: String,
    val activityName: String,
    val appName: String,
    val windowTitle: String = "",
    val timestamp: Long = System.currentTimeMillis()
) {
    val displayTitle: String
        get() = windowTitle.ifBlank { shortActivityName }

    val shortActivityName: String
        get() {
            val parts = activityName.split(".")
            return if (parts.size > 2) {
                parts.takeLast(2).joinToString(".")
            } else {
                activityName
            }
        }
}
