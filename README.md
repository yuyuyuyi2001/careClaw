# CareClaw（小棉袄）

**帮父母远程安装软件的端侧多模态 Android 智能体。**

孩子在外地发一句"帮妈装个微信"，CareClaw 在父母手机上自主完成：
检查已装 → 应用商店搜索 → 下载 → 安装 → 验证 → 回报结果。
父母也可以直接对着手机说"我要装 XX"，语音指令同样走 Agent 闭环执行。

---

## 简介 / Overview

CareClaw is an **edge-native multimodal Android agent** (Observe–Reason–Act) that helps children remotely install apps on their parents' phones. It runs fully on-device, observes the screen via accessibility tree + screenshots, reasons with an LLM, and executes atomic operations (search / download / install / verify), then reports back. Long-term memory (install history + user profile + gallery theme retrieval) is its core capability.

**来源 / Origin**：由 [X-OmniClaw](../X-OmniClaw/) 改造而来——从 71.7K 行精简到 **40.0K 行**、纯 Kotlin 单模块、无 Python（Chaquopy）、去 Discord/Observer/YOLO/CameraX/WebSocket 等外围，保留并重组了记忆三件套、安装链路、飞书 + HTTP 双远程通道、安全层、教学模式等 20 项亮点功能。

---

## 功能亮点 / Features

| 模块 | 说明 |
|---|---|
| **Observe–Reason–Act 闭环** | `AgentLoop`（Kotlin）：迭代控制、循环检测、Token 预算、按需工具路由 |
| **混合感知** | 无障碍树 + 坐标（`AccessibilityBinderService`）+ 截屏（`MediaProjectionHelper`） |
| **设备原子操作** | tap / swipe / type / longPress / back / home / open_app + 误触保护 + VLM 双轨定位 |
| **应用安装链路（核心业务）** | 检查已装 → 商店搜索 → APK 下载 → 无障碍点"安装" → 验证 → 回报 |
| **LLM 统一调用** | OpenAI 兼容 + SSE 流式 + 视觉理解，多 Provider（硅基流动 / OpenRouter / Ollama 本地） |
| **记忆三件套（核心亮点）** | 工作记忆（hybrid search + 上下文压缩）· Gallery Memory（相册扫描/摘要/主题检索）· Memory Evolution（定时增量画像） |
| **远程双通道** | 飞书 Bot（主，`com.xiaomo.feishu`）+ HTTP（备用，NanoHTTPD 端口 8765） |
| **安全层** | 指令白名单 + 高危二次确认 + 一键停止 + 审计日志 |
| **教学模式** | 非 root 录制安装流程 → 生成 SKILL.md → 下次一句话完成 |
| **SKILL.md 技能热加载** | install / app-search / gallery-qa / gallery-memory / memory-evolution 等 |
| **极简 UI** | 主界面（对话 / 状态 / 设置三 Tab）+ 悬浮窗进度 |
| **后台感知与结果通知** | 常驻通知实时刷新「步骤 X/Y + 当前动作」（不依赖悬浮窗权限），任务完成/出错推送可点开的结果通知；UI 不在前台时广播/HTTP 指令自动转入后台执行 |

---

## 核心架构 / Architecture

```
远程/语音指令 → MainEntryNew.runWithSession (core/)
  → 每会话独立 AgentLoop (agent/loop/)
  → ContextBuilder 组装 system prompt → UnifiedLLMProvider (providers/, OpenAI 兼容+SSE+视觉)
  → tool_call → ToolCallDispatcher → ① ToolRegistry(通用) ② AndroidToolRegistry(平台)
  → 循环检测 ToolLoopDetection → 结果回填 → 验证 → 回报(remoteReply/broadcast)
  → 记忆沉淀：工作记忆 + MemoryEvolution 定时聚合画像
```

关键包结构（`app/src/main/java/com/shijing/xomniclaw/`）：

```
agent/          # AgentLoop + context + session + skills + tools + memory + behavior
accessibility/  # 感知层（无障碍服务 + 截屏 + 权限页）
voice/          # 录音 → LLM 理解（无固定 STT）+ TTS 外围
providers/      # UnifiedLLMProvider（OpenAI 兼容）+ ApiAdapter
remote/         # FeishuManager（主）+ GatewayHttp（备用）
safety/         # 指令白名单 + 一键停止 + 审计日志
core/           # MainEntryNew（入口）+ MyApplication
config/ util/   # 配置 + 工具
com/xiaomo/feishu/  # 折叠进来的飞书 SDK（13 文件，暂未统一包名）
```

**权威文档**：`architecture.md`（架构）、`plan.md`（计划）、`process.md`（进度）在仓库根目录。

---

## 开发环境 / Development

- **JDK 17**（本机 `D:\JDK_17`；不要用 Android Studio JBR 21）
- **Android SDK**：android-34 + build-tools 33.0.1（`local.properties` 设 `sdk.dir`）
- **签名**：`../keystore.jks`（alias/password = android），debug/release 共用

```bash
# 在 CareClaw/ 目录下
.\gradlew.bat :app:assembleDebug          # 编译 debug APK（产物复制到 releases/）
.\gradlew.bat :app:lint                   # Android lint
.\gradlew.bat :app:testDebugUnitTest      # JVM 单元测试
.\gradlew.bat :app:installDebug           # 装到真机/模拟器
powershell -ExecutionPolicy Bypass -File ..\scripts\count_lines.ps1 -Root $PWD   # 行数统计
```

---

## 真机演示 / Demo（三条演示线）

1. **远程装 App**：本地用 HTTP 注入或 ADB broadcast 发指令（如 `PHONE_FORCLAW_SEND_MESSAGE` → "帮我安装微信"），Agent 在手机商店完成搜索-下载-安装-验证并回报。
2. **语音指令**：主界面长按说话 → 录音 → LLM 理解 → 走 Agent 闭环执行。
3. **教学模式**：`capture_behavior` 技能录制一次安装流程 → 生成 SKILL.md → 下次一句话完成。

**真机需要**：无障碍 + 悬浮窗 + 截图授权 + 存储 + 安装未知来源权限（iQOO Z10 Turbo+ / vivo 应用商店验证链路）。

---

## 状态 / Status

- P0–P5 已完成：`46,035 → 40,011` 行 / 186 文件（纯 Kotlin 单模块，`assembleDebug` + 单测全绿，lint 无新增错误）。
- P6 验证打磨：进行中（README 重写、真机冒烟、交付文档）。
- 行数口径：`count_lines.ps1`（`Measure-Object -Line`，不计空行）。
