package com.shijing.xomniclaw.scheduler

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * AlarmManager 调度器。
 *
 * 目标是把“任务下一次触发时间”交给系统级闹钟管理，这样应用进程被杀后仍然有机会恢复执行。
 */
class AlarmTaskScheduler(private val context: Context) {
    companion object {
        private const val TAG = "AlarmTaskScheduler"
        const val ACTION_TRIGGER_SCHEDULED_TASK =
            "com.shijing.xomniclaw.scheduler.ACTION_TRIGGER_SCHEDULED_TASK"
        const val EXTRA_TASK_ID = "task_id"
        const val EXTRA_EXPECTED_TRIGGER_AT_MS = "expected_trigger_at_ms"
    }

    /**
     * 调度模式说明。
     *
     * 用于向上层明确当前任务是否真的走了精确定时，避免“请求 exact=true 但系统静默降级”时难以定位。
     */
    data class SchedulingMode(
        val requestedExact: Boolean,
        val exactPermissionGranted: Boolean,
        val willUseExact: Boolean,
        val description: String
    )

    private val appContext = context.applicationContext
    private val alarmManager = appContext.getSystemService(AlarmManager::class.java)

    fun schedule(task: ScheduledTask) {
        val triggerAtMs = task.nextTriggerAtMs ?: return
        val pendingIntent = buildPendingIntent(task.id, triggerAtMs)
        val schedulingMode = getSchedulingMode(task)

        if (schedulingMode.willUseExact) {
            if (task.allowWhileIdle && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMs,
                    pendingIntent
                )
            } else {
                alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerAtMs, pendingIntent)
            }
            Log.d(
                TAG,
                "Scheduled exact alarm for task=${task.id} at=$triggerAtMs mode=${schedulingMode.description}"
            )
            return
        }

        if (task.allowWhileIdle && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, pendingIntent)
        } else {
            alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAtMs, pendingIntent)
        }
        Log.w(
            TAG,
            "Scheduled inexact alarm for task=${task.id} at=$triggerAtMs mode=${schedulingMode.description}"
        )
    }

    fun cancel(taskId: String) {
        val pendingIntent = buildPendingIntent(taskId, null)
        alarmManager.cancel(pendingIntent)
        pendingIntent.cancel()
        Log.d(TAG, "Cancelled alarm for task=$taskId")
    }

    fun getSchedulingMode(task: ScheduledTask): SchedulingMode {
        val exactPermissionGranted = canUseExactAlarm()
        val willUseExact = task.exact && exactPermissionGranted
        val description = when {
            willUseExact && task.allowWhileIdle && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ->
                "exact_allow_while_idle"
            willUseExact -> "exact"
            task.exact && !exactPermissionGranted && task.allowWhileIdle &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ->
                "inexact_allow_while_idle_fallback_missing_exact_alarm_permission"
            task.exact && !exactPermissionGranted ->
                "inexact_fallback_missing_exact_alarm_permission"
            task.allowWhileIdle && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ->
                "inexact_allow_while_idle"
            else -> "inexact"
        }

        return SchedulingMode(
            requestedExact = task.exact,
            exactPermissionGranted = exactPermissionGranted,
            willUseExact = willUseExact,
            description = description
        )
    }

    fun canUseExactAlarm(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            alarmManager.canScheduleExactAlarms()
        } else {
            true
        }
    }

    private fun buildPendingIntent(taskId: String, expectedTriggerAtMs: Long?): PendingIntent {
        val intent = Intent(appContext, ScheduledTaskReceiver::class.java).apply {
            action = ACTION_TRIGGER_SCHEDULED_TASK
            putExtra(EXTRA_TASK_ID, taskId)
            expectedTriggerAtMs?.let { putExtra(EXTRA_EXPECTED_TRIGGER_AT_MS, it) }
        }

        return PendingIntent.getBroadcast(
            appContext,
            taskId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
