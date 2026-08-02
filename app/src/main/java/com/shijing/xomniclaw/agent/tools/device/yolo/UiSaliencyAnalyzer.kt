package com.shijing.xomniclaw.agent.tools.device.yolo

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 轻量级显著性分析器。
 *
 * 目标不是做论文级显著性建模，而是快速把“前景弹窗/主按钮”和“暗色背景干扰”
 * 区分开来，给 YOLO 结果增加一道注意力过滤。
 */
data class UiSaliencyAnalysis(
    val map: FloatArray,
    val width: Int,
    val height: Int,
    val foregroundThreshold: Float,
    val focusThreshold: Float
) {
    /**
     * 计算原图坐标框内的平均显著度，返回值范围 [0, 1]。
     */
    fun averageSaliency(
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        srcWidth: Int,
        srcHeight: Int
    ): Float {
        if (width <= 0 || height <= 0 || map.isEmpty()) return 0f
        val scaleX = width.toFloat() / srcWidth.coerceAtLeast(1).toFloat()
        val scaleY = height.toFloat() / srcHeight.coerceAtLeast(1).toFloat()
        val x0 = (left * scaleX).toInt().coerceIn(0, width - 1)
        val y0 = (top * scaleY).toInt().coerceIn(0, height - 1)
        val x1 = max(x0, (right * scaleX).toInt().coerceIn(0, width - 1))
        val y1 = max(y0, (bottom * scaleY).toInt().coerceIn(0, height - 1))
        var sum = 0f
        var count = 0
        for (y in y0..y1) {
            val rowOffset = y * width
            for (x in x0..x1) {
                sum += map[rowOffset + x]
                count++
            }
        }
        return if (count == 0) 0f else sum / count.toFloat()
    }
}

object UiSaliencyAnalyzer {
    private const val ANALYSIS_WIDTH = 96
    private const val ANALYSIS_HEIGHT = 160
    const val FOREGROUND_THRESHOLD = 0.30f
    const val FOCUS_THRESHOLD = 0.70f

    fun analyze(bitmap: Bitmap): UiSaliencyAnalysis {
        val scaled = Bitmap.createScaledBitmap(bitmap, ANALYSIS_WIDTH, ANALYSIS_HEIGHT, true)
        try {
            val pixelCount = ANALYSIS_WIDTH * ANALYSIS_HEIGHT
            val pixels = IntArray(pixelCount)
            scaled.getPixels(pixels, 0, ANALYSIS_WIDTH, 0, 0, ANALYSIS_WIDTH, ANALYSIS_HEIGHT)

            val luminance = FloatArray(pixelCount)
            val saturation = FloatArray(pixelCount)
            val red = FloatArray(pixelCount)
            val green = FloatArray(pixelCount)
            val blue = FloatArray(pixelCount)

            var meanLuma = 0f
            var meanSat = 0f
            var meanR = 0f
            var meanG = 0f
            var meanB = 0f
            for (index in pixels.indices) {
                val color = pixels[index]
                val r = ((color shr 16) and 0xFF) / 255f
                val g = ((color shr 8) and 0xFF) / 255f
                val b = (color and 0xFF) / 255f
                val maxChannel = max(r, max(g, b))
                val minChannel = min(r, min(g, b))
                val luma = 0.299f * r + 0.587f * g + 0.114f * b
                val sat = if (maxChannel <= 1e-6f) 0f else (maxChannel - minChannel) / maxChannel
                red[index] = r
                green[index] = g
                blue[index] = b
                luminance[index] = luma
                saturation[index] = sat
                meanR += r
                meanG += g
                meanB += b
                meanLuma += luma
                meanSat += sat
            }
            val invCount = 1f / pixelCount.toFloat()
            meanR *= invCount
            meanG *= invCount
            meanB *= invCount
            meanLuma *= invCount
            meanSat *= invCount

            val baseSaliency = FloatArray(pixelCount)
            val centerWeight = buildCenterPrior(ANALYSIS_WIDTH, ANALYSIS_HEIGHT)
            for (index in baseSaliency.indices) {
                val lumaContrast = abs(luminance[index] - meanLuma)
                val satContrast = abs(saturation[index] - meanSat)
                val colorDistance = sqrt(
                    squared(red[index] - meanR) +
                        squared(green[index] - meanG) +
                        squared(blue[index] - meanB)
                ) / 1.732f
                // 用亮度、色彩和中心先验组合出一个“近似显著性”分数。
                baseSaliency[index] =
                    lumaContrast * 0.45f +
                    colorDistance * 0.35f +
                    satContrast * 0.10f +
                    centerWeight[index] * 0.10f
            }

            val blurred = boxBlur(baseSaliency, ANALYSIS_WIDTH, ANALYSIS_HEIGHT, radius = 4)
            val expanded = boxBlur(blurred, ANALYSIS_WIDTH, ANALYSIS_HEIGHT, radius = 8)
            val normalized = normalize(expanded)
            return UiSaliencyAnalysis(
                map = normalized,
                width = ANALYSIS_WIDTH,
                height = ANALYSIS_HEIGHT,
                foregroundThreshold = FOREGROUND_THRESHOLD,
                focusThreshold = FOCUS_THRESHOLD
            )
        } finally {
            if (!scaled.isRecycled) {
                scaled.recycle()
            }
        }
    }

    private fun buildCenterPrior(width: Int, height: Int): FloatArray {
        val output = FloatArray(width * height)
        val cx = (width - 1) / 2f
        val cy = (height - 1) / 2f
        val sigmaX = width * 0.24f
        val sigmaY = height * 0.24f
        for (y in 0 until height) {
            for (x in 0 until width) {
                val dx = (x - cx) / sigmaX
                val dy = (y - cy) / sigmaY
                output[y * width + x] = exp(-(dx * dx + dy * dy) / 2f)
            }
        }
        return output
    }

    private fun boxBlur(input: FloatArray, width: Int, height: Int, radius: Int): FloatArray {
        if (radius <= 0) return input.copyOf()
        val output = FloatArray(input.size)
        for (y in 0 until height) {
            for (x in 0 until width) {
                var sum = 0f
                var count = 0
                val top = max(0, y - radius)
                val bottom = min(height - 1, y + radius)
                val left = max(0, x - radius)
                val right = min(width - 1, x + radius)
                for (yy in top..bottom) {
                    val rowOffset = yy * width
                    for (xx in left..right) {
                        sum += input[rowOffset + xx]
                        count++
                    }
                }
                output[y * width + x] = if (count == 0) 0f else sum / count.toFloat()
            }
        }
        return output
    }

    private fun normalize(values: FloatArray): FloatArray {
        if (values.isEmpty()) return values
        var minValue = Float.MAX_VALUE
        var maxValue = Float.MIN_VALUE
        for (value in values) {
            minValue = min(minValue, value)
            maxValue = max(maxValue, value)
        }
        val range = maxValue - minValue
        if (range <= 1e-6f) {
            return FloatArray(values.size) { 0f }
        }
        return FloatArray(values.size) { index ->
            ((values[index] - minValue) / range).coerceIn(0f, 1f)
        }
    }

    private fun squared(value: Float): Float = value * value
}
