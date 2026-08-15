# 学习进度 · 亮点5：操作 SKILL 固化与热加载

> 简历原文：设计 SKILL 固化与渐进式加载机制，将高频操作沉淀为 SKILL.md 技能文件，运行时按需注入、热加载即时生效，实现对话操作的一次沉淀、多次复用。
>
> 状态：🟢 已理清（2026-08-15）
> 代码根：`e:\aqiuzhao\myClaw\CareClaw\app\src\main\java\com\shijing\xomniclaw\`

---

## 一、功能前因后果（为什么需要 SKILL 固化）

**痛点**：端侧弱模型走长链路操作不稳定（每步都靠 LLM 现场推理），且重复操作无沉淀——父母常用的挂号/缴费/视频通话入口，每次都要重新让 Agent 摸索一遍，容易卡壳。

**解法**：把高频操作沉淀成 **SKILL.md 技能文件**（人可读、模型可执行的"操作说明书"），之后用户说一句技能名就能复用。**技能的来源是"某次 Agent 对话操作的沉淀"**——一次对话成功跑通了某条操作路径，就把这条路径固化下来，下次不再从零推理。三个关键词对应三块能力：

- **SKILL 固化** → 把一次成功执行的操作路径沉淀为 SKILL.md（写入 workspace，最高优先级）
- **渐进式加载** → 根据metadata的定义：always = true 全量注入SKILL.md，always = false 只注入description。
- **热加载即时生效** → 每次 loadSkills 全量重扫，新沉淀的技能下次会话即生效

核心文件：

```
agent/skills/SkillsLoader.kt             ← 单层加载（workspace，每次全量重扫）+ 技能选择
agent/skills/SkillParser.kt              ← 统一解析 SKILL.md（frontmatter + body）
agent/skills/SkillDocument.kt            ← SkillDocument / SkillMetadata 数据模型
agent/context/ContextBuilder.kt          ← 运行时按需注入技能目录
workspace/WorkspaceInitializer.kt        ← 首启把内置种子技能部署到 workspace/skills
```

---

## 二、SKILL.md 文件格式（AgentSkills.io 对齐）

SKILL.md = **frontmatter（元数据）+ Markdown body（执行说明书）**，由 `SkillParser` 统一解析（[SkillParser.kt:40-72](CareClaw/app/src/main/java/com/shijing/xomniclaw/agent/skills/SkillParser.kt#L40-L72)）：

```markdown
---
name: app-search                      # 唯一标识（LLM 触发用）
description: |                        # 描述（| 块标量，多行）
  主流APP搜索。当用户想要在拼多多、美团、抖音等APP中搜索时激活。
metadata:
  {
    "xomniclaw": {
      "always": false,                # true=全量注入每个 system prompt；false=按需
      "emoji": "🔍",
      "category": "search",
      "requires": {...},              # 可选依赖（bins/env/config），不满足则不注入
      "install": [...]                # 可选安装规格（brew/npm/apk...）
    }
  }
---

# 正文：执行步骤（Step 1/2/3）、工具选择规则、回复模板、注意事项
```

---

## 三、单层技能目录 

### 单层：workspace 技能主目录

技能来源**只有一层**：`/sdcard/.xomniclaw/workspace/skills/`。`SkillsLoader.loadSkills()`（[SkillsLoader.kt:89-130](CareClaw/app/src/main/java/com/shijing/xomniclaw/agent/skills/SkillsLoader.kt#L89-L130)）把所有技能 load 进 `skillsCache`（Map<name, SkillDocument>），key = skill.name，后扫描覆盖先扫描（同名以最近写入为准）。

| 来源 | 路径 | 加载器 |
|---|---|---|
| Workspace（唯一） | `/sdcard/.xomniclaw/workspace/skills/` | `loadWorkspaceSkills` |

**配套机制**：
1. **同名覆盖**：同一个 Map、key=name，后扫描覆盖先扫描。
2. **每次加载全量重扫**：loadSkills() 每次重扫唯一目录，新写入技能下次调用即生效，无需缓存/指纹/监听。

---

## 四、运行时按需注入（ContextBuilder → system prompt）

`ContextBuilder.buildSkillsSection()`（[ContextBuilder.kt:350-460](CareClaw/app/src/main/java/com/shijing/xomniclaw/agent/context/ContextBuilder.kt#L350-L460)）把技能注入 system prompt，**分层控制 token**：

```
纯寒暄？ → 整段跳过（和 tools 的寒暄短路一致）

selectRelevantSkills(userGoal)   ← 按当前任务选候选（见下）
  ├─ always 技能 → 全量注入正文（每轮都要用）
  └─ 普通技能   → 只注入 TSV 目录：name \t path \t 一行描述
                    （LLM 先看目录，命中后用 read_file 按需读全文）
```

**注入示例**（实际 system prompt 里的 Skills 段）：

```
## Skills (mandatory)
Before replying: scan the lines under the header row (tab-separated: name, path, description).
- If exactly one skill clearly applies: read its SKILL.md at the path with `read_file`, then follow it.
- If none clearly apply: do not read any SKILL.md.

name	path	description
app-search	assets://skills/app-search/SKILL.md	🔍 主流APP搜索
gallery-qa	/sdcard/.xomniclaw/workspace/skills/gallery-qa/SKILL.md	🖼️ 相册问答
```

**去正则化设计**（[SkillsLoader.kt:307-366](CareClaw/app/src/main/java/com/shijing/xomniclaw/agent/skills/SkillsLoader.kt#L307-L366)）：不再用关键词/正则硬过滤"是否注入"——凡 OS/requirements 满足的技能都进目录，由 LLM 自己根据描述选择；旧关键词规则（`identifyTaskType` / `matchesKeywords`）**降级为打分排序**用，让强相关技能在目录里靠前，弱模型也能优先看到。个别高价值技能（app-search / taobao-search）在 `buildSkillPriorityHints` 里给 `Skill Override (MUST obey)` 强提示，防止模型漏读。

**read_file 配套**：`read_file` 本身是 on-demand 工具（默认不给 schema），但当 system prompt 含 `## Skills (mandatory)` 时**强制注入**（[LlmOnDemandToolInclusion.kt:120-122](CareClaw/app/src/main/java/com/shijing/xomniclaw/agent/tools/LlmOnDemandToolInclusion.kt#L120-L122)）——保证模型"想读技能文件但 tools 里没有"的断层不发生。

---

## 五、即时生效（每次全量重扫，不缓存）

用户沉淀/修改技能后，希望"下次就能用"。最简做法就是**每次 `loadSkills()` 都全量重扫唯一目录**（[SkillsLoader.kt:59-82](CareClaw/app/src/main/java/com/shijing/xomniclaw/agent/skills/SkillsLoader.kt#L59-L82)）：

- 技能文件单个几 KB，全量读取 + 解析成本（毫秒级）远小于一次 LLM 调用（秒级），**重扫开销可忽略**
- 换来**永远最新、逻辑最简**：没有缓存失效判断、没有 mtime 指纹、没有 FileObserver 监听
- 新写入的技能，下一次 `loadSkills()`（下一次会话）即自动生效，无需重启

---

## 六、技能固化：Agent 对话操作的沉淀与复用

### 沉淀的是什么

**一次 Agent 对话成功执行的操作路径**。例如：

- 远程装 App：`install_app` 装好微信 → 操作路径 = "定位 APK → install_app"
- 打开高频页面：`device(action="open", uri="weixin://...")` 直达视频通话页
- 跨 APP 搜索：`device open` 深链 → wait → snapshot → 提取 → 按模板回复

把这些"跑通的路"写成 SKILL.md，就是技能固化。**沉淀的本质是"把成功的操作路径变成说明书"**，下次说技能名直接复用，不再从零推理。

### 沉淀到哪里

写入 **`/sdcard/.xomniclaw/workspace/skills/<名>/SKILL.md`**（唯一技能目录），SkillsLoader 每次加载全量重扫，**下一次会话即生效**（见第五节）。

```
一次对话跑通操作路径
  → 沉淀为 SKILL.md 写入 workspace/skills/<名>/（最高优先级）
  → 下次 loadSkills 全量重扫 → 自动进 catalog
  → 用户说"<技能名>" → selectRelevantSkills 命中 → LLM read_file 读 SKILL.md
  → 复用操作路径 → 完成
```

### 沉淀的两种形态

1. **手动沉淀（当前落地方式）**：把对话里跑通的操作步骤写成 SKILL.md。可含踩坑经验（如 app-search 里"哪些 deep link scheme 实测不可用、哪些参数严禁传"），越详细复用越稳。
2. **Agent 自动导出（设计方向，非当前实现）**：对话结束后由 Agent 把 `toolsUsed` 序列 + 关键参数导出为 SKILL.md 模板。**基建已就绪**：
   - `AgentLoop` 返回 `toolsUsed`（本次用到的工具列表）
   - `session/JsonlSessionStorage` 的 JSONL 保留完整 tool 调用块
   - `SkillsLoader` 每次加载全量重扫（写入即生效）
   
   缺的只是一个"对话结束 → 生成 SKILL.md"的导出器，属可扩展方向而非架构缺陷。

### 为什么沉淀比"操作录制/回放"可靠（面试可答）

- **录制回放的致命点**：录下来的坐标依赖分辨率、UI 树一变就失效、回放遇阻无法纠错，端侧弱模型根本hold不住多步回放
- **沉淀的稳**：SKILL.md 是**语义化的操作步骤**（`device open uri=...` + 提取规则 + 回复模板），不是坐标序列——换设备、UI 微调后仍可复用，且模型执行时还能临场应变
- 父辈场景（挂号/缴费/视频通话）恰恰是"进入高频页面 + 简单确认"型操作，沉淀一页说明书就够用

---

## 七、面试 QA

**Q1：SKILL.md 和亮点3 的 Tool/Skill 代码工具是什么关系？会不会重复？**
答：互补不重复。Tool/Skill 代码工具是**编译进 APK** 的原子能力（点屏/装包/读文件），一个方法一套逻辑，面向"平台能力"；SKILL.md 是**运行时外部文件**的"操作说明书"，面向"任务流程"（打开淘宝→深链搜索→提取价格→按模板回复）。区别在：SKILL.md 不用改代码就能新增/修改、能热更新，非工程师也能维护；弱模型按描述即可执行。SkillParser 统一解析，两者在 AgentLoop 里各司其职。

**Q2：技能这么多，为什么不全部塞进 system prompt？**
答：token 成本。全量注入 30-50K 字符，端侧模型窗口扛不住。所以渐进式三层：①always 技能（每轮都要用的）全量注入；②普通技能只注入 TSV 目录（name/path/一行描述），技能段从 30-50K 压到 1-3K；③LLM 选中某技能后再用 read_file 按需读全文。这就是"渐进式加载"的含义——**由浅入深，用到哪读到哪**。

**Q3：技能改动怎么"即时生效"？**
答：不缓存、不监听。`loadSkills()` 每次被调用都全量重扫唯一目录——技能文件单个几 KB、全量读取解析成本远小于一次 LLM 调用，换来永远最新、逻辑最简。新写入的技能下一次 loadSkills（下一次会话）即生效，无需重启。

**Q4：技能文件从哪来？"一次对话操作的沉淀"具体怎么落地？**
答：三层来源。①**内置预置**（assets/skills）——把已知高频操作预先写成 SKILL.md，开箱即用；②**手动沉淀**（写入 workspace/skills）——一次对话跑通了某条操作路径，把步骤写成 SKILL.md，指纹热加载即时生效、最高优先级可覆盖内置；③**Agent 自动导出**（设计方向）——对话结束后把 toolsUsed 序列导出为 SKILL.md，基建（toolsUsed / session JSONL / workspace 热加载）已就绪，缺导出器。

**Q5：沉淀的技能，LLM 怎么知道什么时候触发？**
答：触发靠 catalog 里技能的 name + description 相关度。SKILL.md 的 frontmatter 自带 name + description（写明"当用户说『打开XX』『使用XX』『XX』时激活"），LLM 扫目录读到描述即命中。所以沉淀时 description 写好触发词，技能名就是语音指令，实现"一次沉淀、语音复用"。

**Q6：同名技能在多个目录同时存在怎么办？**
答：技能来源单层（workspace/skills），同名时后扫描覆盖先扫描；`copySeedSkills` 只拷缺失文件，不会把用户沉淀/修改的技能覆盖回去。

**Q7：为什么不做"操作录制回放"，而用 SKILL.md 沉淀？**
答：录制回放是**坐标/UI 绑定的执行流**，换分辨率、UI 变化就失效，且回放遇阻没有纠错能力，端侧弱模型hold不住多步回放。SKILL.md 是**语义化操作说明书**（步骤 + 规则 + 模板），模型执行时能临场应变，换设备仍可复用；父辈高频场景（进某个页面 + 确认）一页说明书就够。所以选"沉淀说明书"而不是"录坐标流"。

**Q8：Agent 自动导出沉淀没实现，被追问怎么办？（能力边界坦诚版）**
答：如实说明当前沉淀靠手动写 SKILL.md，自动"对话→SKILL.md"导出是设计方向。然后给出落地方案：Agent 对话结束后把 `toolsUsed` + 关键参数（从 AgentResult / session JSONL 拿）填充进 SKILL.md 模板（frontmatter + Step 1/2/3），写入 workspace/skills 即被下次全量重扫识别。基建全在，缺一个导出器——能答出"基建已就绪 + 模板化导出"反而是加分项。

---

*整理日期：2026-08-15。来源：用户学习笔记 + 源码核对（SkillsLoader / SkillParser / SkillDocument / ContextBuilder / WorkspaceInitializer / LlmOnDemandToolInclusion / AgentLoop / JsonlSessionStorage）。*
