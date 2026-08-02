package com.shijing.xomniclaw.scheduler

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlin.concurrent.thread

/**
 * 闹钟触发接收器。
 *
 * 只负责把系统闹钟事件桥接回应用内部的 ScheduledTaskManager。
 */
class ScheduledTaskReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "ScheduledTaskReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AlarmTaskScheduler.ACTION_TRIGGER_SCHEDULED_TASK) {
            Log.w(TAG, "Ignore unexpected action: ${intent.action}")
            return
        }

        val taskId = intent.getStringExtra(AlarmTaskScheduler.EXTRA_TASK_ID)
        if (taskId.isNullOrBlank()) {
            Log.e(TAG, "Missing task id in scheduled task broadcast")
            return
        }

        val expectedTriggerAtMs = intent.getLongExtra(
            AlarmTaskScheduler.EXTRA_EXPECTED_TRIGGER_AT_MS,
            -1L
        ).takeIf { it > 0L }
        val alarmReceivedAtMs = System.currentTimeMillis()

        Log.i(
            TAG,
            "Received scheduled task trigger: $taskId expected=$expectedTriggerAtMs alarmAt=$alarmReceivedAtMs"
        )

        // Receiver 只做短桥接，把真正执行放到前台 Service，降低后台执行被系统打断的风险。
        val pendingResult = goAsync()
        thread(name = "scheduled-task-dispatch") {
            try {
                ScheduledTaskExecutionService.enqueue(
                    context = context,
                    taskId = taskId,
                    expectedTriggerAtMs = expectedTriggerAtMs,
                    alarmReceivedAtMs = alarmReceivedAtMs
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start scheduled task execution service, fallback to direct handling", e)
                ScheduledTaskManager(context).handleTriggeredTask(
                    taskId = taskId,
                    expectedTriggerAtMs = expectedTriggerAtMs,
                    alarmReceivedAtMs = alarmReceivedAtMs,
                    triggerSource = "receiver_fallback",
                    wakeSummary = "receiver_fallback_no_wake_prep"
                )
            } finally {
                pendingResult.finish()
            }
        }
    }
}
