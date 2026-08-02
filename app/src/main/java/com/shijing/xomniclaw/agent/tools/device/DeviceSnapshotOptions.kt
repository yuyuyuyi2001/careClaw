package com.shijing.xomniclaw.agent.tools.device

/**
 * 集中管理 snapshot 的可选增强能力，避免把参数解析散落在 DeviceTool 中。
 */
object DeviceSnapshotOptions {

    private const val KEY_INCLUDE_YOLO_FUSED_TREE = "include_yolo_fused_tree"

    /**
     * `yolo_fused_tree` 支持两层来源：
     * 1. 工具入参显式指定；
     * 2. 菜单栏设置中的默认值。
     */
    fun shouldIncludeYoloFusedTree(
        args: Map<String, Any?>,
        defaultEnabled: Boolean
    ): Boolean {
        val explicitValue = args[KEY_INCLUDE_YOLO_FUSED_TREE]
        return if (explicitValue is Boolean) {
            explicitValue
        } else {
            defaultEnabled
        }
    }
}
