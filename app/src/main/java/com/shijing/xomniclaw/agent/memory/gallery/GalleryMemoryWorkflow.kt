package com.shijing.xomniclaw.agent.memory.gallery

import android.content.Context
import com.shijing.xomniclaw.agent.memory.MemoryManager

/**
 * 相册记忆工作流。
 *
 * 把“扫描 -> 提炼 -> 落盘 -> 画像生成”串成统一入口，便于：
 * - 手动调用
 * - 定时任务调用
 * - 后续做状态展示或重试
 */
class GalleryMemoryWorkflow(
    context: Context,
    private val memoryManager: MemoryManager
) {
    companion object {
        private const val MIN_SCAN_BATCH_SIZE = 20
    }

    private val scanner = AlbumScanner(context)
    private val cursorStore = AlbumScanCursorStore()
    private val summarizer = ImageMemorySummarizer(context)
    private val repository = GalleryMemoryRepository(memoryManager)
    private val userProfileGenerator = UserProfileGenerator(memoryManager, repository)

    suspend fun syncGalleryMemories(
        maxImages: Int = 10,
        forceRescan: Boolean = false,
        updateProfile: Boolean = true,
        progressListener: ((GalleryMemorySyncProgress) -> Unit)? = null
    ): GalleryMemorySyncReport {
        val initialCursor = if (forceRescan) AlbumScanCursor() else cursorStore.load()
        // 极简 image-memories 以文件名为主键，因此这里也按 displayName 去重。
        val existingDisplayNames = repository.loadExistingStableKeys().toMutableSet()
        val batchSize = maxOf(MIN_SCAN_BATCH_SIZE, maxImages)
        val discovered = mutableListOf<AlbumImageRecord>()
        var inspectedCount = 0
        var skippedExistingCount = 0
        var scanCursor = initialCursor

        progressListener?.invoke(
            GalleryMemorySyncProgress(
                stage = "scanning",
                message = "正在扫描相册中的新增图片",
                inspectedCount = 0,
                discoveredCount = 0,
                skippedCount = 0
            )
        )

        while (discovered.size < maxImages) {
            val batch = scanner.scanNewImages(cursor = scanCursor, maxResults = batchSize)
            if (batch.isEmpty()) {
                break
            }

            inspectedCount += batch.size
            var cursorAdvanceRecord: AlbumImageRecord? = null
            var reachedQuotaInsideBatch = false
            for (record in batch) {
                if (existingDisplayNames.contains(record.displayName)) {
                    skippedExistingCount += 1
                    cursorAdvanceRecord = record
                    continue
                }

                if (discovered.size < maxImages) {
                    discovered += record
                    existingDisplayNames += record.displayName
                    cursorAdvanceRecord = record
                } else {
                    reachedQuotaInsideBatch = true
                    break
                }
            }

            // 扫描顺序为「新→旧」时，若本批已完整遍历，游标须推进到本批在 (修改时间, mediaId) 上的最大值，
            // 否则游标会落在较旧的一条上，下一轮查询会再次带上本批里较新的项。
            if (batch.isNotEmpty()) {
                scanCursor = if (reachedQuotaInsideBatch && cursorAdvanceRecord != null) {
                    AlbumScanCursor(
                        lastTimestampSec = cursorAdvanceRecord.incrementalTimestampSec,
                        lastMediaId = cursorAdvanceRecord.mediaId
                    )
                } else {
                    val furthest = batch.maxWith(
                        compareBy({ it.incrementalTimestampSec }, { it.mediaId })
                    )
                    AlbumScanCursor(
                        lastTimestampSec = furthest.incrementalTimestampSec,
                        lastMediaId = furthest.mediaId
                    )
                }
            }

            if (reachedQuotaInsideBatch || batch.size < batchSize) {
                break
            }

            progressListener?.invoke(
                GalleryMemorySyncProgress(
                    stage = "scanning",
                    message = "正在扫描相册中的新增图片",
                    inspectedCount = inspectedCount,
                    discoveredCount = discovered.size,
                    skippedCount = skippedExistingCount
                )
            )
        }

        if (inspectedCount > 0 && scanCursor != initialCursor) {
            cursorStore.save(scanCursor)
        }

        if (discovered.isEmpty()) {
            val profileUpdated = if (updateProfile) {
                val profile = userProfileGenerator.buildUserProfile()
                repository.writeUserProfile(profile)
                true
            } else {
                false
            }
            progressListener?.invoke(
                GalleryMemorySyncProgress(
                    stage = "completed",
                    message = "没有发现新的相册图片",
                    inspectedCount = inspectedCount,
                    discoveredCount = 0,
                    processedCount = 0,
                    writtenCount = 0,
                    skippedCount = skippedExistingCount
                )
            )
            return GalleryMemorySyncReport(
                inspectedCount = inspectedCount,
                scannedCount = 0,
                writtenCount = 0,
                skippedCount = skippedExistingCount,
                profileUpdated = profileUpdated,
                message = if (scanner.hasRequiredPermission()) {
                    if (inspectedCount > 0 && skippedExistingCount > 0) {
                        "No new gallery images found. Skipped $skippedExistingCount existing records already stored in IMAGE-MEMORY.md."
                    } else {
                        "No new gallery images found."
                    }
                } else {
                    "Gallery permission is not granted."
                }
            )
        }

        val entries = mutableListOf<ImageMemoryEntry>()
        discovered.forEachIndexed { index, record ->
            progressListener?.invoke(
                GalleryMemorySyncProgress(
                    stage = "summarizing",
                    message = "正在生成图片记忆 ${index + 1}/${discovered.size}",
                    inspectedCount = inspectedCount,
                    discoveredCount = discovered.size,
                    processedCount = index,
                    writtenCount = entries.size,
                    skippedCount = skippedExistingCount
                )
            )
            entries += summarizer.summarize(record)
            progressListener?.invoke(
                GalleryMemorySyncProgress(
                    stage = "summarizing",
                    message = "正在生成图片记忆 ${index + 1}/${discovered.size}",
                    inspectedCount = inspectedCount,
                    discoveredCount = discovered.size,
                    processedCount = index + 1,
                    writtenCount = entries.size,
                    skippedCount = skippedExistingCount
                )
            )
        }

        progressListener?.invoke(
            GalleryMemorySyncProgress(
                stage = "writing",
                message = "正在写入 image-memories 和 user-profile",
                inspectedCount = inspectedCount,
                discoveredCount = discovered.size,
                processedCount = entries.size,
                writtenCount = entries.size,
                skippedCount = skippedExistingCount
            )
        )
        repository.appendImageMemories(entries)

        val profileUpdated = if (updateProfile) {
            val profile = userProfileGenerator.buildUserProfile()
            repository.writeUserProfile(profile)
            true
        } else {
            false
        }

        progressListener?.invoke(
            GalleryMemorySyncProgress(
                stage = "completed",
                message = "相册扫描完成，已写入 ${entries.size} 条图片记忆",
                inspectedCount = inspectedCount,
                discoveredCount = discovered.size,
                processedCount = entries.size,
                writtenCount = entries.size,
                skippedCount = skippedExistingCount
            )
        )

        return GalleryMemorySyncReport(
            inspectedCount = inspectedCount,
            scannedCount = discovered.size,
            writtenCount = entries.size,
            skippedCount = skippedExistingCount,
            profileUpdated = profileUpdated,
            message = "Synced ${entries.size} gallery memories and skipped $skippedExistingCount existing records."
        )
    }

    suspend fun rebuildUserProfile(): String {
        val profile = userProfileGenerator.buildUserProfile()
        repository.writeUserProfile(profile)
        return profile
    }

    suspend fun getStatus(): String {
        val cursor = cursorStore.load()
        val imageMemories = repository.readImageMemories()
        val userProfile = repository.readUserProfile()
        return buildString {
            appendLine("Gallery memory workflow status")
            appendLine("- cursor_timestamp_sec: ${cursor.lastTimestampSec}")
            appendLine("- cursor_media_id: ${cursor.lastMediaId}")
            appendLine("- image_memories_available: ${imageMemories.isNotBlank()}")
            appendLine("- user_profile_available: ${userProfile.isNotBlank()}")
            append("- gallery_permission: ${scanner.hasRequiredPermission()}")
        }
    }

    fun resetCursor() {
        cursorStore.clear()
    }

    suspend fun clearImageMemories() {
        repository.clearImageMemories()
    }

    suspend fun clearUserProfile() {
        repository.clearUserProfile()
    }

    suspend fun resetAll() {
        cursorStore.clear()
        repository.clearImageMemories()
        repository.clearUserProfile()
    }
}
