package com.shijing.xomniclaw.agent.tools

/**
 * OmniClaw Source Reference:
 * - ../xomniclaw/src/agents/tools/(all)
 *
 * OmniClaw adaptation: agent tool implementation.
 */


import android.content.Context
import android.util.Log
import com.shijing.xomniclaw.agent.memory.MemoryManager
import com.shijing.xomniclaw.agent.tools.memory.ImageMemorySearchEntriesSkill
import com.shijing.xomniclaw.agent.tools.LlmOnDemandToolInclusion
import com.shijing.xomniclaw.agent.tools.memory.MemoryGetSkill
import com.shijing.xomniclaw.agent.tools.memory.MemorySearchSkill
import com.shijing.xomniclaw.agent.tools.device.DeviceToolSkillAdapter
import com.shijing.xomniclaw.data.model.TaskDataManager
import com.shijing.xomniclaw.providers.ToolDefinition

/**
 * Android Tool Registry
 *
 * Manages Android platform-specific tools (Platform-specific tools)
 *
 * Aligned with OmniClaw architecture:
 * - ToolRegistry: Universal tools (read, write, exec, web_fetch)
 * - AndroidToolRegistry: Android platform tools (tap, screenshot, open_app, memory)
 * - SkillsLoader: Markdown Skills (mobile-operations.md)
 *
 * Reference: Platform-specific capabilities in OmniClaw's pi-tools.ts
 */
class AndroidToolRegistry(
    private val context: Context,
    private val taskDataManager: TaskDataManager,
    private val memoryManager: MemoryManager? = null,
    private val workspacePath: String = "/sdcard/.xomniclaw/workspace"
) {
    companion object {
        private const val TAG = "AndroidToolRegistry"
    }

    private val tools = mutableMapOf<String, Skill>()

    init {
        registerAndroidTools()
        registerMemoryTools()
    }

    /**
     * Register Android platform-specific tools
     */
    private fun registerAndroidTools() {
        // === Unified device tool (Playwright-aligned) ===
        // Single entry point for ALL screen operations via ref-based interaction
        // Replaces: screenshot, get_view_tree, tap, swipe, type, long_press, home, back, open_app, wait
        register(DeviceToolSkillAdapter(context))

        // === App management tools ===
        register(ListInstalledAppsSkill(context))  // List apps
        register(ListGalleryImagesSkill(context))   // MediaStore recent images (album / URI / meta)
        register(CopyImagesToAlbumSkill(context))   // Copy URIs into new Pictures/<album>/ folder
        register(InstallAppSkill(context))         // Install APK
        register(StartActivityTool(context))       // Start Activity
        register(ScheduleTaskSkill(context))       // AlarmManager-based scheduled tasks
        register(ScheduleAppTaskSkill(context))    // Natural-language app scheduling
        register(GalleryMemorySkill(context))      // Gallery -> memory -> user profile
        register(MemoryEvolutionSkill(context))    // Task memories -> MEMORY.md -> user profile

        // === Control tools ===
        register(StopSkill(taskDataManager)) // Stop
        register(LogSkill())                 // Log
        register(SystemSettingsSkill(context)) // System settings: Bluetooth, WiFi, Airplane mode, etc.

        // === Feishu image (kept as direct tool — media upload needs special handling) ===
        register(FeishuSendImageSkill(context))

        Log.d(TAG, "✅ Registered ${tools.size} Android platform tools")
    }

    /**
     * Register memory tools
     */
    private fun registerMemoryTools() {
        if (memoryManager == null) {
            Log.d(TAG, "⚠️ MemoryManager not provided, skipping memory tools")
            return
        }

        // === Memory tools (Memory) ===
        register(MemoryGetSkill(memoryManager, workspacePath))
        register(MemorySearchSkill(memoryManager, workspacePath))
        register(ImageMemorySearchEntriesSkill(memoryManager, workspacePath))

        Log.d(TAG, "✅ Registered memory tools")
    }

    /**
     * Register a tool
     */
    private fun register(tool: Skill) {
        tools[tool.name] = tool
        Log.d(TAG, "  📱 ${tool.name}")
    }

    fun getRegisteredToolNames(): Set<String> = tools.keys.toSet()

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
            Log.e(TAG, "Unknown Android tool: $name")
            return SkillResult.error("Unknown Android tool: $name")
        }

        Log.d(TAG, "Executing Android tool: $name with args: $args")
        return try {
            tool.execute(args)
        } catch (e: Exception) {
            Log.e(TAG, "Android tool execution failed: $name", e)
            SkillResult.error("Execution failed: ${e.message}")
        }
    }

    /**
     * 供 LLM 的 `tools` 列表。
     * [LlmOnDemandToolInclusion] 中列出的工具默认**不**提供 schema；需把当轮要启用的名放入 [onDemandLlmNamesToInclude]。
     * 网关/工具目录需展示**全量**时传 [LlmOnDemandToolInclusion.ALL_ON_DEMAND_NAMES]。
     */
    fun getToolDefinitions(
        onDemandLlmNamesToInclude: Set<String> = emptySet()
    ): List<ToolDefinition> {
        return tools.values
            .asSequence()
            .filter { skill ->
                val n = skill.name
                if (LlmOnDemandToolInclusion.isOnDemandTool(n)) {
                    n in onDemandLlmNamesToInclude
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
            appendLine("## Android Platform Tools")
            appendLine()
            appendLine("Android 设备专属能力,通过 AccessibilityService 和系统 API 提供：")
            appendLine()

            // Organize by category
            val categories = mapOf(
                "屏幕与交互" to listOf("device"),
                "应用管理" to listOf("list_installed_apps", "install_app", "start_activity"),
                "记忆" to listOf("gallery_memory", "memory_evolution", "memory_get", "memory_search"),
                "系统设置" to listOf("system_settings"),
                "媒体" to listOf("send_image"),
                "控制" to listOf("stop", "log")
            )

            categories.forEach { (category, toolNames) ->
                val availableTools = toolNames.mapNotNull { name -> tools[name] }
                if (availableTools.isEmpty()) return@forEach
                appendLine("### $category")
                availableTools.forEach { tool ->
                    appendLine("- **${tool.name}**: ${tool.description.lines().first()}")
                }
                appendLine()
            }
        }
    }

    /**
     * Get tool count
     */
    fun getToolCount(): Int = tools.size
}
