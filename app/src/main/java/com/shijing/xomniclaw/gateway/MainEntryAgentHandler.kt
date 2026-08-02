/**
 * OmniClaw Source Reference:
 * - ../omniclaw/src/gateway/(all)
 *
 * OmniClaw adaptation: gateway server and RPC methods.
 */
package com.shijing.xomniclaw.gateway

import android.app.Application
import android.util.Log
import com.shijing.xomniclaw.agent.context.ContextBuilder
import com.shijing.xomniclaw.agent.memory.MemoryManager
import com.shijing.xomniclaw.agent.loop.AgentLoop
import com.shijing.xomniclaw.agent.loop.ProgressUpdate
import com.shijing.xomniclaw.providers.UnifiedLLMProvider
import com.shijing.xomniclaw.agent.tools.AndroidToolRegistry
import com.shijing.xomniclaw.agent.tools.ToolRegistry
import com.shijing.xomniclaw.config.ConfigLoader
import com.shijing.xomniclaw.data.model.TaskDataManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * AgentHandler implementation - connects GatewayService and AgentLoop
 *
 * Responsibilities:
 * 1. Receive Gateway RPC requests
 * 2. Call AgentLoop to execute tasks
 * 3. Send back progress and results
 */
class MainEntryAgentHandler(
    private val application: Application
) : AgentHandler {

    companion object {
        private const val TAG = "MainEntryAgentHandler"
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val taskDataManager: TaskDataManager = TaskDataManager.getInstance()

    // Core components - use unified LLM Provider
    private val llmProvider: UnifiedLLMProvider by lazy {
        UnifiedLLMProvider(application)
    }

    private val configLoader: ConfigLoader by lazy {
        ConfigLoader(application)
    }

    private val toolRegistry: ToolRegistry by lazy {
        ToolRegistry(
            context = application,
            taskDataManager = taskDataManager
        )
    }

    private val androidToolRegistry: AndroidToolRegistry by lazy {
        val openClawCfg = configLoader.loadOmniClawConfig()
        val embeddingProviders = openClawCfg.resolveProviders()
        val embeddingBaseUrl = embeddingProviders.values.firstOrNull()?.baseUrl ?: ""
        val embeddingApiKey = embeddingProviders.values.firstOrNull()?.apiKey ?: ""
        val memoryManager = MemoryManager(
            workspacePath = "/sdcard/.xomniclaw/workspace",
            context = application,
            embeddingBaseUrl = embeddingBaseUrl,
            embeddingApiKey = embeddingApiKey
        )
        AndroidToolRegistry(
            context = application,
            taskDataManager = taskDataManager,
            memoryManager = memoryManager
        )
    }

    private val contextBuilder: ContextBuilder by lazy {
        ContextBuilder(
            context = application,
            toolRegistry = toolRegistry,
            androidToolRegistry = androidToolRegistry,
            configLoader = configLoader
        )
    }

    override fun executeAgent(
        sessionId: String,
        userMessage: String,
        systemPrompt: String?,
        tools: List<Any>?,
        maxIterations: Int,
        progressCallback: (Map<String, Any>) -> Unit,
        completeCallback: (Map<String, Any>) -> Unit
    ) {
        Log.d(TAG, "executeAgent called: session=$sessionId, message=$userMessage")

        scope.launch {
            try {
                // 1. Build system prompt (if not provided)
                val finalSystemPrompt = systemPrompt ?: contextBuilder.buildSystemPrompt(
                    userGoal = userMessage,
                    packageName = "",
                    testMode = "exploration",
                    loadAgentPolicies = true
                )

                Log.d(TAG, "System prompt ready (${finalSystemPrompt.length} chars)")

                // 2. Create AgentLoop (with context management)
                val contextManager = com.shijing.xomniclaw.agent.context.ContextManager(llmProvider)
                val agentLoop = AgentLoop(
                    llmProvider = llmProvider,
                    toolRegistry = toolRegistry,
                    androidToolRegistry = androidToolRegistry,
                    contextManager = contextManager,
                    maxIterations = maxIterations,
                    modelRef = null  // Use default model, can be read from config
                )

                // 3. Listen to progress
                val progressJob = launch {
                    agentLoop.progressFlow.collect { update ->
                        val progressData = convertProgressToMap(update)
                        progressCallback(progressData)
                    }
                }

                // 4. Execute Agent
                Log.d(TAG, "Starting AgentLoop execution...")
                val result = agentLoop.run(
                    systemPrompt = finalSystemPrompt,
                    userMessage = userMessage,
                    reasoningEnabled = true  // Enable reasoning by default
                )

                Log.d(TAG, "AgentLoop completed: ${result.iterations} iterations")

                // 5. Return result
                completeCallback(
                    buildMap {
                        put("success", true)
                        put("iterations", result.iterations)
                        put("toolsUsed", result.toolsUsed)
                        put("finalContent", result.finalContent)
                        put("sessionId", sessionId)
                        result.tokenUsage?.let { u ->
                            put(
                                "tokenUsage",
                                mapOf(
                                    "promptTokens" to u.promptTokens,
                                    "completionTokens" to u.completionTokens,
                                    "totalTokens" to u.totalTokens
                                )
                            )
                        }
                    }
                )

                progressJob.cancel()

            } catch (e: Exception) {
                Log.e(TAG, "Agent execution failed", e)
                completeCallback(mapOf(
                    "success" to false,
                    "error" to (e.message ?: "Unknown error"),
                    "sessionId" to sessionId
                ))
            }
        }
    }

    /**
     * Convert ProgressUpdate to Map (for JSON serialization)
     */
    private fun convertProgressToMap(update: ProgressUpdate): Map<String, Any> {
        return when (update) {
            is ProgressUpdate.Iteration -> mapOf(
                "type" to "iteration",
                "number" to update.number
            )

            is ProgressUpdate.Thinking -> mapOf(
                "type" to "thinking",
                "iteration" to update.iteration
            )

            is ProgressUpdate.Reasoning -> mapOf(
                "type" to "reasoning",
                "content" to update.content,
                "duration" to update.llmDuration
            )

            is ProgressUpdate.ToolCall -> mapOf(
                "type" to "tool_call",
                "name" to update.name,
                "arguments" to update.arguments
            )

            is ProgressUpdate.ToolResult -> mapOf(
                "type" to "tool_result",
                "result" to update.result,
                "duration" to update.execDuration
            )

            is ProgressUpdate.IterationComplete -> mapOf(
                "type" to "iteration_complete",
                "number" to update.number,
                "iterationDuration" to update.iterationDuration,
                "llmDuration" to update.llmDuration,
                "execDuration" to update.execDuration
            )

            is ProgressUpdate.ContextOverflow -> mapOf(
                "type" to "context_overflow",
                "message" to update.message
            )

            is ProgressUpdate.ContextRecovered -> mapOf(
                "type" to "context_recovered",
                "strategy" to update.strategy,
                "attempt" to update.attempt
            )

            is ProgressUpdate.LoopDetected -> mapOf(
                "type" to "loop_detected",
                "detector" to update.detector,
                "count" to update.count,
                "message" to update.message,
                "critical" to update.critical
            )

            // 单次 LLM 调用的 token 用量（用于 UI/渠道侧实时成本展示；与最终回合汇总可并存）
            is ProgressUpdate.LlmUsage -> mapOf(
                "type" to "llm_usage",
                "promptTokens" to update.usage.promptTokens,
                "completionTokens" to update.usage.completionTokens,
                "totalTokens" to update.usage.totalTokens
            )

            is ProgressUpdate.Error -> mapOf(
                "type" to "error",
                "message" to update.message
            )
            is ProgressUpdate.BlockReply -> mapOf(
                "type" to "block_reply",
                "text" to update.text,
                "iteration" to update.iteration
            )
        }
    }
}
