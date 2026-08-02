package com.shijing.xomniclaw.agent.loop

import android.util.Log
import com.chaquo.python.Python
import com.chaquo.python.PyException
import com.shijing.xomniclaw.agent.context.ContextManager
import com.shijing.xomniclaw.agent.context.ContextWindowGuard
import com.shijing.xomniclaw.agent.skills.SkillsLoader
import com.shijing.xomniclaw.agent.session.HistorySanitizer
import com.shijing.xomniclaw.config.ConfigLoader
import com.shijing.xomniclaw.agent.tools.AndroidToolRegistry
import com.shijing.xomniclaw.agent.tools.ToolCallDispatcher
import com.shijing.xomniclaw.agent.tools.ToolRegistry
import com.shijing.xomniclaw.agent.tools.LlmOnDemandToolInclusion
import com.shijing.xomniclaw.agent.tools.LlmToolRouter
import com.shijing.xomniclaw.providers.ToolDefinition
import com.shijing.xomniclaw.providers.UnifiedLLMProvider
import com.shijing.xomniclaw.providers.LLMResponse
import com.shijing.xomniclaw.providers.LLMToolCall
import com.shijing.xomniclaw.providers.llm.Message
import com.shijing.xomniclaw.providers.llm.ToolCall
import com.shijing.xomniclaw.util.LayoutExceptionLogger
import com.shijing.xomniclaw.util.PromptArtifactNaming
import com.shijing.xomniclaw.util.ToolArgsNormalizer
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * Agent Loop — thin Kotlin bridge.
 *
 * All business logic (iteration control, context management, loop detection,
 * incremental sensing, nudge injection, error tracking) has been migrated to
 * Python via Chaquopy (see app/src/main/python/agent_logic.py).
 *
 * This class retains only Android platform glue:
 *   - SharedFlow for progress updates
 *   - Coroutine scope / Dispatchers.IO
 *   - Tool execution via ToolCallDispatcher
 *   - LLM HTTP calls via UnifiedLLMProvider
 *   - KotlinBridge callback object exposed to Python
 */
class AgentLoop(
    private val llmProvider: UnifiedLLMProvider,
    private val toolRegistry: ToolRegistry,
    private val androidToolRegistry: AndroidToolRegistry,
    private val contextManager: ContextManager? = null,
    private val maxIterations: Int = 40,
    private val modelRef: String? = null,
    private val configLoader: ConfigLoader? = null
) {
    companion object {
        private const val TAG = "AgentLoop"
        private const val LLM_TIMEOUT_MS = 180_000L
        private const val DEFAULT_TOOL_TIMEOUT_MS = 30_000L
        private const val GALLERY_MEMORY_TOOL_TIMEOUT_MS = 300_000L
    }

    private val gson = Gson()
    private val toolCallDispatcher = ToolCallDispatcher(toolRegistry, androidToolRegistry)

    /**
     * 当轮 Agent 发给大模型的 tools（在 [runViaPython] 入口按用户话/系统提示算好，避免每轮都带上 image_memory_search_entries）。
     */
    @Volatile
    private var llmToolDefinitionsForThisRun: List<ToolDefinition> = emptyList()

    /**
     * 首跳工具路由决策快照。
     * 用于把“第 1 步怎么选工具”的思考过程写入 progressFlow，便于 UI/日志可见。
     */
    private data class RouteDecision(
        val onDemandNames: Set<String>,
        val hint: String?,
        val source: String
    )

    private fun buildLlmToolDefinitionsForThisRun(onDemand: Set<String>): List<ToolDefinition> {
        if (onDemand.isNotEmpty()) {
            Log.d(TAG, "LLM on-demand tools: $onDemand")
        }
        return toolRegistry.getToolDefinitions(onDemandLlmNamesToInclude = onDemand) +
            androidToolRegistry.getToolDefinitions(onDemandLlmNamesToInclude = onDemand)
    }

    /**
     * 首跳 [LlmToolRouter] 无 tools 仅 JSON，失败则回退 [LlmOnDemandToolInclusion] 关键词。
     * 返回 (按需名, 可选 hint 供拼进主 system)。
     */
    private suspend fun resolveOnDemandAndRouteHint(
        systemPrompt: String,
        userMessage: String
    ): RouteDecision {
        // 整段可路由逻辑失败时不应让 Agent 整轮失败：回退到关键词集（与 router 关时一致）
        return try {
            val routable = LlmToolRouter.buildRoutableNameList(
                LlmOnDemandToolInclusion.ON_DEMAND_LLM_TOOL_NAMES,
                toolRegistry.getRegisteredToolNames(),
                androidToolRegistry.getRegisteredToolNames()
            )
            when {
                !LlmToolRouter.ENABLED || routable.isEmpty() -> {
                    RouteDecision(
                        onDemandNames = LlmOnDemandToolInclusion.resolveInclusions(systemPrompt, userMessage),
                        hint = null,
                        source = "keyword"
                    )
                }
                else -> {
                    val outcome = LlmToolRouter.runRouterLlm(
                        provider = llmProvider,
                        modelRef = modelRef,
                        userMessage = userMessage,
                        systemPrompt = systemPrompt,
                        routableNames = routable
                    )
                    if (outcome != null) {
                        RouteDecision(
                            onDemandNames = outcome.onDemandNames,
                            hint = outcome.hint,
                            source = "router_llm"
                        )
                    } else {
                        Log.w(TAG, "LLM tool router 解析失败, 回退关键词启发式")
                        RouteDecision(
                            onDemandNames = LlmOnDemandToolInclusion.resolveInclusions(systemPrompt, userMessage),
                            hint = null,
                            source = "router_fallback_keyword"
                        )
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "按需工具路由阶段异常, 回退关键词启发式", e)
            RouteDecision(
                onDemandNames = LlmOnDemandToolInclusion.resolveInclusions(systemPrompt, userMessage),
                hint = null,
                source = "router_error_keyword"
            )
        }
    }

    /**
     * 把首跳工具路由过程整理成一条可读 reasoning 日志。
     * 目标：让“第一步思考过程”在 UI/日志里可见，而不是只在内部 Logcat。
     */
    private fun buildRouteReasoningText(
        decision: RouteDecision,
        selectedToolDefinitions: List<ToolDefinition>
    ): String {
        val selectedToolNames = selectedToolDefinitions.map { it.function.name }.sorted()
        val shownTools = selectedToolNames.take(12).joinToString(", ")
        val toolPreview = if (shownTools.isBlank()) "无" else shownTools
        val hintPreview = decision.hint?.trim()?.take(160)?.ifBlank { "无" } ?: "无"
        return buildString {
            append("[第1步·工具路由] ")
            append("source=${decision.source}; ")
            append("onDemand=${decision.onDemandNames.size}; ")
            append("selectedTools=${selectedToolDefinitions.size}; ")
            append("tools=$toolPreview; ")
            append("routeHint=$hintPreview")
        }
    }

    private val _progressFlow = MutableSharedFlow<ProgressUpdate>(
        replay = 1,
        extraBufferCapacity = 10
    )
    val progressFlow: SharedFlow<ProgressUpdate> = _progressFlow.asSharedFlow()

    @Volatile
    private var shouldStop = false

    private fun toolExecutionTimeoutMs(toolName: String): Long {
        return when (toolName) {
            "gallery_memory" -> GALLERY_MEMORY_TOOL_TIMEOUT_MS
            else -> DEFAULT_TOOL_TIMEOUT_MS
        }
    }

    private fun resolveContextWindowTokens(): Int {
        if (configLoader == null) return ContextWindowGuard.DEFAULT_CONTEXT_WINDOW_TOKENS
        val parts = modelRef?.split("/", limit = 2)
        val providerName = if (parts != null && parts.size == 2) parts[0] else null
        val modelId = if (parts != null && parts.size == 2) parts[1] else modelRef
        val guard = ContextWindowGuard.resolveAndEvaluate(configLoader, providerName, modelId)
        return guard.tokens
    }

    /**
     * Run Agent Loop — public API (unchanged from callers' perspective).
     */
    suspend fun run(
        systemPrompt: String,
        userMessage: String,
        contextHistory: List<Message> = emptyList(),
        reasoningEnabled: Boolean = true
    ): AgentResult {
        // 在整轮任务入口就固定调试产物批次时间戳，避免每轮 LLM 请求重新生成新前缀。
        PromptArtifactNaming.beginAgentLoop(userMessage)
        return try {
            runViaPython(systemPrompt, userMessage, contextHistory, reasoningEnabled)
        } catch (e: Exception) {
            val cancelled = isCancellationError(e)
            if (cancelled) {
                Log.i(TAG, "🛑 AgentLoop cancelled by user/parent job: ${e.message}")
            } else {
                Log.e(TAG, "❌ AgentLoop 未捕获的错误", e)
            }
            LayoutExceptionLogger.log("AgentLoop#run", e)

            val errorMessage = if (cancelled) {
                buildString {
                    append("🛑 已停止执行\n\n")
                    append("你已手动取消当前任务，这不是系统错误。\n\n")
                    append("你可以：\n")
                    append("- 继续发送新的指令\n")
                    append("- 或点击“新对话”开始新的会话")
                }
            } else {
                buildString {
                    append("❌ Agent 执行失败\n\n")
                    append("**错误信息**: ${e.message ?: "未知错误"}\n\n")
                    append("**错误类型**: ${e.javaClass.simpleName}\n\n")
                    append("**建议**: \n")
                    append("- 请检查网络连接\n")
                    append("- 如果问题持续，请点击“新对话”重新开始\n")
                    append("- 查看日志获取更多详细信息")
                }
            }

            AgentResult(
                finalContent = errorMessage,
                toolsUsed = emptyList(),
                messages = listOf(
                    Message(role = "system", content = systemPrompt),
                    Message(role = "user", content = userMessage),
                    Message(role = "assistant", content = errorMessage)
                ),
                iterations = 0,
                tokenUsage = null
            )
        }
    }

    /**
     * 统一识别“用户主动停止/父协程取消”场景，避免被误展示为执行失败。
     */
    private fun isCancellationError(error: Throwable): Boolean {
        if (error is CancellationException) return true
        var cursor: Throwable? = error
        while (cursor != null) {
            if (cursor is CancellationException) return true
            val msg = cursor.message?.lowercase().orEmpty()
            if (msg.contains("cancel")) return true
            cursor = cursor.cause
        }
        return false
    }

    /**
     * Delegate to Python agent_logic.run_agent() via Chaquopy.
     */
    private suspend fun runViaPython(
        systemPrompt: String,
        userMessage: String,
        contextHistory: List<Message>,
        reasoningEnabled: Boolean
    ): AgentResult = withContext(Dispatchers.IO) {
        shouldStop = false

        contextManager?.reset()

        // Sanitize history (filter system messages, limit turns)
        // thinking 仅用于主对话复盘/落盘，不得进入 LLM 多轮上文
        val filtered = contextHistory.filter { it.role != "system" && it.role != "thinking" }
        // 仅保留最近 5 轮对话，避免把过多历史直接喂给大模型。
        val sanitized = HistorySanitizer.sanitize(filtered, maxTurns = 5)
        val historyJson = gson.toJson(sanitized.map { it.toDict() })

        val contextWindowTokens = resolveContextWindowTokens()

        val greetingOnly = LlmOnDemandToolInclusion.isGreetingOnlyMessage(userMessage)
        val routeDecision = if (greetingOnly) {
            // 轻量方案：寒暄场景完全不传 tools，避免模型上下文被函数 schema 污染。
            Log.d(TAG, "Greeting-only user message detected, send tools=[]")
            RouteDecision(
                onDemandNames = emptySet(),
                hint = null,
                source = "greeting_short_circuit"
            )
        } else {
            resolveOnDemandAndRouteHint(systemPrompt, userMessage)
        }
        val onDemand = routeDecision.onDemandNames.toMutableSet()
        // 无论是 router_llm 还是关键词回退，只要本轮 prompt 有 Skills 目录，就保证 read_file 可用，
        // 避免出现“推理想先读 SKILL.md，但 tools 中没有 read_file”的能力断层。
        if (!greetingOnly && systemPrompt.contains("## Skills (mandatory)", ignoreCase = true)) {
            onDemand.add("read_file")
        }
        val routeHint = routeDecision.hint
        llmToolDefinitionsForThisRun = if (greetingOnly) {
            emptyList()
        } else {
            buildLlmToolDefinitionsForThisRun(onDemand)
        }
        val routeReasoningText = buildRouteReasoningText(routeDecision, llmToolDefinitionsForThisRun)
        Log.i(TAG, routeReasoningText)
        // 将“首跳工具路由思考过程”显式发到进度流，确保第一步在日志/UI可见。
        _progressFlow.emit(
            ProgressUpdate.Reasoning(
                content = routeReasoningText,
                llmDuration = 0L
            )
        )

        val systemForAgent = if (!routeHint.isNullOrBlank()) {
            systemPrompt + "\n\n[RouteHint 供主 Agent 参考, 非硬约束]\n" + routeHint.trim() + "\n"
        } else {
            systemPrompt
        }

        // Obtain Python module
        val py = Python.getInstance()
        val agentLogic = py.getModule("agent_logic")

        // Create bridge object
        val bridge = KotlinBridge()

        // Call Python entry point
        val resultJson = try {
            agentLogic.callAttr(
                "run_agent",
                bridge,
                systemForAgent,
                userMessage,
                historyJson,
                reasoningEnabled,
                maxIterations,
                contextWindowTokens
            ).toString()
        } catch (e: PyException) {
            Log.e(TAG, "Python agent_logic.run_agent failed", e)
            throw RuntimeException("Python AgentLogic error: ${e.message}", e)
        }

        // Parse result
        parseAgentResult(resultJson, systemForAgent, userMessage)
    }

    /**
     * Parse the JSON result from Python into AgentResult.
     */
    private fun parseAgentResult(
        resultJson: String,
        systemPrompt: String,
        userMessage: String
    ): AgentResult {
        return try {
            val map: Map<String, Any?> = gson.fromJson(
                resultJson,
                object : TypeToken<Map<String, Any?>>() {}.type
            )

            val finalContent = map["final_content"] as? String ?: "无响应"
            @Suppress("UNCHECKED_CAST")
            val toolsUsed = (map["tools_used"] as? List<String>) ?: emptyList()
            val iterations = (map["iterations"] as? Double)?.toInt() ?: 0
            @Suppress("UNCHECKED_CAST")
            val rawMessages = (map["messages"] as? List<Map<String, Any?>>) ?: emptyList()
            val tokenUsage = (map["usage"] as? Map<*, *>)?.let { u ->
                LlmTokenUsage(
                    promptTokens = (u["prompt_tokens"] as? Number)?.toInt() ?: 0,
                    completionTokens = (u["completion_tokens"] as? Number)?.toInt() ?: 0,
                    totalTokens = (u["total_tokens"] as? Number)?.toInt() ?: 0
                )
            }

            val messages = rawMessages.map { m ->
                val role = m["role"] as? String ?: "user"
                val content = m["content"] as? String ?: ""
                val name = m["name"] as? String
                val toolCallId = m["tool_call_id"] as? String
                @Suppress("UNCHECKED_CAST")
                val tcs = (m["tool_calls"] as? List<Map<String, Any?>>)?.map { tc ->
                    ToolCall(
                        id = tc["id"] as? String ?: "",
                        name = tc["name"] as? String ?: "",
                        arguments = tc["arguments"] as? String ?: ""
                    )
                }

                Message(
                    role = role,
                    content = content,
                    name = name,
                    toolCallId = toolCallId,
                    toolCalls = tcs
                )
            }

            AgentResult(
                finalContent = finalContent,
                toolsUsed = toolsUsed,
                messages = messages,
                iterations = iterations,
                tokenUsage = tokenUsage
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse Python result", e)
            AgentResult(
                finalContent = "❌ 内部错误：无法解析执行结果",
                toolsUsed = emptyList(),
                messages = listOf(
                    Message(role = "system", content = systemPrompt),
                    Message(role = "user", content = userMessage),
                    Message(role = "assistant", content = "❌ 内部错误：无法解析执行结果")
                ),
                iterations = 0,
                tokenUsage = null
            )
        }
    }

    fun stop() {
        shouldStop = true
        Log.d(TAG, "Stop signal received")
    }

    // =========================================================================
    // KotlinBridge — callback object passed to Python
    // =========================================================================

    /**
     * Bridge object exposed to Python via Chaquopy.
     *
     * Python calls these methods synchronously (Chaquopy runs on the caller thread);
     * since the caller is already on Dispatchers.IO, blocking is acceptable.
     */
    inner class KotlinBridge {

        /**
         * Check whether stop was requested by the user.
         * Called from Python at the start of each iteration.
         */
        fun is_stop_requested(): Boolean = shouldStop

        /**
         * Call LLM and return JSON response.
         *
         * @param messagesJson JSON array of message dicts
         * @param reasoningEnabled whether reasoning/thinking is enabled
         * @param iteration current iteration index (1-based), used for prompt dump filename
         * @return JSON string with keys: content, tool_calls, thinking_content, finish_reason
         */
        fun call_llm(messagesJson: String, reasoningEnabled: Boolean, iteration: Int = 0): String {
            if (shouldStop) throw RuntimeException("__AGENT_STOPPED__")
            llmProvider.currentIterationHint = iteration
            val messages = deserializeMessages(messagesJson)

            val response: LLMResponse = runBlocking {
                kotlinx.coroutines.withTimeout(LLM_TIMEOUT_MS) {
                    llmProvider.chatWithTools(
                        messages = messages,
                        tools = llmToolDefinitionsForThisRun,
                        modelRef = modelRef,
                        reasoningEnabled = reasoningEnabled
                    )
                }
            }

            val toolCallsList = response.toolCalls?.map { tc ->
                mapOf("id" to tc.id, "name" to tc.name, "arguments" to tc.arguments)
            }

            val result = mutableMapOf<String, Any?>(
                "content" to response.content,
                "tool_calls" to toolCallsList,
                "thinking_content" to response.thinkingContent,
                "finish_reason" to response.finishReason
            )
            // 供 Python 端累加全轮次消耗（OpenAI 兼容 usage）
            response.usage?.let { u ->
                // 同步发出“实时 token 增量”事件，供状态页在任务进行中即时刷新。
                runBlocking {
                    _progressFlow.emit(
                        ProgressUpdate.LlmUsage(
                            usage = LlmTokenUsage(
                                promptTokens = u.promptTokens,
                                completionTokens = u.completionTokens,
                                totalTokens = u.totalTokens
                            )
                        )
                    )
                }
                result["usage"] = mapOf(
                    "prompt_tokens" to u.promptTokens,
                    "completion_tokens" to u.completionTokens,
                    "total_tokens" to u.totalTokens
                )
            }
            return gson.toJson(result)
        }

        /**
         * Execute a tool and return JSON result.
         *
         * @param name tool name
         * @param argsJson JSON string of tool arguments
         * @return JSON string with keys: success, content, metadata
         */
        fun execute_tool(name: String, argsJson: String): String {
            if (shouldStop) throw RuntimeException("__AGENT_STOPPED__")
            val args: Map<String, Any?> = try {
                @Suppress("UNCHECKED_CAST")
                ToolArgsNormalizer.normalize(gson.fromJson(argsJson, Map::class.java) as Map<String, Any?>)
            } catch (_: Exception) {
                emptyMap()
            }

            val timeoutMs = toolExecutionTimeoutMs(name)
            val result = runBlocking {
                try {
                    kotlinx.coroutines.withTimeout(timeoutMs) {
                        toolCallDispatcher.execute(name, args)
                    }
                } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                    com.shijing.xomniclaw.agent.tools.SkillResult.error(
                        "Tool execution timeout after ${timeoutMs / 1000} seconds."
                    )
                }
            }

            return gson.toJson(
                mapOf(
                    "success" to result.success,
                    "content" to result.content,
                    "metadata" to result.metadata
                )
            )
        }

        /**
         * Emit a progress update to the SharedFlow.
         *
         * @param type progress type (iteration, thinking, reasoning, tool_call, etc.)
         * @param dataJson JSON string with type-specific data
         */
        fun emit_progress(type: String, dataJson: String) {
            val data: Map<String, Any?> = try {
                @Suppress("UNCHECKED_CAST")
                gson.fromJson(dataJson, Map::class.java) as Map<String, Any?>
            } catch (_: Exception) {
                emptyMap()
            }

            val update = when (type) {
                "iteration" -> ProgressUpdate.Iteration(
                    (data["number"] as? Double)?.toInt() ?: 0
                )
                "thinking" -> ProgressUpdate.Thinking(
                    (data["iteration"] as? Double)?.toInt() ?: 0
                )
                "reasoning" -> ProgressUpdate.Reasoning(
                    data["content"] as? String ?: "",
                    (data["llm_duration"] as? Double)?.toLong() ?: 0L
                )
                "tool_call" -> {
                    @Suppress("UNCHECKED_CAST")
                    ProgressUpdate.ToolCall(
                        data["name"] as? String ?: "",
                        ToolArgsNormalizer.normalize((data["arguments"] as? Map<String, Any?>) ?: emptyMap())
                    )
                }
                "tool_result" -> ProgressUpdate.ToolResult(
                    data["name"] as? String ?: "",
                    data["result"] as? String ?: "",
                    (data["exec_duration"] as? Double)?.toLong() ?: 0L
                )
                "iteration_complete" -> ProgressUpdate.IterationComplete(
                    (data["number"] as? Double)?.toInt() ?: 0,
                    (data["iteration_duration"] as? Double)?.toLong() ?: 0L,
                    (data["llm_duration"] as? Double)?.toLong() ?: 0L,
                    (data["exec_duration"] as? Double)?.toLong() ?: 0L
                )
                "block_reply" -> ProgressUpdate.BlockReply(
                    data["text"] as? String ?: "",
                    (data["iteration"] as? Double)?.toInt() ?: 0
                )
                "loop_detected" -> ProgressUpdate.LoopDetected(
                    detector = data["detector"] as? String ?: "",
                    count = (data["count"] as? Double)?.toInt() ?: 0,
                    message = data["message"] as? String ?: "",
                    critical = data["critical"] as? Boolean ?: false
                )
                "skip_reasoning" -> {
                    Log.d(TAG, "⏭️ Incremental sensor: UI unchanged, reasoning skipped")
                    return
                }
                "error" -> ProgressUpdate.Error(data["message"] as? String ?: "Unknown error")
                "context_overflow" -> ProgressUpdate.ContextOverflow(
                    data["message"] as? String ?: ""
                )
                "context_recovered" -> ProgressUpdate.ContextRecovered(
                    strategy = data["strategy"] as? String ?: "",
                    attempt = (data["attempt"] as? Double)?.toInt() ?: 0
                )
                else -> {
                    Log.w(TAG, "Unknown progress type: $type")
                    return
                }
            }

            runBlocking {
                _progressFlow.emit(update)
            }
        }

        /**
         * Get tool definitions as JSON string.
         */
        fun get_tool_definitions(): String {
            return gson.toJson(llmToolDefinitionsForThisRun)
        }

        /**
         * Return latest skill-selection trace built in SkillsLoader.
         * Python side writes this into agentloop_*.log, so diagnosis does not depend on logcat.
         */
        fun get_skill_selection_trace(): String {
            return SkillsLoader.getLastSelectionTraceForBridge()
        }

        private fun deserializeMessages(json: String): List<Message> {
            val listType = object : TypeToken<List<Map<String, Any?>>>() {}.type
            val rawList: List<Map<String, Any?>> = gson.fromJson(json, listType)

            return rawList.map { m ->
                val role = m["role"] as? String ?: "user"
                val content = m["content"] as? String ?: ""
                val name = m["name"] as? String
                val toolCallId = m["tool_call_id"] as? String
                @Suppress("UNCHECKED_CAST")
                val tcs = (m["tool_calls"] as? List<Map<String, Any?>>)?.map { tc ->
                    ToolCall(
                        id = tc["id"] as? String ?: "",
                        name = tc["name"] as? String ?: "",
                        arguments = tc["arguments"] as? String ?: ""
                    )
                }
                Message(
                    role = role,
                    content = content,
                    name = name,
                    toolCallId = toolCallId,
                    toolCalls = tcs
                )
            }
        }
    }
}

// =========================================================================
// Extension: Message -> dict for JSON serialization
// =========================================================================

private fun Message.toDict(): Map<String, Any?> {
    val m = mutableMapOf<String, Any?>(
        "role" to role,
        "content" to content
    )
    name?.let { m["name"] = it }
    toolCallId?.let { m["tool_call_id"] = it }
    toolCalls?.let { tcs ->
        m["tool_calls"] = tcs.map { tc ->
            mapOf("id" to tc.id, "name" to tc.name, "arguments" to tc.arguments)
        }
    }
    return m
}

// =========================================================================
// Data classes (unchanged — consumed by Android UI layer)
// =========================================================================

/** 本轮 Agent 在 Kotlin 侧 LLM 调用的 token 累加（由 Python 汇总多轮后返回，若提供商未给 usage 则可能为 0） */
data class LlmTokenUsage(
    val promptTokens: Int,
    val completionTokens: Int,
    val totalTokens: Int
)

data class AgentResult(
    val finalContent: String,
    val toolsUsed: List<String>,
    val messages: List<Message>,
    val iterations: Int,
    val tokenUsage: LlmTokenUsage? = null
)

sealed class ProgressUpdate {
    data class Iteration(val number: Int) : ProgressUpdate()
    data class Thinking(val iteration: Int) : ProgressUpdate()
    data class Reasoning(val content: String, val llmDuration: Long) : ProgressUpdate()
    data class ToolCall(val name: String, val arguments: Map<String, Any?>) : ProgressUpdate()
    data class ToolResult(val name: String, val result: String, val execDuration: Long) : ProgressUpdate()
    data class IterationComplete(val number: Int, val iterationDuration: Long, val llmDuration: Long, val execDuration: Long) : ProgressUpdate()
    data class ContextOverflow(val message: String) : ProgressUpdate()
    data class ContextRecovered(val strategy: String, val attempt: Int) : ProgressUpdate()
    data class Error(val message: String) : ProgressUpdate()
    data class LoopDetected(
        val detector: String,
        val count: Int,
        val message: String,
        val critical: Boolean
    ) : ProgressUpdate()
    /**
     * 单次 LLM 请求返回后的 token 增量（实时事件）。
     */
    data class LlmUsage(val usage: LlmTokenUsage) : ProgressUpdate()
    data class BlockReply(val text: String, val iteration: Int) : ProgressUpdate()
}
