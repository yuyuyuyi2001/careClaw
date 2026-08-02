package com.shijing.xomniclaw.scheduler

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.WindowManager

/**
 * 仅用于定时任务场景的短生命周期亮屏 Activity。
 *
 * 作用：
 * 1. 在息屏时请求点亮屏幕；
 * 2. 在无强凭证锁屏时，尝试把锁屏页收起；
 * 3. 执行完成后立即退出，不打扰正常前台界面。
 */
class ScheduledTaskWakeActivity : Activity() {
    companion object {
        private const val TAG = "ScheduledTaskWakeActivity"
        private const val DISPATCH_AFTER_RESUME_DELAY_MS = 1200L
        private const val AUTO_FINISH_DELAY_MS = 3500L
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        tryTurnScreenOnAndShowOverLockscreen()
        tryDismissKeyguard()
        scheduleDispatch(intent)
    }

    override fun onResume() {
        super.onResume()
        tryDismissKeyguard()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        tryTurnScreenOnAndShowOverLockscreen()
        tryDismissKeyguard()
        scheduleDispatch(intent)
    }

    private fun tryTurnScreenOnAndShowOverLockscreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setTurnScreenOn(true)
            setShowWhenLocked(true)
        }

        @Suppress("DEPRECATION")
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
        )
    }

    private fun tryDismissKeyguard() {
        val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager ?: return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                keyguardManager.requestDismissKeyguard(this, null)
                Log.i(TAG, "Requested keyguard dismissal for scheduled task execution")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to dismiss keyguard", e)
            }
        }
    }

    private fun scheduleDispatch(intent: Intent?) {
        val taskId = intent?.getStringExtra(ScreenWakeCoordinator.EXTRA_TASK_ID)
        val dispatchToken = intent?.getStringExtra(ScreenWakeCoordinator.EXTRA_DISPATCH_TOKEN)
        if (taskId.isNullOrBlank() || dispatchToken.isNullOrBlank()) {
            Log.w(TAG, "Wake activity started without scheduled task payload")
            finishLater(AUTO_FINISH_DELAY_MS)
            return
        }

        val expectedTriggerAtMs = intent.getLongExtra(
            ScreenWakeCoordinator.EXTRA_EXPECTED_TRIGGER_AT_MS,
            -1L
        ).takeIf { it > 0L }
        val alarmReceivedAtMs = intent.getLongExtra(
            ScreenWakeCoordinator.EXTRA_ALARM_RECEIVED_AT_MS,
            System.currentTimeMillis()
        )
        val wakeSummary = intent.getStringExtra(ScreenWakeCoordinator.EXTRA_WAKE_SUMMARY)

        // 等系统完成亮屏、锁屏层收起和无障碍服务恢复后，再把 Agent 派发到前台场景执行。
        mainHandler.postDelayed(
            {
                dispatchScheduledTask(
                    taskId = taskId,
                    expectedTriggerAtMs = expectedTriggerAtMs,
                    alarmReceivedAtMs = alarmReceivedAtMs,
                    dispatchToken = dispatchToken,
                    wakeSummary = wakeSummary
                )
            },
            DISPATCH_AFTER_RESUME_DELAY_MS
        )
        finishLater(AUTO_FINISH_DELAY_MS)
    }

    private fun dispatchScheduledTask(
        taskId: String,
        expectedTriggerAtMs: Long?,
        alarmReceivedAtMs: Long,
        dispatchToken: String,
        wakeSummary: String?
    ) {
        if (!ScreenWakeCoordinator.tryMarkDispatched(dispatchToken, "wake_activity")) {
            finishSafely()
            return
        }

        try {
            ScheduledTaskManager(applicationContext).handleTriggeredTask(
                taskId = taskId,
                expectedTriggerAtMs = expectedTriggerAtMs,
                alarmReceivedAtMs = alarmReceivedAtMs,
                triggerSource = "wake_activity",
                wakeSummary = wakeSummary
            )
            Log.i(TAG, "Scheduled task dispatched from wake activity: $taskId")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to dispatch scheduled task from wake activity", e)
        }
    }

    private fun finishLater(delayMs: Long) {
        mainHandler.postDelayed({ finishSafely() }, delayMs)
    }

    private fun finishSafely() {
        try {
            finish()
            overridePendingTransition(0, 0)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to finish wake activity cleanly", e)
        }
    }
}
