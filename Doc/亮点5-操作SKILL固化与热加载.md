# 亮点5：操作 SKILL 固化与热加载

> 简历原文：提供操作 SKILL 固化能力，基于 SKILL 热加载 + 教学模式，支持录制操作流程自动生成 SKILL，实现操作复用。
>
> 代码根：`e:\aqiuzhao\myClaw\CareClaw\app\src\main\java\com\shijing\xomniclaw\`（下文路径相对该目录）。

---

## 1. 功能前因后果（为什么这样设计）

纯靠 LLM 每轮"看截图→猜控件→点击"有两大痛点：
- **弱模型 + 长链路操作极不稳定**：第三方 Activity 大多不允许显式 `start_activity`，盲点容易死循环，白白消耗迭代轮数；
- **重复性操作没有沉淀**：长辈的挂号页、缴费页、视频通话入口，每次都要重新走一遍 Observe–Reason–Act。

设计思路：把"一次成功的操作路径"**固化成一个 Markdown 技能文件（SKILL.md）**，之后一句话（说出技能名）即可复用：
1. **录制（capture_behavior 工具）**：无障碍服务拿前台页面包名/Activity，`dumpsys activity` 非 root 捕获 deeplink Intent，生成 SKILL.md 到 workspace/skills 目录；
2. **热加载（SkillsLoader 指纹懒失效）**：SKILL.md 只是 sdcard 上的 markdown，下次会话加载技能目录时靠 mtime 指纹自动发现新文件，**无需重启 App**；
3. **按需注入（catalog 模式）**：不把几十个 SKILL 全文塞进 system prompt（会占 30–50K token），只注入 TSV 目录（name/path/描述），LLM 命中后用 `read_file` 读对应 SKILL.md 再照着执行——这就是"操作复用"闭环。

两条生成路径殊途同归：`capture_behavior`（代码录制当前页）与内置 `clipboard-to-shortcut` 技能（LLM 从剪切板链接写 SKILL.md），产出同一格式，共用同一套解析/加载/执行管线。

---

## 2. 核心代码调用链路

```
录制链路（用户说"录制当前页面" → 生成 SKILL.md）
  LLM 感知"录制"意图 → 调 capture_behavior 工具
    （在 AndroidToolRegistry.kt:81 注册，不在 ON_DEMAND 集合 → 每轮都进 tools schema）
  → CaptureBehaviorSkill.execute()                        agent/behavior/CaptureBehaviorSkill.kt:62-108
    ├─ 取前台页面：参数优先，其次 BehaviorCapture.foregroundPage(context)
    │    └─ 读 AccessibilityBinderService.serviceInstance 的 currentPackageName / activityClassName
    │         （前台包跟踪在 PhoneAccessibilityService.onAccessibilityEvent 监听 TYPE_WINDOW_STATE_CHANGED）
    ├─ BehaviorCapture.captureIntent(packageName, activity)  BehaviorCapture.kt:55-73
    │    └─ Runtime.exec("/system/bin/dumpsys activity activities")，受限 ROM 拦截时返回 null（降级 Activity 兜底）
    └─ IntentParser.parseBlock                           agent/behavior/IntentParser.kt:24-59
         └─ 定位含目标 Activity 的 Hist 行 → 解析 Intent {...}（act=/dat=/cmp=/flg=/cat=/extras）
  → BehaviorSkillExporter.export                          agent/behavior/BehaviorSkillExporter.kt:44-79
    ├─ generateSkillName（App名+页面名、去非法字符、≤10 字）
    └─ buildSkillMarkdown → /sdcard/.xomniclaw/workspace/skills/<名称>/SKILL.md
         （frontmatter + Step1 device(action="open", uri=..., package_name=...) + Step2 等待/确认 + 注意事项）

热加载链路（新 SKILL.md 生效，无需重启）
  每会话构建 system prompt 前调 ContextBuilder.buildSkillsSection   agent/context/ContextBuilder.kt:361-471
    → skillsLoader.getAllSkills() → SkillsLoader.loadSkills()        agent/skills/SkillsLoader.kt:98-149
         ├─ 入口算 computeUserDirsFingerprint()（FNV-1a 混合目录+子目录+各 SKILL.md mtime）:163-189
         ├─ 指纹未变且 cacheValid → 返回缓存
         └─ 指纹变了 → 清缓存全量重扫（新录制的技能下一会话立即出现）:109-114
  → WorkspaceInitializer.ensureBundledSkills() 兜底补拷 assets        WorkspaceInitializer.kt:397-446
       └─ 拷贝 assets/skills → /sdcard/.xomniclaw/skills/，目标 SKILL.md 已存在则跳过（不覆盖用户改动）:418-421

执行链路（用户说出技能名 → 复用操作）
  ContextBuilder.buildSkillsSection 把命中技能以 TSV 目录注入 system prompt（name\tpath\tdescription）
    ├─ always=true 的技能才全文注入；其余只注入目录，提示"命中后用 read_file 读 SKILL.md 再执行"
  → AgentLoop.runKotlinLoop：system prompt 含 "## Skills (mandatory)" 就强制把 read_file 加入本轮 tools  AgentLoop.kt:256-258
  → LLM 调 read_file(path=...)                          agent/tools/ReadFileTool.kt:56-105
       └─ 支持 assets://skills/<name>/SKILL.md 直读 APK；磁盘缺失时按 managed 路径回退 assets :88-94
  → LLM 按步骤执行 device(action="open", uri=...)       agent/tools/device/DeviceTool.kt:1163-1200
       └─ 构造 ACTION_VIEW intent（带 package 约束防跳浏览器；ActivityNotFoundException 回退浏览器）
```

---

## 3. 核心代码片段

**① capture_behavior 工具的执行主体（录制三步曲）** — `CaptureBehaviorSkill.kt:62-108`
```kotlin
override suspend fun execute(args: Map<String, Any?>): SkillResult {
    // 1. 确定前台页面（参数优先，其次无障碍服务）
    val foreground = BehaviorCapture.foregroundPage(context)
    val packageName = (args["package_name"] as? String)?.takeIf { it.isNotBlank() }
        ?: foreground?.packageName
        ?: return SkillResult.error("无法确定当前前台应用，请先打开目标页面或传入 package_name")
    // 2. 非 root 捕获 Intent
    val spec = BehaviorCapture.captureIntent(packageName, activityName)
    val dataUri = spec?.dataUri.orEmpty()
    // 3. 生成 SKILL.md
    val pageTitle = activityName.substringAfterLast(".")
    val result = BehaviorSkillExporter.export(
        appName = appName, pageTitle = pageTitle,
        packageName = packageName, activityName = activityName, dataUri = dataUri)
    ...
}
```

**② 非 root 录制：无障碍前台页 + dumpsys 捕获 Intent** — `BehaviorCapture.kt:28-37, 55-73`
```kotlin
fun foregroundPage(context: Context): ForegroundPage? {
    val svc = AccessibilityBinderService.serviceInstance ?: return null
    val pkg = svc.currentPackageName.takeIf { it.isNotBlank() } ?: return null
    val activity = svc.activityClassName.takeIf { it.isNotBlank() } ?: return null
    return ForegroundPage(packageName = pkg, activityName = activity, appName = appNameOf(context, pkg))
}
// 受限 ROM 拦截 dumpsys activity 时返回 null，由调用方降级为 Activity 兜底跳转
suspend fun captureIntent(packageName: String, activityName: String): IntentParser.CapturedIntentSpec? =
    withContext(Dispatchers.IO) {
        try {
            val process = Runtime.getRuntime().exec(arrayOf("/system/bin/dumpsys", "activity", "activities"))
            val output = process.inputStream.bufferedReader().use { it.readText() }
            process.waitFor()
            if (output.isBlank()) null else IntentParser.parseBlock(output.lines(), packageName, activityName)
        } catch (_: Exception) { null }
    }
```

**③ 热加载核心：mtime 指纹"懒失效"（不重启即生效的关键）** — `SkillsLoader.kt:98-114, 163-189`
```kotlin
fun loadSkills(): Map<String, SkillDocument> {
    val currentFingerprint = computeUserDirsFingerprint()
    val cacheUsable = cacheValid && skillsCache.isNotEmpty() && currentFingerprint == lastUserDirsFingerprint
    if (cacheUsable) { return skillsCache.toMap() }
    if (cacheValid && currentFingerprint != lastUserDirsFingerprint) {
        Log.i(TAG, "检测到 Managed/Workspace skills 目录变化，重新扫描")
    }
    skillsCache.clear()
    ...
}
// 仅查磁盘元数据（不读文件内容）：顶层 mtime + 各子目录 mtime + 各 SKILL.md mtime
private fun computeUserDirsFingerprint(): Long {
    var hash = 1125899906842597L  // FNV-1a offset basis (64-bit)
    hash = mixDirIntoFingerprint(File(MANAGED_SKILLS_DIR), hash)
    hash = mixDirIntoFingerprint(File(WORKSPACE_SKILLS_DIR), hash)
    return hash
}
```
> 注释（:68-79）记录设计动机：`ContextBuilder.skillsLoader` 是进程级单例，旧实现 `cacheValid=true` 后永久锁死；FileObserver 不递归监听子目录且无调用方，导致新增技能必须重启 App。改为 mtime 指纹后，单会话内多次 loadSkills 仍走缓存，新写盘文件下次调用即触发失效重扫。

**④ SKILL 如何被 LLM 感知：catalog 注入 + read_file 强制可用** — `ContextBuilder.kt:393-397` + `AgentLoop.kt:255-258`
```kotlin
parts.add("## Skills (mandatory)")
parts.add("Before replying: scan the lines under the header row (tab-separated: name, path, description).")
parts.add("- If exactly one skill clearly applies: read its SKILL.md at the path with `read_file`, then follow it.")
// TSV 目录行： name\tpath\tdescription（.kt:426-465）

// AgentLoop 侧：命中技能目录就强制注入 read_file，防止"想读但没工具"
val onDemand = routeDecision.onDemandNames.toMutableSet()
if (!greetingOnly && systemPrompt.contains("## Skills (mandatory)", ignoreCase = true)) {
    onDemand.add("read_file")
}
```

**⑤ 内置 SKILL 部署：拷贝 assets 不覆盖已存在文件** — `WorkspaceInitializer.kt:397-421`
```kotlin
private fun copyBundledSkills() {
    val bundledSkills = assetManager.list("skills") ?: return
    for (skillName in bundledSkills) {
        val skillFiles = try { assetManager.list("skills/$skillName") } catch (_: Exception) { null }
        if (skillFiles.isNullOrEmpty()) continue
        val targetDir = File(skillsDir, skillName)
        val skillMd = File(targetDir, "SKILL.md")
        if (skillMd.exists()) { skippedCount++; continue }   // 已存在即跳过，不覆盖用户修改
        targetDir.mkdirs()
        for (fileName in skillFiles) { ... inputStream.copyTo(targetFile.outputStream()) }
    }
}
```

**真实 SKILL.md 示例**（`assets/skills/clipboard-to-shortcut/SKILL.md:315-367`）：
```markdown
---
name: 淘宝板鞋
description: |
  一键直达淘宝商品页面（Nike SB Dunk Low板鞋）的快捷指令。
  当用户说"打开淘宝板鞋"、"使用淘宝板鞋"时激活。
metadata:
  {
    "xomniclaw": { "always": false, "emoji": "🛒", "version": "1.0.0", "category": "navigation" }
  }
---
# 淘宝板鞋
一键直达淘宝 Nike SB Dunk Low 板鞋商品页面。

## 📋 执行步骤
### Step 1: 跳转目标页面
```kotlin
device(action = "open", uri = "https://e.tb.cn/h.io9Fd5S?tk=fNSL55NU59K", package_name = "com.taobao.taobao")
```
### Step 2: 等待并确认
```kotlin
device(action = "act", kind = "wait", timeMs = 1000)
device(action = "snapshot")
```
## ⚠️ 注意事项
- 必须使用 `device(action="open", uri="...", package_name="...")` 跳转，不带 package_name 会跳浏览器
- 创建时间：2026-04-13
```

---

## 4. 面试常用描述（可口头背诵，~115 字）

> "操作固化就是'教一次、以后照做'：用户先打开目标页面，Agent 通过无障碍服务拿到前台包名，再用 dumpsys 非 root 抓出 deeplink Intent，生成一个带 frontmatter 的 SKILL.md 放到手机磁盘。Skill 目录靠 mtime 指纹自动热加载，不用重启 App。执行时系统提示只注入技能目录，LLM 命中后用 read_file 读 SKILL.md、按步骤调 device 工具完成跳转。这样把一次成功的操作路径沉淀下来，一句话即可复用。"

---

## 5. 深挖点与应答

**Q1：为什么用 dumpsys 而不是无障碍树直接拿 Intent？**
答：`dumpsys activity activities` 能拿到完整 `Intent {...}`（act/dat/cmp/extras），含 deeplink，是"一键直达页面"最可靠的来源；无障碍树只提供页面结构，没有 Intent 信息。受限 ROM 拦截 dumpsys 时 `captureIntent` 返回 null，录制自动降级为 `device(action="open", package_name=..., class_name=Activity)` 显式 Activity 跳转，并在 SKILL.md 注意事项里注明"若 Activity 未导出可能失败"。

**Q2：为什么说"无需重启就能生效"？热加载的真正机制是什么？**
答：真正生效的不是 FileObserver（enableHotReload 无调用方、且不递归监听子目录），而是 `SkillsLoader` 的 **mtime 指纹懒失效**：每次 `loadSkills()` 只查磁盘元数据算出 FNV-1a 指纹，与上次不同即清缓存重扫。每个会话的 system prompt 都会重建并调 loadSkills，所以新录制的技能**下一次会话立即可见**；同会话内多次 loadSkills 走缓存，无重复 IO。`SkillsLoader.kt:68-79` 注释记录了这处演进。

**Q3：SKILL.md 怎么被 LLM 执行？为什么不全文注入 system prompt？**
答：分两段。`ContextBuilder.buildSkillsSection` 按用户目标用 `selectRelevantSkills`（关键词打分排序）筛候选，`always=true` 的全文注入，其余只注入 TSV 目录 `name\tpath\tdescription`，prompt 明确要求"命中后用 read_file 读 SKILL.md 再执行"。这样 skills 段从 30–50K 字符压到 1–3K。为避免"LLM 想读但本轮没有 read_file"的断层，`AgentLoop.kt:256-258` 只要检测到 skills 段就强制把 read_file 加入 tools。

**Q4：capture_behavior 和内置的 clipboard-to-shortcut 技能是什么关系？**
答：两条路径殊途同归。`capture_behavior` 是**代码录制**（Kotlin 工具，取无障碍前台页 + dumpsys 抓 Intent），适合"当前正在看的页面"；`clipboard-to-shortcut` 是**LLM 生成**（SKILL.md 里的 Workflow 教模型从剪切板复制链接→识别 App→写 SKILL.md 到同一目录），适合"分享链接"场景。两者产出同一 SKILL.md 格式，共用 SkillParser 解析和 SkillsLoader 加载，验证了格式设计的一致性。

**Q5：新录制的技能会不会和内置技能/用户目录冲突？优先级怎么定？**
答：加载顺序低→高：extraDirs → assets/skills（bundled）→ /sdcard/.xomniclaw/skills（managed）→ plugin → /sdcard/.xomniclaw/workspace/skills（workspace，最高），按 name 去重、高者覆盖。录制产物落在 workspace（最高优先级），天然盖过同名内置技能；内置技能部署用"已存在即跳过"保证用户改动不被覆盖。

**Q6：SKILL 文件安全与隔离怎么做？**
答：`read_file` 有路径边界：非 `assets://` 的路径若 `allowedDir` 非空会做 canonicalPath 前缀校验；SKILL.md 由 LLM 照着执行，本质是把设备操作权限交给 prompt 里的 markdown，所以安全靠两层：外层 `SafetyPolicy`（远程指令白名单+高危二次确认）＋ 执行动作统一收敛到 `device` 工具（`ACTION_VIEW` 等受限 API），避免让 SKILL 直接接触 shell/exec。
