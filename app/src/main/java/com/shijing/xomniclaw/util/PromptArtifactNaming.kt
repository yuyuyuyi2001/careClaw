package com.shijing.xomniclaw.util

import com.shijing.xomniclaw.providers.llm.Message
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 统一生成 prompt-dump / screenshot 等调试产物文件名。
 *
 * 目标：
 * 1. query 前缀规则保持一致
 * 2. iter 标签保持一致
 * 3. 截图 / prompt-dump / agentloop 日志尽量共享同一批次时间戳
 */
object PromptArtifactNaming {

    private const val NO_QUERY = "no_query"
    private val CONTEXT_FILE = File("/sdcard/.xomniclaw/workspace/.xomniclaw/artifact-context.txt")

    /**
     * 识别「日期_时分秒_毫秒」旧格式，用于与 agentloop 日志的秒级前缀对齐。
     */
    private val TIMESTAMP_WITH_MILLIS =
        Regex("""^\d{4}-\d{2}-\d{2}_\d{2}-\d{2}-\d{2}_\d{3}$""")
    private data class ArtifactContext(
        val queryPrefix: String,
        val iteration: Int,
        val timestamp: String,
    )

    @Volatile
    private var latestQueryPrefix: String = NO_QUERY

    @Volatile
    private var latestIteration: Int = 0

    @Volatile
    private var latestTimestamp: String = ""

    @JvmStatic
    fun beginAgentLoop(userMessage: String): String {
        val timestamp = buildSessionTimestamp()
        latestQueryPrefix = buildQueryPrefix(userMessage)
        latestIteration = 0
        latestTimestamp = timestamp
        persistContext(latestQueryPrefix, latestIteration, latestTimestamp)
        return timestamp
    }

    @JvmStatic
    fun rememberRequest(messages: List<Message>, iteration: Int): String {
        ensureLatestContextLoaded()
        if (latestTimestamp.isBlank()) {
            latestTimestamp = buildSessionTimestamp()
        }
        // query 前缀以 AgentLoop 入口的用户问题为准；只有完全缺失上下文时才回退到当前 messages 推断。
        latestQueryPrefix = resolveRememberedQueryPrefix(messages)
        latestIteration = iteration.coerceAtLeast(0)
        persistContext(latestQueryPrefix, latestIteration, latestTimestamp)
        return latestTimestamp
    }

    @JvmStatic
    fun buildPromptDumpFilename(
        messages: List<Message>,
        iteration: Int,
        providerName: String,
        modelId: String
    ): String {
        val timestamp = getRememberedTimestamp()
        return buildPromptDumpFilename(
            timestamp = timestamp,
            messages = messages,
            iteration = iteration,
            providerName = providerName,
            modelId = modelId
        )
    }

    @JvmStatic
    fun buildPromptDumpFilename(
        timestamp: String,
        messages: List<Message>,
        iteration: Int,
        providerName: String,
        modelId: String
    ): String {
        val queryPrefix = resolveRememberedQueryPrefix(messages)
        val iterTag = buildIterTag(iteration)
        val normalizedTimestamp = normalizeToSessionTimestamp(timestamp)
        return "${normalizedTimestamp}_${queryPrefix}${iterTag}_${providerName}_${modelId.replace('/', '_')}.json"
    }

    @JvmStatic
    fun buildScreenshotFile(dir: File, suffix: String = "screenshot"): File {
        if (!dir.exists()) {
            dir.mkdirs()
        }
        ensureLatestContextLoaded()
        // 与 agentloop_YYYY-MM-DD_HH-MM-SS 一致：仅秒级，禁止回退到带毫秒的 buildTimestamp()
        val ts = normalizeToSessionTimestamp(latestTimestamp.ifBlank { buildSessionTimestamp() })
        val queryPrefix = latestQueryPrefix.ifBlank { NO_QUERY }
        val iterTag = buildIterTag(latestIteration)
        val safeSuffix = sanitizeSegment(suffix, 24)
        return File(dir, "${ts}_${queryPrefix}${iterTag}_${safeSuffix}.png")
    }

    /**
     * 返回当前请求批次的时间戳。
     *
     * 若内存上下文已丢失，则尝试从持久化文件恢复；只有完全没有上下文时才生成新时间戳。
     */
    @JvmStatic
    fun getRememberedTimestamp(): String {
        ensureLatestContextLoaded()
        return normalizeToSessionTimestamp(latestTimestamp.ifBlank { buildSessionTimestamp() })
    }

    @JvmStatic
    fun buildQueryPrefix(messages: List<Message>): String {
        val userContent = messages
            .asReversed()
            .firstOrNull { it.role == "user" && it.content.isNotBlank() }
            ?.content
            ?.trim()
            .orEmpty()

        return buildQueryPrefix(userContent)
    }

    @JvmStatic
    fun buildQueryPrefix(raw: String): String {
        if (raw.isBlank()) {
            return NO_QUERY
        }

        val prefix = buildString {
            raw.take(20).forEach { ch ->
                append(
                    when {
                        ch.isLetterOrDigit() -> ch
                        ch in '\u4e00'..'\u9fa5' -> ch
                        else -> '_'
                    }
                )
            }
        }.trim('_')

        return prefix.ifBlank { NO_QUERY }
    }

    @JvmStatic
    fun buildTimestamp(): String {
        return SimpleDateFormat("yyyy-MM-dd_HH-mm-ss_SSS", Locale.US).format(Date())
    }

    @JvmStatic
    fun buildSessionTimestamp(): String {
        return SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
    }

    /**
     * 将持久化或历史遗留的「…_HH-mm-ss_SSS」规范为秒级「…_HH-mm-ss」，与日志批次前缀一致。
     */
    private fun normalizeToSessionTimestamp(raw: String): String {
        val t = raw.trim()
        if (t.isEmpty()) return t
        return if (TIMESTAMP_WITH_MILLIS.matches(t)) {
            t.dropLast(4) // 去掉 "_SSS"
        } else {
            t
        }
    }

    private fun buildIterTag(iteration: Int): String {
        return if (iteration > 0) {
            "_iter${iteration.toString().padStart(3, '0')}"
        } else {
            ""
        }
    }

    /**
     * 调试产物前缀一旦由 AgentLoop 入口锁定，就不要在后续 provider 请求中漂移。
     */
    private fun resolveRememberedQueryPrefix(messages: List<Message>): String {
        ensureLatestContextLoaded()
        return latestQueryPrefix.takeUnless { it.isBlank() || it == NO_QUERY }
            ?: buildQueryPrefix(messages)
    }

    private fun sanitizeSegment(raw: String, maxChars: Int): String {
        val sanitized = buildString {
            raw.take(maxChars).forEach { ch ->
                append(
                    when {
                        ch.isLetterOrDigit() -> ch
                        ch in '\u4e00'..'\u9fa5' -> ch
                        ch == '_' || ch == '-' -> ch
                        else -> '_'
                    }
                )
            }
        }.trim('_')

        return sanitized.ifBlank { "artifact" }
    }

    private fun ensureLatestContextLoaded() {
        val hasInMemoryContext = latestQueryPrefix != NO_QUERY || latestIteration != 0 || latestTimestamp.isNotBlank()
        if (hasInMemoryContext) return
        loadPersistedContext()?.let { context ->
            latestQueryPrefix = context.queryPrefix
            latestIteration = context.iteration
            latestTimestamp = normalizeToSessionTimestamp(context.timestamp)
        }
    }

    private fun persistContext(queryPrefix: String, iteration: Int, timestamp: String) {
        runCatching {
            CONTEXT_FILE.parentFile?.mkdirs()
            CONTEXT_FILE.writeText(
                buildString {
                    appendLine(queryPrefix)
                    appendLine(iteration)
                    append(timestamp)
                }
            )
        }
    }

    private fun loadPersistedContext(): ArtifactContext? {
        return runCatching {
            if (!CONTEXT_FILE.exists()) return null
            val lines = CONTEXT_FILE.readLines()
            val queryPrefix = lines.getOrNull(0)?.trim().orEmpty().ifBlank { NO_QUERY }
            val iteration = lines.getOrNull(1)?.trim()?.toIntOrNull() ?: 0
            val timestamp = lines.getOrNull(2)?.trim().orEmpty()
            ArtifactContext(queryPrefix, iteration, timestamp)
        }.getOrNull()
    }
}
