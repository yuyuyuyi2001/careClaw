package com.shijing.xomniclaw.agent.tools.device.yolo

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * YOLO 输出解析、NMS 和坐标回映射。
 */
object UiYoloPostprocess {
    private fun sigmoid(value: Float): Float = 1f / (1f + exp(-value))

    data class RawDetection(
        val x1: Float,
        val y1: Float,
        val x2: Float,
        val y2: Float,
        val score: Float,
        val classId: Int
    )

    fun xywhToXyxy(centerX: Float, centerY: Float, width: Float, height: Float): FloatArray {
        val left = centerX - width / 2f
        val top = centerY - height / 2f
        val right = centerX + width / 2f
        val bottom = centerY + height / 2f
        return floatArrayOf(left, top, right, bottom)
    }

    /**
     * 将 letterbox 坐标还原到原始截图坐标系。
     */
    fun scaleBoxesXyxy(xyxy: FloatArray, params: UiLetterboxParams): FloatArray {
        val left = (xyxy[0] - params.padX) / params.ratio
        val top = (xyxy[1] - params.padY) / params.ratio
        val right = (xyxy[2] - params.padX) / params.ratio
        val bottom = (xyxy[3] - params.padY) / params.ratio
        val width = params.srcWidth.toFloat()
        val height = params.srcHeight.toFloat()
        return floatArrayOf(
            left.coerceIn(0f, width),
            top.coerceIn(0f, height),
            right.coerceIn(0f, width),
            bottom.coerceIn(0f, height)
        )
    }

    /**
     * 默认读取 channel-major 展平布局 [1, 4+nc, numAnchors]。
     */
    fun decodeRaw(
        raw: FloatArray,
        shape: LongArray,
        confThresh: Float,
        useSigmoidOnClass: Boolean = true,
        anchorMajor: Boolean = false
    ): List<RawDetection> {
        if (shape.size != 3) return emptyList()
        val batch = shape[0].toInt()
        val channels = shape[1].toInt()
        val anchorCount = shape[2].toInt()
        if (batch != 1) return emptyList()
        val classCount = channels - 4
        if (classCount <= 0) return emptyList()
        val stride = 4 + classCount

        fun channelMajor(channel: Int, anchor: Int): Float = raw[channel * anchorCount + anchor]
        fun anchorMajor(anchor: Int, channel: Int): Float = raw[anchor * stride + channel]

        val detections = ArrayList<RawDetection>(256)
        for (anchor in 0 until anchorCount) {
            var bestScore = -1f
            var bestClassId = -1
            for (classIndex in 0 until classCount) {
                val scoreLogit = if (anchorMajor) {
                    anchorMajor(anchor, 4 + classIndex)
                } else {
                    channelMajor(4 + classIndex, anchor)
                }
                val score = if (useSigmoidOnClass) sigmoid(scoreLogit) else scoreLogit
                if (score > bestScore) {
                    bestScore = score
                    bestClassId = classIndex
                }
            }
            if (bestScore < confThresh) continue

            val centerX = if (anchorMajor) anchorMajor(anchor, 0) else channelMajor(0, anchor)
            val centerY = if (anchorMajor) anchorMajor(anchor, 1) else channelMajor(1, anchor)
            val width = if (anchorMajor) anchorMajor(anchor, 2) else channelMajor(2, anchor)
            val height = if (anchorMajor) anchorMajor(anchor, 3) else channelMajor(3, anchor)
            val xyxy = xywhToXyxy(centerX, centerY, width, height)
            detections += RawDetection(
                x1 = xyxy[0],
                y1 = xyxy[1],
                x2 = xyxy[2],
                y2 = xyxy[3],
                score = bestScore,
                classId = bestClassId
            )
        }
        return detections
    }

    fun nmsXyxy(detections: List<RawDetection>, iouThresh: Float, maxDet: Int): List<RawDetection> {
        if (detections.isEmpty()) return emptyList()
        val sorted = detections.sortedByDescending { it.score }
        val kept = ArrayList<RawDetection>(min(maxDet, sorted.size))
        val suppressed = BooleanArray(sorted.size)
        for (index in sorted.indices) {
            if (suppressed[index]) continue
            val current = sorted[index]
            kept += current
            if (kept.size >= maxDet) break
            for (otherIndex in index + 1 until sorted.size) {
                if (suppressed[otherIndex]) continue
                if (iouXyxy(current, sorted[otherIndex]) > iouThresh) {
                    suppressed[otherIndex] = true
                }
            }
        }
        return kept
    }

    fun iouXyxy(first: RawDetection, second: RawDetection): Float {
        val interLeft = max(first.x1, second.x1)
        val interTop = max(first.y1, second.y1)
        val interRight = min(first.x2, second.x2)
        val interBottom = min(first.y2, second.y2)
        val intersection = max(0f, interRight - interLeft) * max(0f, interBottom - interTop)
        val areaA = max(0f, first.x2 - first.x1) * max(0f, first.y2 - first.y1)
        val areaB = max(0f, second.x2 - second.x1) * max(0f, second.y2 - second.y1)
        val union = areaA + areaB - intersection + 1e-6f
        return intersection / union
    }
}
