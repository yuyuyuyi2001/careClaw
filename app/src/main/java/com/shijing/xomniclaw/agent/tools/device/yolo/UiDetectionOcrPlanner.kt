package com.shijing.xomniclaw.agent.tools.device.yolo

import kotlin.math.max

/**
 * OCR 规划器：
 * 1. 判断哪些检测框值得跑 OCR。
 * 2. 规划裁剪区域、padding 和最小放大倍率。
 */
data class UiDetectionOcrCropPlan(
    val cropLeft: Int,
    val cropTop: Int,
    val cropWidth: Int,
    val cropHeight: Int,
    val scaleMultiplier: Float
)

object UiDetectionOcrPlanner {
    private const val PADDING_RATIO = 0.08f
    private const val MIN_PADDING_PX = 6
    private const val MIN_EDGE_FOR_OCR = 64
    private val OCR_CLASS_IDS = setOf(7, 18) // EditText, TextButton

    fun shouldRunOcr(classId: Int, classCount: Int = UiYoloClassLabels.NUM_CLASSES): Boolean {
        if (classCount != UiYoloClassLabels.NUM_CLASSES) {
            return false
        }
        return classId in OCR_CLASS_IDS
    }

    fun buildCropPlan(
        imageWidth: Int,
        imageHeight: Int,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int
    ): UiDetectionOcrCropPlan? {
        if (imageWidth <= 0 || imageHeight <= 0) return null
        val safeLeft = left.coerceIn(0, imageWidth - 1)
        val safeTop = top.coerceIn(0, imageHeight - 1)
        val safeRight = right.coerceIn(safeLeft + 1, imageWidth)
        val safeBottom = bottom.coerceIn(safeTop + 1, imageHeight)
        val rawWidth = safeRight - safeLeft
        val rawHeight = safeBottom - safeTop
        if (rawWidth <= 0 || rawHeight <= 0) return null

        val padding = max((max(rawWidth, rawHeight) * PADDING_RATIO).toInt(), MIN_PADDING_PX)
        val cropLeft = (safeLeft - padding).coerceAtLeast(0)
        val cropTop = (safeTop - padding).coerceAtLeast(0)
        val cropRight = (safeRight + padding).coerceAtMost(imageWidth)
        val cropBottom = (safeBottom + padding).coerceAtMost(imageHeight)
        val cropWidth = cropRight - cropLeft
        val cropHeight = cropBottom - cropTop
        if (cropWidth <= 0 || cropHeight <= 0) return null

        val minEdge = minOf(cropWidth, cropHeight)
        val scaleMultiplier = if (minEdge >= MIN_EDGE_FOR_OCR) {
            1f
        } else {
            MIN_EDGE_FOR_OCR.toFloat() / minEdge.toFloat()
        }

        return UiDetectionOcrCropPlan(
            cropLeft = cropLeft,
            cropTop = cropTop,
            cropWidth = cropWidth,
            cropHeight = cropHeight,
            scaleMultiplier = scaleMultiplier
        )
    }
}
