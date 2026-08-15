# 学习进度 · 亮点2：端侧执行框架与 ORA 闭环

> 简历原文：基于 Kotlin + Android 构建端侧 Agent 执行框架，实现 Observe-Reason-Act 闭环与多会话状态管理，支持多轮工具协同调用及复杂任务规划。
>
> 状态：🟢 已理清（2026-08-15）
> 代码根：`e:\aqiuzhao\myClaw\CareClaw\app\src\main\java\com\shijing\xomniclaw\`

---

## 一、整体流程（从「整体流程与核心机制」拆入）

1. **UI 接收消息**：调用 `ChatViewModel.sendMessage(content)`
2. **开始 agent 流程**：`MainEntryNew.runWithSession(userInput, sessionId, application)`
3. **agent 初始化**：`initialize`
4. **创建会话 + 构造上下文 + 系统提示词**：`SessionUiContext`、`getRecentMessages`
5. **创建新 AgentLoop**（`AgentLoop.kt`）：达到最大轮次 / Agent 认为可以结束，退出 loop
6. **退出方式（四种）**：
   - LLM 给出最终回复
   - 达到最大轮次
   - 循环检测出循环（同一操作多次）
   - 手动停止
7. **进度展示**：`SessionFloatWindow`（悬浮窗步骤）+ `uiProgressFlow`（聊天界面流式气泡）
8. **会话保存**：`sessionManager.save(session)`
9. **记忆更新**：`queueMemoryEvolutionEvent`
10. **finally 统一收尾**

## 二、runWithSession 核心流程

1. **会话隔离**：`getOrCreate(sessionId)` —— 从 JSONL 恢复历史
2. **构建 System Prompt**：`ContextBuilder.buildSystemPrompt(userGoal)`
3. **创建独立 AgentLoop**：`createSessionScopedAgentLoop()`（每会话 new 实例，隔离 progressFlow）
4. **启动进度监听**：`progressFlow.collect → handleProgressUpdate`
5. **执行 ORA 循环**：`AgentLoop.run(systemPrompt, userMessage, history)`
6. **结果处理**：去 reasoning tag → 通知 → 远程回执
7. **落盘 + 记忆**：`extractRunDeltaMessages → save → queueMemoryEvolutionEvent`

## 三、agent-loop 核心（AgentLoop.kt）

1. **注册工具**（agent 工具 + 安卓相关工具）
2. **决定 agent 用啥工具**（关键词匹配 + 小模型判断，**优先后者，前者降级**）——详见亮点3
   - `onDemandNames = LlmOnDemandToolInclusion.resolveInclusions(systemPrompt, userMessage)`（关键词匹配、SKILL 兜底）
   - `outcome = LlmToolRouter.runRouterLlm`（小模型判断选哪些工具）
3. `suspend run`（调用 `runKotlinLoop` —— 真正的核心）
4. **动态工具载入**（上面的第 2 步）
5. **组装 message**
6. **loop until shouldStop**：
   - `enforceContextBudget`（上下文裁剪）
   - 解析结果是否返回工具调用：
     - **返回工具调用**：检测是否循环调用同一个工具
       - 两个等级（**CRITICAL**，直接失败；普通循环，把结果发给模型提醒它）
     - 如果没有循环调用 → 执行工具调用（记录 + 执行）
     - 后处理
7. **Loop 终止**（LLM 不再调用工具 / 达到最大迭代次数 / 被取消 / 报错）

### run() 结构树

```
run(systemPrompt, userMessage, contextHistory)
  └─ runKotlinLoop()
      ├─ 工具路由：决定用哪些工具
      ├─ 组装 messages：[system, 历史对话, user]
      │
      └─ for 1..maxIterations (默认 40 轮)
          ├─ 检查 shouldStop
          ├─ 裁剪上下文（删最早的 tool 消息）
          ├─ callLlm → 发消息给 LLM
          │   └─ 流式推送思考/回答到 UI
          │
          ├─ LLM 返回了工具调用？
          │   ├─ 逐个执行工具
          │   │   ├─ 循环检测 → 发现循环则终止
          │   │   ├─ executeTool → 实际执行
          │   │   ├─ snapshot 过期？自动提示刷新
          │   │   └─ 结果追加到 messages
          │   └─ continue → 下一轮迭代（LLM 看到工具结果）
          │
          └─ LLM 返回了纯文本？
              └─ 这就是最终答案 → break

      └─ 返回 AgentResult（最终回复 + 工具列表 + Token 消耗）
```

## 四、核心控制参数（AgentLoop）

| 参数 | 值 | 作用 |
|---|---|---|
| `maxIterations` | 40 | 迭代上限 |
| `LLM_TIMEOUT_MS` | 180s | 单次 LLM 调用超时 |
| `DEFAULT_TOOL_TIMEOUT_MS` | 30s | 工具默认超时 |
| `GALLERY_MEMORY_TOOL_TIMEOUT_MS` | 300s | 相册记忆放宽超时 |
| `CONTEXT_BUDGET_RATIO` | 0.75 | 只用上下文窗口 75% |

## 五、面试 QA

**Q1：为什么每个会话要新建 AgentLoop？**
答：`progressFlow` 是 replay=1 的 MutableSharedFlow，全局共享会让 A 会话进度串进 B 会话 UI。所以 `createSessionScopedAgentLoop` 每次 run 都 new 独立实例（顺带 new 了 Provider/ConfigLoader，解决单例缓存旧 API Key），按 sessionId 存 `activeSessionLoops`，停止时只停目标会话。

**Q2：extractRunDeltaMessages 锚定解决什么问题？**
答：`result.messages` 是「system + 历史回放 + 本轮新增」完整序列，直接整段落盘会让下一轮把旧 assistant 回复重复读回（provider 会改写历史句，公共前缀对齐失效）。所以用「本轮用户句」作锚点（NFKC 归一化后 indexOfLast），只保存锚点之后的增量。

**Q3：上下文预算怎么控制？**
答：`enforceContextBudget` 用 `contextWindowTokens × 4字符/token × 0.75` 算预算（128K 窗口约 384K 字符），超了从最老的 tool 消息开始删、至少保留最近 10 条。

---

*拆自「整体流程与核心机制.md」一~三节 + 深挖点，2026-08-15。*
