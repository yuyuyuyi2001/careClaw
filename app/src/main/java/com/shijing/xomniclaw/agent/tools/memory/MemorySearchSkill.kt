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
 */
class MemorySearchSkill(
    private val memoryManager: MemoryManager,
    private val workspacePath: String
) : Skill {
    companion object {
        private const val TAG = "MemorySearchSkill"
        private const val SNIPPET_MAX_CHARS = 700
        private const val LLM_FUNCTION_DESCRIPTION = "Hybrid search (FTS + optional embedding) over memory files; returns scored snippets. " +
            "query is required. maxResults default 6, minScore default 0.35. " +
            "For IMAGE-MEMORY.md, prefer full memory_get when a whole-file read is viable."
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

            val results = memoryIndex.hybridSearch(query, maxResults, minScore)

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
                appendLine("- If a result points to memory/IMAGE-MEMORY.md, do not answer with only the path or line range.")
                appendLine("- For photo questions and gallery-image operations, prefer loading the full compact memory/IMAGE-MEMORY.md via memory_get.")
                appendLine("- If you still use memory_get on a cited range, read the full entry and summarize the photo content in natural language.")
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
