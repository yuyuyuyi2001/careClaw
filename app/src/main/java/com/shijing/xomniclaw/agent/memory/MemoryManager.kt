package com.shijing.xomniclaw.agent.memory

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * Memory Manager — aligned with OmniClaw memory system.
 *
 * Features:
 * - Long-term memory (MEMORY.md) read/write
 * - Daily log (memory/YYYY-MM-DD.md) append
 * - Memory file path management
 * - MemoryIndex integration (SQLite + FTS5 + vector search)
 */
class MemoryManager(
    private val workspacePath: String,
    private val context: Context? = null,
    embeddingBaseUrl: String = "",
    embeddingApiKey: String = "",
    embeddingModel: String = "text-embedding-3-small"
) {
    companion object {
        private const val TAG = "MemoryManager"
        private const val MEMORY_FILE = "MEMORY.md"
        private const val MEMORY_DIR = "memory"
        private val LEGACY_MEMORY_FILE_NAMES = mapOf(
            "IMAGE-MEMORY.md" to listOf("image-memories.md", "image-memory.md"),
            "USER-PROFILE.md" to listOf("user-profile.md")
        )
        private val DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    }

    private val workspaceDir = File(workspacePath)
    private val memoryFile = File(workspaceDir, MEMORY_FILE)
    private val memoryDir = File(workspaceDir, MEMORY_DIR)

    private var embeddingProvider: EmbeddingProvider? = null
    private var memoryIndex: MemoryIndex? = null

    init {
        if (!workspaceDir.exists()) workspaceDir.mkdirs()
        if (!memoryDir.exists()) memoryDir.mkdirs()
        migrateLegacyMemoryFileNames()

        // Initialize embedding provider and memory index if context available
        if (context != null) {
            embeddingProvider = EmbeddingProvider(
                baseUrl = embeddingBaseUrl,
                apiKey = embeddingApiKey,
                model = embeddingModel
            )
            memoryIndex = MemoryIndex(context, embeddingProvider)
            Log.d(TAG, "MemoryIndex initialized (embedding available: ${embeddingProvider?.isAvailable})")
        }
    }

    /**
     * 旧版本使用小写记忆文件名；新版本统一使用大写命名。
     * 这里仅做一次文件迁移，后续读写仍全部走新文件名，避免历史记忆丢失。
     */
    private fun migrateLegacyMemoryFileNames() {
        LEGACY_MEMORY_FILE_NAMES.forEach { (currentName, legacyNames) ->
            val currentFile = File(memoryDir, currentName)
            if (currentFile.exists()) {
                return@forEach
            }

            val legacyFile = legacyNames
                .map { File(memoryDir, it) }
                .firstOrNull { it.exists() && it.isFile }
                ?: return@forEach

            runCatching {
                currentFile.parentFile?.mkdirs()
                if (!legacyFile.renameTo(currentFile)) {
                    legacyFile.copyTo(currentFile, overwrite = false)
                    legacyFile.delete()
                }
                Log.i(TAG, "Migrated memory file ${legacyFile.name} -> $currentName")
            }.onFailure {
                Log.w(TAG, "Failed to migrate memory file ${legacyFile.name} -> $currentName", it)
            }
        }
    }

    /**
     * Initialize MemoryIndex with context (call after construction if context wasn't provided).
     */
    fun initIndex(ctx: Context, baseUrl: String = "", apiKey: String = "", model: String = "text-embedding-3-small") {
        embeddingProvider = EmbeddingProvider(baseUrl = baseUrl, apiKey = apiKey, model = model)
        memoryIndex = MemoryIndex(ctx, embeddingProvider)
        Log.d(TAG, "MemoryIndex initialized (embedding available: ${embeddingProvider?.isAvailable})")
    }

    fun getMemoryIndex(): MemoryIndex? = memoryIndex
    fun getEmbeddingProvider(): EmbeddingProvider? = embeddingProvider

    /**
     * Sync all memory files into the index.
     */
    suspend fun syncIndex() {
        val index = memoryIndex ?: return
        val files = getAllMemoryFiles()
        index.sync(files.map { File(it) })
    }

    /**
     * Get all memory-related files for indexing.
     */
    private fun getAllMemoryFiles(): List<String> {
        val files = mutableListOf<String>()
        if (memoryFile.exists()) files.add(memoryFile.absolutePath)

        // All .md files in memory/ directory
        memoryDir.listFiles { f -> f.isFile && f.name.endsWith(".md") }
            ?.forEach { files.add(it.absolutePath) }

        // Other workspace .md files (SOUL.md, etc.)
        workspaceDir.listFiles { f -> f.isFile && f.name.endsWith(".md") && f.name != MEMORY_FILE }
            ?.forEach { files.add(it.absolutePath) }

        return files
    }

    /**
     * Index a single file (call on file change).
     */
    suspend fun indexFile(file: File, source: String = "memory") {
        memoryIndex?.indexFile(file, source)
    }

    // ---- Existing MemoryManager methods (unchanged) ----

    suspend fun readMemory(): String = withContext(Dispatchers.IO) {
        try {
            if (memoryFile.exists()) memoryFile.readText()
            else {
                Log.d(TAG, "MEMORY.md does not exist, creating template")
                createMemoryTemplate()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read MEMORY.md", e)
            ""
        }
    }

    suspend fun writeMemory(content: String) = withContext(Dispatchers.IO) {
        try {
            memoryFile.writeText(content)
            Log.d(TAG, "MEMORY.md written successfully")
            // Re-index after write
            memoryIndex?.indexFile(memoryFile)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write MEMORY.md", e)
        }
    }

    suspend fun appendToMemory(section: String, content: String) = withContext(Dispatchers.IO) {
        try {
            val currentContent = readMemory()
            val newContent = if (currentContent.contains(section)) {
                currentContent.replace(section, "$section\n$content")
            } else {
                "$currentContent\n\n$section\n$content"
            }
            writeMemory(newContent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to append to MEMORY.md", e)
        }
    }

    suspend fun getTodayLog(): String = withContext(Dispatchers.IO) {
        val today = DATE_FORMAT.format(Date())
        val logFile = File(memoryDir, "$today.md")
        try { if (logFile.exists()) logFile.readText() else "" }
        catch (e: Exception) { Log.e(TAG, "Failed to read today's log", e); "" }
    }

    suspend fun getYesterdayLog(): String = withContext(Dispatchers.IO) {
        val calendar = Calendar.getInstance().apply { add(Calendar.DAY_OF_MONTH, -1) }
        val yesterday = DATE_FORMAT.format(calendar.time)
        val logFile = File(memoryDir, "$yesterday.md")
        try { if (logFile.exists()) logFile.readText() else "" }
        catch (e: Exception) { Log.e(TAG, "Failed to read yesterday's log", e); "" }
    }

    suspend fun appendToToday(content: String) = withContext(Dispatchers.IO) {
        val today = DATE_FORMAT.format(Date())
        val logFile = File(memoryDir, "$today.md")
        try {
            if (!logFile.exists()) {
                logFile.writeText("# Daily Log - $today\n\n")
            }
            val timestamp = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
            logFile.appendText("\n## [$timestamp]\n$content\n")
            // Re-index
            memoryIndex?.indexFile(logFile)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to append to today's log", e)
        }
    }

    suspend fun getLogByDate(date: String): String = withContext(Dispatchers.IO) {
        val logFile = File(memoryDir, "$date.md")
        try { if (logFile.exists()) logFile.readText() else "" }
        catch (e: Exception) { Log.e(TAG, "Failed to read log: $date.md", e); "" }
    }

    suspend fun listLogs(): List<String> = withContext(Dispatchers.IO) {
        try {
            memoryDir.listFiles { f -> f.name.matches(Regex("\\d{4}-\\d{2}-\\d{2}\\.md")) }
                ?.map { it.nameWithoutExtension }?.sortedDescending() ?: emptyList()
        } catch (e: Exception) { Log.e(TAG, "Failed to list logs", e); emptyList() }
    }

    suspend fun listMemoryFiles(): List<String> = withContext(Dispatchers.IO) {
        try {
            val files = mutableListOf<String>()
            if (memoryFile.exists()) files.add(memoryFile.absolutePath)
            memoryDir.listFiles { f ->
                f.isFile && f.name.endsWith(".md") && !f.name.matches(Regex("\\d{4}-\\d{2}-\\d{2}\\.md"))
            }?.forEach { files.add(it.absolutePath) }
            files
        } catch (e: Exception) { Log.e(TAG, "Failed to list memory files", e); emptyList() }
    }

    /**
     * 读取指定 memory 文件内容。
     *
     * 仅允许读取：
     * - `MEMORY.md`
     * - `memory/` 目录下的 markdown 文件
     */
    suspend fun readNamedMemoryFile(fileName: String): String = withContext(Dispatchers.IO) {
        try {
            if (fileName == MEMORY_FILE) {
                return@withContext readMemory()
            }

            require(!fileName.contains("/") && !fileName.contains("\\")) { "Invalid memory file name" }
            val target = File(memoryDir, fileName)
            require(target.canonicalPath.startsWith(memoryDir.canonicalPath)) { "Invalid memory file path" }
            if (!target.exists() || !target.isFile) {
                return@withContext ""
            }
            target.readText()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read memory file: $fileName", e)
            ""
        }
    }

    /**
     * 读取 workspace 根目录下的 markdown 文件。
     *
     * 用于读取 `AGENTS.md`、`SOUL.md` 等 workspace 文件，让画像生成器可复用显式信息（全局交互准则等现位于 AGENTS 内）。
     */
    suspend fun readWorkspaceMarkdownFile(fileName: String): String = withContext(Dispatchers.IO) {
        try {
            require(fileName.isNotBlank()) { "fileName cannot be blank" }
            require(fileName.endsWith(".md", ignoreCase = true)) { "Only markdown files are allowed" }
            require(!fileName.contains("/") && !fileName.contains("\\")) { "Invalid workspace file name" }
            val target = File(workspaceDir, fileName)
            require(target.canonicalPath.startsWith(workspaceDir.canonicalPath)) { "Invalid workspace file path" }
            if (!target.exists() || !target.isFile) {
                return@withContext ""
            }
            target.readText()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read workspace markdown file: $fileName", e)
            ""
        }
    }

    /**
     * 写入 `memory/` 目录下的指定文件。
     *
     * 仅允许写入单层文件名，避免把通用 MemoryManager 变成任意路径写入口。
     */
    suspend fun writeNamedMemoryFile(fileName: String, content: String) = withContext(Dispatchers.IO) {
        try {
            require(fileName.isNotBlank()) { "fileName cannot be blank" }
            require(!fileName.contains("/") && !fileName.contains("\\")) { "Invalid memory file name" }
            val target = File(memoryDir, fileName)
            require(target.canonicalPath.startsWith(memoryDir.canonicalPath)) { "Invalid memory file path" }
            target.parentFile?.mkdirs()
            target.writeText(content)
            memoryIndex?.indexFile(target)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write memory file: $fileName", e)
        }
    }

    /**
     * 追加写入 `memory/` 目录下的指定文件。
     */
    suspend fun appendToNamedMemoryFile(fileName: String, content: String) = withContext(Dispatchers.IO) {
        try {
            require(fileName.isNotBlank()) { "fileName cannot be blank" }
            require(!fileName.contains("/") && !fileName.contains("\\")) { "Invalid memory file name" }
            val target = File(memoryDir, fileName)
            require(target.canonicalPath.startsWith(memoryDir.canonicalPath)) { "Invalid memory file path" }
            target.parentFile?.mkdirs()
            if (!target.exists()) {
                target.writeText("")
            }
            target.appendText(
                if (content.endsWith("\n")) content else "$content\n"
            )
            memoryIndex?.indexFile(target)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to append memory file: $fileName", e)
        }
    }

    private fun createMemoryTemplate(): String {
        val template = loadBundledMemoryTemplate() ?: """
# X-OmniClaw 全局记忆

此文件保存用户使用 X-OmniClaw 执行任务过程中沉淀的长期记忆。

## 用户偏好与习惯

- 暂无稳定记忆。

## X-OmniClaw 任务经验与工作流

- 暂无稳定记忆。

## 失败经验与绕过方式

- 暂无稳定记忆。

## 长期项目上下文

- 暂无稳定记忆。
        """.trimIndent()
        try { memoryFile.writeText(template) } catch (e: Exception) { Log.e(TAG, "Failed to create template", e) }
        return template
    }

    private fun loadBundledMemoryTemplate(): String? {
        return try {
            context?.assets
                ?.open("bootstrap/memory/MEMORY.md")
                ?.bufferedReader()
                ?.use { it.readText() }
                ?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load bundled MEMORY.md template: ${e.message}")
            null
        }
    }

    suspend fun pruneOldLogs(days: Int) = withContext(Dispatchers.IO) {
        try {
            val cutoff = Calendar.getInstance().apply { add(Calendar.DAY_OF_MONTH, -days) }.time
            memoryDir.listFiles { f -> f.name.matches(Regex("\\d{4}-\\d{2}-\\d{2}\\.md")) }
                ?.forEach { file ->
                    try {
                        val fileDate = DATE_FORMAT.parse(file.nameWithoutExtension)
                        if (fileDate != null && fileDate.before(cutoff)) {
                            file.delete()
                            Log.d(TAG, "Pruned old log: ${file.name}")
                        }
                    } catch (_: Exception) {}
                }
        } catch (e: Exception) { Log.e(TAG, "Failed to prune old logs", e) }
    }
}
