/**
 * OmniClaw Source Reference:
 * - ../xomniclaw/src/gateway/(all)
 *
 * OmniClaw adaptation: gateway server and RPC methods.
 */
package com.shijing.xomniclaw.gateway.methods

import android.content.Context
import com.shijing.xomniclaw.agent.loop.AgentLoop
import com.shijing.xomniclaw.agent.loop.AgentResult
import com.shijing.xomniclaw.agent.session.SessionManager
import com.shijing.xomniclaw.gateway.protocol.*
import com.shijing.xomniclaw.gateway.websocket.GatewayWebSocketServer
import com.shijing.xomniclaw.util.CallerAwareLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import com.shijing.xomniclaw.agent.loop.ProgressUpdate
import com.shijing.xomniclaw.agent.tools.LlmOnDemandToolInclusion

/**
 * Agent RPC methods implementation with async execution
 */
class AgentMethods(
    private val context: Context,
    private val agentLoop: AgentLoop,
    private val sessionManager: SessionManager,
    private val gateway: GatewayWebSocketServer
) {
    private val TAG = "AgentMethods"
    private val agentScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Store running agent tasks
    private val runningTasks = ConcurrentHashMap<String, AgentTask>()

    /** 统一输出带 [文件:行号] 的 Android 调试日志。 */
    private fun logd(message: String) {
        CallerAwareLog.d(TAG, message)
    }

    private fun logw(message: String) {
        CallerAwareLog.w(TAG, message)
    }

    private fun logi(message: String) {
        CallerAwareLog.i(TAG, message)
    }

    private fun loge(message: String, error: Throwable? = null) {
        CallerAwareLog.e(TAG, message, error)
    }

    /**
     * 仅当请求确实属于 Agent 任务时加载 AGENTS.md。
     * 纯寒暄/简单问答不加载，避免无关 token 开销。
     */
    private fun shouldLoadAgentPolicies(message: String): Boolean {
        if (LlmOnDemandToolInclusion.isGreetingOnlyMessage(message)) return false
        val normalized = message.trim().lowercase()
        if (normalized.isBlank()) return false
        val hasTaskVerb = listOf(
            "打开", "执行", "设置", "发送", "安装", "截图", "点击", "读取", "写入", "搜索", "监控", "自动化",
            "open", "run", "set", "send", "install", "screenshot", "click", "read", "write", "search", "monitor", "automate"
        ).any { normalized.contains(it) }
        if (hasTaskVerb) return true
        val looksQuestion = normalized.contains("?") ||
            normalized.contains("？") ||
            listOf("请问", "是什么", "多少", "为什么", "怎么", "如何", "what", "why", "how", "when").any {
                normalized.contains(it)
            }
        return !looksQuestion
    }

    /**
     * agent() - Execute an agent run asynchronously
     */
    suspend fun agent(params: AgentParams): AgentRunResponse {
        val runId = "run_${UUID.randomUUID()}"
        val acceptedAt = System.currentTimeMillis()

        // Create task
        val task = AgentTask(
            runId = runId,
            sessionKey = params.sessionKey,
            message = params.message,
            status = "running"
        )
        runningTasks[runId] = task

        // Send agent.start event
        broadcastEvent("agent.start", mapOf(
            "runId" to runId,
            "sessionKey" to params.sessionKey,
            "message" to params.message,
            "acceptedAt" to acceptedAt
        ))

        // Execute agent asynchronously
        agentScope.launch {
            try {
                executeAgent(runId, params)
            } catch (e: Exception) {
                loge("Agent execution failed: $runId", e)
                task.status = "error"
                task.error = e.message

                // Send agent.error event
                broadcastEvent("agent.error", mapOf(
                    "runId" to runId,
                    "error" to e.message
                ))
            } finally {
                // Keep task for a while after completion for wait() queries
                // Should have TTL cleanup mechanism
            }
        }

        return AgentRunResponse(
            runId = runId,
            acceptedAt = acceptedAt
        )
    }

    /**
     * agent.wait() - Wait for agent run completion
     */
    suspend fun agentWait(params: AgentWaitParams): AgentWaitResponse {
        val task = runningTasks[params.runId]
            ?: return AgentWaitResponse(
                runId = params.runId,
                status = "not_found",
                result = null
            )

        val timeout = params.timeout ?: 30000L

        // Wait for task completion
        val result = withTimeoutOrNull(timeout) {
            task.resultChannel.receive()
        }

        return if (result != null) {
            AgentWaitResponse(
                runId = params.runId,
                status = "completed",
                result = buildMap {
                    put("content", result.finalContent)
                    put("iterations", result.iterations)
                    put("toolsUsed", result.toolsUsed)
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
        } else {
            AgentWaitResponse(
                runId = params.runId,
                status = if (task.status == "error") "error" else "timeout",
                result = if (task.status == "error") mapOf("error" to task.error) else null
            )
        }
    }

    /**
     * agent.identity() - Get agent identity
     */
    fun agentIdentity(): AgentIdentityResult {
        return AgentIdentityResult(
            name = "omniclaw",
            version = "1.0.0",
            platform = "android",
            capabilities = listOf(
                "screenshot",
                "tap",
                "swipe",
                "type",
                "navigation",
                "app_control",
                "accessibility"
            )
        )
    }

    /**
     * Execute agent task
     */
    private suspend fun executeAgent(runId: String, params: AgentParams) {
        val task = runningTasks[runId] ?: return

        try {
            val loadAgentPolicy = shouldLoadAgentPolicies(params.message)
            val agentsText = if (loadAgentPolicy) try {
                context.assets.open("bootstrap/AGENTS.md").bufferedReader().readText()
            } catch (_: Exception) { "" }
            else ""
            val opsGuideText = if (!loadAgentPolicy) try {
                context.assets.open("bootstrap/OPS_GUIDE.md").bufferedReader().readText()
            } catch (_: Exception) { "" }
            else ""

            val systemPrompt = """
$agentsText
$opsGuideText

Available tools:
- screenshot(): Capture screen
- tap(x, y): Tap at coordinates
- swipe(startX, startY, endX, endY, duration): Swipe gesture
- type(text): Input text
- home(): Press home button
- back(): Press back button
- open_app(package): Open application

Instructions:
1. Verify results after each operation
2. Be precise with coordinates
3. Use stop() when task is complete
            """.trimIndent()

            // Get or create session
            val session = sessionManager.getOrCreate(params.sessionKey)

            // Subscribe to AgentLoop progress updates and forward as Gateway Events
            val progressJob = agentLoop.progressFlow
                .onEach { progress ->
                    when (progress) {
                        is ProgressUpdate.Iteration -> {
                            broadcastEvent("agent.iteration", mapOf(
                                "runId" to runId,
                                "iteration" to progress.number
                            ))
                        }
                        is ProgressUpdate.Thinking -> {
                            // Intermediate feedback: thinking at step X
                            broadcastEvent("agent.thinking", mapOf(
                                "runId" to runId,
                                "iteration" to progress.iteration,
                                "message" to "正在处理第 ${progress.iteration} 步..."
                            ))
                        }
                        is ProgressUpdate.ToolCall -> {
                            broadcastEvent("agent.tool_call", mapOf(
                                "runId" to runId,
                                "tool" to progress.name,
                                "arguments" to progress.arguments
                            ))
                        }
                        is ProgressUpdate.ToolResult -> {
                            broadcastEvent("agent.tool_result", mapOf(
                                "runId" to runId,
                                "tool" to progress.name,
                                "result" to progress.result,
                                "duration" to progress.execDuration
                            ))
                        }
                        is ProgressUpdate.Reasoning -> {
                            // Extended thinking progress (optional)
                            broadcastEvent("agent.thinking", mapOf(
                                "runId" to runId,
                                "content" to progress.content.take(200), // Limit length
                                "duration" to progress.llmDuration
                            ))
                        }
                        is ProgressUpdate.IterationComplete -> {
                            // Iteration completion statistics (optional)
                            logd("Iteration ${progress.number} complete: ${progress.iterationDuration}ms")
                        }
                        is ProgressUpdate.ContextOverflow -> {
                            broadcastEvent("agent.context_overflow", mapOf(
                                "runId" to runId,
                                "message" to progress.message
                            ))
                        }
                        is ProgressUpdate.ContextRecovered -> {
                            broadcastEvent("agent.context_recovered", mapOf(
                                "runId" to runId,
                                "strategy" to progress.strategy,
                                "attempt" to progress.attempt
                            ))
                        }
                        is ProgressUpdate.LoopDetected -> {
                            broadcastEvent("agent.loop_detected", mapOf(
                                "runId" to runId,
                                "detector" to progress.detector,
                                "count" to progress.count,
                                "message" to progress.message,
                                "critical" to progress.critical
                            ))
                        }
                        is ProgressUpdate.LlmUsage -> {
                            // 单次 LLM 调用的 token 用量（与最终 tokenUsage 汇总可同时存在）
                            broadcastEvent(
                                "agent.llm_usage",
                                mapOf(
                                    "runId" to runId,
                                    "promptTokens" to progress.usage.promptTokens,
                                    "completionTokens" to progress.usage.completionTokens,
                                    "totalTokens" to progress.usage.totalTokens
                                )
                            )
                        }
                        is ProgressUpdate.Error -> {
                            // Error already sent via agent.error event
                            logw("Progress error: ${progress.message}")
                        }
                        is ProgressUpdate.BlockReply -> {
                            logd("📤 Block reply: ${progress.text.take(100)}")
                            com.shijing.xomniclaw.gateway.GatewayServer.getInstance()?.broadcast("agent.block_reply", mapOf(
                                "text" to progress.text,
                                "iteration" to progress.iteration
                            ))
                        }
                    }
                }
                .launchIn(agentScope)

            // Execute agent loop
            val result = agentLoop.run(
                systemPrompt = systemPrompt,
                userMessage = params.message,
                contextHistory = emptyList(),
                reasoningEnabled = true
            )

            // Cancel progress subscription
            progressJob.cancel()

            // Update task status
            task.status = "completed"
            task.result = result

            // Send completion signal
            task.resultChannel.send(result)

            // Send agent.complete event
            broadcastEvent(
                "agent.complete",
                buildMap {
                    put("runId", runId)
                    put("status", "completed")
                    put("iterations", result.iterations)
                    put("toolsUsed", result.toolsUsed)
                    put("content", result.finalContent)
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

            logi("Agent completed: $runId, iterations=${result.iterations}")

        } catch (e: Exception) {
            task.status = "error"
            task.error = e.message
            throw e
        }
    }

    /**
     * Broadcast event (OmniClaw Protocol v3: uses "payload" not "data")
     */
    private var eventSeq = 0L

    private fun broadcastEvent(event: String, data: Any?) {
        try {
            gateway.broadcast(EventFrame(
                event = event,
                payload = data,  // OmniClaw uses "payload" not "data"
                seq = eventSeq++  // Add sequence number
            ))
        } catch (e: Exception) {
            loge("Failed to broadcast event: $event", e)
        }
    }
}

/**
 * Agent task
 */
private data class AgentTask(
    val runId: String,
    val sessionKey: String,
    val message: String,
    var status: String,
    var result: AgentResult? = null,
    var error: String? = null,
    val resultChannel: Channel<AgentResult> = Channel(1)
)
