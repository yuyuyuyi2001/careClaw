package com.shijing.xomniclaw.scheduler

/**
 * 纯规则层的亮屏规划器。
 *
 * 这里不直接依赖 Android API，方便单元测试，也方便把“息屏”和“锁屏”拆开判断。
 */
data class ScreenWakeState(
    val interactive: Boolean,
    val keyguardLocked: Boolean,
    val keyguardSecure: Boolean
)

data class ScreenWakePlan(
    val shouldAcquireWakeLock: Boolean,
    val shouldLaunchWakeActivity: Boolean,
    val expectedUiAutomationBlocked: Boolean,
    val postWakeDelayMs: Long,
    val summary: String
)

object ScreenWakePlanner {
    fun buildPlan(state: ScreenWakeState): ScreenWakePlan {
        val shouldLaunchWakeActivity = !state.interactive || state.keyguardLocked
        val expectedUiAutomationBlocked = state.keyguardLocked && state.keyguardSecure
        val shouldAcquireWakeLock = shouldLaunchWakeActivity || state.interactive

        val summary = when {
            expectedUiAutomationBlocked ->
                "device_locked_secure_keyguard"
            state.keyguardLocked ->
                "device_locked_non_secure_keyguard"
            !state.interactive ->
                "screen_off_needs_wake"
            else ->
                "screen_ready"
        }

        val postWakeDelayMs = when {
            expectedUiAutomationBlocked -> 1800L
            shouldLaunchWakeActivity -> 1200L
            else -> 150L
        }

        return ScreenWakePlan(
            shouldAcquireWakeLock = shouldAcquireWakeLock,
            shouldLaunchWakeActivity = shouldLaunchWakeActivity,
            expectedUiAutomationBlocked = expectedUiAutomationBlocked,
            postWakeDelayMs = postWakeDelayMs,
            summary = summary
        )
    }
}
