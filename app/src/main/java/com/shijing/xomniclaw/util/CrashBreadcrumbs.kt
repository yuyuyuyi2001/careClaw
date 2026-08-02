package com.shijing.xomniclaw.util

import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 记录“死前最后一步”的轻量埋点。
 *
 * 设计目标：
 * 1. 每一步同步落盘，尽量在进程突然死亡前保住最后现场
 * 2. 不依赖复杂初始化，避免调试工具本身成为新故障点
 * 3. 同时保留最新一步和滚动历史，方便快速判断卡在哪个阶段
 */
object CrashBreadcrumbs {
    private const val TAG = "CrashBreadcrumbs"
    private const val ROOT_DIR = "/sdcard/.xomniclaw/logs"
    private const val HISTORY_FILE = "$ROOT_DIR/companion_last_steps.log"
    private const val LATEST_FILE = "$ROOT_DIR/companion_last_step.txt"
    private const val MAX_HISTORY_LINES = 200

    private val timestampFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    @Synchronized
    fun mark(stage: String, detail: String = "") {
        runCatching {
            val dir = File(ROOT_DIR)
            if (!dir.exists()) {
                dir.mkdirs()
            }

            val line = buildString {
                append(timestampFormat.format(Date()))
                append(" | ")
                append(stage)
                if (detail.isNotBlank()) {
                    append(" | ")
                    append(detail.replace('\n', ' '))
                }
            }

            // 单独写一份“最后一步”，排查时无需翻完整历史。
            File(LATEST_FILE).writeText(line + System.lineSeparator())

            val historyFile = File(HISTORY_FILE)
            historyFile.appendText(line + System.lineSeparator())

            trimHistoryIfNeeded(historyFile)
            Log.i(TAG, line)
        }.onFailure { error ->
            Log.w(TAG, "Failed to write breadcrumb: ${error.message}")
        }
    }

    fun readLatest(): String? {
        return runCatching {
            File(LATEST_FILE)
                .takeIf { it.exists() }
                ?.readText()
                ?.trim()
                ?.takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    private fun trimHistoryIfNeeded(historyFile: File) {
        val lines = historyFile.readLines()
        if (lines.size > MAX_HISTORY_LINES) {
            historyFile.writeText(lines.takeLast(MAX_HISTORY_LINES).joinToString(System.lineSeparator()))
            historyFile.appendText(System.lineSeparator())
        }
    }
}
