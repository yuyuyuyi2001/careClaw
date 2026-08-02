package com.shijing.xomniclaw.agent.tools.device

import com.tencent.mmkv.MMKV

/**
 * `device` 工具设置（YOLO 选项已随外围砍除移除，保留占位存储）。
 */
data class DeviceToolSettings(val unused: Boolean = false)

class DeviceToolSettingsStore(
    private val mmkv: MMKV? = MMKV.defaultMMKV()
) {
    fun load(): DeviceToolSettings = DeviceToolSettings()
    fun save(settings: DeviceToolSettings) {}
    fun update(transform: (DeviceToolSettings) -> DeviceToolSettings): DeviceToolSettings {
        val updated = transform(load())
        save(updated)
        return updated
    }
}
