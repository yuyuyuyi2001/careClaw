package com.shijing.xomniclaw.agent.tools.memory

/**
 * Shared helpers for working with `memory/IMAGE-MEMORY.md`.
 *
 * Keep the parsing logic in one place so readers agree on:
 * - what a full image-memory entry looks like
 * - how to expand a hit range into full entries
 * - which compact fields are available downstream
 */
object ImageMemoryMarkdownUtils {
    const val IMAGE_MEMORIES_PATH = "memory/IMAGE-MEMORY.md"

    data class ImageMemoryEntrySlice(
        val stableKey: String,
        val startLine: Int,
        val endLine: Int,
        val rawEntry: String,
        val displayName: String?,
        val bucketName: String?,
        val timestamp: String?,
        val tags: List<String>,
        val summary: String?,
        val extractedText: String?
    )

    /**
     * Parse the markdown file into complete `## media-*` entry blocks with line ranges.
     * Line numbers are 1-based to match tool-facing conventions.
     */
    fun parseEntries(content: String): List<ImageMemoryEntrySlice> {
        val lines = content.lines()
        val entryStartIndexes = lines.mapIndexedNotNull { index, line ->
            index.takeIf { line.startsWith("## ") }
        }
        if (entryStartIndexes.isEmpty()) {
            return emptyList()
        }

        return entryStartIndexes.mapIndexed { index, startIndex ->
            val endExclusive = entryStartIndexes.getOrNull(index + 1) ?: lines.size
            val entryLines = lines.subList(startIndex, endExclusive)
                .filterNot { it.trim() == "---" }
            val displayName = entryLines.first().removePrefix("## ").trim()
            val fields = parseFieldMap(entryLines.drop(1))
            ImageMemoryEntrySlice(
                stableKey = displayName,
                startLine = startIndex + 1,
                endLine = endExclusive,
                rawEntry = entryLines.joinToString("\n"),
                displayName = displayName,
                bucketName = fields["album"] ?: fields["bucket"],
                timestamp = fields["time"] ?: fields["timestamp"],
                tags = emptyList(),
                summary = fields["summary"],
                extractedText = null
            )
        }
    }

    /**
     * Expand an arbitrary line window to the full overlapping image-memory entries.
     * This keeps the model from seeing partial snippets without the key fields.
     */
    fun expandRangeToFullEntries(
        content: String,
        startLine: Int,
        lineCount: Int?
    ): String {
        val entries = parseEntries(content)
        if (entries.isEmpty()) {
            return sliceRawLines(content, startLine, lineCount)
        }

        val lines = content.lines()
        val start = startLine.coerceAtLeast(1)
        val requestedEnd = if (lineCount == null) {
            lines.size
        } else {
            (start + lineCount - 1).coerceAtLeast(start)
        }

        val overlapping = entries.filter { entry ->
            entry.endLine >= start && entry.startLine <= requestedEnd
        }
        if (overlapping.isEmpty()) {
            return sliceRawLines(content, startLine, lineCount)
        }

        return overlapping.joinToString("\n") { it.rawEntry }
    }

    private fun sliceRawLines(content: String, startLine: Int, lineCount: Int?): String {
        val lines = content.lines()
        val start = (startLine - 1).coerceIn(0, lines.size)
        val count = lineCount ?: (lines.size - start)
        val end = (start + count).coerceIn(start, lines.size)
        return lines.subList(start, end).joinToString("\n")
    }

    private fun parseFieldMap(lines: List<String>): Map<String, String> {
        val result = linkedMapOf<String, String>()
        for (line in lines) {
            val trimmed = line.trim()
            if (!trimmed.startsWith("- ")) {
                continue
            }
            val separatorIndex = trimmed.indexOf(':')
            if (separatorIndex <= 2) {
                continue
            }
            val key = trimmed.substring(2, separatorIndex).trim()
            val value = trimmed.substring(separatorIndex + 1).trim()
            result[key] = value
        }
        return result
    }
}
