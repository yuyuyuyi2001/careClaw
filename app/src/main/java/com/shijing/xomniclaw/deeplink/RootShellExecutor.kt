package com.shijing.xomniclaw.deeplink

import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * Root Shell 执行器。
 *
 * 这个特性只在 root 可用时暴露入口，因此实现也只保留 root 分支，
 * 避免把 Shizuku、ADB 等额外依赖混进当前集成。
 */
object RootShellExecutor {
    data class Result(
        val exitCode: Int,
        val output: String,
        val error: String
    ) {
        val success: Boolean
            get() = exitCode == 0 && !error.contains("SecurityException", ignoreCase = true)
    }

    private const val DETECTION_CACHE_MS = 5_000L

    @Volatile
    private var lastDetectedAtMs: Long = 0L

    @Volatile
    private var rootAvailableCache: Boolean = false

    /**
     * 检测当前设备是否能执行 `su -c`。
     * 加一个很短的缓存，避免底部导航和列表页反复触发阻塞式探测。
     */
    fun hasRootAccess(forceRefresh: Boolean = false): Boolean {
        val now = System.currentTimeMillis()
        if (!forceRefresh && now - lastDetectedAtMs < DETECTION_CACHE_MS) {
            return rootAvailableCache
        }
        rootAvailableCache = try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            val output = BufferedReader(InputStreamReader(process.inputStream)).readText()
            val finished = process.waitFor(2, TimeUnit.SECONDS)
            finished && process.exitValue() == 0 && output.contains("uid=0")
        } catch (_: Exception) {
            false
        }
        lastDetectedAtMs = now
        return rootAvailableCache
    }

    fun execute(command: String): Result {
        if (!hasRootAccess()) {
            return Result(exitCode = -1, output = "", error = "Root unavailable")
        }
        return executeInternal(arrayOf("su", "-c", command))
    }

    fun executeAmCommand(amCommand: String): Result {
        if (!amCommand.startsWith("am start")) {
            return Result(exitCode = -1, output = "", error = "Invalid am command")
        }
        return execute(amCommand)
    }

    private fun executeInternal(argv: Array<String>): Result {
        return try {
            val process = Runtime.getRuntime().exec(argv)
            val outputBuilder = StringBuilder()
            val errorBuilder = StringBuilder()

            val outputThread = Thread {
                try {
                    BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                        reader.forEachLine { outputBuilder.appendLine(it) }
                    }
                } catch (_: Exception) {
                }
            }
            val errorThread = Thread {
                try {
                    BufferedReader(InputStreamReader(process.errorStream)).use { reader ->
                        reader.forEachLine { errorBuilder.appendLine(it) }
                    }
                } catch (_: Exception) {
                }
            }

            outputThread.start()
            errorThread.start()

            val finished = process.waitFor(15, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                return Result(exitCode = -1, output = "", error = "Root command timeout (15s)")
            }

            outputThread.join(2_000L)
            errorThread.join(2_000L)

            Result(
                exitCode = process.exitValue(),
                output = outputBuilder.toString().trim(),
                error = errorBuilder.toString().trim()
            )
        } catch (e: Exception) {
            Result(exitCode = -1, output = "", error = "Root exec failed: ${e.message}")
        }
    }
}
