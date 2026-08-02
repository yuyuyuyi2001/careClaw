package com.shijing.xomniclaw.agent.behavior

/**
 * Upstream reference (OmniClaw):
 * - ../xomniclaw/src/gateway/(all)
 *
 * CareClaw adaptation: behavior teaching core chain.
 *
 * 非 root 行为录制：前台页面来自无障碍服务，Intent 通过 dumpsys 捕获。
 * 部分受限 ROM 会拦截 dumpsys activity，此时返回 null，由调用方降级为 Activity 兜底跳转。
 */
import android.content.Context
import com.shijing.xomniclaw.accessibility.service.AccessibilityBinderService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class ForegroundPage(
    val packageName: String,
    val activityName: String,
    val appName: String
)

object BehaviorCapture {

    /**
     * 从无障碍服务读取当前前台页面。
     */
    fun foregroundPage(context: Context): ForegroundPage? {
        val svc = AccessibilityBinderService.serviceInstance ?: return null
        val pkg = svc.currentPackageName.takeIf { it.isNotBlank() } ?: return null
        val activity = svc.activityClassName.takeIf { it.isNotBlank() } ?: return null
        return ForegroundPage(
            packageName = pkg,
            activityName = activity,
            appName = appNameOf(context, pkg)
        )
    }

    /**
     * 包名 -> 应用显示名。
     */
    fun appNameOf(context: Context, packageName: String): String {
        return try {
            val applicationInfo = context.packageManager.getApplicationInfo(packageName, 0)
            context.packageManager.getApplicationLabel(applicationInfo).toString()
        } catch (_: Exception) {
            packageName.substringAfterLast(".")
        }
    }

    /**
     * 非 root 捕获当前前台 Activity 的 Intent 信息。
     * `dumpsys activity activities` 在部分 ROM 上对普通应用受限，失败时返回 null。
     */
    suspend fun captureIntent(
        packageName: String,
        activityName: String
    ): IntentParser.CapturedIntentSpec? = withContext(Dispatchers.IO) {
        try {
            val process = Runtime.getRuntime().exec(
                arrayOf("/system/bin/dumpsys", "activity", "activities")
            )
            val output = process.inputStream.bufferedReader().use { it.readText() }
            process.waitFor()
            if (output.isBlank()) {
                null
            } else {
                IntentParser.parseBlock(output.lines(), packageName, activityName)
            }
        } catch (_: Exception) {
            null
        }
    }
}

