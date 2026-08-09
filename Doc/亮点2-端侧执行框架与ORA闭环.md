# 亮点2：端侧执行框架与 ORA 闭环

> 简历原文：基于 Kotlin + Android 构建端侧 Agent 执行框架，实现 Observe-Reason-Act 闭环与多会话状态管理，支持多轮工具协同调用及复杂任务规划。
>
> 代码根：`e:\aqiuzhao\myClaw\CareClaw\app\src\main\java\com\shijing\xomniclaw\`（下文路径相对该目录）。

---

## 1. 功能前因后果（为什么这样设计）

- 项目把 Python 版 X-OmniClaw 重写为**纯 Kotlin 单模块**，核心循环内联到 `AgentLoop.kt`（原 Python agentloop 删除）。
- **为什么每会话独立 AgentLoop**：`AgentLoop.progressFlow` 是 replay=1 的 `MutableSharedFlow<ProgressUpdate>`，全局共享会让 A 会话的进度事件串进 B 会话的悬浮窗/聊天流。所以 `MainEntryNew.createSessionScopedAgentLoop()` 每次 new 独立实例（顺带 new 了 Provider/ConfigLoader，一并解决了单例 Provider 缓存旧 API Key 的历史问题），按 sessionId 存进 `activeSessionLoops`。
- **为什么只落盘增量消息**：`run()` 返回的 `result.messages` 是"system + 历史回放 + 本轮新增"的完整序列，直接写回 session JSONL 会让下一轮把上一轮的 assistant 气泡重复读回来（OpenRouter/Gemma 会改写历史句，公共前缀对齐失效）。因此 `extractRunDeltaMessages` 以"本轮用户句"为锚点，只保存锚点之后的增量。
- **为什么上下文预算**：端侧窗口有限，装 App 这种 20+ 轮工具调用会累积海量 tool result。`CONTEXT_BUDGET_RATIO=0.75` 只预支窗口的 75%，超了从最老的 tool 消息开始删。

---

## 2. 核心代码调用链路

```
入口：AgentMessageReceiver.onReceive（ADB/HTTP/Gateway 注入）→ MainEntryNew.runWithSession(message, sessionId)
  ① runWithSession (core/MainEntryNew.kt:475-773)
     :496 session = sessionManager.getOrCreate(sessionId)   ← 从 JSONL 恢复历史
     :514-520 生成 runToken；cancel 上一个同会话 Job；createSessionScopedAgentLoop() 新建独立循环
     :541-551 ContextBuilder.buildSystemPrompt(userGoal, ...)  ← 组装 system prompt
     :557 ensureUserMessagePersisted() 先落盘本轮 user（防止 thinking 抢跑）
     :560-567 launch{ progressFlow.collect { ... } }  ← 进度收集绑定本次 run 的 UI 上下文（隔离关键）
     :570-575 result = sessionAgentLoop.run(systemPrompt, userInput, contextHistory, reasoningEnabled=false)
     :602 remoteReply?.invoke(finalContent)  ← 飞书/HTTP 回执
     :617-625 extractRunDeltaMessages(contextHistory, result.messages, userInput)  ← 只取增量
     :626-637 逐条 addMessage(去 system + 去重 user) → sessionManager.save(session)  ← JSONL 落盘
     :639-647 queueMemoryEvolutionEvent(...)  ← 记忆沉淀
     :661-674 finally：cancel progressJob、按 runToken 清理 activeSessionLoops

  ② AgentLoop.runKotlinLoop (agent/loop/AgentLoop.kt:235-419)
     :244-245 历史过滤 + HistorySanitizer.sanitize(maxTurns=5)  ← 修复 tool 配对/限轮
     :248-260 寒暄短路 tools=[]；否则 resolveOnDemandAndRouteHint() 按需注入工具 schema
     :284 for (iter in 1..maxIterations)   ← 迭代控制（默认 40）
     :292     enforceContextBudget(messages, contextWindowTokens)  ← 预算裁剪
     :295-309 callLlm()  ← withTimeout(LLM_TIMEOUT_MS=180s) → UnifiedLLMProvider.chatWithToolsStreaming
                → SSE 逐块解析，tool_calls 按 index 累积拼接（支持一次多 tool_call）
     :322     toolCalls 非空 → :337 for (tc in toolCalls)  ← 一次响应多个工具顺序执行
     :347       ToolLoopDetection.detectToolCallLoop(...)  ← 循环检测先于执行
     :371       executeTool(fn, args) → withTimeout(工具超时) → ToolCallDispatcher.execute
     :386       messages.add(role="tool", content=result)  ← 结果回填，下一轮 LLM 可见
     :401-404 无 tool_call → 收尾 finalContent → break
     :412-418 返回 AgentResult(finalContent, toolsUsed, messages, iterations, tokenUsage)

  ③ 会话落盘/恢复 (agent/session/SessionManager.kt)
     :88-93 getOrCreate → loadSession 读 {sessionId}.jsonl（含坏行修复）
     :105-133 save：ReentrantReadWriteLock 写锁 + 临时文件 + 原子 rename
```

---

## 3. 核心代码片段

**① 主循环骨架（ORA 迭代壳）** — `AgentLoop.kt:284-405`（摘关键段）
```kotlin
for (iter in 1..maxIterations) {          // 迭代控制，默认 40
    iteration = iter
    if (shouldStop) { finalContent = "已按用户请求停止。"; break }   // 一键停止
    enforceContextBudget(messages, contextWindowTokens)             // 上下文预算裁剪
    val response = callLlm(messages, reasoningEnabled, iter)        // Reason：LLM 决策
    val toolCalls = response.toolCalls
    if (!toolCalls.isNullOrEmpty()) {
        messages.add(Message(role="assistant", content=response.content ?: "",
            toolCalls = toolCalls.map { ToolCall(it.id, it.name, it.arguments) }))  // 一次多 tool_call
        for (tc in toolCalls) {                                    // 顺序执行全部工具
            val det = ToolLoopDetection.detectToolCallLoop(loopDetectorState, fnName, args)
            if (det is LoopDetectionResult.LoopDetected && det.level == CRITICAL) { ... break }
            val result = executeTool(fnName, tc.arguments)         // Act：执行（带超时）
            messages.add(Message(role="tool", content=result.content, toolCallId=tc.id, name=fnName))
        }
        continue
    }
    finalContent = ReasoningTagFilter.stripReasoningTags(response.content ?: "")
    break
}
```

**② 关键控制参数** — `AgentLoop.kt:50-53, 157-162`
```kotlin
private const val LLM_TIMEOUT_MS = 180_000L
private const val DEFAULT_TOOL_TIMEOUT_MS = 30_000L
private const val GALLERY_MEMORY_TOOL_TIMEOUT_MS = 300_000L
private const val CONTEXT_BUDGET_RATIO = 0.75        // 只用上下文窗口的 75%
private fun toolExecutionTimeoutMs(toolName: String): Long =
    if (toolName == "gallery_memory") GALLERY_MEMORY_TOOL_TIMEOUT_MS else DEFAULT_TOOL_TIMEOUT_MS
```

**③ 上下文预算裁剪（预支式，删最老 tool 消息）** — `AgentLoop.kt:476-492`
```kotlin
private fun enforceContextBudget(messages: MutableList<Message>, contextWindowTokens: Int) {
    val budgetChars = (contextWindowTokens * 4 * CONTEXT_BUDGET_RATIO).toInt()  // 128K*4*0.75≈384K字符
    var totalChars = messages.sumOf { it.content.length + (it.toolCalls?.sumOf { tc -> tc.arguments.length } ?: 0) }
    if (totalChars <= budgetChars) return
    val keepFrom = maxOf(0, messages.size - 10)      // 至少保留最近 10 条
    var i = 0
    while (i < keepFrom && totalChars > budgetChars) {
        val m = messages[i]
        if (m.role == "tool") {                      // 优先删最老的 tool 结果
            val removed = m.content.length
            messages.removeAt(i); totalChars -= removed
        } else i++
    }
}
```

**④ 会话隔离：每会话新建独立 AgentLoop** — `MainEntryNew.kt:350-364`
```kotlin
private fun createSessionScopedAgentLoop(application: Application): AgentLoop {
    val sessionConfigLoader = ConfigLoader(application.applicationContext)
    val sessionLlmProvider = UnifiedLLMProvider(application.applicationContext)  // 新 Provider，避免旧 Key 缓存
    return AgentLoop(
        llmProvider = sessionLlmProvider, toolRegistry = toolRegistry,
        androidToolRegistry = androidToolRegistry,
        contextManager = ContextManager(sessionLlmProvider),
        maxIterations = agentMaxIterations, modelRef = null, configLoader = sessionConfigLoader)
}
```

**⑤ 增量落盘锚定** — `MainEntryNew.kt:1287-1307`（摘要）
```kotlin
// extractRunDeltaMessages：先 dropWhile 掉首条 system（context 不含）
// 优先按"本轮用户句"（NFKC 归一化）indexOfLast 找最后一次匹配的 user 消息，只返回它之后的新消息
// 无匹配时回退公共前缀对齐（provider 改写历史句的场景）
```

---

## 4. 面试常用描述（可口头背诵，~110 字）

> "端侧 Android 多模态 Agent 执行框架：指令进入后组装 system prompt，进入上限 40 次的 Agent 循环——每轮以设备 UI 快照驱动 LLM 决策工具调用，顺序执行并回填结果，配合循环检测与 75% 上下文预算，形成 Observe–Reason–Act 闭环。每会话独立循环实例、JSONL 只落增量，支持多轮工具协同与多会话并发。"

---

## 5. 深挖点与应答

**Q1：为什么每个会话要新建 AgentLoop？progressFlow 串线具体怎么避免？**
答：`progressFlow` 是 replay=1 的 MutableSharedFlow，全局共享时 A 会话的进度会实时刷进 B 会话的悬浮窗/聊天流。做法是 `createSessionScopedAgentLoop` 每次 run 都 new 独立实例（顺带 new 了 Provider/ConfigLoader，解决单例缓存旧 API Key），按 sessionId 存进 `activeSessionLoops`；进度收集绑定本次 run 的 `SessionUiContext`，停止时 `activeSessionLoops.remove(sessionId)?.stop()` 只停目标会话。

**Q2：extractRunDeltaMessages 锚定逻辑解决什么问题？不锚定会怎样？**
答：`result.messages` 是"system + 历史回放 + 本轮新增"的完整序列，直接整段落盘会让下一轮把旧 assistant 回复又读回来，聊天时间线重复气泡（OpenRouter/Gemma 会改写历史句，公共前缀对齐会失效）。所以用"本轮用户句"（NFKC 归一化后 `indexOfLast` 找到最后一次匹配的 user 消息）作锚点，只保存它之后的增量。

**Q3：上下文预算怎么控制？75% 怎么来的？工具结果截断策略？**
答：`enforceContextBudget` 用 `contextWindowTokens × 4字符/token × 0.75` 算字符预算（128K 窗口约 384K 字符），超了就删最老的 tool 结果、至少保留最近 10 条；0.75 是留安全余量，避免触发 API context overflow。溢出恢复层（ContextManager，已接线）按 OmniClaw 策略：LLM 摘要压缩 → 超 8000 字符的 tool 结果截成头 1500 + 尾 1500 → 放弃报错。

**Q4：多轮工具协同调用怎么支持？一次返回多个 tool_call 怎么处理？**
答：两层。单轮内：OpenAI 兼容响应可带 `tool_calls` 数组，`for (tc in toolCalls)` 顺序执行，把 assistant（含全部 toolCalls）和每个 tool 结果都追加进 messages，再 `continue` 下一迭代。跨轮：每轮 LLM 都能看到上一轮全部工具结果，模型自己编排调用链（如装 App：open_app → snapshot → tap → input_text → install_app）。工具 schema 不是每轮全量下发，而是按需注入（见亮点3），省 token。

**Q5：最大迭代次数到了或超时怎么办？**
答：`for (iter in 1..maxIterations)` 耗尽后 finalContent 置"达到最大迭代次数(40)，任务未完成，建议拆分为更小步骤"（`AgentLoop.kt:407-410`）；LLM 单次 `withTimeout(180s)`，工具默认 `withTimeout(30s)`、gallery_memory 放宽到 300s，超时返回 SkillResult.error 回填给模型。用户可点停止走 `stop()` 置 shouldStop，循环每轮开头检查并抛 `__AGENT_STOPPED__` 中断。

**Q6：多会话并发默认开启吗？怎么保证不串线？**
答：UI 侧通常一次一个任务，但框架层完整支持并发——`activeSessionJobs / activeSessionLoops / activeSessionRunTokens` 三张 ConcurrentHashMap + runToken 防串清理，这是"多会话状态管理"的加分项，可以主动展示。
