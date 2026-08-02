package com.shijing.xomniclaw.deeplink

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.shijing.xomniclaw.deeplink.model.DeeplinkBookmark

/**
 * Deeplink / 页面直达启动器。
 *
 * 优先使用 root 回放完整 `am start`，失败后再降级到显式 Intent，
 * 保证 root 临时不可用时也尽量给用户一个兜底结果。
 */
object DeeplinkLauncher {
    sealed class LaunchResult {
        object RootSuccess : LaunchResult()
        object DirectIntentSuccess : LaunchResult()
        data class Failed(val reason: String) : LaunchResult()
    }

    fun launch(
        context: Context,
        bookmark: DeeplinkBookmark
    ): LaunchResult {
        if (RootShellExecutor.hasRootAccess()) {
            val rootResult = RootShellExecutor.executeAmCommand(bookmark.effectiveAmCommand)
            if (rootResult.success) {
                return LaunchResult.RootSuccess
            }
        }
        return tryDirectIntent(context, bookmark)
    }

    private fun tryDirectIntent(
        context: Context,
        bookmark: DeeplinkBookmark
    ): LaunchResult {
        return try {
            val intent = Intent().apply {
                component = ComponentName(bookmark.packageName, bookmark.activityName)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                if (bookmark.dataUri.isNotBlank()) {
                    action = Intent.ACTION_VIEW
                    data = Uri.parse(bookmark.dataUri)
                }
            }
            val resolveInfo = context.packageManager.resolveActivity(intent, 0)
            if (resolveInfo == null) {
                LaunchResult.Failed("当前页面仅支持 root 回放，普通 Intent 无法解析")
            } else {
                context.startActivity(intent)
                LaunchResult.DirectIntentSuccess
            }
        } catch (e: SecurityException) {
            LaunchResult.Failed("启动被系统拒绝：${e.message}")
        } catch (e: Exception) {
            LaunchResult.Failed("启动失败：${e.message}")
        }
    }
}
