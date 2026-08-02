package com.shijing.xomniclaw.ui.floatwindow

import com.tencent.mmkv.MMKV

/**
 * 持久化替身浮窗的尺寸，避免用户每次进入替身模式都重新调整。
 */
object ScreenCompanionFloatPrefs {
    private const val KEY_WIDTH_PX = "screen_companion_float_width_px"
    private const val KEY_HEIGHT_PX = "screen_companion_float_height_px"

    data class FloatSize(
        val widthPx: Int,
        val heightPx: Int
    )

    private fun mmkv(): MMKV = MMKV.defaultMMKV()

    fun loadSize(defaultWidthPx: Int, defaultHeightPx: Int): FloatSize {
        val store = mmkv()
        val width = store.decodeInt(KEY_WIDTH_PX, defaultWidthPx)
        val height = store.decodeInt(KEY_HEIGHT_PX, defaultHeightPx)
        return FloatSize(
            widthPx = sanitize(width, defaultWidthPx),
            heightPx = sanitize(height, defaultHeightPx)
        )
    }

    fun saveSize(size: FloatSize) {
        val store = mmkv()
        store.encode(KEY_WIDTH_PX, size.widthPx)
        store.encode(KEY_HEIGHT_PX, size.heightPx)
    }

    private fun sanitize(value: Int, fallback: Int): Int {
        return if (value > 0) value else fallback
    }
}
