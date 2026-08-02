package com.shijing.xomniclaw.scheduler

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 开机 / 升级后重新注册闹钟。
 *
 * AlarmManager 的闹钟在重启后不会自动恢复，因此需要在系统广播到达时主动重建。
 */
class ScheduledTaskBootReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "ScheduledTaskBootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                Log.i(TAG, "Rescheduling tasks after system/package event: ${intent.action}")
                ScheduledTaskManager(context).rescheduleAll()
            }

            else -> Log.w(TAG, "Ignore unexpected action: ${intent.action}")
        }
    }
}
