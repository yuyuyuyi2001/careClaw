package com.shijing.xomniclaw.agent.memory.gallery

import com.tencent.mmkv.MMKV
import com.shijing.xomniclaw.util.MMKVKeys
import org.json.JSONObject

/**
 * 相册记忆后台同步进度快照。
 *
 * 通过 MMKV 持久化，便于：
 * - 前台 Service 后台执行时跨页面可见；
 * - 状态页定时刷新时读取最新进度；
 * - 扫描结束后保留最后一次结果摘要。
 */
data class GalleryMemorySyncStatus(
    val isRunning: Boolean = false,
    val stage: String = "idle",
    val message: String = "未开始扫描",
    val maxImages: Int = 0,
    val inspectedCount: Int = 0,
    val discoveredCount: Int = 0,
    val processedCount: Int = 0,
    val writtenCount: Int = 0,
    val skippedCount: Int = 0,
    val startedAtMs: Long? = null,
    val updatedAtMs: Long? = null
) {
    fun progressFraction(): Float? {
        if (discoveredCount <= 0) {
            return null
        }
        return (processedCount.toFloat() / discoveredCount.toFloat()).coerceIn(0f, 1f)
    }

    fun progressText(): String {
        return when {
            isRunning && discoveredCount > 0 ->
                "已检查 $inspectedCount 张，待处理 $discoveredCount 张，已完成 $processedCount 张"
            isRunning ->
                "已检查 $inspectedCount 张，正在继续扫描..."
            else ->
                "最近一次：检查 $inspectedCount 张，写入 $writtenCount 条，跳过 $skippedCount 条"
        }
    }
}

class GalleryMemorySyncStatusStore(
    private val mmkv: MMKV? = MMKV.defaultMMKV()
) {
    fun load(): GalleryMemorySyncStatus {
        val raw = mmkv?.decodeString(MMKVKeys.GALLERY_MEMORY_SYNC_STATUS_JSON.key).orEmpty()
        if (raw.isBlank()) {
            return GalleryMemorySyncStatus()
        }

        return runCatching {
            val root = JSONObject(raw)
            GalleryMemorySyncStatus(
                isRunning = root.optBoolean("is_running", false),
                stage = root.optString("stage", "idle"),
                message = root.optString("message", "未开始扫描"),
                maxImages = root.optInt("max_images", 0),
                inspectedCount = root.optInt("inspected_count", 0),
                discoveredCount = root.optInt("discovered_count", 0),
                processedCount = root.optInt("processed_count", 0),
                writtenCount = root.optInt("written_count", 0),
                skippedCount = root.optInt("skipped_count", 0),
                startedAtMs = root.optLong("started_at_ms").takeIf { it > 0L },
                updatedAtMs = root.optLong("updated_at_ms").takeIf { it > 0L }
            )
        }.getOrDefault(GalleryMemorySyncStatus())
    }

    fun save(status: GalleryMemorySyncStatus) {
        val json = JSONObject()
            .put("is_running", status.isRunning)
            .put("stage", status.stage)
            .put("message", status.message)
            .put("max_images", status.maxImages)
            .put("inspected_count", status.inspectedCount)
            .put("discovered_count", status.discoveredCount)
            .put("processed_count", status.processedCount)
            .put("written_count", status.writtenCount)
            .put("skipped_count", status.skippedCount)
            .put("started_at_ms", status.startedAtMs ?: 0L)
            .put("updated_at_ms", status.updatedAtMs ?: System.currentTimeMillis())

        mmkv?.encode(MMKVKeys.GALLERY_MEMORY_SYNC_STATUS_JSON.key, json.toString())
    }

    fun update(transform: (GalleryMemorySyncStatus) -> GalleryMemorySyncStatus): GalleryMemorySyncStatus {
        val updated = transform(load()).copy(updatedAtMs = System.currentTimeMillis())
        save(updated)
        return updated
    }

    fun markRunning(maxImages: Int, message: String, stage: String): GalleryMemorySyncStatus {
        val now = System.currentTimeMillis()
        val status = GalleryMemorySyncStatus(
            isRunning = true,
            stage = stage,
            message = message,
            maxImages = maxImages,
            startedAtMs = now,
            updatedAtMs = now
        )
        save(status)
        return status
    }
}

data class GalleryMemorySyncProgress(
    val stage: String,
    val message: String,
    val inspectedCount: Int = 0,
    val discoveredCount: Int = 0,
    val processedCount: Int = 0,
    val writtenCount: Int = 0,
    val skippedCount: Int = 0
)
