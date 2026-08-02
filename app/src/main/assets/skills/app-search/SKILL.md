---
name: app-search
description: |
  主流APP搜索。当用户想要在拼多多、美团、高德地图、抖音、快手、小红书、哔哩哔哩、知乎、百度、OPPO音乐、OPPO软件商店等APP中搜索时激活。覆盖电商购物、生活服务、短视频内容、知识搜索、音乐搜索、应用商店搜索等场景。
metadata:
  {
    "xomniclaw": {
      "always": false,
      "emoji": "🔍",
      "version": "1.0.0",
      "category": "search"
    }
  }
---

# App Search Tool

通过各主流 APP 的 Deep Link 直达搜索页面，浏览结果并为用户提供有价值的总结。

## 🎯 When to Use

Use this skill when user asks about:

### 电商购物
✅ **拼多多搜索** - "拼多多搜一下纸巾"、"拼多多有什么便宜的耳机"

### 生活服务
✅ **美团搜索** - "美团上搜搜附近火锅店"、"帮我在美团找酒店"
✅ **高德地图** - "高德上搜一下加油站"、"帮我找附近停车场"

### 短视频 / 内容
✅ **抖音搜索** - "抖音搜一下健身教程"、"在抖音看看旅行攻略"
✅ **快手搜索** - "快手搜一下美食做法"、"快手上找一下钓鱼视频"
✅ **小红书搜索** - "小红书搜一下穿搭"、"帮我在小红书找护肤攻略"
✅ **哔哩哔哩搜索** - "B站搜一下编程教程"、"在B站看看数码评测"

### 知识 / 搜索
✅ **知乎搜索** - "知乎上搜一下如何学Python"、"帮我在知乎找一下理财建议"
✅ **百度搜索** - "百度搜一下天气预报"、"帮我百度一下这个问题"

### 音乐
✅ **OPPO音乐搜索** - "OPPO音乐搜周杰伦"、"帮我在OPPO音乐搜一下晴天"

### 应用商店
✅ **OPPO软件商店搜索** - "OPPO软件商店搜红果短剧"、"帮我在软件商店找抖音"

## 🛑 工具选择（必读，最易踩坑）

跳转 APP 搜索页**只能且必须**调用 `device(action="open", uri="...")`：

| 操作 | ✅ 必须使用 | ❌ 绝对禁止 |
|---|---|---|
| 跳转目标 APP 搜索页 | `device(action="open", uri="<deep_link>")` | `start_activity(uri=...)` / `start_activity(package=..., activity=...)` / `start_activity(component=...)` |

> **为什么禁用 `start_activity`**：第三方 APP（小红书/抖音/拼多多/B 站/快手/知乎等）的 activity 几乎全部不允许从其他进程显式启动。无论你尝试 `MainActivity` / `IndexSearchV2Activity` / `.search.SearchActivity` 等何种 component 或 package+activity 组合，**返回值 100% 都是 `Unable to find explicit activity class`**，会浪费 10+ 轮迭代仍无法成功。
>
> **正确路径**：路由表里的 deep link（`xhsdiscover://`、`snssdk1128://`、`pinduoduo://` 等）会经由 `ACTION_VIEW` Intent 被系统自动路由到对应 APP，这正是 `device(action="open", uri=...)` 内部的工作；**你完全不需要也不应该指定 activity 名**。
>
> **think → action 一致性**：如果你在 `<think>` 里写过"使用 `device(action="open")`"，function call 时务必真的调用 `device`，不要切换到 `start_activity`——这是弱模型最常见的失败模式。读到本段时再次自检：本轮要调用的工具名是不是 `device`？

## 📋 Workflow

### 标准搜索流程

> 🚫 **禁令**：调用 `device(action="open", ...)` 时**只传 `action` 和 `uri` 两个参数**，**严禁传 `package_name`**。系统根据 URI scheme 自动解析目标 APP，手动指定 `package_name` 会导致 ActivityNotFound。
>
> ⚡ **唯一例外 — 百度APP**：`baiduboxapp://` scheme 实测无法触发搜索，必须使用 HTTPS 链接并指定 `package_name`。
>
> ✅ `device(action="open", uri="imeituan://www.meituan.com/search?q=火锅")`
> ❌ `device(action="open", package_name="com.meituan.android.fe", uri="imeituan://www.meituan.com/search?q=火锅")`
> ✅ `device(action="open", uri="https://m.baidu.com/s?word=天气预报", package_name="com.baidu.searchbox")` ← 百度例外
> ❌ `device(action="open", uri="baiduboxapp://search?keyword=天气预报")` ← 实测不可用

```
1. 解析用户意图，识别目标 APP 和搜索关键词
2. 根据 APP 路由表构造对应的 Deep Link（必须从路由表原样复制模板，只替换 {keyword}）
3. 通过 device(action="open", uri="...") 跳转目标 APP 搜索页（禁传 package_name）
4. 等待 APP 加载搜索结果：device(action="act", kind="wait", timeMs=1000)
5. 拉取页面 UI 树：device(action="snapshot")
6. 从 snapshot 返回的 text 节点中提取结果中的具体信息
7. 基于提取到的具体信息回答用户（200字以内，必须包含真实数字和细节）
```

## 🗺️ APP 路由表

### 电商类

| APP | Deep Link 模板 | Scheme |
|------|----------------|--------|
| 拼多多 | `pinduoduo://com.xunmeng.pinduoduo/search_result.html?search_key={keyword}` | `pinduoduo` |

### 生活服务类

| APP | Deep Link 模板 | Scheme |
|------|----------------|--------|
| 美团（全局搜索） | `imeituan://www.meituan.com/search?q={keyword}` | `imeituan` |
| 美团（酒店搜索） | `imeituan://www.meituan.com/hotel/search?q={keyword}` | `imeituan` |
| 高德地图 | `androidamap://poi?keywords={keyword}` | `androidamap` |

> ⚠️ **美团特别注意**：scheme 是 **`imeituan`**（带 `i` 前缀），不是 ~~`meituan`~~。路径是 `/search?q=`，不是 ~~`/web/?keyword=`~~。错误写法 `meituan://www.meituan.com/web/?keyword=xxx` 会导致 ActivityNotFound。

### 短视频 / 内容类

| APP | Deep Link 模板 | Scheme |
|------|----------------|--------|
| 抖音 | `snssdk1128://search?keyword={keyword}` | `snssdk1128` |
| 快手 | `kwai://search?keyword={keyword}` | `kwai` |
| 小红书 | `xhsdiscover://search/result?keyword={keyword}` | `xhsdiscover` |
| 哔哩哔哩 | `bilibili://search?keyword={keyword}` | `bilibili` |

> ⚠️ **小红书特别注意**：scheme 是 **`xhsdiscover`**，不是 ~~`xhslink`~~、~~`xhs`~~、~~`xiaohongshu`~~。路径是 **`/search/result`**（不是 ~~`/search`~~），参数名是 **`keyword`**。正确写法 `xhsdiscover://search/result?keyword=xxx`。常见错误：scheme 写成 ~~`xhslink://search?keyword=xxx`~~ 会导致 ActivityNotFound。

### 知识 / 搜索类

| APP | Deep Link 模板 | Scheme |
|------|----------------|--------|
| 知乎 | `zhihu://search?q={keyword}` | `zhihu` |
| 百度APP | `https://m.baidu.com/s?word={keyword}`（需指定包名 `com.baidu.searchbox`） | `https` |

> ⚠️ **百度APP特别注意**：`baiduboxapp://search?keyword=xxx` Deep Link **实测不可用**（会停留在桌面，不触发搜索）。必须使用 HTTPS 链接 `https://m.baidu.com/s?word=xxx` 并**显式指定包名** `com.baidu.searchbox`，即 `device(action="open", uri="https://m.baidu.com/s?word=xxx", package_name="com.baidu.searchbox")`。百度是唯一需要传 `package_name` 的例外。

### 音乐类

| APP | Deep Link 模板 | Scheme | 包名 |
|------|----------------|--------|------|
| OPPO音乐 | `allmusic://search/keyword/{keyword}` | `allmusic` | `com.heytap.music` |

> ⚠️ **OPPO音乐特别注意**：路径格式是 `/search/keyword/{关键词}`，关键词直接拼接在路径中。正确写法：`allmusic://search/keyword/周杰伦晴天`。

### 应用商店类

| APP | Deep Link 模板 | Scheme | 包名 |
|------|----------------|--------|------|
| OPPO软件商店 | `market://search?q={keyword}` | `market` | `com.heytap.market`（Android 10+）/ `com.oppo.market`（Android 9及以下） |

> ⚠️ **OPPO软件商店特别注意**：**严禁传 `package_name`**，系统会自动根据 `market://` scheme 路由到已安装的应用商店。正确写法：`market://search?q=红果短剧`。

## 🔗 Deep Link 构造规则

关键词用 `+` 连接多个搜索词。

### 通用 APP 示例

| APP | 用户意图 | 搜索关键词 | Deep Link |
|-----|---------|-----------|-----------|
| 拼多多 | 拼多多搜纸巾 | 纸巾 | `pinduoduo://com.xunmeng.pinduoduo/search_result.html?search_key=纸巾` |
| 美团 | 美团搜火锅 | 火锅 | `imeituan://www.meituan.com/search?q=火锅` |
| 高德 | 高德搜加油站 | 加油站 | `androidamap://poi?keywords=加油站` |
| 抖音 | 抖音搜健身教程 | 健身教程 | `snssdk1128://search?keyword=健身教程` |
| 快手 | 快手搜美食做法 | 美食做法 | `kwai://search?keyword=美食做法` |
| 小红书 | 小红书搜穿搭 | 穿搭 | `xhsdiscover://search/result?keyword=穿搭` |
| B站 | B站搜编程教程 | 编程教程 | `bilibili://search?keyword=编程教程` |
| 知乎 | 知乎搜理财建议 | 理财建议 | `zhihu://search?q=理财建议` |
| 百度 | 百度搜天气预报 | 天气预报 | `https://m.baidu.com/s?word=天气预报`（需指定 `package_name="com.baidu.searchbox"`） |
| OPPO音乐 | OPPO音乐搜周杰伦晴天 | 周杰伦晴天 | `allmusic://search/keyword/周杰伦晴天` |
| OPPO软件商店 | OPPO软件商店搜红果短剧 | 红果短剧 | `market://search?q=红果短剧` |

## 🚀 X-OmniClaw Implementation

**Tool**: `device` (DeviceTool — 统一设备控制工具)

⚠️ **重要**: 不要使用 shell `am start` 命令跳转 Deep Link（会因 SecurityException 权限拒绝），必须使用 `device(action="open", uri="...")` 通过 X-OmniClaw 应用上下文发起 `ACTION_VIEW` Intent。

### Step 1: 识别目标 APP 并构造 Deep Link

```kotlin
// 根据用户意图识别 APP 和关键词
val app = "douyin"  // 从用户查询中识别目标APP
val keyword = "健身+教程"

// APP → Deep Link 映射
val deepLink = when (app) {
    // 电商类
    "pdd", "拼多多" ->
        "pinduoduo://com.xunmeng.pinduoduo/search_result.html?search_key=$keyword"

    // 生活服务类
    "meituan", "美团" ->
        "imeituan://www.meituan.com/search?q=$keyword"
    "meituan_hotel", "美团酒店" ->
        "imeituan://www.meituan.com/hotel/search?q=$keyword"
    "amap", "高德", "高德地图" ->
        "androidamap://poi?keywords=$keyword"

    // 短视频/内容类
    "douyin", "抖音" ->
        "snssdk1128://search?keyword=$keyword"
    "kuaishou", "快手" ->
        "kwai://search?keyword=$keyword"
    "xiaohongshu", "小红书" ->
        "xhsdiscover://search/result?keyword=$keyword"
    "bilibili", "B站", "哔哩哔哩" ->
        "bilibili://search?keyword=$keyword"

    // 知识/搜索类
    "zhihu", "知乎" ->
        "zhihu://search?q=$keyword"
    // ⚠️ 百度是例外：baiduboxapp:// scheme 实测不可用，必须用 HTTPS + 指定包名
    "baidu", "百度" ->
        "https://m.baidu.com/s?word=$keyword"  // 需额外传 package_name="com.baidu.searchbox"

    // 音乐类
    "oppomusic", "OPPO音乐", "oppo音乐" ->
        "allmusic://search/keyword/$keyword"

    // 应用商店类
    "oppostore", "OPPO软件商店", "oppo软件商店", "软件商店" ->
        "market://search?q=$keyword"

    else -> throw IllegalArgumentException("不支持的 APP: $app")
}

// 百度需要额外指定包名，其他 APP 严禁传 package_name
if (app in listOf("baidu", "百度")) {
    device(action = "open", uri = deepLink, package_name = "com.baidu.searchbox")
} else {
    device(action = "open", uri = deepLink)
}
```

### Step 2: 等待页面加载并确认

```kotlin
device(action = "act", kind = "wait", timeMs = 1000)
device(action = "snapshot")
```

### Step 3: 分析 snapshot 内容（关键步骤）

**你必须仔细阅读每一份 snapshot 返回的 text 节点**，根据不同 APP 类型提取对应信息：

#### 电商类（拼多多）
- **商品完整名称**（含品牌、系列、型号）
- **价格**（精确到元，如 ¥599、¥1299）
- **店铺名称**（如 "XX旗舰店"）
- **销量/评价数**（如 "10万+评价"、"已拼5万件"）
- **标签**（如 "百亿补贴"、"包邮"）

#### 生活服务类（美团、高德地图）
- **店铺/地点名称**
- **评分**（如 4.8分）
- **人均价格**（如 ¥89/人）
- **距离**（如 1.2km）
- **地址**
- **营业状态**（营业中/已打烊）
- **标签**（如 "必吃榜"、"黑珍珠"）

#### 短视频/内容类（抖音、快手、小红书、哔哩哔哩）
- **视频/笔记标题**
- **作者/UP主名称**
- **播放量/点赞数**（如 "100万播放"、"5.2万赞"）
- **发布时间**
- **内容简介/标签**

#### 知识/搜索类（知乎、百度）
- **问题/文章标题**
- **回答者/作者**
- **赞同数/阅读量**
- **摘要内容**

#### 音乐类（OPPO音乐）
- **歌曲名称**
- **歌手/艺人**
- **专辑名称**
- **发行年份**
- **歌曲时长**
- **播放量/热度**（如有显示）

#### 应用商店类（OPPO软件商店）
- **应用名称**
- **应用图标描述**
- **开发者/公司**
- **评分**（如 4.8分）
- **下载量**（如 "1亿次下载"）
- **应用大小**
- **应用简介/标签**
- **是否为官方/认证应用**

### Step 4: 基于具体信息回答用户

⛔ **严禁笼统回复**。以下是禁止出现的回复方式：

```
❌ "搜索结果展示了多个相关内容，你可以看看"
❌ "你可以在当前页面继续上下滑动查看更多"
❌ "点击感兴趣的内容查看详情"
❌ "包含不同类型和价位，可以根据你的喜好选择"
```

✅ **必须包含具体数字和细节**，回复格式根据 APP 类型调整：

**电商类回复模板**：

```
为您在{APP名}搜索了"{搜索词}"，挑选了几款值得关注的：

1. {完整商品名} - ¥{价格}，{店铺名}，{销量/评价}
2. {完整商品名} - ¥{价格}，{店铺名}，{销量/评价}
3. {完整商品名} - ¥{价格}，{店铺名}，{销量/评价}

价格区间¥{最低}-¥{最高}。{1-2句具体购物建议}。需要我点开某款看详情吗？
```

**生活服务类回复模板**：

```
为您在{APP名}搜索了"{搜索词}"，推荐几家：

1. {店名} - {评分}分，人均¥{价格}，距{距离}，{标签}
2. {店名} - {评分}分，人均¥{价格}，距{距离}，{标签}
3. {店名} - {评分}分，人均¥{价格}，距{距离}，{标签}

{1-2句推荐理由}。需要我查看某家的详情或帮你导航吗？
```

**内容类回复模板**：

```
为您在{APP名}搜索了"{搜索词}"，推荐几个：

1. 「{标题}」- @{作者}，{播放量/点赞}，{简要描述}
2. 「{标题}」- @{作者}，{播放量/点赞}，{简要描述}
3. 「{标题}」- @{作者}，{播放量/点赞}，{简要描述}

{1-2句推荐理由}。需要我打开某个看看吗？
```

**音乐类回复模板**：

```
为您在{APP名}搜索了"{搜索词}"，找到以下结果：

1. 「{歌曲名}」- {歌手}，专辑《{专辑名}》，{发行年份}
2. 「{歌曲名}」- {歌手}，专辑《{专辑名}》，{发行年份}
3. 「{歌曲名}」- {歌手}，专辑《{专辑名}》，{发行年份}

{1-2句歌曲介绍或推荐理由}。需要我播放哪首吗？
```

**应用商店类回复模板**：

```
为您在{APP名}搜索了"{搜索词}"，找到以下应用：

1. 「{应用名}」- {开发者}，{评分}分，{下载量}，{应用大小}
2. 「{应用名}」- {开发者}，{评分}分，{下载量}，{应用大小}
3. 「{应用名}」- {开发者}，{评分}分，{下载量}，{应用大小}

{1-2句应用介绍或推荐理由}。需要我帮你下载哪个吗？
```

## 💡 Complete Examples

### 示例1：拼多多搜索

**用户**: "拼多多搜一下纸巾"

```kotlin
val keyword = "纸巾"
device(action = "open", uri = "pinduoduo://com.xunmeng.pinduoduo/search_result.html?search_key=$keyword")
device(action = "act", kind = "wait", timeMs = 1000)
device(action = "snapshot")
```

**回复示例**：

```
为您在拼多多搜索了"纸巾"，挑选了几款值得关注的：

1. 植护原木抽纸40包整箱 - ¥16.9，植护旗舰店，已拼100万+件，百亿补贴
2. 洁柔抽纸Face系列3层120抽30包 - ¥39.9，洁柔官方旗舰店，已拼50万+件
3. 维达超韧抽纸24包 - ¥29.9，维达官方旗舰店，已拼80万+件，包邮

价格区间¥16.9-¥39.9。植护性价比最高适合囤货，洁柔Face系列手感更好，维达超韧兼顾品质和价格。需要我点开某款看详情吗？
```

### 示例2：抖音搜索

**用户**: "抖音搜一下健身教程"

```kotlin
val keyword = "健身教程"
device(action = "open", uri = "snssdk1128://search?keyword=$keyword")
device(action = "act", kind = "wait", timeMs = 1000)
device(action = "snapshot")
```

**回复示例**：

```
为您在抖音搜索了"健身教程"，推荐几个热门视频：

1. 「新手全身燃脂30分钟跟练」- @帕梅拉PamelaReif，320万播放，适合零基础
2. 「男生增肌必练的5个动作」- @刘畊宏，180万播放，哑铃居家训练
3. 「每天15分钟马甲线养成计划」- @周六野Zoey，250万播放，28天打卡挑战

这几位都是健身领域头部博主，内容专业。帕梅拉适合跟练燃脂，刘畊宏侧重力量，周六野适合女生塑形。需要我打开某个视频吗？
```

### 示例3：美团搜索

**用户**: "美团搜搜附近火锅店"

```kotlin
val keyword = "火锅"
device(action = "open", uri = "imeituan://www.meituan.com/search?q=$keyword")
device(action = "act", kind = "wait", timeMs = 1000)
device(action = "snapshot")
```

**回复示例**：

```
为您在美团搜索了"火锅"，推荐几家：

1. 海底捞火锅(万象城店) - 4.8分，人均¥128，距1.2km，必吃榜上榜
2. 巴奴毛肚火锅(CBD店) - 4.7分，人均¥145，距0.8km，招牌毛肚
3. 楠火锅(太古里店) - 4.6分，人均¥95，距2.1km，排队王性价比高

海底捞服务好适合聚餐，巴奴毛肚出名口味正宗，楠火锅性价比最高。需要我查看某家的详情或团购券吗？
```

### 示例4：小红书搜索

**用户**: "小红书搜一下防晒霜推荐"

```kotlin
val keyword = "防晒霜+推荐"
device(action = "open", uri = "xhsdiscover://search/result?keyword=$keyword")
device(action = "act", kind = "wait", timeMs = 1000)
device(action = "snapshot")
```

**回复示例**：

```
为您在小红书搜索了"防晒霜推荐"，精选几篇高赞攻略：

1. 「2026防晒红黑榜！测了30款终于找到真命」- @成分党阿梨，5.2万赞，干货测评帖
2. 「油皮亲妈防晒！上脸不搓泥不闷痘」- @皮肤科林医生，3.8万赞，医生推荐
3. 「通勤防晒天花板，平价替代安耐晒」- @一只梨花，2.1万赞，¥59平价款实测

高赞笔记普遍推荐资生堂蓝胖子和薇诺娜清透防晒，油皮偏爱理肤泉大哥大。需要我打开某篇笔记看看具体产品清单吗？
```

### 示例5：OPPO音乐搜索

**用户**: "OPPO音乐搜一下周杰伦晴天"

```kotlin
val keyword = "周杰伦晴天"
device(action = "open", uri = "allmusic://search/keyword/$keyword")
device(action = "act", kind = "wait", timeMs = 1000)
device(action = "snapshot")
```

**回复示例**：

```
为您在OPPO音乐搜索了"周杰伦晴天"，找到以下结果：

1. 「晴天」- 周杰伦，专辑《叶惠美》，2003年发行，经典华语流行
2. 「晴天 (Live版)」- 周杰伦，2019演唱会录音，现场气氛超燃
3. 「晴天 (钢琴版)」- 纯音乐翻奏，适合学习/放松时听

原版《晴天》是周杰伦最经典的情歌之一，方文山作词。需要我播放哪首吗？
```

### 示例6：OPPO软件商店搜索

**用户**: "OPPO软件商店搜一下红果短剧"

```kotlin
val keyword = "红果短剧"
device(action = "open", uri = "market://search?q=$keyword")
device(action = "act", kind = "wait", timeMs = 1000)
device(action = "snapshot")
```

**回复示例**：

```
为您在OPPO软件商店搜索了"红果短剧"，找到以下应用：

1. 「红果短剧」- 北京红果视界科技有限公司，4.8分，1亿+下载，68MB，官方认证
2. 「红果免费短剧」- 红果科技，4.5分，5000万+下载，55MB，海量短剧免费看
3. 「红果剧场」- 红果传媒，4.3分，1000万+下载，42MB，精选热门短剧

第一个是官方正版应用，下载量最高且评分最好。需要我帮你下载吗？
```

## 🔍 APP 识别策略

从用户查询中识别目标 APP：

| 用户关键词 | 识别为 |
|-----------|--------|
| 拼多多、PDD、拼多 | 拼多多 |
| 美团、美团外卖 | 美团 |
| 美团酒店 | 美团（酒店搜索） |
| 高德、高德地图、导航搜 | 高德地图 |
| 抖音、TikTok | 抖音 |
| 快手 | 快手 |
| 小红书、红书 | 小红书 |
| B站、哔哩哔哩、bilibili | 哔哩哔哩 |
| 知乎 | 知乎 |
| 百度、百度一下 | 百度APP |
| OPPO音乐、oppo音乐 | OPPO音乐 |
| OPPO软件商店、oppo软件商店、软件商店、应用商店 | OPPO软件商店 |

## 🔍 关键词提取策略

从用户查询中提取搜索关键词时：

```kotlin
// 品牌 + 时间 + 品类 组合（电商/旅行场景）
val brand = "罗技"
val year = "2026"
val category = "机械键盘"
val keyword = "$brand+$year+$category"

// 地点 + 品类 组合（生活服务/地图场景）
val location = "三亚"
val type = "酒店"
val keyword = "$location+$type"

// 直接关键词（内容/知识场景）
val keyword = "健身教程"
```

**时间词映射**：

| 用户表述 | 转换为 |
|---------|-------|
| 今年 | 2026 |
| 新款/最新 | 2026+新款 |
| 去年 | 2025 |

**美团场景路由**：

| 用户意图 | 路由 |
|---------|------|
| 搜索餐饮/娱乐/通用 | 美团全局搜索 |
| 明确提到酒店/住宿 | 美团酒店搜索 |


## ⚠️ 注意事项

- **必须使用 device tool**: 通过 `device(action="open", uri="...")` 跳转，不要用 shell `am start`（会报 SecurityException）。**严禁传 `package_name` 参数**——系统会根据 URI scheme 自动解析目标 APP，手动指定 package_name 会导致 ActivityNotFound。**唯一例外是百度APP**：因其 `baiduboxapp://` scheme 实测不可用，必须使用 HTTPS 链接 + `package_name="com.baidu.searchbox"`
- **严格按路由表复制 URI**: 不要凭记忆拼写 Deep Link，必须从上方路由表中原样复制模板，只替换 `{keyword}` 部分。常见错误：美团 scheme 写成 `meituan://`（正确的是 `imeituan://`）、路径写成 `/web/?keyword=`（正确的是 `/search?q=`）
- **APP 必须已安装**: Deep Link 需要对应 APP，若未安装会返回 ActivityNotFoundException
- **登录状态**: 部分搜索结果需要登录账号才能完整展示
- **网络依赖**: 需要联网才能加载搜索结果
- **加载时间**: 网络较慢时适当增加 `wait` 的 timeMs
- **回复字数**: 字数控制在 200 字以内，但必须包含从 snapshot 中读取的具体信息
- **严禁套话**: 绝对不能回复"包含不同类型""你可以继续浏览"这类笼统废话。如果 snapshot 没拿到足够文字信息，应如实说明并再 wait 后重新 snapshot

## 🔄 Error Handling

### APP 未安装

```kotlin
// device(action="open", uri="...") 会返回 ActivityNotFoundException 错误
// 向用户说明需要安装对应 APP
// 部分 APP 可降级到网页版：
// 注意：百度主链接已经是 HTTPS，不受此 fallback 影响
val webFallback = mapOf(
    "拼多多" to "https://mobile.yangkeduo.com/search_result.html?search_key=$keyword",
    "知乎" to "https://www.zhihu.com/search?type=content&q=$keyword",
    "哔哩哔哩" to "https://search.bilibili.com/all?keyword=$keyword",
    "百度" to "https://www.baidu.com/s?wd=$keyword"
)
device(action = "open", uri = webFallback[app] ?: "https://www.baidu.com/s?wd=$keyword")
```

### 搜索无结果

```kotlin
// 尝试简化关键词，去掉年份、限定词
val simplifiedKeyword = "机械键盘"  // 去掉品牌等限定词
// 用简化关键词重新搜索
```

### 页面加载超时

```kotlin
device(action = "act", kind = "wait", timeMs = 1000)
device(action = "snapshot")
// 如果仍未加载，提示用户检查网络
```

### 美团 Scheme 错误

```kotlin
// ❌ 错误示例（scheme 少了 i 前缀，路径也不对）
device(action = "open", uri = "meituan://www.meituan.com/web/?keyword=$keyword")

// ✅ 正确写法
device(action = "open", uri = "imeituan://www.meituan.com/search?q=$keyword")
```

### 百度APP baiduboxapp:// Scheme 不可用

```kotlin
// ❌ baiduboxapp:// scheme 实测无法触发搜索（停留在桌面）
device(action = "open", uri = "baiduboxapp://search?keyword=$keyword")

// ❌ utils 路径同样不可用
device(action = "open", uri = "baiduboxapp://utils/search?keyword=$keyword")

// ✅ 正确写法：使用 HTTPS 链接 + 显式指定包名
device(action = "open", uri = "https://m.baidu.com/s?word=$keyword", package_name = "com.baidu.searchbox")
```


## 📱 Supported Queries

| APP | 查询类型 | 示例 | 搜索关键词 |
|-----|---------|------|-----------|
| 拼多多 | 低价搜索 | "拼多多搜便宜耳机" | 耳机 |
| 美团 | 美食搜索 | "美团搜火锅" | 火锅 |
| 美团 | 酒店搜索 | "美团搜酒店" | 酒店 |
| 高德 | POI搜索 | "高德搜加油站" | 加油站 |
| 抖音 | 视频搜索 | "抖音搜健身" | 健身 |
| 快手 | 视频搜索 | "快手搜美食做法" | 美食做法 |
| 小红书 | 笔记搜索 | "小红书搜穿搭" | 穿搭 |
| B站 | 视频搜索 | "B站搜编程教程" | 编程教程 |
| 知乎 | 问答搜索 | "知乎搜如何学Python" | 如何学Python |
| 百度 | 通用搜索 | "百度搜天气预报" | 天气预报 |
| OPPO音乐 | 音乐搜索 | "OPPO音乐搜周杰伦晴天" | 周杰伦晴天 |
| OPPO软件商店 | 应用搜索 | "软件商店搜红果短剧" | 红果短剧 |
