/**
 * OmniClaw Source Reference:
 * - ../xomniclaw/src/agents/(all)
 *
 * OmniClaw adaptation: 主进程入口委托到 accessibility 模块的全局单例，
 * 避免与无障碍/屏内识别权限页各持一套 MediaProjection 互相顶替导致「录屏权限被关闭」。
 */
package com.shijing.xomniclaw.util

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import com.shijing.xomniclaw.accessibility.MediaProjectionHelper as ProjectionCore
import com.shijing.xomniclaw.core.ForegroundService
import android.media.projection.MediaProjection

/**
 * 主应用侧薄封装：录屏状态与 VirtualDisplay 仅在 [ProjectionCore] 中维护一份。
 */
object MediaProjectionHelper {

    /** 与 [requestMediaProjection] / 无障碍侧一致，Activity 须在 onActivityResult 中转发 [handleActivityResult]。 */
    const val REQUEST_CODE: Int = ProjectionCore.SCREEN_CAPTURE_REQUEST_CODE

    const val STATUS_AUTHORIZED = "已授权"
    const val STATUS_OBJECT_NULL = "权限已获取但对象为空"
    const val STATUS_NOT_AUTHORIZED = "未授权"

    /**
     * 请求录屏权限；内部先启动主进程 [ForegroundService]（系统对 MediaProjection 的前台要求），
     * 再委托无障碍单例弹出系统授权页。
     */
    fun requestMediaProjection(activity: Activity): Boolean {
        CrashBreadcrumbs.mark(
            stage = "media_projection.request",
            detail = "granted=${ProjectionCore.isAuthorized()}, hasProjection=${ProjectionCore.getMediaProjection() != null}",
        )
        android.util.Log.d(
            "MediaProjectionHelper",
            "requestMediaProjection: delegated, authorized=${ProjectionCore.isAuthorized()}",
        )
        if (ProjectionCore.isAuthorized()) {
            CrashBreadcrumbs.mark("media_projection.request", "reuse_existing_projection")
            return true
        }
        startForegroundForMediaProjection(activity)
        // observer 侧：已授权返回 true；弹出系统页等待用户时返回 false
        val grantedWithoutDialog = ProjectionCore.requestPermission(activity)
        if (!grantedWithoutDialog) {
            CrashBreadcrumbs.mark("media_projection.request", "system_permission_page_launched")
        }
        return grantedWithoutDialog
    }

    fun isMediaProjectionGranted(): Boolean = ProjectionCore.isAuthorized()

    /**
     * 提供给音频回放捕获等能力复用当前授权过的 MediaProjection。
     */
    fun getMediaProjection(): MediaProjection? = ProjectionCore.getMediaProjection()

    fun getPermissionStatus(): String {
        return when {
            ProjectionCore.isAuthorized() && ProjectionCore.getMediaProjection() != null -> STATUS_AUTHORIZED
            ProjectionCore.isAuthorized() -> STATUS_OBJECT_NULL
            else -> STATUS_NOT_AUTHORIZED
        }
    }

    fun resetPermission() {
        CrashBreadcrumbs.mark("media_projection.reset", "permission_state_cleared")
        ProjectionCore.reset()
    }

    fun forceRequestPermission(activity: Activity) {
        resetPermission()
        requestMediaProjection(activity)
    }

    fun handleActivityResult(
        context: Context,
        requestCode: Int,
        resultCode: Int,
        data: Intent?,
    ): Boolean {
        CrashBreadcrumbs.mark(
            stage = "media_projection.on_activity_result",
            detail = "requestCode=$requestCode, resultCode=$resultCode, hasData=${data != null}",
        )
        val activity = context as? Activity ?: return false
        val handled = ProjectionCore.handlePermissionResult(activity, requestCode, resultCode, data)
        if (handled) {
            CrashBreadcrumbs.mark("media_projection.on_activity_result", "permission_granted_delegated")
        } else if (requestCode == REQUEST_CODE) {
            CrashBreadcrumbs.mark("media_projection.on_activity_result", "denied_or_not_handled")
        }
        return handled
    }

    /**
     * Compose / Activity Result API：在 ActivityResult 回调里调用。
     */
    fun applyScreenCaptureResult(context: Context, resultCode: Int, data: Intent?) {
        CrashBreadcrumbs.mark(
            stage = "media_projection.compose_result",
            detail = "resultCode=$resultCode, hasData=${data != null}",
        )
        ProjectionCore.applyScreenCaptureResult(context, resultCode, data)
        if (resultCode == Activity.RESULT_OK && data != null) {
            CrashBreadcrumbs.mark("media_projection.compose_result", "delegated_to_accessibility_singleton")
        } else {
            CrashBreadcrumbs.mark("media_projection.compose_result", "cancelled_or_empty_keeps_session")
        }
    }

    fun createScreenCaptureIntent(context: Context): Intent {
        CrashBreadcrumbs.mark("media_projection.create_intent", "requesting_system_capture_intent")
        return ProjectionCore.createScreenCaptureIntent(context)
    }

    /** 主进程录屏所需前台服务（与 observer 的 ObserverForegroundService 并存，满足各自组件的前台约束）。 */
    fun startForegroundForMediaProjection(context: Context) {
        CrashBreadcrumbs.mark("media_projection.foreground_service", "starting_projection_foreground_service")
        val foregroundServiceIntent = Intent(context, ForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(foregroundServiceIntent)
        } else {
            context.startService(foregroundServiceIntent)
        }
    }

    fun captureScreen(): Pair<Bitmap, String>? {
        CrashBreadcrumbs.mark(
            stage = "media_projection.capture.begin",
            detail = "delegated_singleton_capture",
        )
        return try {
            ProjectionCore.captureScreen()
        } catch (e: Exception) {
            CrashBreadcrumbs.mark(
                stage = "media_projection.capture.exception",
                detail = "${e.javaClass.simpleName}:${e.message ?: "unknown"}",
            )
            LayoutExceptionLogger.log("MediaProjectionHelper#captureScreen", e)
            null
        }
    }
}
