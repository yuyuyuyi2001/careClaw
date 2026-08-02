package com.shijing.xomniclaw.util

import android.content.Context
import android.provider.Settings
import android.util.Log
import java.util.concurrent.TimeUnit

/**
 * 尝试自动开启无障碍服务。
 *
 * 说明：
 * - 普通应用通常无权限直接修改系统无障碍设置，必须用户手动授权。
 * - 若设备授予了 WRITE_SECURE_SETTINGS（系统签名/adb 授权）或有 root，本方法可自动生效。
 */
object AccessibilityAutoEnableHelper {
    private const val TAG = "AutoEnableA11y"
    private const val SERVICE_CLASS = "com.shijing.xomniclaw.accessibility.service.PhoneAccessibilityService"
    private const val LEGACY_SERVICE_PACKAGE = "com.shijing.xomniclaw.accessibility"

    data class Result(
        val success: Boolean,
        val method: String,
        val message: String
    )

    fun tryEnable(context: Context): Result {
        val target = "${context.packageName}/$SERVICE_CLASS"
        val legacyTarget = "$LEGACY_SERVICE_PACKAGE/$SERVICE_CLASS"

        val current = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: ""

        if (containsService(current, target, legacyTarget)) {
            return Result(true, "already_enabled", "accessibility already enabled")
        }

        val merged = mergeServiceList(current, target)

        // 1) Try direct Secure Settings write (requires privileged permission)
        try {
            Settings.Secure.putString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                merged
            )
            Settings.Secure.putInt(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)

            val readBack = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: ""
            if (containsService(readBack, target, legacyTarget)) {
                Log.i(TAG, "Accessibility auto-enabled via Secure settings write")
                return Result(true, "secure_settings", "enabled via secure settings")
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "No permission for direct secure settings write: ${e.message}")
        } catch (e: Exception) {
            Log.w(TAG, "Direct secure settings write failed: ${e.message}")
        }

        // 2) Try root shell fallback
        val okList = runRoot("settings put secure enabled_accessibility_services \"$merged\"")
        val okEnabled = runRoot("settings put secure accessibility_enabled 1")
        if (okList && okEnabled) {
            val readBack = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: ""
            if (containsService(readBack, target, legacyTarget)) {
                Log.i(TAG, "Accessibility auto-enabled via root shell")
                return Result(true, "root_shell", "enabled via root shell")
            }
        }

        return Result(
            success = false,
            method = "manual_required",
            message = "no privileged permission; user must enable in settings"
        )
    }

    private fun containsService(enabledServices: String, target: String, legacyTarget: String): Boolean {
        return enabledServices.contains(target) || enabledServices.contains(legacyTarget)
    }

    private fun mergeServiceList(current: String, target: String): String {
        if (current.isBlank()) return target
        val parts = current.split(':')
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toMutableList()
        if (!parts.contains(target)) parts.add(target)
        return parts.joinToString(":")
    }

    private fun runRoot(cmd: String): Boolean {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
            val finished = process.waitFor(2, TimeUnit.SECONDS)
            finished && process.exitValue() == 0
        } catch (_: Exception) {
            false
        }
    }
}

