---
name: taobao-search
description: |
  淘宝商品搜索。当用户想要搜索商品、找某品牌产品、比价、查看新款等电商购物场景时激活。
metadata:
  {
    "xomniclaw": {
      "always": false,
      "emoji": "🛒",
      "version": "1.0.0",
      "category": "shopping"
    }
  }
---

# Taobao Search Tool

通过淘宝 Deep Link 直达搜索页面，浏览商品并为用户提供购物建议。

## 🎯 When to Use

Use this skill when user asks about:

✅ **商品搜索** - "帮我找阿迪达斯今年新款男鞋"
✅ **品牌查找** - "搜一下耐克跑步鞋"
✅ **比价购物** - "淘宝上看看有什么好的蓝牙耳机"
✅ **新品浏览** - "今年有什么新款连衣裙"
✅ **特定条件搜索** - "200元以内的双肩包"

## 📋 Workflow

### 标准搜索流程

```
1. 解析用户意图，提取搜索关键词
2. 通过 device(action="open", uri="taobao://...") 跳转淘宝搜索页
3. 等待淘宝 App 加载搜索结果：device(action="act", kind="wait", timeMs=1500)
4. 拉取页面 UI 树：device(action="snapshot", format="compact")
   —— 这是商品名/价格/店铺/销量等文字信息的**唯一来源**
5. 从 snapshot 返回的 text 节点里逐条提取商品信息（详见 Step 3）
6. 基于提取到的具体商品信息回答用户（200字以内，必须包含真实数字和细节）
```

> ⚠️ **绝对禁止**: 不要用 `read_file` 读截图 PNG（如 `read_file("/sdcard/.xomniclaw/workspace/screenshots/*.png")`）。PNG 是二进制，read_file 会以 UTF-8 解码出大量乱码并撑爆上下文窗口，整个 session 会被 `context window exceeds limit` 终止。**只用 `device(action="snapshot")` 读页面文字**。

## 🔗 Deep Link 构造规则

淘宝搜索 Deep Link 格式：

```
taobao://s.taobao.com/search?q={关键词}
```

关键词用 `+` 连接多个搜索词，示例：

| 用户意图 | 搜索关键词 | Deep Link |
|---------|-----------|-----------|
| 阿迪达斯今年新款男鞋 | 阿迪达斯+2026+新款+男鞋 | `taobao://s.taobao.com/search?q=阿迪达斯+2026+新款+男鞋` |
| 耐克跑步鞋 | 耐克+跑步鞋 | `taobao://s.taobao.com/search?q=耐克+跑步鞋` |
| 200元蓝牙耳机 | 蓝牙耳机 | `taobao://s.taobao.com/search?q=蓝牙耳机` |

## 🚀 X-OmniClaw Implementation

**Tool**: `device` (DeviceTool — 统一设备控制工具)

⚠️ **重要**: 不要使用 shell `am start` 命令跳转 Deep Link（会因 SecurityException 权限拒绝），必须使用 `device(action="open", uri="...")` 通过 X-OmniClaw 应用上下文发起 `ACTION_VIEW` Intent。

### Step 1: Deep Link 跳转淘宝搜索页

```kotlin
// 构造搜索关键词
val keyword = "阿迪达斯+2026+新款+男鞋"
val deepLink = "taobao://s.taobao.com/search?q=$keyword"

// 通过 device tool 的 open action + uri 参数跳转
device(action = "open", uri = deepLink)
```

### Step 2: 等待页面加载并拉取 UI 树

```kotlin
// 等待淘宝 App 启动并加载搜索结果
device(action = "act", kind = "wait", timeMs = 1500)

// 拉取页面 UI 树(含所有可见文本节点) —— 商品信息的唯一来源
device(action = "snapshot", format = "compact")
```

> 不要在这一步用 `screenshot`：`screenshot` 只用于内部 grounding（定位点击坐标），不会把图像内容送给你。`snapshot` 才会把页面上每个可见文本节点（含商品名、价格、店铺名）以纯文本方式返回给你。

### Step 3: 从 snapshot 的 text 节点中提取商品信息（关键步骤）

snapshot 返回的是 UI 树文本，例如：
```
text "ULTRABOOST 5X 2026春季款男子跑步鞋"
text "¥899"
text "阿迪达斯官方旗舰店"
text "2000+人付款"
```

**你必须从 text 节点逐条提取以下字段**：

- **商品完整名称**（含品牌、系列、型号，如 "Nike Air Max 270 React"）
- **价格**（精确到元，如 ¥599、¥1299）
- **颜色/配色**（如 黑白、灰蓝、荧光绿）—— 若 snapshot 中没有出现颜色文字，可省略此字段
- **店铺名称**（如 "耐克官方旗舰店"、"XX运动专营店"）
- **销量/付款人数**（如 "2000+人付款"、"月销500+"）
- **标签/卖点**（如 "官方正品"、"新品首发"、"包邮"）

> 如果 snapshot 文本节点稀疏（页面还在加载或被弹窗遮挡），**再次 `device(action="act", kind="wait", timeMs=1500)` 然后重新 snapshot**；不要 `read_file` 截图 PNG。

### Step 4: 基于具体商品信息回答用户

⛔ **严禁笼统回复**。以下是禁止出现的回复方式：

```
❌ "搜索结果展示了多个商家的耐克新款男鞋，包含不同款式和价位"
❌ "你可以在当前页面继续上下滑动查看更多商品"
❌ "点击感兴趣的商品查看详情和价格"
❌ "包含不同款式和价位，可以根据你的喜好选择"
```

这类回复等于没有回答，用户自己也能看到屏幕。你的价值在于**帮用户总结屏幕上的具体商品信息**。

✅ **必须包含具体数字和细节**，回复格式：

```
为您在淘宝搜索了"{搜索词}"，挑选了几款值得关注的：

1. {完整商品名含型号} - ¥{具体价格}，{颜色}，{店铺名}，{销量}
2. {完整商品名含型号} - ¥{具体价格}，{颜色}，{店铺名}，{销量}
3. {完整商品名含型号} - ¥{具体价格}，{颜色}，{店铺名}，{销量}

价格区间¥{最低}-¥{最高}。{1-2句具体购物建议，如哪款性价比高、哪家是官方旗舰店等}。需要我点开某款看详情吗？
```

## 💡 Complete Example

**用户**: "帮我找阿迪达斯今年新款男鞋"

**执行流程**:

```kotlin
// 1. 解析意图 → 关键词: 阿迪达斯+2026+新款+男鞋
val keyword = "阿迪达斯+2026+新款+男鞋"

// 2. Deep Link 跳转淘宝（使用 device tool 的 uri 参数）
device(action = "open", uri = "taobao://s.taobao.com/search?q=$keyword")

// 3. 等待加载并拉取 UI 树
device(action = "act", kind = "wait", timeMs = 1500)
device(action = "snapshot", format = "compact")  // ← 唯一信息来源

// 4. 从 snapshot 的 text 节点中提取: 商品名(含型号)、价格、颜色、店铺名、销量
// 5. 基于提取到的具体信息回答用户（200字以内）
```

**回复示例**（注意每一条都是从 snapshot 的 text 节点中实际读取的具体信息）：

```
为您在淘宝搜索了"阿迪达斯2026新款男鞋"，挑选了几款值得关注的：

1. Adidas ULTRABOOST 5X 2026春季款男子跑步鞋 - ¥899，黑白配色，阿迪达斯官方旗舰店，2000+人付款
2. Adidas Originals GAZELLE Indoor 板鞋 - ¥799，深蓝/白色，adidas三叶草官方旗舰店，1500+人付款
3. Adidas SAMBA OG 德训鞋经典复刻 - ¥699，白色/黑尾，得物adidas授权店，3000+人付款

价格区间¥699-¥899。3款都是今年新配色，其中SAMBA销量最高性价比也不错；如果追求跑步性能推荐ULTRABOOST。前两家是官方旗舰店更有保障。需要我点开某款看详情吗？
```

## 🔍 关键词提取策略

从用户查询中提取搜索关键词时：

```kotlin
// 品牌 + 时间 + 品类 组合
val brand = "阿迪达斯"     // 品牌名
val year = "2026"          // 年份（"今年"→当前年份）
val category = "新款+男鞋"  // 商品类别

val keyword = "$brand+$year+$category"
```

**时间词映射**：

| 用户表述 | 转换为 |
|---------|-------|
| 今年 | 2026 |
| 新款 | 2026+新款 |
| 最新 | 2026+新款 |
| 去年 | 2025 |

## ⚠️ 注意事项

- **必须使用 device tool**: 通过 `device(action="open", uri="...")` 跳转，不要用 shell `am start`（会报 SecurityException）
- **淘宝 App 必须已安装**: Deep Link 需要淘宝 App，若未安装会返回 ActivityNotFoundException
- **登录状态**: 部分搜索结果需要登录淘宝账号才能完整展示
- **网络依赖**: 需要联网才能加载搜索结果
- **加载时间**: 网络较慢时适当增加 `wait` 的 timeMs；snapshot 文本稀疏时再 wait 1-2 秒后重 snapshot
- **回复字数**: 字数控制在 200 字以内，但必须包含从 snapshot 中读取的具体商品信息
- **严禁套话**: 绝对不能回复"包含不同款式和价位""你可以继续浏览"这类笼统废话。如果 snapshot 里没有足够商品文字，应再 wait 后重新 snapshot，仍不行则如实说明
- **严禁 read_file 截图 PNG**: `read_file("/sdcard/.../screenshots/*.png")` 会返回二进制乱码并撑爆上下文，整个 session 会被 `context window exceeds limit` 终止；任何"读截图内容"的需求一律改用 `device(action="snapshot")`

## 🔄 Error Handling

### 淘宝未安装

```kotlin
// device(action="open", uri="...") 会返回 ActivityNotFoundException 错误
// 备选方案：使用浏览器打开
device(action = "open", uri = "https://s.taobao.com/search?q=$keyword")
```

### 搜索无结果

```kotlin
// 如果搜索结果为空，尝试简化关键词
val simplifiedKeyword = "阿迪达斯+男鞋"  // 去掉年份等限定词
device(action = "open", uri = "taobao://s.taobao.com/search?q=$simplifiedKeyword")
```

### 页面加载超时

```kotlin
// 增加等待时间并重新拉取 UI 树
device(action = "act", kind = "wait", timeMs = 2000)
device(action = "snapshot", format = "compact")
// 如果 snapshot 仍无商品 text 节点，提示用户检查网络/登录状态
```

## 📱 Supported Queries

| 查询类型 | 示例 | 搜索关键词 |
|---------|------|-----------|
| 品牌+品类 | "找耐克跑步鞋" | 耐克+跑步鞋 |
| 品牌+年份+品类 | "阿迪达斯今年新款" | 阿迪达斯+2026+新款 |
| 品类+价格 | "300以内的双肩包" | 双肩包 |
| 泛品类 | "好看的连衣裙" | 连衣裙+新款 |
| 特定商品 | "iPhone 16 手机壳" | iPhone+16+手机壳 |
