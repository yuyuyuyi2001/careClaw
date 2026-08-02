package com.shijing.xomniclaw.agent.tools.memory

import android.util.Log
import com.shijing.xomniclaw.agent.tools.LlmOnDemandToolInclusion
import com.shijing.xomniclaw.agent.memory.ChunkUtils
import com.shijing.xomniclaw.agent.memory.MemoryIndex
import com.shijing.xomniclaw.agent.memory.MemoryManager
import com.shijing.xomniclaw.agent.tools.Skill
import com.shijing.xomniclaw.agent.tools.SkillResult
import com.shijing.xomniclaw.providers.FunctionDefinition
import com.shijing.xomniclaw.providers.ParametersSchema
import com.shijing.xomniclaw.providers.PropertySchema
import com.shijing.xomniclaw.providers.ToolDefinition
import java.io.File

/**
 * Image-memory-specific retrieval tool.
 *
 * This is the high-level entry point for gallery-image tasks:
 * - the caller (LLM) supplies `query`; there is no server-side query generator
 * - search only inside image-memories
 * - return complete image entries plus field semantics
 *
 * The goal is to stop the model from stitching together a brittle
 * `memory_search -> memory_get -> find` chain on its own.
 */
class ImageMemorySearchEntriesSkill(
    private val memoryManager: MemoryManager,
    private val workspacePath: String
) : Skill {
    companion object {
        private const val TAG = "ImageMemorySearchEntries"
        private const val DEFAULT_MAX_ENTRIES = 12
        private const val COVERAGE_QUERY_LIMIT = 8

        /**
         * 发给 LLM 的**唯一**长说明：所有参数语义都写在此，避免在 JSON Schema 的每个 property 上重复长 description（每轮 tools 全量进上下文）。
         * 是否把本工具加入当轮 `tools` 由 [LlmOnDemandToolInclusion] 决定。
         */
        // buildString 非编译期常量，故不能用 const
        private val LLM_FUNCTION_DESCRIPTION = buildString {
            append("Legacy image-memory retrieval (compat). Prefer memory_get of memory/IMAGE-MEMORY.md; ")
            append("use this only when a legacy prompt needs retrieval-first over that file. ")
            append("Required field `query`: you (the model) build this when calling; the app does not auto-fill from the user message. ")
            append("Use high-recall retrieval text: space-separated topic synonyms + standard nouns the summaries are likely to use. ")
            append("Examples: pets '猫 猫咪 小猫 宠物 动物'; screenshots '截图 截屏 屏幕 页面'; receipts '发票 小票 票据 receipt'. ")
            append("If the user uses a cute compound (e.g. 猫猫), still add the core head noun (猫) and common variants (猫咪 宠物). ")
            append("Do not pass only one rare spelling; broaden before the first call. Put dates in timeHint instead of overloading query. ")
            append("Optional: maxEntries (default 12), timeHint, requireCompleteCoverage (for 全部/所有/都), minScore (default 0.35).")
        }
    }

    override val name = "image_memory_search_entries"
    override val description =
        "Legacy image-memory retrieval helper. Prefer memory_get of IMAGE-MEMORY.md; see LLM_FUNCTION_DESCRIPTION in code for full semantics."

    override fun getToolDefinition(): ToolDefinition {
        return ToolDefinition(
            type = "function",
            function = FunctionDefinition(
                name = name,
                description = LLM_FUNCTION_DESCRIPTION,
                parameters = ParametersSchema(
                    type = "object",
                    properties = mapOf(
                        "query" to PropertySchema(
                            type = "string",
                            description = "—"
                        ),
                        "maxEntries" to PropertySchema(
                            type = "number",
                            description = "—"
                        ),
                        "timeHint" to PropertySchema(
                            type = "string",
                            description = "—"
                        ),
                        "requireCompleteCoverage" to PropertySchema(
                            type = "boolean",
                            description = "—"
                        ),
                        "minScore" to PropertySchema(
                            type = "number",
                            description = "—"
                        )
                    ),
                    required = listOf("query")
                )
            )
        )
    }

    override suspend fun execute(args: Map<String, Any?>): SkillResult {
        val query = (args["query"] as? String)?.trim()
            ?: return SkillResult.error("Missing required parameter: query")
        if (query.isBlank()) {
            return SkillResult.error("Query must not be blank")
        }

        val maxEntries = ((args["maxEntries"] as? Number)?.toInt() ?: DEFAULT_MAX_ENTRIES)
            .coerceIn(1, 100)
        val timeHint = (args["timeHint"] as? String)?.trim().orEmpty()
        val requireCompleteCoverage = args["requireCompleteCoverage"] as? Boolean ?: false
        val minScore = (args["minScore"] as? Number)?.toFloat() ?: MemoryIndex.DEFAULT_MIN_SCORE

        return try {
            val imageMemoriesFile = File(workspacePath, ImageMemoryMarkdownUtils.IMAGE_MEMORIES_PATH)
            if (!imageMemoriesFile.exists()) {
                return SkillResult.success(
                    content = "No image memories are available yet at memory/IMAGE-MEMORY.md.",
                    metadata = mapOf(
                        "query" to query,
                        "results_count" to 0,
                        "mode" to "missing_file"
                    )
                )
            }

            val content = imageMemoriesFile.readText()
            val entries = ImageMemoryMarkdownUtils.parseEntries(content)
            if (entries.isEmpty()) {
                return SkillResult.success(
                    content = buildNoResultMessage(query),
                    metadata = mapOf(
                        "query" to query,
                        "results_count" to 0,
                        "mode" to "empty_file"
                    )
                )
            }

            val searchQueries = buildSearchQueries(query, timeHint, requireCompleteCoverage)
            val matchedEntries = retrieveMatchedEntries(
                searchQueries = searchQueries,
                allEntries = entries,
                maxEntries = maxEntries,
                minScore = minScore
            )

            if (matchedEntries.isEmpty()) {
                return SkillResult.success(
                    content = buildNoResultMessage(query),
                    metadata = mapOf(
                        "query" to query,
                        "results_count" to 0,
                        "mode" to "indexed_search",
                        "coverage_mode" to if (requireCompleteCoverage) "complete_attempted" else "partial",
                        "queries" to searchQueries
                    )
                )
            }

            val fieldGuide = buildFieldGuide()
            val entryBlock = matchedEntries.joinToString("\n\n") { it.entry.rawEntry }
            SkillResult.success(
                content = buildString {
                    appendLine("# Image Memory Search Entries")
                    appendLine()
                    appendLine("Use the full entries below as the candidate set for any image-related question or operation.")
                    appendLine("Judge topic relevance from these entries, then use explicit fields such as display_name for later execution.")
                    appendLine()
                    appendLine(fieldGuide)
                    appendLine()
                    append(entryBlock)
                }.trim(),
                metadata = mapOf(
                    "query" to query,
                    "time_hint" to timeHint,
                    "results_count" to matchedEntries.size,
                    "mode" to "indexed_search",
                    "coverage_mode" to if (requireCompleteCoverage) "complete_attempted" else "partial",
                    "has_more_candidates" to (matchedEntries.size >= maxEntries),
                    "queries" to searchQueries,
                    "entries" to matchedEntries.map {
                        mapOf(
                            "stableKey" to it.entry.stableKey,
                            "displayName" to (it.entry.displayName ?: ""),
                            "timestamp" to (it.entry.timestamp ?: ""),
                            "bucket" to (it.entry.bucketName ?: ""),
                            "startLine" to it.entry.startLine,
                            "endLine" to it.entry.endLine,
                            "score" to it.score
                        )
                    }
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to search image memories", e)
            SkillResult.error("Failed to search image memories: ${e.message}")
        }
    }

    private suspend fun retrieveMatchedEntries(
        searchQueries: List<String>,
        allEntries: List<ImageMemoryMarkdownUtils.ImageMemoryEntrySlice>,
        maxEntries: Int,
        minScore: Float
    ): List<ScoredEntry> {
        val memoryIndex = memoryManager.getMemoryIndex()
        val imageMemoryPath = File(workspacePath, ImageMemoryMarkdownUtils.IMAGE_MEMORIES_PATH).canonicalPath
        val merged = linkedMapOf<String, ScoredEntry>()

        if (memoryIndex != null) {
            memoryManager.syncIndex()
            val candidateLimit = (maxEntries * if (searchQueries.size > 1) 4 else 3).coerceAtLeast(maxEntries)
            for (searchQuery in searchQueries) {
                val results = memoryIndex.hybridSearch(searchQuery, candidateLimit, minScore)
                val imageResults = results.filter { result ->
                    try {
                        File(result.path).canonicalPath == imageMemoryPath
                    } catch (_: Exception) {
                        false
                    }
                }
                for (result in imageResults) {
                    val overlappingEntries = allEntries.filter { entry ->
                        entry.endLine >= result.startLine && entry.startLine <= result.endLine
                    }
                    for (entry in overlappingEntries) {
                        val existing = merged[entry.stableKey]
                        val score = maxOf(existing?.score ?: 0f, result.score)
                        merged[entry.stableKey] = ScoredEntry(entry, score)
                    }
                }
            }
        }

        // Fall back to an in-memory scan if the index did not return image-memory candidates.
        if (merged.isEmpty()) {
            val keywords = ChunkUtils.extractKeywords(searchQueries.joinToString(" "))
            for (entry in allEntries) {
                val haystack = buildString {
                    append(entry.stableKey)
                    append('\n')
                    append(entry.displayName.orEmpty())
                    append('\n')
                    append(entry.bucketName.orEmpty())
                    append('\n')
                    append(entry.timestamp.orEmpty())
                    append('\n')
                    append(entry.tags.joinToString(" "))
                    append('\n')
                    append(entry.summary.orEmpty())
                    append('\n')
                    append(entry.extractedText.orEmpty())
                }
                val matchCount = keywords.count { keyword ->
                    haystack.contains(keyword, ignoreCase = true)
                }
                if (matchCount > 0) {
                    merged[entry.stableKey] = ScoredEntry(
                        entry = entry,
                        score = matchCount.toFloat() / keywords.size.coerceAtLeast(1)
                    )
                }
            }
        }

        return merged.values
            .sortedWith(
                compareByDescending<ScoredEntry> { it.score }
                    .thenByDescending { it.entry.timestamp.orEmpty() }
                    .thenByDescending { it.entry.stableKey }
            )
            .take(maxEntries)
    }

    private fun buildSearchQueries(
        query: String,
        timeHint: String,
        requireCompleteCoverage: Boolean
    ): List<String> {
        val queries = linkedSetOf<String>()
        val keywords = ChunkUtils.extractKeywords(query)

        queries += query
        if (keywords.isNotEmpty()) {
            queries += keywords.joinToString(" ")
        }
        if (timeHint.isNotBlank()) {
            queries += "$query $timeHint".trim()
            if (keywords.isNotEmpty()) {
                queries += (keywords + timeHint).joinToString(" ")
            }
        }
        if (requireCompleteCoverage) {
            keywords.take(COVERAGE_QUERY_LIMIT).forEach { keyword ->
                queries += keyword
            }
        }

        return queries.filter { it.isNotBlank() }
    }

    private fun buildFieldGuide(): String {
        return """
            Field meanings:
            - display_name: the concrete image file name to use for later file lookup or copy/move operations
            - time: when the image was created or captured
            - album: gallery bucket / album display name (where the image came from in MediaStore)
            - summary: the main semantic description of the image
        """.trimIndent()
    }

    private fun buildNoResultMessage(query: String): String {
        return "No matching image-memory entries were found for query: \"$query\". Do not guess a filename prefix. If needed, ask for a narrower topic or resync image memories first."
    }

    private data class ScoredEntry(
        val entry: ImageMemoryMarkdownUtils.ImageMemoryEntrySlice,
        val score: Float
    )
}
