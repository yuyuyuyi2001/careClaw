package com.shijing.xomniclaw.agent.loop

import android.util.Log
import com.shijing.xomniclaw.agent.context.ContextManager
import com.shijing.xomniclaw.agent.context.ContextWindowGuard
import com.shijing.xomniclaw.agent.session.HistorySanitizer
import com.shijing.xomniclaw.config.ConfigLoader
import com.shijing.xomniclaw.agent.tools.AndroidToolRegistry
import com.shijing.xomniclaw.agent.tools.SkillResult
import com.shijing.xomniclaw.agent.tools.ToolCallDispatcher
import com.shijing.xomniclaw.agent.tools.ToolRegistry
import com.shijing.xomniclaw.agent.tools.LlmOnDemandToolInclusion
import com.shijing.xomniclaw.agent.tools.LlmToolRouter
import com.shijing.xomniclaw.providers.ToolDefinition
import com.shijing.xomniclaw.providers.UnifiedLLMProvider
import com.shijing.xomniclaw.providers.LLMResponse
import com.shijing.xomniclaw.providers.llm.Message
import com.shijing.xomniclaw.providers.llm.ToolCall
import com.shijing.xomniclaw.util.LayoutExceptionLogger
import com.shijing.xomniclaw.util.PromptArtifactNaming
import com.shijing.xomniclaw.util.ReasoningTagFilter
import com.shijing.xomniclaw.util.ToolArgsNormalizer
import com.google.gson.Gson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Agent Loop — Kotlin 内联实现（已移除 Chaquopy/Python）。
 *
 * 循环编排直接在本类实现：迭代控制、循环检测(ToolLoopDetection)、
 * 工具执行(ToolCallDispatcher)、LLM 调用(UnifiedLLMProvider)、
 * 上下文预算(简化裁剪)、进度事件(progressFlow)。
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
        private const val CONTEXT_BUDGET_RATIO = 0.75
    }

    private val gson = Gson()
    private val toolCallDispatcher = ToolCallDispatcher(toolRegistry, androidToolRegistry)

    @Volatile
    private var llmToolDefinitionsForThisRun: List<ToolDefinition> = emptyList()

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

    private suspend fun resolveOnDemandAndRouteHint(
        systemPrompt: String,
        userMessage: String
    ): RouteDecision {
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

    suspend fun run(
        systemPrompt: String,
        userMessage: String,
        contextHistory: List<Message> = emptyList(),
        reasoningEnabled: Boolean = true
    ): AgentResult {
        PromptArtifactNaming.beginAgentLoop(userMessage)
        return try {
            runKotlinLoop(systemPrompt, userMessage, contextHistory, reasoningEnabled)
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

    private fun isCancellationError(error: Throwable): Boolean {
        if (error is CancellationException) return true
        var cursor: Throwable? = error
        while (cursor != null) {
            if (cursor is CancellationException) return true
            cursor = cursor.cause
        }
        return false
    }

    private suspend fun runKotlinLoop(
        systemPrompt: String,
        userMessage: String,
        contextHistory: List<Message>,
        reasoningEnabled: Boolean
    ): AgentResult = withContext(Dispatchers.IO) {
        shouldStop = false
        contextManager?.reset()

        val filtered = contextHistory.filter { it.role != "system" && it.role != "thinking" }
        val sanitized = HistorySanitizer.sanitize(filtered, maxTurns = 5)
        val contextWindowTokens = resolveContextWindowTokens()

        val greetingOnly = LlmOnDemandToolInclusion.isGreetingOnlyMessage(userMessage)
        val routeDecision = if (greetingOnly) {
            Log.d(TAG, "Greeting-only user message detected, send tools=[]")
            RouteDecision(emptySet(), null, "greeting_short_circuit")
        } else {
            resolveOnDemandAndRouteHint(systemPrompt, userMessage)
        }
        val onDemand = routeDecision.onDemandNames.toMutableSet()
        if (!greetingOnly && systemPrompt.contains("## Skills (mandatory)", ignoreCase = true)) {
            onDemand.add("read_file")
        }
        val routeHint = routeDecision.hint
        llmToolDefinitionsForThisRun = if (greetingOnly) emptyList() else buildLlmToolDefinitionsForThisRun(onDemand)
        val routeReasoningText = buildRouteReasoningText(routeDecision, llmToolDefinitionsForThisRun)
        Log.i(TAG, routeReasoningText)
        _progressFlow.emit(ProgressUpdate.Reasoning(content = routeReasoningText, llmDuration = 0L))

        val systemForAgent = if (!routeHint.isNullOrBlank()) {
            systemPrompt + "\n\n[RouteHint 供主 Agent 参考, 非硬约束]\n" + routeHint.trim() + "\n"
        } else {
            systemPrompt
        }

        val messages = mutableListOf<Message>()
        messages.add(Message(role = "system", content = systemForAgent))
        messages.addAll(sanitized)
        messages.add(Message(role = "user", content = userMessage))

        val loopDetectorState = ToolLoopDetection.SessionState()
        val toolsUsed = mutableListOf<String>()
        var finalContent: String? = null
        var promptTokens = 0
        var completionTokens = 0
        var totalTokens = 0
        var iteration = 0

        for (iter in 1..maxIterations) {
            iteration = iter
            if (shouldStop) {
                finalContent = "已按用户请求停止。"
                break
            }
            _progressFlow.emit(ProgressUpdate.Iteration(iter))

            enforceContextBudget(messages, contextWindowTokens)

            _progressFlow.emit(ProgressUpdate.Thinking(iter))
            val llmStart = System.currentTimeMillis()
            val response: LLMResponse = try {
                callLlm(messages, reasoningEnabled, iter)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val errMsg = e.message ?: "unknown"
                if (errMsg.contains("__AGENT_STOPPED__") || shouldStop) {
                    finalContent = "已按用户请求停止。"
                    break
                }
                Log.e(TAG, "LLM 调用失败: $errMsg", e)
                finalContent = "❌ 执行出错\n\n**错误信息**: $errMsg"
                break
            }
            val llmDurationMs = System.currentTimeMillis() - llmStart

            response.usage?.let { u ->
                promptTokens += u.promptTokens
                completionTokens += u.completionTokens
                totalTokens += u.totalTokens
            }

            response.thinkingContent?.let {
                _progressFlow.emit(ProgressUpdate.Reasoning(content = it, llmDuration = llmDurationMs))
            }

            val toolCalls = response.toolCalls
            if (!toolCalls.isNullOrEmpty()) {
                response.content?.trim()?.takeIf { it.isNotEmpty() }?.let {
                    _progressFlow.emit(ProgressUpdate.BlockReply(text = it, iteration = iter))
                }
                messages.add(
                    Message(
                        role = "assistant",
                        content = response.content ?: "",
                        toolCalls = toolCalls.map { tc -> ToolCall(tc.id, tc.name, tc.arguments) }
                    )
                )

                var shouldStopLoop = false
                var totalExecMs = 0L
                for (tc in toolCalls) {
                    if (shouldStop) {
                        finalContent = "已按用户请求停止。"
                        shouldStopLoop = true
                        break
                    }
                    val fnName = tc.name
                    val args = parseToolArgs(tc.arguments)
                    _progressFlow.emit(ProgressUpdate.ToolCall(name = fnName, arguments = args))

                    val det = ToolLoopDetection.detectToolCallLoop(loopDetectorState, fnName, args)
                    if (det is ToolLoopDetection.LoopDetectionResult.LoopDetected) {
                        _progressFlow.emit(
                            ProgressUpdate.LoopDetected(
                                detector = det.detector.name,
                                count = det.count,
                                message = det.message,
                                critical = det.level == ToolLoopDetection.LoopDetectionResult.Level.CRITICAL
                            )
                        )
                        if (det.level == ToolLoopDetection.LoopDetectionResult.Level.CRITICAL) {
                            messages.add(Message(role = "tool", content = det.message, toolCallId = tc.id, name = fnName))
                            finalContent = "Task failed: ${det.message}"
                            shouldStopLoop = true
                            break
                        }
                        messages.add(Message(role = "tool", content = det.message, toolCallId = tc.id, name = fnName))
                        continue
                    }

                    ToolLoopDetection.recordToolCall(loopDetectorState, fnName, args, tc.id)
                    toolsUsed.add(fnName)

                    val execStart = System.currentTimeMillis()
                    val result = executeTool(fnName, tc.arguments)
                    val execMs = System.currentTimeMillis() - execStart
                    totalExecMs += execMs

                    if (!result.success && fnName == "device" && isSnapshotRefError(result.content)) {
                        val hint = "系统建议：上一步依赖 snapshot/ref 的操作失败。" +
                            "下一轮请先 device(action=\"snapshot\") 刷新 ref；若仍不足，再使用 device(action=\"screenshot\", query=\"与任务相关的控件描述\") 做视觉定位。"
                        messages.add(Message(role = "tool", content = hint, toolCallId = tc.id, name = fnName))
                    }

                    ToolLoopDetection.recordToolCallOutcome(
                        loopDetectorState, fnName, args, result.content,
                        if (result.success) null else Exception(result.content), tc.id
                    )

                    messages.add(Message(role = "tool", content = result.content, toolCallId = tc.id, name = fnName))
                    _progressFlow.emit(ProgressUpdate.ToolResult(name = fnName, result = result.content, execDuration = execMs))
                }
                _progressFlow.emit(
                    ProgressUpdate.IterationComplete(
                        number = iter,
                        iterationDuration = System.currentTimeMillis() - llmStart,
                        llmDuration = llmDurationMs,
                        execDuration = totalExecMs
                    )
                )
                if (shouldStopLoop) break
                continue
            }

            val stripped = ReasoningTagFilter.stripReasoningTags(response.content ?: "")
            finalContent = stripped
            messages.add(Message(role = "assistant", content = stripped))
            break
        }

        if (finalContent == null && iteration >= maxIterations) {
            finalContent = "达到最大迭代次数 ($maxIterations)，任务未完成。建议将任务拆分为更小的步骤。"
        }
        if (finalContent == null) finalContent = "无响应"

        AgentResult(
            finalContent = finalContent,
            toolsUsed = toolsUsed.distinct(),
            messages = messages,
            iterations = iteration,
            tokenUsage = LlmTokenUsage(promptTokens, completionTokens, totalTokens)
        )
    }

    private suspend fun callLlm(messages: List<Message>, reasoningEnabled: Boolean, iteration: Int): LLMResponse {
        if (shouldStop) throw RuntimeException("__AGENT_STOPPED__")
        llmProvider.currentIterationHint = iteration
        val response = withTimeout(LLM_TIMEOUT_MS) {
            llmProvider.chatWithTools(
                messages = messages,
                tools = llmToolDefinitionsForThisRun,
                modelRef = modelRef,
                reasoningEnabled = reasoningEnabled
            )
        }
        response.usage?.let { u ->
            _progressFlow.emit(
                ProgressUpdate.LlmUsage(
                    usage = LlmTokenUsage(u.promptTokens, u.completionTokens, u.totalTokens)
                )
            )
        }
        return response
    }

    private suspend fun executeTool(name: String, argsJson: String): SkillResult {
        if (shouldStop) throw RuntimeException("__AGENT_STOPPED__")
        val args: Map<String, Any?> = try {
            @Suppress("UNCHECKED_CAST")
            ToolArgsNormalizer.normalize(gson.fromJson(argsJson, Map::class.java) as Map<String, Any?>)
        } catch (_: Exception) {
            emptyMap()
        }
        val timeoutMs = toolExecutionTimeoutMs(name)
        return try {
            withTimeout(timeoutMs) { toolCallDispatcher.execute(name, args) }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            SkillResult.error("Tool execution timeout after ${timeoutMs / 1000} seconds.")
        }
    }

    private fun parseToolArgs(argsJson: String): Map<String, Any?> {
        return try {
            @Suppress("UNCHECKED_CAST")
            ToolArgsNormalizer.normalize(gson.fromJson(argsJson, Map::class.java) as Map<String, Any?>)
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun enforceContextBudget(messages: MutableList<Message>, contextWindowTokens: Int) {
        val budgetChars = (contextWindowTokens * 4 * CONTEXT_BUDGET_RATIO).toInt()
        var totalChars = messages.sumOf { it.content.length + (it.toolCalls?.sumOf { tc -> tc.arguments.length } ?: 0) }
        if (totalChars <= budgetChars) return
        val keepFrom = maxOf(0, messages.size - 10)
        var i = 0
        while (i < keepFrom && totalChars > budgetChars) {
            val m = messages[i]
            if (m.role == "tool") {
                val removed = m.content.length
                messages.removeAt(i)
                totalChars -= removed
            } else {
                i++
            }
        }
    }

    private fun isSnapshotRefError(err: String): Boolean {
        val msg = err.lowercase()
        return listOf(
            "已过期", "ref '", "ref \"", "不存在于最近一次 snapshot",
            "无障碍服务未开启", "无障碍服务未启用", "获取 ui 树失败",
            "accessibility", "dumpviewtree failed"
        ).any { it in msg }
    }

    fun stop() {
        shouldStop = true
        Log.d(TAG, "Stop signal received")
    }
}

// =========================================================================
// Data classes (unchanged — consumed by Android UI layer)
// =========================================================================

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
    data class LlmUsage(val usage: LlmTokenUsage) : ProgressUpdate()
    data class BlockReply(val text: String, val iteration: Int) : ProgressUpdate()
}
