package com.shijing.xomniclaw.agent.tools

/**
 * OmniClaw Source Reference:
 * - ../xomniclaw/src/agents/tools/(all)
 * - ../xomniclaw/src/gateway/(all)
 *
 * OmniClaw adaptation: register app, android, config, and extension tools.
 */


import android.content.Context
import android.util.Log
import com.shijing.xomniclaw.data.model.TaskDataManager
import com.shijing.xomniclaw.providers.ToolDefinition
import com.shijing.xomniclaw.gateway.methods.ConfigMethods
import java.io.File

/**
 * Tool Registry - Manages universal low-level Tools
 * Inspired by OmniClaw's pi-tools (from Pi Coding Agent)
 *
 * Tools are cross-platform universal capabilities:
 * - read_file, write_file, edit_file: File operations
 * - list_dir: Directory listing
 * - exec: Execute shell commands
 * - web_fetch: Web fetching
 * - javascript: JavaScript execution
 *
 * Note: Android-specific capabilities are managed in AndroidToolRegistry
 */
class ToolRegistry(
    private val context: Context,
    private val taskDataManager: TaskDataManager
) {
    companion object {
        private const val TAG = "ToolRegistry"
    }

    private val tools = mutableMapOf<String, Tool>()

    init {
        registerDefaultTools()
    }

    /**
     * Register universal tools (cross-platform capabilities)
     */
    private fun registerDefaultTools() {
        // Use external storage workspace (aligned with OmniClaw ~/.xomniclaw/workspace/)
        val workspace = File("/sdcard/.xomniclaw/workspace")
        workspace.mkdirs()

        // === File system tools (from Pi Coding Agent) ===
        register(ReadFileTool(context = context, workspace = workspace))
        register(WriteFileTool(workspace = workspace))
        register(EditFileTool(workspace = workspace))
        register(ListDirTool(workspace = workspace))

        // === Memory tools (Memory Recall) ===
        
        // Memory tools registered in AndroidToolRegistry (MemorySearchSkill/MemoryGetSkill)

        // === Shell tools ===
        register(ExecFacadeTool(workingDir = workspace.absolutePath))

        // === Network tools ===
        register(WebFetchTool())

        // === Config tools ===
        val configMethods = ConfigMethods(context)
        register(ConfigGetTool(configMethods))
        register(ConfigSetTool(configMethods))

        Log.d(TAG, "✅ Registered ${tools.size} universal tools (memory tools in AndroidToolRegistry)")
    }

    /**
     * Register a tool
     */
    fun register(tool: Tool) {
        tools[tool.name] = tool
        Log.d(TAG, "Registered tool: ${tool.name}")
    }

    /**
     * Check if the specified tool exists
     */
    fun contains(name: String): Boolean = tools.containsKey(name)

    /**
     * Execute tool
     */
    suspend fun execute(name: String, args: Map<String, Any?>): SkillResult {
        val tool = tools[name]
        if (tool == null) {
            Log.e(TAG, "Unknown tool: $name")
            return SkillResult.error("Unknown tool: $name")
        }

        Log.d(TAG, "Executing tool: $name with args: $args")
        return try {
            tool.execute(args)
        } catch (e: Exception) {
            Log.e(TAG, "Tool execution failed: $name", e)
            SkillResult.error("Execution failed: ${e.message}")
        }
    }

    fun getRegisteredToolNames(): Set<String> = tools.keys.toSet()

    /**
     * 供 LLM 的 `tools` 列表。
     * [LlmOnDemandToolInclusion] 列出的工具默认不提供 schema；需把当轮要启用的名放入 [onDemandLlmNamesToInclude]。
     * 网关/工具目录需展示全量时传 [LlmOnDemandToolInclusion.ALL_ON_DEMAND_NAMES]。
     */
    fun getToolDefinitions(
        onDemandLlmNamesToInclude: Set<String> = emptySet()
    ): List<ToolDefinition> {
        return tools.values
            .asSequence()
            .filter { tool ->
                val name = tool.name
                if (LlmOnDemandToolInclusion.isOnDemandTool(name)) {
                    name in onDemandLlmNamesToInclude
                } else {
                    true
                }
            }
            .map { it.getToolDefinition() }
            .toList()
    }

    /**
     * Get all tools description (for building system prompt)
     */
    fun getToolsDescription(): String {
        return buildString {
            appendLine("## Universal Tools")
            appendLine()
            appendLine("跨平台通用工具，来自 Pi Coding Agent 和 OmniClaw：")
            appendLine()
            tools.values.forEach { tool ->
                appendLine("### ${tool.name}")
                appendLine(tool.description)
                appendLine()
            }
        }
    }

    /**
     * Get tool count
     */
    fun getToolCount(): Int = tools.size
}
