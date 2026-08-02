package com.shijing.xomniclaw.agent.memory.gallery

import android.net.Uri
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 相册图片记录。
 *
 * 保留扫描阶段需要的基础元信息，后续摘要与画像阶段都基于这个统一结构继续处理。
 */
data class AlbumImageRecord(
    val mediaId: Long,
    val contentUri: Uri,
    val displayName: String,
    val bucketName: String?,
    val mimeType: String?,
    val width: Int?,
    val height: Int?,
    val sizeBytes: Long?,
    val dateTakenMs: Long?,
    val dateAddedSec: Long?,
    val dateModifiedSec: Long?
) {
    /**
     * 稳定的唯一键。
     */
    val stableKey: String
        get() = "media-$mediaId"

    /**
     * 统一使用修改时间作为增量扫描主游标；若缺失则退回创建时间。
     */
    val incrementalTimestampSec: Long
        get() = dateModifiedSec ?: dateAddedSec ?: 0L

    /**
     * 记忆展示与排序优先使用拍摄时间；若缺失则回退到增量游标时间。
     */
    val memoryTimestampMs: Long
        get() = dateTakenMs ?: (incrementalTimestampSec * 1000L)

    fun formattedTimestamp(): String {
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(memoryTimestampMs))
    }
}

/**
 * 相册扫描游标。
 *
 * 使用 “时间戳 + mediaId” 双字段，避免多张图片同秒写入时丢数据。
 */
data class AlbumScanCursor(
    val lastTimestampSec: Long = 0L,
    val lastMediaId: Long = 0L
)

/**
 * 图片记忆条目。
 */
data class ImageMemoryEntry(
    val stableKey: String,
    val displayName: String,
    val bucketName: String?,
    val timestampText: String,
    val summary: String,
    val tags: List<String>,
    val sensitivity: String,
    val summarySource: String,
    val originalTextExcerpt: String? = null
) {
    /**
     * markdown 作为 Memory 文件里的稳定格式。
     *
     * 收敛方案下，image-memories 每条保留：
     * - 文件名（作为条目标题）
     * - time
     * - album：图片来源（系统相册/目录显示名，来自 MediaStore BUCKET_DISPLAY_NAME）
     * - summary
     *
     * `##` 继续作为结构锚点，`---` 作为明显的视觉分隔符，
     * 这样后续即使走全文加载，模型也更容易识别完整条目边界。
     */
    fun toMarkdown(): String {
        val albumLine = (bucketName?.takeIf { it.isNotBlank() } ?: "unknown")
        return buildString {
            appendLine("## $displayName")
            appendLine("- time: $timestampText")
            appendLine("- album: $albumLine")
            appendLine("- summary: $summary")
            appendLine()
            appendLine("---")
            appendLine()
        }
    }
}

/**
 * 相册同步报告。
 */
data class GalleryMemorySyncReport(
    val inspectedCount: Int,
    val scannedCount: Int,
    val writtenCount: Int,
    val skippedCount: Int,
    val profileUpdated: Boolean,
    val message: String
)
