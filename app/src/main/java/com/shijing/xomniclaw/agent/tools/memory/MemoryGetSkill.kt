package com.shijing.xomniclaw.agent.tools.memory

/**
 * OmniClaw Source Reference:
 * - ../xomniclaw/src/agents/tools/(all)
 *
 * OmniClaw adaptation: agent tool implementation.
 */


import com.shijing.xomniclaw.agent.memory.MemoryManager
import com.shijing.xomniclaw.agent.tools.Skill
import com.shijing.xomniclaw.agent.tools.SkillResult
import com.shijing.xomniclaw.providers.FunctionDefinition
import com.shijing.xomniclaw.providers.ParametersSchema
import com.shijing.xomniclaw.providers.PropertySchema
import com.shijing.xomniclaw.providers.ToolDefinition
import java.io.File

/**
 * memory_get tool
 * Aligned with OmniClaw memory-tool.ts
 *
 * Read specific memory file or log
 */
class MemoryGetSkill(
    private val memoryManager: MemoryManager,
    private val workspacePath: String
) : Skill {
    companion object {
        /** 仅一段 function description，减少 tools 中 property 描述重复占 token。 */
        private const val LLM_FUNCTION_DESCRIPTION = "Read a workspace-relative .md memory file. " +
            "For memory/IMAGE-MEMORY.md, line ranges expand to full image entries. " +
            "path is required (no ..); from = 1-based start line; lines = count (default all). " +
            "Examples: MEMORY.md, memory/USER-PROFILE.md, memory/IMAGE-MEMORY.md."
    }

    override val name = "memory_get"
    override val description = "Read memory .md under workspace. Full semantics in getToolDefinition LLM block."

    override fun getToolDefinition(): ToolDefinition {
        return ToolDefinition(
            type = "function",
            function = FunctionDefinition(
                name = name,
                description = LLM_FUNCTION_DESCRIPTION,
                parameters = ParametersSchema(
                    type = "object",
                    properties = mapOf(
                        "path" to PropertySchema(type = "string", description = "—"),
                        "from" to PropertySchema(type = "number", description = "—"),
                        "lines" to PropertySchema(type = "number", description = "—")
                    ),
                    required = listOf("path")
                )
            )
        )
    }

    override suspend fun execute(args: Map<String, Any?>): SkillResult {
        val path = args["path"] as? String
            ?: return SkillResult.error("Missing required parameter: path")

        val startLine = (args["from"] as? Number)?.toInt()
        val lineCount = (args["lines"] as? Number)?.toInt()

        return try {
            // Validate path security (prevent directory traversal attacks)
            if (path.contains("..") || path.startsWith("/")) {
                return SkillResult.error("Invalid path: path must be relative and cannot contain '..'")
            }

            val normalizedPath = path.replace("\\", "/")

            // Build full path
            val file = File(workspacePath, path)

            // Verify file is within workspace
            if (!file.canonicalPath.startsWith(File(workspacePath).canonicalPath)) {
                return SkillResult.error("Invalid path: file must be within workspace")
            }

            // Verify file exists
            if (!file.exists()) {
                return SkillResult.error("File not found: $path")
            }

            // Verify is Markdown file
            if (!file.name.endsWith(".md")) {
                return SkillResult.error("Invalid file type: only .md files are allowed")
            }

            // Read file content
            val content = file.readText()

            // If line range specified, extract corresponding lines
            val result = when {
                normalizedPath == ImageMemoryMarkdownUtils.IMAGE_MEMORIES_PATH && startLine != null ->
                    ImageMemoryMarkdownUtils.expandRangeToFullEntries(content, startLine, lineCount)
                startLine != null -> {
                    val lines = content.lines()
                    val start = (startLine - 1).coerceIn(0, lines.size)
                    val count = lineCount ?: (lines.size - start)
                    val end = (start + count).coerceIn(start, lines.size)
                    lines.subList(start, end).joinToString("\n")
                }
                else -> content
            }

            SkillResult.success(
                content = result,
                metadata = mapOf(
                    "path" to path,
                    "size" to result.length,
                    "lines" to result.lines().size
                )
            )
        } catch (e: Exception) {
            SkillResult.error("Failed to read memory file: ${e.message}")
        }
    }
}
