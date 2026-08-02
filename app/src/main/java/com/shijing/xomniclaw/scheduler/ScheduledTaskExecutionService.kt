package com.shijing.xomniclaw.scheduler

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.shijing.xomniclaw.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 定时任务执行前台服务。
 *
 * 这个 Service 只在定时任务真正触发时短暂拉起，用更高的系统优先级承接后续 Agent 执行，
 * 避免 Receiver 返回后后台协程被系统延迟或打断。
 */
class ScheduledTaskExecutionService : Service() {
    companion object {
        private const val TAG = "ScheduledTaskExecSvc"
        private const val CHANNEL_ID = "scheduled_task_execution"
        private const val WAKE_CHANNEL_ID = "scheduled_task_wake"
        private const val CHANNEL_NAME = "OmniClaw 定时任务执行"
        private const val WAKE_CHANNEL_NAME = "OmniClaw 定时任务唤醒"
        private const val NOTIFICATION_ID = 2002
        private const val WAKE_NOTIFICATION_ID = 2003
        private const val WAKE_FALLBACK_DELAY_MS = 8_000L

        private const val ACTION_EXECUTE_TASK =
            "com.shijing.xomniclaw.scheduler.action.EXECUTE_TASK"
        private const val EXTRA_TASK_ID = "task_id"
        private const val EXTRA_EXPECTED_TRIGGER_AT_MS = "expected_trigger_at_ms"
        private const val EXTRA_ALARM_RECEIVED_AT_MS = "alarm_received_at_ms"

        /**
         * 从闹钟接收器拉起前台服务，尽量把调度延迟压缩到最小。
         */
        fun enqueue(
            context: android.content.Context,
            taskId: String,
            expectedTriggerAtMs: Long?,
            alarmReceivedAtMs: Long
        ) {
            val intent = Intent(context, ScheduledTaskExecutionService::class.java).apply {
                action = ACTION_EXECUTE_TASK
                putExtra(EXTRA_TASK_ID, taskId)
                putExtra(EXTRA_ALARM_RECEIVED_AT_MS, alarmReceivedAtMs)
                expectedTriggerAtMs?.let { putExtra(EXTRA_EXPECTED_TRIGGER_AT_MS, it) }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    createNotification("正在准备执行定时任务"),
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(NOTIFICATION_ID, createNotification("正在准备执行定时任务"))
            }
        } catch (e: Exception) {
            Log.w(TAG, "startForeground with type failed, fallback to basic mode", e)
            startForeground(NOTIFICATION_ID, createNotification("正在准备执行定时任务"))
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != ACTION_EXECUTE_TASK) {
            Log.w(TAG, "Ignore unexpected action: ${intent?.action}")
            stopSelfResult(startId)
            return START_NOT_STICKY
        }

        val taskId = intent.getStringExtra(EXTRA_TASK_ID)
        if (taskId.isNullOrBlank()) {
            Log.e(TAG, "Missing task id in execution service")
            stopSelfResult(startId)
            return START_NOT_STICKY
        }

        val expectedTriggerAtMs = intent.getLongExtra(EXTRA_EXPECTED_TRIGGER_AT_MS, -1L)
            .takeIf { it > 0L }
        val alarmReceivedAtMs = intent.getLongExtra(
            EXTRA_ALARM_RECEIVED_AT_MS,
            System.currentTimeMillis()
        )

        serviceScope.launch {
            try {
                val now = System.currentTimeMillis()
                val latenessMs = expectedTriggerAtMs?.let { (alarmReceivedAtMs - it).coerceAtLeast(0L) }
                val wakePreparation = ScreenWakeCoordinator.prepareForScheduledExecution(
                    context = applicationContext,
                    taskId = taskId,
                    expectedTriggerAtMs = expectedTriggerAtMs,
                    alarmReceivedAtMs = alarmReceivedAtMs
                )

                updateNotification(
                    "正在唤醒执行环境",
                    buildString {
                        append("任务 ID: ")
                        append(taskId.take(8))
                        if (latenessMs != null) {
                            append("，闹钟延迟 ${latenessMs}ms")
                        } else {
                            append("，已进入执行阶段")
                        }
                        append("，服务启动耗时 ${now - alarmReceivedAtMs}ms")
                        append("，唤醒策略 ${wakePreparation.plan.summary}")
                    }
                )
                showWakeNotification(
                    taskId = taskId,
                    expectedTriggerAtMs = expectedTriggerAtMs,
                    alarmReceivedAtMs = alarmReceivedAtMs,
                    dispatchToken = wakePreparation.dispatchToken,
                    wakeSummary = wakePreparation.plan.summary
                )

                // 首选路径由 WakeActivity 在前台 onResume 后派发；这里仅兜底，避免 ROM 拦截全屏页后完全不执行。
                delay(wakePreparation.plan.postWakeDelayMs + WAKE_FALLBACK_DELAY_MS)

                if (!ScreenWakeCoordinator.hasDispatched(wakePreparation.dispatchToken) &&
                    ScreenWakeCoordinator.tryMarkDispatched(wakePreparation.dispatchToken, "foreground_service_fallback")
                ) {
                    Log.w(TAG, "WakeActivity did not dispatch in time, fallback to service dispatch")
                    ScheduledTaskManager(applicationContext).handleTriggeredTask(
                        taskId = taskId,
                        expectedTriggerAtMs = expectedTriggerAtMs,
                        alarmReceivedAtMs = alarmReceivedAtMs,
                        triggerSource = "foreground_service_fallback",
                        wakeSummary = "${wakePreparation.plan.summary}:wake_activity_timeout"
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Scheduled task execution failed", e)
            } finally {
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

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "承接 OmniClaw 定时任务的实际执行"
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
            }

            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)

            val wakeChannel = NotificationChannel(
                WAKE_CHANNEL_ID,
                WAKE_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "定时任务到点时用于亮屏并拉起执行页"
                setShowBadge(false)
                enableLights(true)
                enableVibration(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }

            getSystemService(NotificationManager::class.java).createNotificationChannel(wakeChannel)
        }
    }

    private fun updateNotification(title: String, text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, createNotification(text, title))
    }

    private fun createNotification(
        text: String,
        title: String = "OmniClaw 定时任务执行中"
    ): Notification {
        // 点击通知可快速回到应用主界面，方便查看任务执行状态。
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

    private fun showWakeNotification(
        taskId: String,
        expectedTriggerAtMs: Long?,
        alarmReceivedAtMs: Long,
        dispatchToken: String,
        wakeSummary: String
    ) {
        val wakeIntent = ScreenWakeCoordinator.buildWakeActivityIntent(
            context = this,
            taskId = taskId,
            expectedTriggerAtMs = expectedTriggerAtMs,
            alarmReceivedAtMs = alarmReceivedAtMs,
            dispatchToken = dispatchToken,
            wakeSummary = wakeSummary
        )
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val fullScreenIntent = PendingIntent.getActivity(
            this,
            taskId.hashCode(),
            wakeIntent,
            flags
        )
        val notification = NotificationCompat.Builder(this, WAKE_CHANNEL_ID)
            .setContentTitle("OmniClaw 定时任务到点")
            .setContentText("正在唤醒屏幕并准备执行任务")
            .setSmallIcon(R.drawable.ic_baseline_adb_24)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setShowWhen(true)
            .setOngoing(false)
            .setContentIntent(fullScreenIntent)
            .setFullScreenIntent(fullScreenIntent, true)
            .setAutoCancel(true)
            .build()

        getSystemService(NotificationManager::class.java).notify(WAKE_NOTIFICATION_ID, notification)
    }
}
