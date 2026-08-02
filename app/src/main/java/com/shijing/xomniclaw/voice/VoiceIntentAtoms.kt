package com.shijing.xomniclaw.voice

/**
 * 语音意图判定用的「原子词库」：每个词只维护一份，通过集合运算组合出
 * 搜索 / 多步 / 编排 / 答题 等语义，避免在多个 List 里重复同一关键词。
 */
internal object VoiceIntentAtoms {

    /** 明确偏「检索、浏览、查参数」类表述（同时用于 searchIntent）。 */
    val SEARCH: Set<String> = setOf(
        "搜索", "搜一下", "搜下", "搜", "查找", "查一下", "查下", "查", "找一下", "找下",
        "看看", "检索", "配置", "参数", "攻略", "型号", "info", "search",
    )

    /** 多步意图里除 [SEARCH] 外的动词与场景词（与 SEARCH 无交集，减少重复维护）。 */
    val MULTI_NON_SEARCH: Set<String> = setOf(
        "总结", "发送", "转发", "截图", "保存", "复制", "查看", "浏览",
        "帮我", "告诉我", "分析", "对比", "评论", "点赞",
        "关注", "下单", "购买", "配置信息",
    )

    /** 多任务连接词（与原 MULTI_STEP_HINTS 中序列触发一致）。 */
    val SEQUENCE_MARKERS: Set<String> = setOf("然后", "接着", "之后", "并且", "同时", "再")

    /** 多步判定：检索类 ∪ 动词 ∪ 序列词（等价于原 MULTI_STEP_HINTS 去重并集）。 */
    val MULTI_STEP_ANY: Set<String> = SEARCH + MULTI_NON_SEARCH + SEQUENCE_MARKERS

    /**
     * 在「多步」之外、仍希望交给主 Agent 编排的补充词（问卷/步骤类口语等）。
     * 与 [QUIZ_SURFACE] 有交集时，以 [QUIZ_SURFACE] 专用于答题语义判断。
     */
    val ORCHESTRATION_EXTRA: Set<String> = setOf(
        "题", "题目", "问卷", "单选", "多选", "作答", "答题", "选项", "全选",
        "下一题", "下一步", "全部", "逐步", "一步一步", "帮我做", "帮我点", "帮我完成",
        "解答", "做完", "做到", "依次", "每道", "每题", "几道",
    )

    /** 用于「是否像答题/问卷」的窄集合（可从 [ORCHESTRATION_EXTRA] 语义上取出问卷相关子集）。 */
    val QUIZ_SURFACE: Set<String> = setOf(
        "题", "题目", "问卷", "单选", "多选", "作答", "答题", "选项", "全选",
        "下一题", "每题", "每道",
    )

    fun matchAny(text: String, atoms: Set<String>): Boolean =
        atoms.any { text.contains(it, ignoreCase = true) }

    /**
     * 用户话术是否「明显依赖当前屏幕/画面」——仅在此类场景向主对话插入「思考对齐截图」气泡。
     * 避免纯语音闲聊、日期常识等轮次附带与问题无关的界面帧，造成干扰。
     *
     * @param configuredAppDisplayNames [VoiceAppBootstrapConfig] 里配置的 App 中文名等，用于「在某 App 内搜索」类语句。
     */
    fun impliesAlignedScreenshotDisclosureForChat(
        userInput: String,
        configuredAppDisplayNames: Set<String>,
    ): Boolean {
        val t = userInput.trim()
        if (t.isBlank()) return false

        if (matchAny(t, SCREEN_REFERENCE_FOR_CHAT_BUBBLE)) return true
        // 避免使用含单字「题」的集合，防止「问题」「话题」等误判为答题场景。
        if (matchAny(t, QUIZ_VISIBLE_ROUND_FOR_CHAT_BUBBLE)) return true

        // 显式检索/浏览意图且点名 App（含常见电商/地图名），通常需要对照当前界面。
        if (matchAny(t, SEARCH)) {
            val appHints = configuredAppDisplayNames + COMMON_IN_APP_SCREEN_QUERY_MARKERS
            if (appHints.any { marker -> t.contains(marker) }) return true
        }

        return false
    }

    /** 明确指向画面、控件或相册/摄像头的表述。 */
    private val SCREEN_REFERENCE_FOR_CHAT_BUBBLE: Set<String> = setOf(
        "屏幕", "界面", "页面", "图上", "图片", "照片", "截图", "截屏", "画面",
        "看见", "看到", "看这个", "看一下", "帮我看", "这是什么", "什么意思",
        "上面显示", "下面显示", "显示的字", "写着",
        "这个软件", "这个应用", "这个app",
        "游戏里", "视频里", "相册", "相机", "摄像头",
        "点哪里", "点哪个", "按钮", "菜单", "开关",
        "screenshot", "screen",
    )

    /**
     * 答题/问卷类可见语义（不含单字「题」，以免命中「问题」等泛化词）。
     */
    private val QUIZ_VISIBLE_ROUND_FOR_CHAT_BUBBLE: Set<String> = setOf(
        "题目", "问卷", "单选", "多选", "作答", "答题", "选项", "全选",
        "下一题", "每题", "每道", "这道题", "那道题", "填空",
    )

    /** 未写入 bootstrap 时仍可能口头点名的、强依赖界面检索的应用简称。 */
    private val COMMON_IN_APP_SCREEN_QUERY_MARKERS: Set<String> = setOf(
        "淘宝", "拼多多", "美团", "高德", "抖音", "快手", "京东", "饿了么", "小红书",
    )

    private val WEATHER_QUERY_MARKERS: List<String> = listOf(
        "天气怎么样", "天气如何", "今天天气", "现在天气", "外面天气", "本地天气",
        "会下雨吗", "下不下雨", "会不会下雨", "气温", "多少度", "冷不冷", "热不热",
        "空气质量", "雾霾", "紫外线",
    )

    /**
     * 是否像「查天气」类单轮问句（无实时气象 API 时交给主 Agent 在本机打开天气应用）。
     * 多步复合句（含「然后」「搜索…再」等）不命中，避免抢多步流程。
     */
    fun looksLikeWeatherQuery(text: String): Boolean {
        val t = text.trim()
        if (t.length < 3) return false
        if (WEATHER_QUERY_MARKERS.any { t.contains(it) }) return true
        return t.contains("天气") && (t.contains("怎么样") || t.contains("如何"))
    }
}
