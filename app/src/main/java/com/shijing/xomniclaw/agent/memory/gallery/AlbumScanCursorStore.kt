package com.shijing.xomniclaw.agent.memory.gallery

import com.tencent.mmkv.MMKV

/**
 * 相册扫描游标存储。
 *
 * 使用 MMKV 持久化最近处理到的位置，让定时任务可以做增量同步。
 */
class AlbumScanCursorStore(
    private val mmkv: MMKV? = MMKV.defaultMMKV()
) {
    companion object {
        private const val KEY_LAST_TIMESTAMP_SEC = "gallery_memory_last_timestamp_sec"
        private const val KEY_LAST_MEDIA_ID = "gallery_memory_last_media_id"
    }

    fun load(): AlbumScanCursor {
        return AlbumScanCursor(
            lastTimestampSec = mmkv?.decodeLong(KEY_LAST_TIMESTAMP_SEC, 0L) ?: 0L,
            lastMediaId = mmkv?.decodeLong(KEY_LAST_MEDIA_ID, 0L) ?: 0L
        )
    }

    fun save(cursor: AlbumScanCursor) {
        mmkv?.encode(KEY_LAST_TIMESTAMP_SEC, cursor.lastTimestampSec)
        mmkv?.encode(KEY_LAST_MEDIA_ID, cursor.lastMediaId)
    }

    fun clear() {
        mmkv?.removeValueForKey(KEY_LAST_TIMESTAMP_SEC)
        mmkv?.removeValueForKey(KEY_LAST_MEDIA_ID)
    }
}
