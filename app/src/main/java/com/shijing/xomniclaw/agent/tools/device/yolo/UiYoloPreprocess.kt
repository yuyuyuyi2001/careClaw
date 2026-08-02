package com.shijing.xomniclaw.agent.tools.device.yolo

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Letterbox 与 NCHW 预处理，和样例工程保持一致。
 */
data class UiLetterboxParams(
    val ratio: Float,
    val padX: Float,
    val padY: Float,
    val srcWidth: Int,
    val srcHeight: Int,
    val dstSize: Int
)

object UiYoloPreprocess {
    private const val PAD_COLOR = 114

    /**
     * 将原图缩放到固定正方形输入，保留宽高比并记录逆变换参数。
     */
    fun letterbox(bitmap: Bitmap, dstSize: Int = UiYoloClassLabels.INPUT_SIZE): Pair<Bitmap, UiLetterboxParams> {
        val width = bitmap.width
        val height = bitmap.height
        val ratio = min(dstSize / width.toFloat(), dstSize / height.toFloat())
        val newWidth = (width * ratio).roundToInt().coerceAtLeast(1)
        val newHeight = (height * ratio).roundToInt().coerceAtLeast(1)
        val padX = (dstSize - newWidth) / 2f
        val padY = (dstSize - newHeight) / 2f

        val scaled = Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
        val output = Bitmap.createBitmap(dstSize, dstSize, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        canvas.drawColor(Color.rgb(PAD_COLOR, PAD_COLOR, PAD_COLOR))
        canvas.drawBitmap(scaled, padX, padY, null)
        if (scaled !== bitmap && !scaled.isRecycled) {
            scaled.recycle()
        }

        return output to UiLetterboxParams(
            ratio = ratio,
            padX = padX,
            padY = padY,
            srcWidth = width,
            srcHeight = height,
            dstSize = dstSize
        )
    }

    /**
     * Bitmap → float NCHW，通道顺序 R/G/B，值域 [0,1]。
     */
    fun bitmapToNchw01(bitmap: Bitmap, size: Int): FloatArray {
        val pixels = IntArray(size * size)
        bitmap.getPixels(pixels, 0, size, 0, 0, size, size)
        val output = FloatArray(3 * size * size)
        val planeSize = size * size
        for (index in pixels.indices) {
            val pixel = pixels[index]
            output[index] = ((pixel shr 16) and 0xFF) / 255f
            output[planeSize + index] = ((pixel shr 8) and 0xFF) / 255f
            output[2 * planeSize + index] = (pixel and 0xFF) / 255f
        }
        return output
    }
}
