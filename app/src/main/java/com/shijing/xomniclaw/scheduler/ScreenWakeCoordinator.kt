package com.shijing.xomniclaw.scheduler

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.util.Log
import com.shijing.xomniclaw.util.WakeLockManager
import java.util.concurrent.ConcurrentHashMap

/**
 * 定时任务亮屏协调器。
 *
 * 负责：
 * 1. 判断当前是否处于息屏/锁屏状态；
 * 2. 在真正执行 Agent 前，先做一次最佳努力的唤醒与锁屏页处理；
 * 3. 给上层返回诊断信息，便于真机定位为什么任务没有按预期自动完成。
 */
object ScreenWakeCoordinator {
    private const val TAG = "ScreenWakeCoordinator"

    const val ACTION_WAKE_AND_EXECUTE =
        "com.shijing.xomniclaw.scheduler.action.WAKE_AND_EXECUTE"
    const val EXTRA_TASK_ID = "task_id"
    const val EXTRA_EXPECTED_TRIGGER_AT_MS = "expected_trigger_at_ms"
    const val EXTRA_ALARM_RECEIVED_AT_MS = "alarm_received_at_ms"
    const val EXTRA_DISPATCH_TOKEN = "dispatch_token"
    const val EXTRA_WAKE_SUMMARY = "wake_summary"

    private val dispatchedTokens = ConcurrentHashMap.newKeySet<String>()

    data class PreparationResult(
        val state: ScreenWakeState,
        val plan: ScreenWakePlan,
        val dispatchToken: String
    )

    fun prepareForScheduledExecution(
        context: Context,
        taskId: String,
        expectedTriggerAtMs: Long?,
        alarmReceivedAtMs: Long
    ): PreparationResult {
        val state = inspectState(context)
        val plan = ScreenWakePlanner.buildPlan(state)
        val dispatchToken = buildDispatchToken(taskId, alarmReceivedAtMs)

        if (plan.shouldAcquireWakeLock) {
            WakeLockManager.acquireScreenWakeLock()
        }

        val wakeIntent = buildWakeActivityIntent(
            context = context,
            taskId = taskId,
            expectedTriggerAtMs = expectedTriggerAtMs,
            alarmReceivedAtMs = alarmReceivedAtMs,
            dispatchToken = dispatchToken,
            wakeSummary = plan.summary
        )

        if (plan.shouldLaunchWakeActivity) {
            launchWakeActivity(context, wakeIntent)
        }

        Log.i(
            TAG,
            "Prepared screen wake: interactive=${state.interactive}, " +
                "keyguardLocked=${state.keyguardLocked}, keyguardSecure=${state.keyguardSecure}, " +
                "plan=${plan.summary}, delay=${plan.postWakeDelayMs}ms, token=$dispatchToken"
        )

        return PreparationResult(
            state = state,
            plan = plan,
            dispatchToken = dispatchToken
        )
    }

    fun buildWakeActivityIntent(
        context: Context,
        taskId: String,
        expectedTriggerAtMs: Long?,
        alarmReceivedAtMs: Long,
        dispatchToken: String,
        wakeSummary: String
    ): Intent {
        return Intent(context, ScheduledTaskWakeActivity::class.java).apply {
            action = ACTION_WAKE_AND_EXECUTE
            putExtra(EXTRA_TASK_ID, taskId)
            putExtra(EXTRA_ALARM_RECEIVED_AT_MS, alarmReceivedAtMs)
            expectedTriggerAtMs?.let { putExtra(EXTRA_EXPECTED_TRIGGER_AT_MS, it) }
            putExtra(EXTRA_DISPATCH_TOKEN, dispatchToken)
            putExtra(EXTRA_WAKE_SUMMARY, wakeSummary)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
            addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
        }
    }

    fun tryMarkDispatched(dispatchToken: String, source: String): Boolean {
        val first = dispatchedTokens.add(dispatchToken)
        if (first) {
            Log.i(TAG, "Scheduled task dispatch token claimed by $source: $dispatchToken")
        } else {
            Log.w(TAG, "Scheduled task dispatch token already claimed, skip $source: $dispatchToken")
        }
        return first
    }

    fun hasDispatched(dispatchToken: String): Boolean {
        return dispatchToken in dispatchedTokens
    }

    private fun inspectState(context: Context): ScreenWakeState {
        val powerManager = context.getSystemService(PowerManager::class.java)
        val keyguardManager = context.getSystemService(KeyguardManager::class.java)

        val interactive = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH) {
            powerManager?.isInteractive ?: true
        } else {
            @Suppress("DEPRECATION")
            powerManager?.isScreenOn ?: true
        }

        val keyguardLocked = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            keyguardManager?.isKeyguardLocked ?: false
        } else {
            keyguardManager?.inKeyguardRestrictedInputMode() ?: false
        }

        val keyguardSecure = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            keyguardManager?.isDeviceSecure ?: false
        } else {
            keyguardManager?.isKeyguardSecure ?: false
        }

        return ScreenWakeState(
            interactive = interactive,
            keyguardLocked = keyguardLocked,
            keyguardSecure = keyguardSecure
        )
    }

    private fun launchWakeActivity(context: Context, intent: Intent) {
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to launch wake activity", e)
        }
    }

    private fun buildDispatchToken(taskId: String, alarmReceivedAtMs: Long): String {
        return "$taskId:$alarmReceivedAtMs"
    }
}
