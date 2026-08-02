package com.shijing.xomniclaw.agent.tools.device.yolo

/**
 * Snapshot 阶段新增的 YOLO 检测数据模型。
 *
 * 这里单独建模而不是复用 RefNode，避免把视觉检测结果强行塞进无障碍 ref 体系。
 */
data class UiDetectionNode(
    val classId: Int,
    val className: String,
    val score: Float,
    val saliencyScore: Float = 1f,
    val isHighSaliency: Boolean = false,
    val recognizedText: String? = null,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2
}

data class UiFusedDetectionNode(
    val detection: UiDetectionNode,
    val matchedAccessibilityRef: String? = null,
    val fusedText: String? = null,
    val fusedContentDesc: String? = null,
    val fusedResourceId: String? = null,
    val fusedClassName: String? = null,
    val fusedPackageName: String? = null,
    val matchMode: String? = null,
    val matchIou: Float = 0f,
    val centerDistancePx: Float? = null
)

data class UiDetectionTiming(
    val preprocessMs: Long,
    val inferenceMs: Long,
    val postprocessMs: Long
)

data class UiDetectionSnapshotResult(
    val status: String,
    val screenshotPath: String? = null,
    val rawDebugImagePath: String? = null,
    val fusedDebugImagePath: String? = null,
    val modelAssetName: String = UiYoloClassLabels.MODEL_ASSET_NAME,
    val rawDetections: List<UiDetectionNode> = emptyList(),
    val fusedDetections: List<UiFusedDetectionNode> = emptyList(),
    val timing: UiDetectionTiming? = null,
    val message: String? = null
)

/**
 * 当前接入的是固定 21 类 Android UI 检测模型。
 *
 * 如果后续替换为不同 nc 的模型，这里需要和权重同步调整。
 */
object UiYoloClassLabels {
    const val MODEL_ASSET_NAME = "android_ui_detection.onnx"
    const val INPUT_SIZE = 640
    const val DEFAULT_CONFIDENCE = 0.15f
    const val DEFAULT_IOU = 0.45f
    const val MAX_DETECTIONS = 50
    const val NUM_CLASSES = 21
    private val ALLOWED_INTERACTIVE_CLASS_IDS = setOf(7, 8, 18) // EditText, Icon, TextButton

    private val NAMES_EN = arrayOf(
        "BackgroundImage", "Bottom_Navigation", "Card", "CheckBox", "Checkbox", "CheckedTextView",
        "Drawer", "EditText", "Icon", "Image", "Map", "Modal", "Multi_Tab", "PageIndicator",
        "Remember", "Spinner", "Switch", "Text", "TextButton", "Toolbar", "UpperTaskBar"
    )

    private val NAMES_ZH = arrayOf(
        "背景图", "底部导航", "卡片", "复选框", "复选框", "可选中文本",
        "抽屉", "输入框", "图标", "图片", "地图", "弹窗/模态", "多标签", "页面指示器",
        "记住选项", "下拉框", "开关", "文本", "文字按钮", "工具栏", "顶栏"
    )

    init {
        require(NAMES_EN.size == NUM_CLASSES && NAMES_ZH.size == NUM_CLASSES) {
            "YOLO UI 类别名表必须与 $NUM_CLASSES 类模型一致"
        }
    }

    fun displayName(classId: Int, classCount: Int = NUM_CLASSES): String {
        if (classId !in 0 until classCount) {
            return "未知类别#$classId"
        }
        if (classCount != NUM_CLASSES || classId !in NAMES_EN.indices) {
            return "类别#$classId（nc=$classCount）"
        }
        return "${NAMES_ZH[classId]}/${NAMES_EN[classId]}"
    }

    /**
     * 当前 YOLO 结果仅保留对交互最有帮助的三类：
     * EditText、Icon、TextButton。
     *
     * 这样可以减少纯装饰性类别进入融合链路，降低误匹配和上下文噪声。
     */
    fun isAllowedInteractiveClass(classId: Int, classCount: Int = NUM_CLASSES): Boolean {
        if (classCount != NUM_CLASSES) {
            // 若后续替换模型导致类别表变化，先不要误过滤未知类别。
            return true
        }
        return classId in ALLOWED_INTERACTIVE_CLASS_IDS
    }
}
