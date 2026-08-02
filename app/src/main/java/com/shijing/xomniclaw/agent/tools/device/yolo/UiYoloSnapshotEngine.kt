package com.shijing.xomniclaw.agent.tools.device.yolo

import android.content.Context
import android.util.Log
import com.shijing.xomniclaw.DeviceController
import com.shijing.xomniclaw.accessibility.service.ViewNode
import com.shijing.xomniclaw.agent.tools.device.DeviceToolSettingsStore
import com.shijing.xomniclaw.agent.tools.device.RefNode

/**
 * Snapshot 阶段的 YOLO 入口。
 *
 * 负责：
 * 1. 复用现有截图能力拿到当前屏幕 Bitmap。
 * 2. 调用本地 ONNX 检测。
 * 3. 将检测框与 Accessibility 节点融合。
 */
class UiYoloSnapshotEngine(private val context: Context) {
    companion object {
        private const val TAG = "UiYoloSnapshotEngine"
    }

    @Volatile
    private var detector: UiYoloOnnxDetector? = null

    @Volatile
    private var initErrorMessage: String? = null

    suspend fun buildSnapshotResult(
        viewNodes: List<ViewNode>,
        refNodes: List<RefNode>
    ): UiDetectionSnapshotResult {
        val screenshotResult = try {
            DeviceController.getScreenshot(context)
        } catch (error: Exception) {
            Log.w(TAG, "capture screenshot failed: ${error.message}")
            null
        } ?: return UiDetectionSnapshotResult(
            status = "screenshot_unavailable",
            message = "YOLO 检测未执行：截图失败或未授予录屏权限。"
        )

        val (bitmap, screenshotPath) = screenshotResult
        try {
            val localDetector = obtainDetector() ?: return UiDetectionSnapshotResult(
                status = "model_unavailable",
                screenshotPath = screenshotPath,
                message = initErrorMessage ?: "YOLO 模型未初始化成功。"
            )

            val detectResult = localDetector.detect(bitmap)
            val saliencyAnalysis = UiSaliencyAnalyzer.analyze(bitmap)
            val rawDetections = detectResult.detections
                .filter { detection ->
                    UiYoloClassLabels.isAllowedInteractiveClass(
                        classId = detection.classId,
                        classCount = detectResult.classCount
                    )
                }
                .map { detection ->
                    val saliencyScore = saliencyAnalysis.averageSaliency(
                        left = detection.x1.toInt(),
                        top = detection.y1.toInt(),
                        right = detection.x2.toInt(),
                        bottom = detection.y2.toInt(),
                        srcWidth = bitmap.width,
                        srcHeight = bitmap.height
                    )
                    UiDetectionNode(
                        classId = detection.classId,
                        className = UiYoloClassLabels.displayName(detection.classId, detectResult.classCount),
                        score = detection.score,
                        saliencyScore = saliencyScore,
                        isHighSaliency = saliencyScore >= saliencyAnalysis.focusThreshold,
                        left = detection.x1.toInt(),
                        top = detection.y1.toInt(),
                        right = detection.x2.toInt(),
                        bottom = detection.y2.toInt()
                    )
                }
                .filter { detection ->
                    detection.saliencyScore >= saliencyAnalysis.foregroundThreshold
                }
            val ocrDetections = UiDetectionOcrEngine.recognizeDetections(
                bitmap = bitmap,
                detections = rawDetections,
                classCount = detectResult.classCount
            )
            val fusedDetections = UiDetectionFusionEngine.fuse(ocrDetections, viewNodes, refNodes)
            val debugImages = UiDetectionDebugImageSaver.saveAnnotatedImages(
                bitmap = bitmap,
                saliencyAnalysis = saliencyAnalysis,
                rawDetections = ocrDetections,
                fusedDetections = fusedDetections,
                status = "ok"
            )
            return UiDetectionSnapshotResult(
                status = "ok",
                screenshotPath = screenshotPath,
                rawDebugImagePath = debugImages.rawImagePath,
                fusedDebugImagePath = debugImages.fusedImagePath,
                rawDetections = ocrDetections,
                fusedDetections = fusedDetections,
                timing = detectResult.timing
            )
        } catch (error: Exception) {
            Log.w(TAG, "yolo detect failed: ${error.message}")
            return UiDetectionSnapshotResult(
                status = "detect_failed",
                screenshotPath = screenshotPath,
                message = "YOLO 检测失败：${error.message}"
            )
        } finally {
            if (!bitmap.isRecycled) {
                bitmap.recycle()
            }
        }
    }

    private fun obtainDetector(): UiYoloOnnxDetector? {
        val settings = DeviceToolSettingsStore().load()
        detector?.let {
            // 每次 snapshot 都同步最新阈值，确保设置页滑块调整后立即生效。
            it.confThreshold = settings.yoloConfidenceThreshold
            it.iouThreshold = settings.yoloIouThreshold
            return it
        }
        synchronized(this) {
            detector?.let {
                it.confThreshold = settings.yoloConfidenceThreshold
                it.iouThreshold = settings.yoloIouThreshold
                return it
            }
            return try {
                UiYoloOnnxDetector(
                    context = context,
                    initialConf = settings.yoloConfidenceThreshold,
                    initialIou = settings.yoloIouThreshold
                ).also {
                    detector = it
                    initErrorMessage = null
                }
            } catch (error: Exception) {
                initErrorMessage = buildString {
                    append("YOLO 模型不可用：未在 assets 中找到 `")
                    append(UiYoloClassLabels.MODEL_ASSET_NAME)
                    append("`，或模型初始化失败。")
                }
                Log.w(TAG, "init detector failed: ${error.message}")
                null
            }
        }
    }
}
