package com.shijing.xomniclaw.agent.memory.gallery

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.shijing.xomniclaw.R
import com.shijing.xomniclaw.agent.memory.MemoryManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 相册记忆同步前台服务。
 *
 * 手动点击“立即扫描一次”后会进入这里执行，避免：
 * - 切后台后页面协程被打断；
 * - 扫描图片较多时用户看不到进度；
 * - 长时间摘要生成时系统回收优先级过低。
 */
class GalleryMemorySyncForegroundService : Service() {
    companion object {
        private const val TAG = "GalleryMemorySyncSvc"
        private const val CHANNEL_ID = "gallery_memory_sync"
        private const val CHANNEL_NAME = "X-OmniClaw 相册记忆同步"
        private const val NOTIFICATION_ID = 2010

        private const val ACTION_START_SYNC =
            "com.shijing.xomniclaw.agent.memory.gallery.action.START_SYNC"
        private const val EXTRA_MAX_IMAGES = "max_images"
        private const val EXTRA_FORCE_RESCAN = "force_rescan"
        private const val EXTRA_UPDATE_PROFILE = "update_profile"

        fun enqueue(
            context: Context,
            maxImages: Int = 100,
            forceRescan: Boolean = false,
            updateProfile: Boolean = true
        ) {
            val intent = Intent(context, GalleryMemorySyncForegroundService::class.java).apply {
                action = ACTION_START_SYNC
                putExtra(EXTRA_MAX_IMAGES, maxImages)
                putExtra(EXTRA_FORCE_RESCAN, forceRescan)
                putExtra(EXTRA_UPDATE_PROFILE, updateProfile)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val statusStore by lazy { GalleryMemorySyncStatusStore() }
    private var activeJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startServiceForeground(
            title = "X-OmniClaw 相册扫描准备中",
            text = "正在初始化后台扫描任务"
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != ACTION_START_SYNC) {
            stopSelfResult(startId)
            return START_NOT_STICKY
        }

        if (activeJob?.isActive == true) {
            updateNotification(
                title = "X-OmniClaw 相册扫描进行中",
                text = "已有后台扫描任务正在执行"
            )
            return START_NOT_STICKY
        }

        val maxImages = intent.getIntExtra(EXTRA_MAX_IMAGES, 100).coerceAtLeast(1)
        val forceRescan = intent.getBooleanExtra(EXTRA_FORCE_RESCAN, false)
        val updateProfile = intent.getBooleanExtra(EXTRA_UPDATE_PROFILE, true)

        statusStore.markRunning(
            maxImages = maxImages,
            message = "后台扫描已启动，准备读取新增图片",
            stage = "preparing"
        )
        updateNotification(
            title = "X-OmniClaw 相册扫描进行中",
            text = "准备扫描最多 $maxImages 张未入库图片"
        )

        activeJob = serviceScope.launch {
            try {
                val workflow = GalleryMemoryWorkflow(
                    context = applicationContext,
                    memoryManager = MemoryManager("/sdcard/.xomniclaw/workspace", applicationContext)
                )
                val report = workflow.syncGalleryMemories(
                    maxImages = maxImages,
                    forceRescan = forceRescan,
                    updateProfile = updateProfile,
                    progressListener = { progress ->
                        val running = statusStore.update { current ->
                            current.copy(
                                isRunning = true,
                                stage = progress.stage,
                                message = progress.message,
                                maxImages = maxImages,
                                inspectedCount = progress.inspectedCount,
                                discoveredCount = progress.discoveredCount,
                                processedCount = progress.processedCount,
                                writtenCount = progress.writtenCount,
                                skippedCount = progress.skippedCount,
                                startedAtMs = current.startedAtMs ?: System.currentTimeMillis()
                            )
                        }
                        updateNotification(
                            title = "X-OmniClaw 相册扫描进行中",
                            text = buildNotificationText(running)
                        )
                    }
                )

                val completed = statusStore.update { current ->
                    current.copy(
                        isRunning = false,
                        stage = "completed",
                        message = report.message,
                        maxImages = maxImages,
                        inspectedCount = report.inspectedCount,
                        discoveredCount = report.scannedCount,
                        processedCount = report.writtenCount,
                        writtenCount = report.writtenCount,
                        skippedCount = report.skippedCount
                    )
                }
                updateNotification(
                    title = "X-OmniClaw 相册扫描已完成",
                    text = buildNotificationText(completed)
                )
            } catch (e: Exception) {
                Log.e(TAG, "Gallery memory sync failed", e)
                val failed = statusStore.update { current ->
                    current.copy(
                        isRunning = false,
                        stage = "failed",
                        message = "相册扫描失败：${e.message ?: "unknown"}"
                    )
                }
                updateNotification(
                    title = "X-OmniClaw 相册扫描失败",
                    text = buildNotificationText(failed)
                )
            } finally {
                activeJob = null
                stopSelfResult(startId)
            }
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startServiceForeground(title: String, text: String) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    createNotification(title = title, text = text),
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(NOTIFICATION_ID, createNotification(title = title, text = text))
            }
        } catch (e: Exception) {
            Log.w(TAG, "startForeground with type failed, fallback to basic mode", e)
            startForeground(NOTIFICATION_ID, createNotification(title = title, text = text))
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "承接 X-OmniClaw 相册记忆扫描与画像生成"
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun updateNotification(title: String, text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, createNotification(title, text))
    }

    private fun createNotification(title: String, text: String): Notification {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        } ?: Intent()

        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            pendingIntentFlags
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_baseline_adb_24)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setShowWhen(true)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .setAutoCancel(false)
            .build()
    }

    private fun buildNotificationText(status: GalleryMemorySyncStatus): String {
        return when {
            status.isRunning && status.discoveredCount > 0 ->
                "${status.message}，已完成 ${status.processedCount}/${status.discoveredCount}"
            status.isRunning ->
                "${status.message}，已检查 ${status.inspectedCount} 张"
            else ->
                status.progressText()
        }
    }
}
