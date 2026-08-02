package com.shijing.xomniclaw.ui.compose

/**
 * 从聊天文本中提取可预览的本地图片/视频路径，供主界面消息气泡渲染。
 * 先覆盖最常见的 Android 本地文件路径场景，避免一次性把消息结构改得太重。
 */
data class ParsedChatMedia(
    val cleanedText: String,
    val mediaRefs: List<ChatMediaRef>
)

data class ChatMediaRef(
    val rawPath: String,
    val normalizedPath: String,
    val type: ChatMediaType
)

enum class ChatMediaType {
    IMAGE,
    VIDEO
}

object ChatMediaParser {
    private val imageExtensions = setOf("png", "jpg", "jpeg", "webp", "gif", "bmp")
    private val videoExtensions = setOf("mp4", "webm", "mkv", "mov", "3gp")
    private val mediaTokenRegex = Regex(
        pattern = """(?:file://)?(?:/sdcard|/storage|/data|/mnt)[^\s"'`()\]]+\.(?:png|jpg|jpeg|webp|gif|bmp|mp4|webm|mkv|mov|3gp)(?:\?[^\s"'`()\]]+)?""",
        option = RegexOption.IGNORE_CASE
    )

    fun extract(text: String): ParsedChatMedia {
        if (text.isBlank()) {
            return ParsedChatMedia(cleanedText = text, mediaRefs = emptyList())
        }

        val mediaRefs = LinkedHashMap<String, ChatMediaRef>()
        val cleanedLines = text.lines().mapNotNull { line ->
            val trimmed = line.trim()
            val matches = mediaTokenRegex.findAll(line).toList()
            matches.forEach { match ->
                buildMediaRef(match.value)?.let { ref ->
                    mediaRefs.putIfAbsent(ref.normalizedPath, ref)
                }
            }
            if (trimmed.isNotBlank() && matches.isNotEmpty() && matches.any { it.value == trimmed }) {
                null
            } else {
                line
            }
        }

        return ParsedChatMedia(
            cleanedText = cleanedLines.joinToString("\n").trim(),
            mediaRefs = mediaRefs.values.toList()
        )
    }

    private fun buildMediaRef(rawPath: String): ChatMediaRef? {
        val normalizedPath = rawPath.substringBefore("?").removePrefix("file://")
        val extension = normalizedPath.substringAfterLast('.', "").lowercase()
        val type = when (extension) {
            in imageExtensions -> ChatMediaType.IMAGE
            in videoExtensions -> ChatMediaType.VIDEO
            else -> null
        } ?: return null
        return ChatMediaRef(
            rawPath = rawPath,
            normalizedPath = normalizedPath,
            type = type
        )
    }
}
