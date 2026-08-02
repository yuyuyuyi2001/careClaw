package com.shijing.xomniclaw.agent.tools.device.yolo

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.Log
import com.shijing.xomniclaw.util.PromptArtifactNaming
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.min

/**
 * 将原始 YOLO 检测框绘制到截图上并保存，便于离线排查检测框是否符合预期。
 *
 * 注意：当前磁盘调试产物与喂给大模型的文本保持同一语义层级，
 * 这里只保存原始检测结果，不再额外输出融合后的可视化图片。
 */
object UiDetectionDebugImageSaver {
    private const val TAG = "UiDetectionDebugImageSaver"
    private const val MAX_LABEL_CHARS = 36

    data class SavedDebugImages(
        val rawImagePath: String?,
        val fusedImagePath: String?
    )

    @Suppress("UNUSED_PARAMETER")
    fun saveAnnotatedImages(
        bitmap: Bitmap,
        saliencyAnalysis: UiSaliencyAnalysis,
        rawDetections: List<UiDetectionNode>,
        fusedDetections: List<UiFusedDetectionNode>,
        status: String
    ): SavedDebugImages {
        return runCatching {
            val rawImagePath = saveRawAnnotatedImage(bitmap, saliencyAnalysis, rawDetections, status)
            SavedDebugImages(
                rawImagePath = rawImagePath,
                // 保留字段兼容现有返回结构，但不再生成融合图。
                fusedImagePath = null
            )
        }.onFailure { error ->
            Log.w(TAG, "save annotated images failed: ${error.message}")
        }.getOrElse { SavedDebugImages(rawImagePath = null, fusedImagePath = null) }
    }

    private fun saveRawAnnotatedImage(
        bitmap: Bitmap,
        saliencyAnalysis: UiSaliencyAnalysis,
        rawDetections: List<UiDetectionNode>,
        status: String
    ): String? {
        val outputBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        return try {
            val canvas = Canvas(outputBitmap)
            val (boxPaint, textPaint, bgPaint, strokeWidth, textSize, textPadding) = createDrawingContext(bitmap)
            drawSaliencyOverlay(canvas, bitmap, saliencyAnalysis)
            val highSaliencyCount = rawDetections.count { it.isHighSaliency }
            val header = buildString {
                append("YOLO raw status=").append(status)
                append(" raw=").append(rawDetections.size)
                append(" focus=").append(highSaliencyCount)
                append(" fg_thresh=").append("%.2f".format(saliencyAnalysis.foregroundThreshold))
                append(" focus_thresh=").append("%.2f".format(saliencyAnalysis.focusThreshold))
            }
            drawLabel(
                canvas = canvas,
                text = header,
                left = strokeWidth,
                top = strokeWidth,
                textPaint = textPaint,
                bgPaint = bgPaint.apply { color = Color.parseColor("#AA111111") },
                textPadding = textPadding
            )

            rawDetections.forEachIndexed { index, detection ->
                val color = pickColor(detection.classId, index)
                boxPaint.color = color
                bgPaint.color = withAlpha(color, 0xD8)
                val rect = RectF(
                    detection.left.toFloat(),
                    detection.top.toFloat(),
                    detection.right.toFloat(),
                    detection.bottom.toFloat()
                )
                canvas.drawRect(rect, boxPaint)
                drawLabel(
                    canvas = canvas,
                    text = buildRawLabel(detection, index + 1),
                    left = rect.left,
                    top = rect.top - textSize - textPadding * 1.4f,
                    textPaint = textPaint,
                    bgPaint = bgPaint,
                    textPadding = textPadding
                )
            }

            saveBitmap(outputBitmap, "yolo_raw_detection_summary")
        } finally {
            outputBitmap.recycle()
        }
    }

    private data class DrawingContext(
        val boxPaint: Paint,
        val textPaint: Paint,
        val bgPaint: Paint,
        val strokeWidth: Float,
        val textSize: Float,
        val textPadding: Float
    )

    private fun createDrawingContext(bitmap: Bitmap): DrawingContext {
        val strokeWidth = max(min(bitmap.width, bitmap.height) / 320f, 2f)
        val textSize = max(min(bitmap.width, bitmap.height) / 36f, 18f)
        val textPadding = max(textSize * 0.28f, 8f)
        val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            this.strokeWidth = strokeWidth
        }
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            this.textSize = textSize
            style = Paint.Style.FILL
        }
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
        return DrawingContext(boxPaint, textPaint, bgPaint, strokeWidth, textSize, textPadding)
    }

    private fun buildRawLabel(detection: UiDetectionNode, index: Int): String {
        val scoreText = "%.2f".format(detection.score)
        val saliencyText = "%.2f".format(detection.saliencyScore)
        val focusTag = if (detection.isHighSaliency) " !" else ""
        val ocrSuffix = detection.recognizedText
            ?.takeIf { it.isNotBlank() }
            ?.let { " / ocr=${it.take(12)}" }
            .orEmpty()
        return "#$index ${detection.className.take(MAX_LABEL_CHARS)} ($scoreText / sal=$saliencyText$focusTag$ocrSuffix)"
    }

    private fun saveBitmap(bitmap: Bitmap, suffix: String): String? {
        val dir = File("/sdcard/.xomniclaw/workspace/screenshots")
        val file = PromptArtifactNaming.buildScreenshotFile(dir, suffix)
        FileOutputStream(file).use { output ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
        }
        return file.absolutePath
    }

    /**
     * 将显著性热力图铺到底图上，同时压暗低显著度区域，便于区分前景弹窗和背景干扰。
     */
    private fun drawSaliencyOverlay(
        canvas: Canvas,
        bitmap: Bitmap,
        analysis: UiSaliencyAnalysis
    ) {
        val overlay = Bitmap.createBitmap(analysis.width, analysis.height, Bitmap.Config.ARGB_8888)
        try {
            val pixels = IntArray(analysis.width * analysis.height)
            for (index in analysis.map.indices) {
                val value = analysis.map[index].coerceIn(0f, 1f)
                pixels[index] = heatmapColor(value)
            }
            overlay.setPixels(pixels, 0, analysis.width, 0, 0, analysis.width, analysis.height)
            val scaledOverlay = Bitmap.createScaledBitmap(overlay, bitmap.width, bitmap.height, true)
            try {
                val dimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.parseColor("#66000000")
                    style = Paint.Style.FILL
                }
                canvas.drawRect(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat(), dimPaint)
                val overlayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    alpha = 170
                }
                canvas.drawBitmap(scaledOverlay, 0f, 0f, overlayPaint)
            } finally {
                if (!scaledOverlay.isRecycled) {
                    scaledOverlay.recycle()
                }
            }
        } finally {
            if (!overlay.isRecycled) {
                overlay.recycle()
            }
        }
    }

    private fun drawLabel(
        canvas: Canvas,
        text: String,
        left: Float,
        top: Float,
        textPaint: Paint,
        bgPaint: Paint,
        textPadding: Float
    ) {
        val safeLeft = left.coerceAtLeast(0f)
        val metrics = textPaint.fontMetrics
        val textWidth = textPaint.measureText(text)
        val textHeight = metrics.descent - metrics.ascent
        val safeTop = top.coerceAtLeast(0f)
        val bgRect = RectF(
            safeLeft,
            safeTop,
            safeLeft + textWidth + textPadding * 2,
            safeTop + textHeight + textPadding * 2
        )
        canvas.drawRoundRect(bgRect, textPadding, textPadding, bgPaint)
        val baseline = bgRect.top + textPadding - metrics.ascent
        canvas.drawText(text, bgRect.left + textPadding, baseline, textPaint)
    }

    private fun pickColor(classId: Int, index: Int): Int {
        val palette = intArrayOf(
            Color.parseColor("#FF5252"),
            Color.parseColor("#FF9800"),
            Color.parseColor("#FFD54F"),
            Color.parseColor("#66BB6A"),
            Color.parseColor("#26C6DA"),
            Color.parseColor("#42A5F5"),
            Color.parseColor("#7E57C2"),
            Color.parseColor("#EC407A")
        )
        val safeIndex = (classId.takeIf { it >= 0 } ?: index) % palette.size
        return palette[safeIndex]
    }

    private fun withAlpha(color: Int, alpha: Int): Int {
        return Color.argb(alpha.coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))
    }

    private fun heatmapColor(value: Float): Int {
        val clamped = value.coerceIn(0f, 1f)
        val red = (255f * smoothstep(0.45f, 1f, clamped)).toInt().coerceIn(0, 255)
        val green = (255f * smoothstep(0.15f, 0.85f, clamped)).toInt().coerceIn(0, 255)
        val blue = (255f * (1f - smoothstep(0.25f, 0.75f, clamped))).toInt().coerceIn(0, 255)
        val alpha = (255f * smoothstep(0.08f, 1f, clamped)).toInt().coerceIn(0, 255)
        return Color.argb(alpha, red, green, blue)
    }

    private fun smoothstep(edge0: Float, edge1: Float, value: Float): Float {
        val t = ((value - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }
}
