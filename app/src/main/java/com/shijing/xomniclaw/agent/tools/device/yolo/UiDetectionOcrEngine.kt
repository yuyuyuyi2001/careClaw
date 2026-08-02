package com.shijing.xomniclaw.agent.tools.device.yolo

import android.graphics.Bitmap
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.tasks.await
import kotlin.math.roundToInt

/**
 * 基于 ML Kit 中文 OCR 的框内文字识别器。
 *
 * 约束：
 * 1. 不改 YOLO 推理和 NMS。
 * 2. 仅对需要读字的检测框跑 OCR，避免给图标类引入噪声。
 * 3. 通过单例复用 TextRecognizer，减少重复初始化成本。
 */
object UiDetectionOcrEngine {
    private const val TAG = "UiDetectionOcrEngine"

    @Volatile
    private var recognizer: TextRecognizer? = null

    suspend fun recognizeDetections(
        bitmap: Bitmap,
        detections: List<UiDetectionNode>,
        classCount: Int
    ): List<UiDetectionNode> {
        if (detections.isEmpty()) return detections
        val localRecognizer = obtainRecognizer()
        return detections.map { detection ->
            if (!UiDetectionOcrPlanner.shouldRunOcr(detection.classId, classCount)) {
                detection.copy(recognizedText = null)
            } else {
                val recognizedText = runCatching {
                    recognizeSingleDetection(localRecognizer, bitmap, detection)
                }.onFailure { error ->
                    Log.w(TAG, "ocr failed for classId=${detection.classId}: ${error.message}")
                }.getOrDefault("")
                detection.copy(recognizedText = recognizedText)
            }
        }
    }

    fun close() {
        synchronized(this) {
            recognizer?.close()
            recognizer = null
        }
    }

    private fun obtainRecognizer(): TextRecognizer {
        recognizer?.let { return it }
        synchronized(this) {
            recognizer?.let { return it }
            return TextRecognition.getClient(
                ChineseTextRecognizerOptions.Builder().build()
            ).also { recognizer = it }
        }
    }

    private suspend fun recognizeSingleDetection(
        recognizer: TextRecognizer,
        bitmap: Bitmap,
        detection: UiDetectionNode
    ): String {
        val cropPlan = UiDetectionOcrPlanner.buildCropPlan(
            imageWidth = bitmap.width,
            imageHeight = bitmap.height,
            left = detection.left,
            top = detection.top,
            right = detection.right,
            bottom = detection.bottom
        ) ?: return ""
        val croppedBitmap = Bitmap.createBitmap(
            bitmap,
            cropPlan.cropLeft,
            cropPlan.cropTop,
            cropPlan.cropWidth,
            cropPlan.cropHeight
        )
        val ocrBitmap = if (cropPlan.scaleMultiplier > 1f) {
            val scaledWidth = (cropPlan.cropWidth * cropPlan.scaleMultiplier).roundToInt().coerceAtLeast(cropPlan.cropWidth)
            val scaledHeight = (cropPlan.cropHeight * cropPlan.scaleMultiplier).roundToInt().coerceAtLeast(cropPlan.cropHeight)
            Bitmap.createScaledBitmap(croppedBitmap, scaledWidth, scaledHeight, true)
        } else {
            croppedBitmap
        }
        return try {
            val image = InputImage.fromBitmap(ocrBitmap, 0)
            val result = recognizer.process(image).await()
            normalizeRecognizedText(result.text)
        } finally {
            if (ocrBitmap !== croppedBitmap && !ocrBitmap.isRecycled) {
                ocrBitmap.recycle()
            }
            if (!croppedBitmap.isRecycled) {
                croppedBitmap.recycle()
            }
        }
    }

    private fun normalizeRecognizedText(rawText: String): String {
        return rawText
            .replace('\r', '\n')
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString(separator = " ")
    }
}
