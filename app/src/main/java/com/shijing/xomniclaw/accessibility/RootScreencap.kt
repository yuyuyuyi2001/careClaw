package com.shijing.xomniclaw.accessibility

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.shijing.xomniclaw.deeplink.RootShellExecutor
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 用 root + system `screencap` 工具截图。
 *
 * 设计意图：
 * 在 Android 14+ / ColorOS 16 等环境上，MediaProjection token 会在息屏瞬间被系统强制 revoke，
 * AccessibilityService.takeScreenshot 也可能受 OEM 二次限制。本通路 **完全绕过这两个体系**：
 *  - 不依赖 MediaProjection 授权；
 *  - 不依赖 AccessibilityService；
 *  - 不依赖任何用户运行时授权（前提：用户在 Magisk / KernelSU 中已授权本应用 root，且**首次授权必须在亮屏前台触发**）；
 *  - 不受息屏 / 后台 / OEM 省电政策影响。
 *
 * 因此当设备 root 时，这条路径应当是定时任务在息屏场景下的**首选**截图方式。
 *
 * 局限：
 *  - 必须设备已 root；
 *  - 首次调用会弹 Magisk/KernelSU 授权框，用户必须能看到屏幕；
 *  - 单次调用约 200~500ms（screencap PNG 编码本身耗时），不适合高频抓帧。
 */
object RootScreencap {
    private const val TAG = "RootScreencap"
    private const val DEFAULT_DIR = "/sdcard/.xomniclaw/workspace/screenshots"

    /**
     * 当前设备是否可用 root 截图。结果带 5s 缓存（由 RootShellExecutor 维护），频繁调用无额外开销。
     */
    fun isAvailable(): Boolean = RootShellExecutor.hasRootAccess()

    /**
     * 触发一次 root screencap，返回 Bitmap + 落盘文件路径。任一环节失败返回 null。
     *
     * 注意：所有 IO 都在 Dispatchers.IO 上完成，调用方可以从任意协程调用。
     */
    suspend fun capture(
        outputDir: File = File(DEFAULT_DIR)
    ): Pair<Bitmap, String>? = withContext(Dispatchers.IO) {
        if (!isAvailable()) {
            return@withContext null
        }
        if (!ensureDirReady(outputDir)) {
            Log.w(TAG, "Output dir not writable: ${outputDir.absolutePath}")
            return@withContext null
        }

        val outputFile = File(outputDir, buildFileName())
        val outputPath = outputFile.absolutePath

        // 用单引号包裹路径以容忍空格；screencap -p 直接落盘 PNG，避免大字节流走 stdout 拷贝。
        val cmd = "screencap -p '$outputPath'"
        val result = RootShellExecutor.execute(cmd)
        if (!result.success) {
            Log.w(TAG, "su screencap failed: exit=${result.exitCode}, err=${result.error}")
            return@withContext null
        }

        if (!outputFile.exists() || outputFile.length() == 0L) {
            Log.w(TAG, "screencap returned ok but file empty: $outputPath")
            return@withContext null
        }

        val bitmap = try {
            BitmapFactory.decodeFile(outputPath)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decode screencap PNG: $outputPath", e)
            null
        } ?: return@withContext null

        Log.i(TAG, "✅ Root screencap saved: $outputPath (${outputFile.length()}B, ${bitmap.width}x${bitmap.height})")
        Pair(bitmap, outputPath)
    }

    private fun ensureDirReady(dir: File): Boolean {
        return try {
            if (!dir.exists()) dir.mkdirs()
            dir.exists() && dir.isDirectory
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create dir: ${dir.absolutePath}", e)
            false
        }
    }

    private fun buildFileName(): String {
        val ts = java.text.SimpleDateFormat("yyyy-MM-dd_HH-mm-ss-SSS", java.util.Locale.US)
            .format(java.util.Date())
        return "${ts}_root_screencap.png"
    }
}
