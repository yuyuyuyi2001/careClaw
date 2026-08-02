---
name: clipboard-to-shortcut
description: |
  将剪切板中的 App 跳转链接制作成一个可复用的快捷指令 Skill，下次用户说出 Skill 名称即可一键直达对应 App 页面。
  当用户说"把剪切板的链接做成skill"、"帮我保存这个链接为快捷方式"、"用剪切板内容创建一个skill XX"、"把这个链接制作成skill"、"帮我把复制的链接做成一键跳转"等意图时激活。
  支持淘宝、拼多多、抖音、快手、知乎、小红书、美团、哔哩哔哩、高德、QQ音乐、支付宝、百度等主流 App 的分享链接。
metadata:
  {
    "xomniclaw": {
      "always": false,
      "emoji": "🔖",
      "version": "1.0.0",
      "category": "navigation"
    }
  }
---

# Clipboard to Shortcut Skill

从手机剪切板读取 App 跳转链接，自动生成一个独立的快捷指令 SKILL.md，用户以后只需说出该 Skill 名称即可一键直达对应 App 页面。

## 🚫 绝对禁止（违反必崩溃）

> **禁止使用 exec / shell 执行以下任何命令，100% 报 SecurityException 崩溃：**
>
> ```
> # ❌ 全部禁止 — 没有例外
> am start -a android.intent.action.VIEW -d "https://..."    # ← 最常见错误！
> am start -n com.taobao.taobao/...
> content query --uri content://clipboard/clipboard
> service call clipboard 2
> adb shell ...
> input tap / input swipe / input text
> ```
>
> **已知崩溃日志（如果你看到类似报错，说明你用错了工具）：**
> ```
> java.lang.SecurityException: Permission Denial: package=com.android.shell does not belong to uid=...
> ```
>
> ---
>
> **✅ 读取剪切板唯一正确方式：**
> ```kotlin
> device(action = "clipboard")
> ```
>
> **✅ 跳转 App 唯一正确方式（必须带 package_name 防止跳浏览器）：**
> ```kotlin
> device(action = "open", uri = "https://...", package_name = "com.taobao.taobao")
> ```
>
> **⛔ 绝对不能用 exec 调 `am start` 作为替代或回退方案。失败时应告知用户，而非换成 shell。**

## 🎯 When to Use

✅ "把剪切板的链接做成 skill 淘宝好物"
✅ "帮我用剪切板内容创建一个skill叫抖音搞笑视频"
✅ "把复制的链接保存为快捷跳转 skill"
✅ "制作一个skill，下次可以一键打开这个页面"
✅ "帮我把这个链接做成一键直达的skill XX"

## 📋 Workflow

### 0) 前置检查

检查 Device status：
- `Accessibility: ✅ connected` → 继续。
- `Accessibility: ❌ not connected` → 停止，回复用户：

```
设备当前未连接无障碍服务（Accessibility 未连接），暂时无法读取剪切板。
请先在 X-OmniClaw 中重新连接设备无障碍服务后重试。
```

### 1) 获取 Skill 名称

从用户 query 中提取 Skill 名称。用户通常会说"做成 skill XX"或"创建 skill 叫 XX"。

- 若用户未提供名称，根据链接对应的 App 和内容自动生成一个简短中文名称，例如 `淘宝板鞋`、`抖音搞笑视频`。
- 名称规范：直接使用中文名称，不超过 20 个字。文件夹名与 name 保持一致（均为中文）。禁止转换为拼音或英文。

### 2) 读取剪切板并提取 URL

```kotlin
val clipboardText = device(action = "clipboard")

val urlRegex = Regex("""https?://[^\s\u4e00-\u9fff\u3000-\u303f\uff00-\uffef]+""")
var url = urlRegex.find(clipboardText)?.value
    ?.trimEnd('，', '。', '、', '"', '"', ')', '）', ']', '】', '>', '》', '￥', ' ')
    ?: throw Exception("剪切板中未找到有效链接")
```

### 3) 识别 App 类型并获取 package_name

根据 URL 特征识别 App，同时确定 `package_name`。**跳转时必须传 `package_name`，否则链接会被浏览器拦截。**

| App | URL 特征 | package_name |
|-----|---------|-------------|
| 淘宝 | `e.tb.cn`、`tb.cn`、`taobao.com` | `com.taobao.taobao` |
| 拼多多 | `yangkeduo.com`、`pinduoduo.com`、`pddurl.cn` | `com.xunmeng.pinduoduo` |
| 抖音 | `v.douyin.com`、`douyin.com` | `com.ss.android.ugc.aweme` |
| 快手 | `v.kuaishou.com`、`kuaishou.com` | `com.smile.gifmaker` |
| 知乎 | `zhihu.com` | `com.zhihu.android` |
| 小红书 | `xhslink.com`、`xiaohongshu.com` | `com.xingin.xhs` |
| 美团 | `dpurl.cn`、`meituan.com`、`dianping.com` | `com.sankuai.meituan` |
| 哔哩哔哩 | `b23.tv`、`bilibili.com` | `tv.danmaku.bili` |
| 高德 | `surl.amap.com`、`amap.com` | `com.autonavi.minimap` |
| QQ音乐 | `y.qq.com` | `com.tencent.qqmusic` |
| 支付宝 | `render.alipay.com`（含 `alipays://`） | `com.eg.android.AlipayGphone` |
| 百度 | `mbd.baidu.com` | `com.baidu.searchbox` |

### 4) 特殊链接处理（支付宝）

```kotlin
if (url.contains("render.alipay.com") && url.contains("scheme=")) {
    val schemeParam = Regex("""scheme=([^&]+)""").find(url)?.groupValues?.get(1)
    if (schemeParam != null) {
        url = java.net.URLDecoder.decode(schemeParam, "UTF-8")
    }
}
```

### 5) 验证链接可用性

先尝试跳转一次，确认链接有效。**必须带 `package_name` 参数，强制在原生 App 中打开，避免跳浏览器：**

```kotlin
device(action = "open", uri = url, package_name = "{对应App的package_name}")
device(action = "act", kind = "wait", timeMs = 1000)
device(action = "snapshot")
```

snapshot确认已成功打开目标 App 页面（而非浏览器）。若链接失效/App 未安装，告知用户无法创建 Skill 并说明原因。

### 6) 生成快捷指令 SKILL.md

确认链接有效后，在 `/sdcard/.xomniclaw/workspace/skills/{skill-name}/` 目录下生成 SKILL.md。`{skill-name}` 直接使用中文名称（如 `淘宝板鞋`），文件夹名与 SKILL.md 中的 name 字段保持一致。

#### ⚠️ YAML Frontmatter 格式强制约束（必须严格遵守）

生成的 SKILL.md **必须**以 YAML frontmatter 开头，格式要求如下：

1. **frontmatter 必须位于文件最顶部第一行**，以 `---` 开始，以 `---` 结束
2. **禁止把 frontmatter 放在文件中间或底部**（如"## 元数据"章节下），SkillParser 只解析文件顶部的 frontmatter
3. frontmatter 中只包含三个字段：`name`、`description`、`metadata`
4. `name`：使用中文名称（如 `淘宝板鞋`），**禁止转为英文或拼音**（如 ~~cheap-braised-duck-chaoyang~~）
5. `description`：使用 YAML block scalar（`|`），包含功能描述和触发词
6. `metadata`：**必须使用 JSON 格式**（花括号包裹），**禁止使用 YAML 嵌套格式**

**✅ 正确的 metadata 格式（JSON）：**
```yaml
metadata:
  {
    "xomniclaw": {
      "always": false,
      "emoji": "🛒",
      "version": "1.0.0",
      "category": "navigation"
    }
  }
```

**❌ 错误的 metadata 格式（YAML 嵌套，SkillParser 无法解析）：**
```yaml
metadata:
  xomniclaw:
    always: false
    emoji: 🛒
    version: 1.0.0
    category: navigation
```

#### 生成的 SKILL.md 完整模板

**严格按照以下模板生成，不得调整 frontmatter 位置或格式：**

```markdown
---
name: {skill-name（中文名称）}
description: |
  一键直达{App名称}指定页面的快捷指令。从剪切板链接自动创建。
  当用户说"打开{skill-name}"、"使用{skill-name}"、"用{skill-name}"时激活。
metadata:
  {
    "xomniclaw": {
      "always": false,
      "emoji": "{对应App的emoji}",
      "version": "1.0.0",
      "category": "navigation"
    }
  }
---

# {skill-name（中文名称）}

一键直达{App名称}指定页面。

## 🚫 绝对禁止（违反必崩溃）

> **禁止使用 exec / shell（包括 `am start`），100% 报 SecurityException。**
>
> **✅ 跳转 App 唯一正确方式（必须带 package_name）：**
> ```kotlin
> device(action = "open", uri = "...", package_name = "{package_name}")
> ```

## 📋 执行步骤

### Step 1: 跳转目标页面

```kotlin
device(action = "open", uri = "{url}", package_name = "{package_name}")
```

### Step 2: 等待并确认

```kotlin
device(action = "act", kind = "wait", timeMs = 1000)
device(action = "snapshot")
```

snapshot后根据页面内容向用户简洁汇报：

```
已跳转到{App名称}，当前显示{页面内容描述}。
```

## ⚠️ 注意事项

- 必须使用 `device(action="open", uri="...", package_name="...")` 跳转，禁止用 shell `am start`
- **必须带 package_name 参数**，不带会跳浏览器而非原生 App。参见步骤 3 的包名表
- 若 App 未安装，snapshot并告知用户
- 若链接过期，告知用户链接已失效
- 原始链接来源：{clipboardText 的摘要信息}
- 创建时间：{当前日期}
```

#### 生成前自检清单

生成 SKILL.md 前，逐项检查：

- [ ] 文件第一行是 `---`（frontmatter 起始标记）
- [ ] `name` 字段使用中文名称
- [ ] `description` 使用 `|` block scalar 格式
- [ ] `metadata` 使用 JSON 格式（花括号），不是 YAML 嵌套格式
- [ ] frontmatter 以 `---` 结束后，紧接 Markdown 正文（`# 标题`）
- [ ] 文件中没有第二处 `---` 包裹的 frontmatter 块

### 7) App Emoji 映射

生成 SKILL.md 时，根据识别到的 App 类型选择 emoji。生成的 SKILL.md 中的 `device(action="open")` 也必须带上 `package_name`：

| App | Emoji | package_name |
|-----|-------|-------------|
| 淘宝 | 🛒 | `com.taobao.taobao` |
| 拼多多 | 🍊 | `com.xunmeng.pinduoduo` |
| 抖音 | 🎵 | `com.ss.android.ugc.aweme` |
| 快手 | 🎬 | `com.smile.gifmaker` |
| 知乎 | 💡 | `com.zhihu.android` |
| 小红书 | 📕 | `com.xingin.xhs` |
| 美团 | 🍜 | `com.sankuai.meituan` |
| 哔哩哔哩 | 📺 | `tv.danmaku.bili` |
| 高德 | 🗺️ | `com.autonavi.minimap` |
| QQ音乐 | 🎶 | `com.tencent.qqmusic` |
| 支付宝 | 💰 | `com.eg.android.AlipayGphone` |
| 百度 | 🔍 | `com.baidu.searchbox` |
| 未知 | 🔗 | （不传 package_name） |

### 8) 回复用户

创建完成后，向用户确认：

```
✅ 已成功创建快捷指令 Skill「{skill-name}」！

📋 链接来源：{App名称} - {内容摘要}
🔗 目标地址：{url}

下次您只需说「打开{skill-name}」或「使用{skill-name}」，即可一键直达该页面。
```

## 💡 Complete Examples

### 示例 1：将淘宝商品链接制作为 Skill

**用户 query**: "把剪切板的链接做成 skill 淘宝板鞋"

**剪切板内容**:
```
【淘宝】https://e.tb.cn/h.io9Fd5S?tk=fNSL55NU59K CZ009 适配Nike SB Dunk Low低帮板鞋
```

**执行流程**:
```kotlin
// 1. 读取剪切板
val clip = device(action = "clipboard")
// → "【淘宝】https://e.tb.cn/h.io9Fd5S?tk=fNSL55NU59K CZ009 ..."

// 2. 提取 URL
val url = "https://e.tb.cn/h.io9Fd5S?tk=fNSL55NU59K"

// 3. 识别为淘宝链接

// 4. 验证链接 - 带 package_name 强制在淘宝 App 打开
device(action = "open", uri = url, package_name = "com.taobao.taobao")
device(action = "act", kind = "wait", timeMs = 1000)
device(action = "snapshot")
// → snapshot确认：淘宝商品页 Nike SB Dunk Low（在淘宝 App 中打开，非浏览器）

// 5. 生成 SKILL.md → /sdcard/.xomniclaw/workspace/skills/淘宝板鞋/SKILL.md
```

**生成的 SKILL.md（注意：frontmatter 在文件最顶部，metadata 用 JSON 格式，name 用中文）**:
```markdown
---
name: 淘宝板鞋
description: |
  一键直达淘宝商品页面（Nike SB Dunk Low板鞋）的快捷指令。
  当用户说"打开淘宝板鞋"、"使用淘宝板鞋"时激活。
metadata:
  {
    "xomniclaw": {
      "always": false,
      "emoji": "🛒",
      "version": "1.0.0",
      "category": "navigation"
    }
  }
---

# 淘宝板鞋

一键直达淘宝 Nike SB Dunk Low 板鞋商品页面。

## 🚫 绝对禁止（违反必崩溃）

> **禁止使用 exec / shell（包括 `am start`），100% 报 SecurityException。**
>
> **✅ 跳转 App 唯一正确方式（必须带 package_name）：**
> ```kotlin
> device(action = "open", uri = "...", package_name = "com.taobao.taobao")
> ```

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

snapshot后回复：`已跳转到淘宝，当前显示 Nike SB Dunk Low 板鞋商品详情页。`

## ⚠️ 注意事项

- 必须使用 `device(action="open", uri="...", package_name="com.taobao.taobao")` 跳转，不带 package_name 会跳浏览器
- 原始链接来源：淘宝分享 - Nike SB Dunk Low低帮板鞋
- 创建时间：2026-04-13
```

**回复**:
```
✅ 已成功创建快捷指令 Skill「淘宝板鞋」！

📋 链接来源：淘宝 - Nike SB Dunk Low低帮板鞋
🔗 目标地址：https://e.tb.cn/h.io9Fd5S?tk=fNSL55NU59K

下次您只需说「打开淘宝板鞋」或「使用淘宝板鞋」，即可一键直达该商品页面。
```

---

### 示例 2：将抖音视频链接制作为 Skill

**用户 query**: "帮我把剪切板里的链接创建成skill 搞笑相声"

**剪切板内容**:
```
3.33 复制打开抖音，看看【西溪历史的作品】1993年录像...
https://v.douyin.com/Sw2BOwYSsp4/ X@M.WZ
```

**执行流程**:
```kotlin
// 1. 读取剪切板
val clip = device(action = "clipboard")

// 2. 提取 URL
val url = "https://v.douyin.com/Sw2BOwYSsp4/"

// 3. 识别为抖音链接

// 4. 验证链接 - 带 package_name 强制在抖音 App 打开
device(action = "open", uri = url, package_name = "com.ss.android.ugc.aweme")
device(action = "act", kind = "wait", timeMs = 1000)
device(action = "snapshot")

// 5. 生成 SKILL.md → /sdcard/.xomniclaw/workspace/skills/搞笑相声/SKILL.md
```

**生成的 SKILL.md** 中的核心跳转指令:
```kotlin
device(action = "open", uri = "https://v.douyin.com/Sw2BOwYSsp4/", package_name = "com.ss.android.ugc.aweme")
```

**回复**:
```
✅ 已成功创建快捷指令 Skill「搞笑相声」！

📋 链接来源：抖音 - 西溪历史 1993年历史录像
🔗 目标地址：https://v.douyin.com/Sw2BOwYSsp4/

下次您只需说「打开搞笑相声」或「使用搞笑相声」，即可一键直达该视频。
```

---

### 示例 3：将支付宝链接制作为 Skill（含特殊解码）

**用户 query**: "把剪切板链接做成skill 支付宝活动"

**剪切板内容**:
```
https://render.alipay.com/p/s/i/?scheme=alipays%3A%2F%2Fplatformapi%2Fstartapp%3FappId%3D20000793%26url%3D...
```

**执行流程**:
```kotlin
// 1. 读取剪切板
val clip = device(action = "clipboard")

// 2. 提取 URL
var url = "https://render.alipay.com/p/s/i/?scheme=alipays%3A%2F%2F..."

// 3. 支付宝特殊处理：解码 scheme 参数
url = "alipays://platformapi/startapp?appId=20000793&url=..."

// 4. 验证链接 - 支付宝用 alipays:// scheme 直接跳转，自动匹配支付宝 App
device(action = "open", uri = url, package_name = "com.eg.android.AlipayGphone")
device(action = "act", kind = "wait", timeMs = 1000)
device(action = "snapshot")

// 5. 生成 SKILL.md，跳转 uri 使用解码后的 alipays:// 深链接
```

**生成的 SKILL.md** 中的核心跳转指令:
```kotlin
device(action = "open", uri = "alipays://platformapi/startapp?appId=20000793&url=...", package_name = "com.eg.android.AlipayGphone")
```

## 🔄 Error Handling

### 剪切板为空或无 URL

```
剪切板内容为空，或未找到有效链接。请先复制 App 分享的链接再试。
```

### 目标 App 未安装

```
已尝试验证链接，但手机未安装{App名称}。请先安装该 App 后再创建快捷指令。
```

### 链接已过期

```
该链接已过期或已失效，无法创建快捷指令。请复制一个有效的链接后重试。
```

### Skill 名称冲突

若目标目录已存在同名 Skill，提示用户：

```
已存在名为「{skill-name}」的 Skill。是否要覆盖？或请提供一个新名称。
```

### 用户未提供 Skill 名称

根据 App 类型和剪切板内容摘要自动生成名称，并告知用户：

```
未指定 Skill 名称，已自动命名为「{auto-name}」。您可以随时要求重命名。
```
