package com.shijing.xomniclaw.deeplink

/**
 * 用 root 抓取当前前台 Activity 对应的完整 Intent 信息。
 *
 * 为了尽量减少 `dumpsys` 带来的耗时，这里保留与 `PageBookmark` 类似的三级降级策略。
 */
object DeeplinkIntentCapture {
    fun capture(
        packageName: String,
        activityName: String
    ): DeeplinkIntentParser.CapturedIntentSpec? {
        if (!RootShellExecutor.hasRootAccess()) {
            return null
        }

        val shortClass = activityName.substringAfterLast(".")

        val grepResult = RootShellExecutor.execute(
            "dumpsys activity activities | grep -B 2 -A 60 'Hist.*$shortClass' | head -80"
        )
        if (grepResult.success && grepResult.output.isNotBlank()) {
            DeeplinkIntentParser.parseBlock(
                lines = grepResult.output.lines(),
                packageName = packageName,
                activityName = activityName
            )?.let { return it }
        }

        val resumedResult = RootShellExecutor.execute(
            "dumpsys activity activities | grep mResumedActivity"
        )
        if (resumedResult.success && resumedResult.output.contains(packageName)) {
            val packageScopedResult = RootShellExecutor.execute(
                "dumpsys activity activities | grep -B 2 -A 60 'Hist.*$packageName' | head -120"
            )
            if (packageScopedResult.success && packageScopedResult.output.isNotBlank()) {
                DeeplinkIntentParser.parseBlock(
                    lines = packageScopedResult.output.lines(),
                    packageName = packageName,
                    activityName = activityName
                )?.let { return it }
            }
        }

        val fullDumpResult = RootShellExecutor.execute("dumpsys activity activities")
        if (!fullDumpResult.success || fullDumpResult.output.isBlank()) {
            return null
        }
        return DeeplinkIntentParser.parseBlock(
            lines = fullDumpResult.output.lines(),
            packageName = packageName,
            activityName = activityName
        )
    }
}
