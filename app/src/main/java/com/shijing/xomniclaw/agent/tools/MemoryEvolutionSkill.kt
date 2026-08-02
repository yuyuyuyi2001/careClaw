package com.shijing.xomniclaw.agent.tools

import android.content.Context
import android.util.Log
import com.shijing.xomniclaw.agent.memory.MemoryManager
import com.shijing.xomniclaw.agent.memory.evolution.MemoryEvolutionManager
import com.shijing.xomniclaw.agent.memory.evolution.MemoryEvolutionSettingsStore
import com.shijing.xomniclaw.agent.memory.evolution.MemoryEvolutionStatusStore
import com.shijing.xomniclaw.config.ConfigLoader
import com.shijing.xomniclaw.providers.FunctionDefinition
import com.shijing.xomniclaw.providers.ParametersSchema
import com.shijing.xomniclaw.providers.PropertySchema
import com.shijing.xomniclaw.providers.ToolDefinition
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * memory_evolution tool
 *
 * 专门供全局记忆进化定时任务调用，统一处理 pending 任务事件并更新 MEMORY.md / USER-PROFILE.md。
 */
class MemoryEvolutionSkill(private val context: Context) : Skill {
    companion object {
        private const val TAG = "MemoryEvolutionSkill"
        private const val WORKSPACE_PATH = "/sdcard/.xomniclaw/workspace"
        private const val LLM_FUNCTION_DESCRIPTION = "Run or inspect global memory evolution. " +
            "action: run|status. run processes pending X-OmniClaw task memories, updates MEMORY.md, and rebuilds memory/USER-PROFILE.md."
        private val TIME_FORMAT = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    }

    override val name = "memory_evolution"
    override val description = "Global task-memory evolution for MEMORY.md and USER-PROFILE.md."

    override fun getToolDefinition(): ToolDefinition {
        return ToolDefinition(
            type = "function",
            function = FunctionDefinition(
                name = name,
                description = LLM_FUNCTION_DESCRIPTION,
                parameters = ParametersSchema(
                    type = "object",
                    properties = mapOf(
                        "action" to PropertySchema(
                            type = "string",
                            description = "—",
                            enum = listOf("run", "status")
                        )
                    ),
                    required = listOf("action")
                )
            )
        )
    }

    override suspend fun execute(args: Map<String, Any?>): SkillResult {
        val action = (args["action"] as? String)?.trim()?.lowercase(Locale.getDefault())
            ?: return SkillResult.error("Missing required parameter: action")
        val manager = buildManager()
        return try {
            when (action) {
                "run" -> {
                    val report = manager.runEvolution()
                    SkillResult.success(
                        content = buildString {
                            appendLine("Memory evolution finished.")
                            appendLine("Processed events: ${report.processedEvents}")
                            appendLine("Accepted candidates: ${report.acceptedCandidates}")
                            appendLine("Skipped candidates: ${report.skippedCandidates}")
                            appendLine("MEMORY.md updated: ${report.globalMemoryUpdated}")
                            appendLine("USER-PROFILE.md updated: ${report.profileUpdated}")
                            appendLine("Pending events remaining: ${report.pendingEventsRemaining}")
                            append(report.message)
                        },
                        metadata = mapOf(
                            "processed_events" to report.processedEvents,
                            "accepted_candidates" to report.acceptedCandidates,
                            "pending_events_remaining" to report.pendingEventsRemaining
                        )
                    )
                }
                "status" -> {
                    val status = manager.getStatus()
                    SkillResult.success(
                        content = buildString {
                            appendLine("Memory evolution status")
                            appendLine("- last_run_at: ${formatTimestamp(status.lastRunAtMs)}")
                            appendLine("- processed_events: ${status.processedEvents}")
                            appendLine("- accepted_candidates: ${status.acceptedCandidates}")
                            appendLine("- pending_events: ${status.pendingEvents}")
                            appendLine("- MEMORY.md chars: ${status.globalMemoryChars}")
                            appendLine("- USER-PROFILE.md chars: ${status.userProfileChars}")
                            append("- message: ${status.lastMessage}")
                        },
                        metadata = mapOf(
                            "pending_events" to status.pendingEvents,
                            "last_run_at_ms" to status.lastRunAtMs
                        )
                    )
                }
                else -> SkillResult.error("Unsupported action: $action")
            }
        } catch (e: Exception) {
            Log.e(TAG, "memory_evolution execution failed", e)
            SkillResult.error("Failed to execute memory_evolution: ${e.message}")
        }
    }

    private fun buildManager(): MemoryEvolutionManager {
        val configLoader = ConfigLoader(context)
        val openClawCfg = configLoader.loadOmniClawConfig()
        val embeddingProviders = openClawCfg.resolveProviders()
        val embeddingBaseUrl = embeddingProviders.values.firstOrNull()?.baseUrl ?: ""
        val embeddingApiKey = embeddingProviders.values.firstOrNull()?.apiKey ?: ""
        val memoryManager = MemoryManager(
            workspacePath = WORKSPACE_PATH,
            context = context,
            embeddingBaseUrl = embeddingBaseUrl,
            embeddingApiKey = embeddingApiKey
        )
        return MemoryEvolutionManager(
            context = context,
            memoryManager = memoryManager,
            settingsStore = MemoryEvolutionSettingsStore(),
            statusStore = MemoryEvolutionStatusStore()
        )
    }

    private fun formatTimestamp(timestampMs: Long): String {
        if (timestampMs <= 0L) {
            return "N/A"
        }
        return TIME_FORMAT.format(Date(timestampMs))
    }
}
