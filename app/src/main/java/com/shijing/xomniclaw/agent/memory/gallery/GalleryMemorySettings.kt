package com.shijing.xomniclaw.agent.memory.gallery

import com.tencent.mmkv.MMKV
import com.shijing.xomniclaw.util.MMKVKeys

/**
 * 相册记忆与画像设置。
 *
 * 单独抽出来，避免 UI、ContextBuilder、调度器各自散落硬编码 key。
 */
data class GalleryMemorySettings(
    val featureEnabled: Boolean = false,
    val profileLoadingEnabled: Boolean = true,
    val scanIntervalMinutes: Int = 24 * 60,
    val manualSyncMaxImages: Int = 100,
    val automationTaskId: String? = null
)

/**
 * 设置仓库。
 */
class GalleryMemorySettingsStore(
    private val mmkv: MMKV? = MMKV.defaultMMKV()
) {
    fun load(): GalleryMemorySettings {
        return GalleryMemorySettings(
            featureEnabled = mmkv?.decodeBool(MMKVKeys.GALLERY_MEMORY_ENABLED.key, false) ?: false,
            profileLoadingEnabled = mmkv?.decodeBool(MMKVKeys.GALLERY_PROFILE_LOADING_ENABLED.key, true) ?: true,
            scanIntervalMinutes = mmkv?.decodeInt(
                MMKVKeys.GALLERY_MEMORY_SCAN_INTERVAL_MINUTES.key,
                24 * 60
            ) ?: (24 * 60),
            // 快速测试默认保留原来的 100 张，避免老用户升级后行为突变。
            manualSyncMaxImages = mmkv?.decodeInt(
                MMKVKeys.GALLERY_MEMORY_MANUAL_SYNC_MAX_IMAGES.key,
                100
            ) ?: 100,
            automationTaskId = mmkv?.decodeString(MMKVKeys.GALLERY_MEMORY_TASK_ID.key)
        )
    }

    fun save(settings: GalleryMemorySettings) {
        mmkv?.encode(MMKVKeys.GALLERY_MEMORY_ENABLED.key, settings.featureEnabled)
        mmkv?.encode(MMKVKeys.GALLERY_PROFILE_LOADING_ENABLED.key, settings.profileLoadingEnabled)
        mmkv?.encode(MMKVKeys.GALLERY_MEMORY_SCAN_INTERVAL_MINUTES.key, settings.scanIntervalMinutes)
        mmkv?.encode(MMKVKeys.GALLERY_MEMORY_MANUAL_SYNC_MAX_IMAGES.key, settings.manualSyncMaxImages)
        mmkv?.encode(MMKVKeys.GALLERY_MEMORY_TASK_ID.key, settings.automationTaskId)
    }

    fun update(transform: (GalleryMemorySettings) -> GalleryMemorySettings): GalleryMemorySettings {
        val updated = transform(load())
        save(updated)
        return updated
    }
}
