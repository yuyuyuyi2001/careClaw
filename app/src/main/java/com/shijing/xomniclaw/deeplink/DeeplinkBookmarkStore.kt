package com.shijing.xomniclaw.deeplink

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.shijing.xomniclaw.deeplink.model.DeeplinkBookmark
import com.shijing.xomniclaw.util.MMKVKeys
import com.tencent.mmkv.MMKV
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Deeplink 收藏持久化存储。
 *
 * 直接基于 MMKV + Gson，避免引入额外数据库层，也能和项目现有配置体系保持一致。
 */
object DeeplinkBookmarkStore {
    private val gson = Gson()
    private val bookmarksFlow = MutableStateFlow(loadBookmarks())

    fun bookmarks(): StateFlow<List<DeeplinkBookmark>> = bookmarksFlow.asStateFlow()

    fun add(bookmark: DeeplinkBookmark): DeeplinkBookmark {
        val updated = bookmarksFlow.value
            .filterNot {
                it.packageName == bookmark.packageName &&
                    it.activityName == bookmark.activityName &&
                    it.dataUri == bookmark.dataUri
            }
            .toMutableList()
        updated.add(0, bookmark)
        save(updated)
        return bookmark
    }

    fun remove(bookmarkId: Long) {
        val updated = bookmarksFlow.value.filterNot { it.id == bookmarkId }
        save(updated)
    }

    fun clear() {
        save(emptyList())
    }

    private fun save(bookmarks: List<DeeplinkBookmark>) {
        MMKV.defaultMMKV().encode(MMKVKeys.DEEPLINK_BOOKMARKS_JSON.key, gson.toJson(bookmarks))
        bookmarksFlow.value = bookmarks
    }

    private fun loadBookmarks(): List<DeeplinkBookmark> {
        val json = MMKV.defaultMMKV().decodeString(MMKVKeys.DEEPLINK_BOOKMARKS_JSON.key)
            ?: return emptyList()
        val type = object : TypeToken<List<DeeplinkBookmark>>() {}.type
        return try {
            gson.fromJson<List<DeeplinkBookmark>>(json, type) ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }
}
