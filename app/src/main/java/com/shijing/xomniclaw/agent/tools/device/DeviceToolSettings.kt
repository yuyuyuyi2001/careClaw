package com.shijing.xomniclaw.agent.tools.device

import com.shijing.xomniclaw.util.MMKVKeys
import com.shijing.xomniclaw.agent.tools.device.yolo.UiYoloClassLabels
import com.tencent.mmkv.MMKV

/**
 * `device` 工具相关设置。
 *
 * 这里单独抽成存储层，避免 UI 和 Tool 直接散落读写 MMKV key。
 */
object DeviceYoloThresholdConfig {
    const val MIN_CONFIDENCE = 0.001f
    const val MAX_CONFIDENCE = 0.999f
    const val MIN_IOU = 0.05f
    const val MAX_IOU = 0.95f
    const val DEFAULT_CONFIDENCE = UiYoloClassLabels.DEFAULT_CONFIDENCE
    const val DEFAULT_IOU = UiYoloClassLabels.DEFAULT_IOU

    fun normalizeConfidence(value: Float): Float = value.coerceIn(MIN_CONFIDENCE, MAX_CONFIDENCE)

    fun normalizeIou(value: Float): Float = value.coerceIn(MIN_IOU, MAX_IOU)
}

data class DeviceToolSettings(
    val includeYoloFusedTreeByDefault: Boolean = false,
    val yoloConfidenceThreshold: Float = DeviceYoloThresholdConfig.DEFAULT_CONFIDENCE,
    val yoloIouThreshold: Float = DeviceYoloThresholdConfig.DEFAULT_IOU
)

class DeviceToolSettingsStore(
    private val mmkv: MMKV? = MMKV.defaultMMKV()
) {
    fun load(): DeviceToolSettings {
        return DeviceToolSettings(
            includeYoloFusedTreeByDefault = mmkv?.decodeBool(
                MMKVKeys.DEVICE_SNAPSHOT_INCLUDE_YOLO_FUSED_TREE.key,
                false
            ) ?: false,
            yoloConfidenceThreshold = DeviceYoloThresholdConfig.normalizeConfidence(
                mmkv?.decodeFloat(
                    MMKVKeys.DEVICE_SNAPSHOT_YOLO_CONFIDENCE_THRESHOLD.key,
                    DeviceYoloThresholdConfig.DEFAULT_CONFIDENCE
                ) ?: DeviceYoloThresholdConfig.DEFAULT_CONFIDENCE
            ),
            yoloIouThreshold = DeviceYoloThresholdConfig.normalizeIou(
                mmkv?.decodeFloat(
                    MMKVKeys.DEVICE_SNAPSHOT_YOLO_IOU_THRESHOLD.key,
                    DeviceYoloThresholdConfig.DEFAULT_IOU
                ) ?: DeviceYoloThresholdConfig.DEFAULT_IOU
            )
        )
    }

    fun save(settings: DeviceToolSettings) {
        val normalized = settings.copy(
            yoloConfidenceThreshold = DeviceYoloThresholdConfig.normalizeConfidence(settings.yoloConfidenceThreshold),
            yoloIouThreshold = DeviceYoloThresholdConfig.normalizeIou(settings.yoloIouThreshold)
        )
        mmkv?.encode(
            MMKVKeys.DEVICE_SNAPSHOT_INCLUDE_YOLO_FUSED_TREE.key,
            normalized.includeYoloFusedTreeByDefault
        )
        mmkv?.encode(
            MMKVKeys.DEVICE_SNAPSHOT_YOLO_CONFIDENCE_THRESHOLD.key,
            normalized.yoloConfidenceThreshold
        )
        mmkv?.encode(
            MMKVKeys.DEVICE_SNAPSHOT_YOLO_IOU_THRESHOLD.key,
            normalized.yoloIouThreshold
        )
    }

    fun update(transform: (DeviceToolSettings) -> DeviceToolSettings): DeviceToolSettings {
        val updated = transform(load())
        save(updated)
        return updated
    }
}
