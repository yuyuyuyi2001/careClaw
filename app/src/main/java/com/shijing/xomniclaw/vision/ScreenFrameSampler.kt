package com.shijing.xomniclaw.vision

import android.graphics.Bitmap
import android.util.Log
import com.shijing.xomniclaw.util.MediaProjectionHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * 按固定帧率从 [MediaProjectionHelper] 取屏，写入 [VisionFrameBuffer]（端侧缓冲，无远端上报）。
 */
class ScreenFrameSampler {

    companion object {
        private const val TAG = "ScreenFrameSampler"
    }

    var fps: Int = 1
    var jpegQuality: Int = 80

    private var job: Job? = null

    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.Default) {
            val interval = (1000L / fps.coerceAtLeast(1)).coerceAtLeast(200L)
            while (isActive) {
                val start = System.currentTimeMillis()
                val jpeg = withContext(Dispatchers.IO) { captureOneJpeg() }
                if (jpeg != null) {
                    pushJpeg(jpeg, start)
                }
                val elapsed = System.currentTimeMillis() - start
                val wait = (interval - elapsed).coerceAtLeast(50L)
                delay(wait)
            }
        }
        Log.i(TAG, "Screen sampler started fps=$fps (端侧缓冲)")
    }

    fun stop() {
        job?.cancel()
        job = null
        Log.i(TAG, "Screen sampler stopped")
    }

    private fun captureOneJpeg(): ByteArray? {
        if (!MediaProjectionHelper.isMediaProjectionGranted()) return null
        val pair = MediaProjectionHelper.captureScreen() ?: return null
        val bitmap = pair.first
        return try {
            ByteArrayOutputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, jpegQuality, out)
                out.toByteArray()
            }
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    /** 仅写入 [VisionFrameBuffer] */
    private fun pushJpeg(jpegBytes: ByteArray, ts: Long) {
        VisionFrameBuffer.offer(jpegBytes, ts)
    }
}
