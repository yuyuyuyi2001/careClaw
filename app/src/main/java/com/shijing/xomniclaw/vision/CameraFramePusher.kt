package com.shijing.xomniclaw.vision

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 摄像头帧推送器
 * 使用 CameraX 捕获预览帧，仅写入 [VisionFrameBuffer] 供端侧视觉链路消费（不再上报远端 Hub）。
 */
class CameraFramePusher(private val context: Context) {

    companion object {
        private const val TAG = "CameraFramePusher"
        private const val DEFAULT_FPS = 1
        private const val DEFAULT_JPEG_QUALITY = 80
        private val GLOBAL_CAMERA_RUNNING = AtomicBoolean(false)

        fun isAnyCameraRunning(): Boolean = GLOBAL_CAMERA_RUNNING.get()
    }

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _frameCount = MutableStateFlow(0)
    val frameCount: StateFlow<Int> = _frameCount.asStateFlow()

    private val _lastPushDurationMs = MutableStateFlow(0L)
    val lastPushDurationMs: StateFlow<Long> = _lastPushDurationMs.asStateFlow()

    var fps: Int = DEFAULT_FPS
    var jpegQuality: Int = DEFAULT_JPEG_QUALITY
    /** [CameraSelector.LENS_FACING_BACK] 或 [CameraSelector.LENS_FACING_FRONT] */
    var lensFacing: Int = CameraSelector.LENS_FACING_BACK

    private var cameraProvider: ProcessCameraProvider? = null
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val pushExecutor = Executors.newSingleThreadExecutor()

    private val lastFrameTimeMs = AtomicLong(0)

    /**
     * 绑定 CameraX 到 LifecycleOwner，开始预览并推流
     */
    fun start(lifecycleOwner: LifecycleOwner, previewView: PreviewView) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            try {
                val provider = cameraProviderFuture.get()
                cameraProvider = provider

                // Preview use case
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

                // ImageAnalysis use case (手动节流到目标 fps)
                val imageAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()

                imageAnalysis.setAnalyzer(analysisExecutor) { imageProxy ->
                    throttleAndPush(imageProxy)
                }

                val cameraSelector = CameraSelector.Builder()
                    .requireLensFacing(lensFacing)
                    .build()

                provider.unbindAll()
                provider.bindToLifecycle(lifecycleOwner, cameraSelector, preview, imageAnalysis)

                _isRunning.value = true
                GLOBAL_CAMERA_RUNNING.set(true)
                Log.i(TAG, "Camera started lens=$lensFacing ${fps}fps (端侧缓冲)")
            } catch (e: Exception) {
                GLOBAL_CAMERA_RUNNING.set(false)
                Log.e(TAG, "Failed to start camera", e)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    /**
     * 停止推流并释放摄像头
     */
    fun stop() {
        cameraProvider?.unbindAll()
        cameraProvider = null
        _isRunning.value = false
        GLOBAL_CAMERA_RUNNING.set(false)
        _frameCount.value = 0
        Log.i(TAG, "Camera stopped")
    }

    /**
     * 根据目标 fps 节流：只有距离上次推送间隔 >= 1000/fps ms 时才推送
     */
    private fun throttleAndPush(imageProxy: ImageProxy) {
        val now = System.currentTimeMillis()
        val intervalMs = 1000L / fps
        val lastTime = lastFrameTimeMs.get()

        if (now - lastTime < intervalMs) {
            imageProxy.close()
            return
        }
        lastFrameTimeMs.set(now)

        try {
            val jpegBytes = imageProxyToJpeg(imageProxy)
            if (jpegBytes != null) {
                pushExecutor.execute { pushFrame(jpegBytes) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Frame conversion failed: ${e.message}")
        } finally {
            imageProxy.close()
        }
    }

    /**
     * 将 ImageProxy (YUV_420_888) 转换为 JPEG bytes
     */
    private fun imageProxyToJpeg(imageProxy: ImageProxy): ByteArray? {
        if (imageProxy.format != ImageFormat.YUV_420_888) {
            Log.w(TAG, "Unexpected image format: ${imageProxy.format}")
            return null
        }

        val yBuffer = imageProxy.planes[0].buffer
        val uBuffer = imageProxy.planes[1].buffer
        val vBuffer = imageProxy.planes[2].buffer

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        val nv21 = ByteArray(ySize + uSize + vSize)
        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)

        val yuvImage = YuvImage(nv21, ImageFormat.NV21, imageProxy.width, imageProxy.height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, imageProxy.width, imageProxy.height), jpegQuality, out)
        return out.toByteArray()
    }

    /**
     * 写入本地帧缓冲并更新统计（无网络上传）
     */
    private fun pushFrame(jpegBytes: ByteArray) {
        val startTime = System.currentTimeMillis()
        VisionFrameBuffer.offer(jpegBytes, startTime)
        val duration = System.currentTimeMillis() - startTime
        _lastPushDurationMs.value = duration
        _frameCount.value += 1
    }
}
