package com.shijing.xomniclaw.deeplink.model

/**
 * Deeplink 收藏记录。
 *
 * `dataUri` 是最接近真实 deeplink 的字段；
 * 当某些页面没有直接暴露 data URI 时，仍然保留完整 `am start` 命令作为兜底。
 */
data class DeeplinkBookmark(
    val id: Long = System.currentTimeMillis(),
    val packageName: String,
    val activityName: String,
    val appName: String,
    val pageTitle: String,
    val createdAt: Long = System.currentTimeMillis(),
    val dataUri: String = "",
    val intentCommand: String = "",
    val rawIntentLine: String = "",
    val capturedExtrasCount: Int = 0,
    val sourceActionSummary: String = ""
) {
    val displayName: String
        get() = pageTitle.ifBlank { shortActivityName }

    val shortActivityName: String
        get() {
            val parts = activityName.split(".")
            return if (parts.size > 2) {
                "...${parts.takeLast(2).joinToString(".")}"
            } else {
                activityName
            }
        }

    val hasPreciseJumpSpec: Boolean
        get() = dataUri.isNotBlank() || capturedExtrasCount > 0

    val effectiveAmCommand: String
        get() = intentCommand.ifBlank { "am start -n $packageName/$activityName" }

    val deeplinkSummary: String
        get() = dataUri.ifBlank {
            if (hasPreciseJumpSpec) {
                "已捕获带 extras 的启动参数"
            } else {
                "未捕获显式 deeplink，使用 Activity 兜底"
            }
        }

    val statusText: String
        get() = when {
            dataUri.isNotBlank() && capturedExtrasCount > 0 ->
                "已捕获 deeplink + $capturedExtrasCount 个 extras"
            dataUri.isNotBlank() ->
                "已捕获 deeplink，可一键直达"
            capturedExtrasCount > 0 ->
                "已捕获 $capturedExtrasCount 个 extras，可精确回放"
            rawIntentLine.contains("(has extras)") ->
                "检测到 extras，但当前 ROM 输出未完全解析"
            else ->
                "仅保存 Activity 兜底信息"
        }
}
