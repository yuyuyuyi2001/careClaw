package com.shijing.xomniclaw.agent.tools.memory

import android.util.Log
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
 * memory_search tool — aligned with OmniClaw memory-tool.ts
 *
 * Hybrid search: SQLite FTS5 + vector embedding cosine similarity.
 * Falls back to FTS5-only when no embedding provider is configured.
 *
 * 检索范围：只针对按日日志（memory/YYYY-MM-DD.md）。MEMORY.md / USER-PROFILE.md /
 * IMAGE-MEMORY.md 不参与本工具，需要时用 memory_get 直接读取。
 */
class MemorySearchSkill(
    private val memoryManager: MemoryManager,
    private val workspacePath: String
) : Skill {
    companion object {
        private const val TAG = "MemorySearchSkill"
        private const val SNIPPET_MAX_CHARS = 700
        private val LOG_FILE_PATTERN = Regex("\\d{4}-\\d{2}-\\d{2}\\.md")
        private const val LLM_FUNCTION_DESCRIPTION = "Search daily logs (memory/YYYY-MM-DD.md) for past task records and context. " +
            "query is required. maxResults default 6, minScore default 0.35. " +
            "MEMORY.md / USER-PROFILE.md / IMAGE-MEMORY.md are NOT searched by this tool; use memory_get to read them directly."
    }

    override val name = "memory_search"
    override val description = "Hybrid search in memory index. See getToolDefinition for LLM block."

    override fun getToolDefinition(): ToolDefinition {
        return ToolDefinition(
            type = "function",
            function = FunctionDefinition(
                name = name,
                description = LLM_FUNCTION_DESCRIPTION,
                parameters = ParametersSchema(
                    type = "object",
                    properties = mapOf(
                        "query" to PropertySchema(type = "string", description = "—"),
                        "maxResults" to PropertySchema(type = "number", description = "—"),
                        "minScore" to PropertySchema(type = "number", description = "—")
                    ),
                    required = listOf("query")
                )
            )
        )
    }

    override suspend fun execute(args: Map<String, Any?>): SkillResult {
        val query = args["query"] as? String
            ?: return SkillResult.error("Missing required parameter: query")

        val maxResults = (args["maxResults"] as? Number)?.toInt() ?: MemoryIndex.DEFAULT_MAX_RESULTS
        val minScore = (args["minScore"] as? Number)?.toFloat() ?: MemoryIndex.DEFAULT_MIN_SCORE

        return try {
            val memoryIndex = memoryManager.getMemoryIndex()
            if (memoryIndex == null) {
                return SkillResult.error("Memory index not initialized")
            }

            // Ensure index is up to date
            memoryManager.syncIndex()

            // 只返回按日日志（memory/YYYY-MM-DD.md）的命中；其余文件（MEMORY/画像/画面）过滤掉。
            // 多取候选再过滤，避免日志数量不足时结果过少。
            val candidateLimit = (maxResults * 4).coerceAtLeast(maxResults)
            val results = memoryIndex.hybridSearch(query, candidateLimit, minScore)
                .filter { result ->
                    val name = try { File(result.path).name } catch (_: Exception) { "" }
                    name.matches(LOG_FILE_PATTERN)
                }
                .take(maxResults)

            if (results.isEmpty()) {
                return SkillResult.success(
                    content = "No matching memories found for query: \"$query\"",
                    metadata = mapOf(
                        "query" to query,
                        "results_count" to 0,
                        "mode" to getSearchMode()
                    )
                )
            }

            // Format results — aligned with OmniClaw output
            val workspaceDir = File(workspacePath)
            val formatted = results.mapIndexed { index, result ->
                val relativePath = try {
                    File(result.path).relativeTo(workspaceDir).path
                } catch (_: Exception) { result.path }
                val snippet = if (result.text.length > SNIPPET_MAX_CHARS) {
                    result.text.take(SNIPPET_MAX_CHARS) + "..."
                } else result.text

                """## Result ${index + 1} ($relativePath, lines ${result.startLine}-${result.endLine}, score: ${"%.2f".format(result.score)})
$snippet"""
            }.joinToString("\n\n")

            val usageHint = buildString {
                appendLine()
                appendLine("Next step guidance:")
                appendLine("- Results only come from daily logs (memory/YYYY-MM-DD.md).")
                appendLine("- To read MEMORY.md / memory/USER-PROFILE.md / memory/IMAGE-MEMORY.md, use memory_get.")
            }.trimEnd()

            val embeddingProvider = memoryManager.getEmbeddingProvider()
            SkillResult.success(
                content = "$formatted\n\n$usageHint",
                metadata = mapOf(
                    "query" to query,
                    "results_count" to results.size,
                    "mode" to getSearchMode(),
                    "provider" to (embeddingProvider?.providerName ?: "none"),
                    "model" to (embeddingProvider?.modelName ?: "fts5-only"),
                    "citations" to results.map { r ->
                        val rp = try { File(r.path).relativeTo(File(workspacePath)).path } catch (_: Exception) { r.path }
                        mapOf("file" to rp, "startLine" to r.startLine, "endLine" to r.endLine)
                    }
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Memory search failed", e)
            SkillResult.error("Failed to search memory: ${e.message}")
        }
    }

    private fun getSearchMode(): String {
        val ep = memoryManager.getEmbeddingProvider()
        return if (ep?.isAvailable == true) "hybrid" else "keyword"
    }
}
