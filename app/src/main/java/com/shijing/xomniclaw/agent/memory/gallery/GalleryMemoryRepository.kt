package com.shijing.xomniclaw.agent.memory.gallery

import com.shijing.xomniclaw.agent.memory.MemoryManager

/**
 * 相册记忆仓库。
 *
 * 统一负责 image memories 与 user profile 的落盘格式，避免 Skill 直接拼接文件文本。
 */
class GalleryMemoryRepository(
    private val memoryManager: MemoryManager
) {
    companion object {
        const val IMAGE_MEMORIES_FILE = "IMAGE-MEMORY.md"
        const val USER_PROFILE_FILE = "USER-PROFILE.md"

        private fun buildImageMemoriesTemplate(): String {
            return """
                # Image Memories

                This file stores compact memories extracted from the user's gallery images.
                Each entry keeps filename, time, album (image source bucket), and a short summary.

            """.trimIndent()
        }

        private fun buildUserProfileTemplate(): String {
            return """
                # USER-PROFILE

                updated: unknown
                version: compact-v4
                confidence: low

                ## Stable
                - name: unknown
                - language: unknown
                - response_style: unknown
                - task_habits: []
                - recurring_needs: []
                - preferred_topics: []
                - content_preferences: []
                - important_context: []
                - risks: [证件号, 支付细节, 验证码]

                ## Recent
                - focus_7d: []
                - gallery_summary: 暂无图片记忆同步结果。
                - task_memory_summary: 暂无任务记忆沉淀。

                ## Sources
                - image_memories_count: 0
                - task_memory_available: false
                - recent_logs: 0

                ## Recent Signals Details
                - unknown
            """.trimIndent()
        }
    }

    suspend fun appendImageMemories(entries: List<ImageMemoryEntry>) {
        if (entries.isEmpty()) {
            return
        }
        val mergedByStableKey = LinkedHashMap<String, ImageMemoryEntry>()
        parseImageMemories(readImageMemories()).forEach { mergedByStableKey[it.stableKey] = it }
        entries.forEach { mergedByStableKey[it.stableKey] = it }

        val sortedEntries = mergedByStableKey.values
            .sortedWith(
                compareByDescending<ImageMemoryEntry> { it.timestampText }
                    .thenByDescending { it.stableKey }
            )

        memoryManager.writeNamedMemoryFile(
            IMAGE_MEMORIES_FILE,
            buildImageMemoriesContent(sortedEntries)
        )
    }

    suspend fun writeUserProfile(content: String) {
        memoryManager.writeNamedMemoryFile(USER_PROFILE_FILE, content)
    }

    suspend fun readImageMemories(): String {
        return memoryManager.readNamedMemoryFile(IMAGE_MEMORIES_FILE)
    }

    suspend fun readUserProfile(): String {
        return memoryManager.readNamedMemoryFile(USER_PROFILE_FILE)
    }

    suspend fun loadExistingStableKeys(): Set<String> {
        return parseImageMemories(readImageMemories())
            .map { it.displayName }
            .filter { it.isNotBlank() }
            .toSet()
    }

    suspend fun clearImageMemories() {
        memoryManager.writeNamedMemoryFile(IMAGE_MEMORIES_FILE, buildImageMemoriesTemplate())
    }

    suspend fun clearUserProfile() {
        memoryManager.writeNamedMemoryFile(USER_PROFILE_FILE, buildUserProfileTemplate())
    }

    private suspend fun ensureImageMemoriesHeader() {
        val existing = memoryManager.readNamedMemoryFile(IMAGE_MEMORIES_FILE)
        if (existing.isNotBlank()) {
            return
        }
        memoryManager.writeNamedMemoryFile(IMAGE_MEMORIES_FILE, buildImageMemoriesTemplate())
    }

    private fun buildImageMemoriesContent(entries: List<ImageMemoryEntry>): String {
        return buildString {
            appendLine(buildImageMemoriesTemplate())
            if (entries.isNotEmpty()) {
                appendLine()
                append(entries.joinToString("\n") { it.toMarkdown().trimEnd() })
                appendLine()
            }
        }
    }

    private fun parseImageMemories(markdown: String): List<ImageMemoryEntry> {
        if (markdown.isBlank()) {
            return emptyList()
        }

        val chunks = markdown.split(Regex("(?m)^## "))
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.startsWith("# Image Memories") }

        return chunks.mapNotNull { chunk ->
            val lines = chunk.lines()
            if (lines.isEmpty()) {
                return@mapNotNull null
            }

            val displayName = lines.first().trim()
            if (displayName.isBlank()) {
                return@mapNotNull null
            }

            val albumOrBucket = extractField(lines, "album")
                ?: extractField(lines, "bucket")
            ImageMemoryEntry(
                stableKey = displayName,
                displayName = displayName,
                bucketName = albumOrBucket?.takeIf { it.isNotBlank() },
                timestampText = extractField(lines, "time")
                    ?: extractField(lines, "timestamp")
                    .orEmpty(),
                summary = extractField(lines, "summary").orEmpty(),
                tags = emptyList(),
                sensitivity = "low",
                summarySource = "compact",
                originalTextExcerpt = null
            )
        }
    }

    private fun extractField(lines: List<String>, field: String): String? {
        val prefix = "- $field:"
        return lines.firstOrNull { it.startsWith(prefix) }
            ?.substringAfter(prefix)
            ?.trim()
    }
}
