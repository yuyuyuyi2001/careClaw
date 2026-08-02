package com.shijing.xomniclaw.accessibility.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.hardware.HardwareBuffer
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import androidx.annotation.RequiresApi
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * 用 [AccessibilityService.takeScreenshot] 截图的封装。
 *
 * 与 [com.shijing.xomniclaw.accessibility.MediaProjectionHelper.captureScreen] 的关键差异：
 * - 不依赖 MediaProjection token，**不会被息屏 / 后台 / OEM 政策撤销**；
 * - 不需要任何用户授权弹窗，只要无障碍服务已启用且 manifest 声明 canTakeScreenshot=true 即可；
 * - 系统对调用频率有硬限制（每个无障碍服务约每秒 1 张），频繁调用会拿到 ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT。
 *
 * 截图结果会被保存为 PNG 文件，路径与已有 MediaProjection 链路统一在 `/sdcard/.xomniclaw/workspace/screenshots/`。
 *
 * 这是一个无状态工具类，所有 IO / Bitmap 处理都在调用方协程上下文里完成。
 */
@RequiresApi(Build.VERSION_CODES.R)
object A11yScreenshotter {
    private const val TAG = "A11yScreenshotter"
    private const val DEFAULT_SCREENSHOT_DIR = "/sdcard/.xomniclaw/workspace/screenshots"

    /**
     * 通过无障碍服务截图并落盘成 PNG。
     *
     * @param service 已 connected 的 AccessibilityService 实例。
     * @param outputDir 截图落盘目录；默认 `/sdcard/.xomniclaw/workspace/screenshots/`。
     * @return Pair<Bitmap, String>：截图的 Bitmap 与保存路径；任一步骤失败返回 null。
     */
    suspend fun capture(
        service: AccessibilityService,
        outputDir: File = File(DEFAULT_SCREENSHOT_DIR)
    ): Pair<Bitmap, String>? {
        if (!ensureDirReady(outputDir)) {
            Log.w(TAG, "Output dir not writable: ${outputDir.absolutePath}")
            return null
        }

        val screenshot = takeScreenshotSuspend(service) ?: return null
        val bitmap = decodeBitmap(screenshot) ?: return null

        return try {
            val file = File(outputDir, buildFileName())
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            Log.i(TAG, "✅ A11y screenshot saved: ${file.absolutePath}")
            Pair(bitmap, file.absolutePath)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save a11y screenshot", e)
            null
        }
    }

    /**
     * 同步触发 takeScreenshot 的 suspend 包装。失败/超时时返回 null。
     */
    private suspend fun takeScreenshotSuspend(
        service: AccessibilityService
    ): ScreenshotResult? = suspendCancellableCoroutine { cont ->
        val mainHandler = Handler(Looper.getMainLooper())
        val executor = java.util.concurrent.Executor { runnable -> mainHandler.post(runnable) }

        try {
            service.takeScreenshot(
                Display.DEFAULT_DISPLAY,
                executor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: ScreenshotResult) {
                        if (cont.isActive) cont.resume(screenshot)
                    }

                    override fun onFailure(errorCode: Int) {
                        Log.w(TAG, "takeScreenshot failed: errorCode=$errorCode (${errorReason(errorCode)})")
                        if (cont.isActive) cont.resume(null)
                    }
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "takeScreenshot threw exception", e)
            if (cont.isActive) cont.resume(null)
        }
    }

    /**
     * 把 ScreenshotResult 的 HardwareBuffer 转成可用的 ARGB_8888 Bitmap。
     *
     * 直接 wrapHardwareBuffer 出来的 Bitmap 是 HARDWARE config，不能 compress / copyPixels；
     * 必须先 copy 到 ARGB_8888 才能 PNG 编码。
     */
    @SuppressLint("WrongConstant")
    private fun decodeBitmap(screenshot: ScreenshotResult): Bitmap? {
        val hardwareBuffer: HardwareBuffer = screenshot.hardwareBuffer
        return try {
            val raw = Bitmap.wrapHardwareBuffer(hardwareBuffer, screenshot.colorSpace)
            val bitmap = raw?.copy(Bitmap.Config.ARGB_8888, /* isMutable = */ false)
            // wrapHardwareBuffer 返回的是 HW Bitmap，原始引用应主动释放。
            raw?.recycle()
            bitmap
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decode HardwareBuffer to Bitmap", e)
            null
        } finally {
            try {
                hardwareBuffer.close()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to close HardwareBuffer", e)
            }
        }
    }

    private fun ensureDirReady(dir: File): Boolean {
        return try {
            if (!dir.exists()) dir.mkdirs()
            dir.exists() && dir.isDirectory
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create screenshot dir: ${dir.absolutePath}", e)
            false
        }
    }

    private fun buildFileName(): String {
        val ts = java.text.SimpleDateFormat("yyyy-MM-dd_HH-mm-ss-SSS", java.util.Locale.US)
            .format(java.util.Date())
        return "${ts}_a11y_screenshot.png"
    }

    private fun errorReason(errorCode: Int): String {
        // 常量值取自 android.accessibilityservice.AccessibilityService（API 30+）
        return when (errorCode) {
            0 -> "ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR"
            1 -> "ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS"
            2 -> "ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT"
            3 -> "ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY"
            else -> "UNKNOWN_ERROR_CODE=$errorCode"
        }
    }

    /**
     * 让上层快速判断当前 SDK 是否支持本能力。
     */
    @JvmStatic
    fun isSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
}
