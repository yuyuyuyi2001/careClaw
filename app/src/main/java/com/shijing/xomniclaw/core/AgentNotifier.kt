package com.shijing.xomniclaw.core

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.shijing.xomniclaw.R

/**
 * CareClaw adaptation: unified notification channel for agent run progress / result.
 *
 * 为什么需要它（修复「感知不到后台跑 + 结果不输出」）：
 * 1. 悬浮窗依赖 SYSTEM_ALERT_WINDOW 权限，无权限时用户完全看不到 Agent 是否在跑；
 * 2. ForegroundService 的常驻通知是静态文案，从不更新；
 * 3. 后台任务结果只落 session 文件，用户无感知（广播/HTTP 入口 remoteReply 均为 null）。
 *
 * 职责：
 * - updateProgress：把「步骤 X/Y + 当前动作」实时刷到常驻运行通知（与 ForegroundService 共用 id=1），
 *   让用户切到桌面 / 锁屏都能看到 Agent 正在做什么；
 * - restoreIdle：任务结束后把常驻通知文案恢复为默认，避免残留「步骤 29/40」；
 * - showResult：任务完成/失败后推一条可点开的结果通知（独立 id=2），点开回到 App 查看详情。
 */
object AgentNotifier {
    private const val TAG = "AgentNotifier"

    /** 结果通知独立 id（与 ForegroundService 的常驻通知 id=1 区分开）。 */
    private const val RESULT_NOTIFICATION_ID = 2
    /** 结果通知独立渠道：IMPORTANCE_HIGH 允许弹横幅提醒（需 POST_NOTIFICATIONS 权限）。 */
    private const val RESULT_CHANNEL_ID = "omniclaw_result"
    private const val RESULT_CHANNEL_NAME = "Agent 任务结果"

    private fun hasNotificationPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } else true
    }

    /** 实时进度：更新常驻运行通知（id=1，与 ForegroundService 共用一条，避免通知栏堆积）。 */
    fun updateProgress(context: Context, title: String, content: String) {
        if (!hasNotificationPermission(context)) return
        try {
            ForegroundService.updateNotification(context, title, content)
        } catch (e: Exception) {
            Log.w(TAG, "updateProgress failed: ${e.message}")
        }
    }

    /** 任务结束：把常驻运行通知恢复为默认文案。 */
    fun restoreIdle(context: Context) {
        if (!hasNotificationPermission(context)) return
        try {
            ForegroundService.updateNotification(context, "OmniClaw 正在运行", "点击打开应用")
        } catch (e: Exception) {
            Log.w(TAG, "restoreIdle failed: ${e.message}")
        }
    }

    /** 最终结果通知（可点开；成功弹横幅提醒，失败用高优先级醒目展示）。 */
    fun showResult(context: Context, success: Boolean, title: String, content: String) {
        if (!hasNotificationPermission(context)) {
            Log.w(TAG, "POST_NOTIFICATIONS not granted, skip result notification: $title")
            return
        }
        try {
            val notificationManager = context.getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    RESULT_CHANNEL_ID,
                    RESULT_CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Agent 任务完成/失败结果"
                    setShowBadge(true)
                }
                notificationManager.createNotificationChannel(channel)
            }

            val launchIntent = context.packageManager
                .getLaunchIntentForPackage(context.packageName)?.apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
            val piFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val pendingIntent = launchIntent?.let {
                PendingIntent.getActivity(context, 0, it, piFlags)
            }

            val notification = NotificationCompat.Builder(context, RESULT_CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(content)
                .setStyle(NotificationCompat.BigTextStyle().bigText(content))
                .setSmallIcon(R.drawable.ic_baseline_adb_24)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .build()

            notificationManager.notify(RESULT_NOTIFICATION_ID, notification)
            Log.d(TAG, "Result notification shown: $title (success=$success, len=${content.length})")
        } catch (e: Exception) {
            Log.e(TAG, "showResult failed", e)
        }
    }
}
