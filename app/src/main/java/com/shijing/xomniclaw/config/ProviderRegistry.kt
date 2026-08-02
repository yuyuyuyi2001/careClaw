package com.shijing.xomniclaw.config

/**
 * OmniClaw Source Reference:
 * - ../omniclaw/src/agents/(all)
 * - ../omniclaw/docs/providers/openai.md
 *
 * OmniClaw adaptation: provider catalog and model defaults.
 */


/**
 * OmniClaw Provider Registry
 *
 * 所有 Provider 定义严格对齐 OmniClaw 源码：
 * - Base URLs: auth-profiles-UpqQjKB-.js (constants)
 * - API types: types.models.d.ts (MODEL_APIS)
 * - Env var names: PROVIDER_ENV_API_KEY_CANDIDATES
 * - Provider IDs: normalizeProviderId()
 * - Model catalogs: build*Provider() functions
 *
 * OmniClaw 版本: 2026.3.8 (3caab92)
 */

/**
 * Provider 定义 — 用于 UI 展示和配置生成
 */
data class ProviderDefinition(
    /** Provider ID，对应 xomniclaw.json 中 models.providers 的 key */
    val id: String,
    /** 显示名称 */
    val name: String,
    /** 简短描述 */
    val description: String,
    /** 默认 Base URL（从 OmniClaw 源码常量提取） */
    val baseUrl: String,
    /** 默认 API 类型 */
    val api: String,
    /** 是否必须填写 API Key */
    val keyRequired: Boolean,
    /** API Key 输入框的 hint */
    val keyHint: String,
    /** 对应的环境变量名（OmniClaw PROVIDER_ENV_API_KEY_CANDIDATES） */
    val envVarName: String,
    /** authHeader 默认值。Anthropic 为 false（用 x-api-key header） */
    val authHeader: Boolean = true,
    /** 自定义请求头（如 Kimi Coding 的 User-Agent） */
    val headers: Map<String, String>? = null,
    /** 获取 Key 的教程步骤 */
    val tutorialSteps: List<String> = emptyList(),
    /** 获取 Key 的 URL */
    val tutorialUrl: String = "",
    /** 预置模型列表 */
    val presetModels: List<PresetModel> = emptyList(),
    /** 分组：primary / more */
    val group: ProviderGroup = ProviderGroup.PRIMARY,
    /** 排序权重，越小越靠前 */
    val order: Int = 100
)

/**
 * 预置模型
 */
data class PresetModel(
    /** 模型 ID（不含 provider 前缀） */
    val id: String,
    /** 显示名 */
    val name: String,
    /** 是否免费 */
    val free: Boolean = false,
    /** 上下文窗口大小 */
    val contextWindow: Int = 128000,
    /** 最大输出 tokens */
    val maxTokens: Int = 8192,
    /** 是否支持推理 */
    val reasoning: Boolean = false,
    /** 输入类型 */
    val input: List<String> = listOf("text")
)

/**
 * Provider 分组
 */
enum class ProviderGroup {
    /** 主要 Provider，列表中靠前 */
    PRIMARY,
    /** 其余标准 Provider，与 PRIMARY 在模型配置页合并展示 */
    MORE,
}

/**
 * Provider 注册表
 *
 * 所有 Base URL 和 API 类型均从 OmniClaw 源码中提取，保持严格一致。
 */
object ProviderRegistry {

    // ========== Base URL Constants (from OmniClaw auth-profiles-UpqQjKB-.js) ==========

    /** OmniClaw auth-profiles-UpqQjKB-.js:2533 */
    private const val OPENROUTER_BASE_URL = "https://openrouter.ai/api/v1"

    /** OmniClaw compact-D3emcZgv.js:2314 */
    private const val OPENAI_BASE_URL = "https://api.openai.com/v1"

    /** OmniClaw compact-D3emcZgv.js:50101 — Anthropic 不带 /v1（SDK 自动追加） */
    private const val ANTHROPIC_BASE_URL = "https://api.anthropic.com"

    /** OmniClaw auth-profiles-UpqQjKB-.js:2504 */
    private const val MOONSHOT_BASE_URL = "https://api.moonshot.ai/v1"

    /** OmniClaw auth-profiles-UpqQjKB-.js:1808 + 734 */
    private const val OLLAMA_BASE_URL = "http://127.0.0.1:11434"

    /** OmniClaw auth-profiles-UpqQjKB-.js:2145 */
    private const val SYNTHETIC_BASE_URL = "https://api.synthetic.new/anthropic"

    /** OmniClaw auth-profiles-UpqQjKB-.js:1115 */
    private const val VENICE_BASE_URL = "https://api.venice.ai/api/v1"

    /** OmniClaw auth-profiles-UpqQjKB-.js:2514 */
    private const val KIMI_CODING_BASE_URL = "https://api.kimi.com/coding/"

    /** OmniClaw auth-profiles-UpqQjKB-.js:2058 */
    private const val BYTEPLUS_BASE_URL = "https://ark.ap-southeast.bytepluses.com/api/v3"

    /** MiniMax OpenAI-compatible API */
    private const val MINIMAX_BASE_URL = "https://api.minimaxi.com/v1"
    /** mimimax.cn 中转图像理解接口（/v1/images/understand） */
    private const val MIMIMAX_RELAY_BASE_URL = "https://mimimax.cn/v1"

    // ========== Provider Definitions ==========

    val OPENROUTER = ProviderDefinition(
        id = "openrouter",
        name = "OpenRouter",
        description = "聚合平台，免费+付费模型",
        baseUrl = OPENROUTER_BASE_URL,
        api = ModelApi.OPENAI_COMPLETIONS,
        keyRequired = false, // 有内置免费 key
        keyHint = "OpenRouter API Key (sk-or-v1-...)",
        envVarName = "OPENROUTER_API_KEY",
        tutorialSteps = listOf(
            "打开 openrouter.ai/keys",
            "登录或注册账号",
            "点击 \"Create Key\"",
            "复制 API Key"
        ),
        tutorialUrl = "https://openrouter.ai/keys",
        presetModels = listOf(
            // 与模型设置页保持一致：OpenRouter 默认展示 5 个付费候选。
            PresetModel(
                id = "moonshotai/kimi-k2.6",
                name = "Kimi K2.6 (付费)",
                free = false,
                contextWindow = 262144,
                maxTokens = 16384,
                reasoning = true,
                input = listOf("text", "image")
            ),
            PresetModel(
                id = "z-ai/glm-5v-turbo",
                name = "GLM 5V Turbo (付费)",
                free = false,
                contextWindow = 131072,
                maxTokens = 8192,
                reasoning = false,
                input = listOf("text", "image")
            ),
            PresetModel(
                id = "qwen/qwen3.6-flash",
                name = "Qwen 3.6 Flash (付费，推荐)",
                free = false,
                contextWindow = 200000,
                maxTokens = 16384,
                reasoning = true,
                input = listOf("text", "image")
            ),
            PresetModel(
                id = "xiaomi/mimo-v2.5",
                name = "Xiaomi Mimo v2.5 (付费)",
                free = false,
                contextWindow = 200000,
                maxTokens = 16384,
                reasoning = false,
                input = listOf("text", "image")
            ),
            PresetModel(
                id = "openai/gpt-5.5",
                name = "GPT-5.5 (付费)",
                free = false,
                contextWindow = 1048576,
                maxTokens = 32768,
                reasoning = true,
                input = listOf("text", "image")
            )
        ),
        group = ProviderGroup.PRIMARY,
        order = 10
    )

    val ANTHROPIC = ProviderDefinition(
        id = "anthropic",
        name = "Anthropic",
        description = "Claude 系列",
        baseUrl = ANTHROPIC_BASE_URL,
        api = ModelApi.ANTHROPIC_MESSAGES,
        keyRequired = true,
        keyHint = "Anthropic API Key (sk-ant-...)",
        envVarName = "ANTHROPIC_API_KEY",
        authHeader = false, // Anthropic 用 x-api-key header
        tutorialSteps = listOf(
            "打开 console.anthropic.com",
            "登录或注册账号",
            "进入 API Keys 页面",
            "点击 \"Create Key\" 并复制"
        ),
        tutorialUrl = "https://console.anthropic.com/settings/keys",
        presetModels = listOf(
            PresetModel(
                id = "claude-opus-4",
                name = "Claude Opus 4",
                contextWindow = 200000,
                maxTokens = 32768,
                reasoning = true,
                input = listOf("text", "image")
            ),
            PresetModel(
                id = "claude-sonnet-4",
                name = "Claude Sonnet 4",
                contextWindow = 200000,
                maxTokens = 16384,
                reasoning = true,
                input = listOf("text", "image")
            ),
            PresetModel(
                id = "claude-haiku-3.5",
                name = "Claude Haiku 3.5",
                contextWindow = 200000,
                maxTokens = 8192,
                reasoning = false,
                input = listOf("text", "image")
            )
        ),
        group = ProviderGroup.PRIMARY,
        order = 20
    )

    val OPENAI = ProviderDefinition(
        id = "openai",
        name = "OpenAI",
        description = "GPT 系列",
        baseUrl = OPENAI_BASE_URL,
        api = ModelApi.OPENAI_COMPLETIONS,
        keyRequired = true,
        keyHint = "OpenAI API Key (sk-...)",
        envVarName = "OPENAI_API_KEY",
        tutorialSteps = listOf(
            "打开 platform.openai.com",
            "登录或注册账号",
            "进入 API Keys 页面",
            "点击 \"Create new secret key\" 并复制"
        ),
        tutorialUrl = "https://platform.openai.com/api-keys",
        presetModels = listOf(
            PresetModel(
                id = "gpt-4.1",
                name = "GPT-4.1",
                contextWindow = 1048576,
                maxTokens = 32768,
                reasoning = false,
                input = listOf("text", "image")
            ),
            PresetModel(
                id = "gpt-4.1-mini",
                name = "GPT-4.1 Mini",
                contextWindow = 1048576,
                maxTokens = 32768,
                reasoning = false,
                input = listOf("text", "image")
            ),
            PresetModel(
                id = "o4-mini",
                name = "o4 Mini",
                contextWindow = 200000,
                maxTokens = 100000,
                reasoning = true,
                input = listOf("text", "image")
            )
        ),
        group = ProviderGroup.PRIMARY,
        order = 30
    )

    val OLLAMA = ProviderDefinition(
        id = "ollama",
        name = "Ollama (本地)",
        description = "本地模型，无需 API Key",
        baseUrl = OLLAMA_BASE_URL,
        api = ModelApi.OLLAMA,
        keyRequired = false,
        keyHint = "API Key (可选)",
        envVarName = "OLLAMA_API_KEY",
        tutorialSteps = listOf(
            "安装 Ollama: ollama.com/download",
            "运行模型: ollama run qwen2.5:7b",
            "确保 Ollama 在同一局域网内运行",
            "如在其他设备上运行，修改 Base URL"
        ),
        tutorialUrl = "https://ollama.com/download",
        // 无预置 id 时可在模型配置里「手动添加」本地模型名，或直改 xomniclaw.json
        presetModels = listOf(),
        group = ProviderGroup.PRIMARY,
        order = 70
    )

    // ========== MORE Group (折叠) ==========

    val MOONSHOT = ProviderDefinition(
        id = "moonshot",
        name = "Moonshot (Kimi)",
        description = "月之暗面 Kimi 大模型",
        baseUrl = MOONSHOT_BASE_URL,
        api = ModelApi.OPENAI_COMPLETIONS,
        keyRequired = true,
        keyHint = "Moonshot API Key",
        envVarName = "MOONSHOT_API_KEY",
        tutorialSteps = listOf(
            "打开 platform.moonshot.cn",
            "登录或注册账号",
            "进入 API 管理页面",
            "创建并复制 API Key"
        ),
        tutorialUrl = "https://platform.moonshot.cn/console/api-keys",
        presetModels = listOf(
            PresetModel(
                id = "kimi-k2.5",
                name = "Kimi K2.5",
                contextWindow = 256000,
                maxTokens = 8192,
                reasoning = false,
                input = listOf("text", "image")
            )
        ),
        group = ProviderGroup.MORE,
        order = 120
    )

    val MINIMAX = ProviderDefinition(
        id = "minimax",
        name = "MiniMax",
        description = "MiniMax 大模型 (OpenAI 兼容)",
        baseUrl = MINIMAX_BASE_URL,
        api = ModelApi.OPENAI_COMPLETIONS,
        keyRequired = true,
        keyHint = "MiniMax API Key (sk-cp-...)",
        envVarName = "MINIMAX_API_KEY",
        tutorialSteps = listOf(
            "打开 platform.minimaxi.com",
            "登录或注册账号",
            "进入 API Keys 页面",
            "创建并复制 API Key"
        ),
        tutorialUrl = "https://platform.minimaxi.com/",
        presetModels = listOf(
            PresetModel(
                id = "MiniMax-M2.5",
                name = "MiniMax M2.5",
                contextWindow = 1048576,
                maxTokens = 16384,
                reasoning = true,
                input = listOf("text")
            )
        ),
        group = ProviderGroup.MORE,
        order = 145
    )

    val MIMIMAX = ProviderDefinition(
        id = "mimimax",
        name = "mimimax.cn (中转图像)",
        description = "mimimax.cn 中转接口（images/understand）",
        baseUrl = MIMIMAX_RELAY_BASE_URL,
        api = ModelApi.MINIMAX_IMAGES_UNDERSTAND,
        keyRequired = true,
        keyHint = "mimimax.cn API Key",
        envVarName = "MIMIMAX_API_KEY",
        tutorialSteps = listOf(
            "确认中转地址为 mimimax.cn",
            "获取中转 API Key",
            "在 VLM 配置中选择 mimimax provider",
            "模型建议使用 minimax/vision"
        ),
        tutorialUrl = "",
        presetModels = listOf(
            PresetModel(
                id = "MiniMax-M2.7-highspeed",
                name = "MiniMax M2.7 Highspeed (Relay Default)",
                contextWindow = 32768,
                maxTokens = 4096,
                reasoning = false,
                input = listOf("text", "image")
            )
        ),
        group = ProviderGroup.MORE,
        order = 146
    )

    /** 未纳入 [ALL]，仅按需引用 */
    val KIMI_CODING = ProviderDefinition(
        id = "kimi-coding",
        name = "Kimi for Coding",
        description = "Kimi 编程专用 API（Anthropic 兼容）",
        baseUrl = KIMI_CODING_BASE_URL,
        api = ModelApi.ANTHROPIC_MESSAGES,
        keyRequired = true,
        keyHint = "Kimi API Key",
        envVarName = "KIMI_API_KEY",
        headers = mapOf("User-Agent" to "claude-code/0.1.0"),
        tutorialSteps = listOf(
            "打开 kimi.com/coding",
            "注册或登录 Kimi Coding 账号",
            "获取 API Key"
        ),
        tutorialUrl = "https://kimi.com/coding",
        presetModels = listOf(
            PresetModel(
                id = "k2p5",
                name = "Kimi for Coding",
                contextWindow = 262144,
                maxTokens = 32768,
                reasoning = true,
                input = listOf("text", "image")
            )
        ),
        group = ProviderGroup.MORE,
        order = 125
    )

    // ========== Registry ==========

    /** 所有已注册 Provider，按 order 排序 */
    val ALL: List<ProviderDefinition> = listOf(
        OPENROUTER, ANTHROPIC, OPENAI, OLLAMA,
        MOONSHOT, MINIMAX, MIMIMAX
    ).sortedBy { it.order }

    /** 按 ID 查找 Provider */
    private val BY_ID: Map<String, ProviderDefinition> = ALL.associateBy { it.id }

    /** 按 group 分组 */
    val PRIMARY_PROVIDERS: List<ProviderDefinition> = ALL.filter { it.group == ProviderGroup.PRIMARY }
    val MORE_PROVIDERS: List<ProviderDefinition> = ALL.filter { it.group == ProviderGroup.MORE }

    /**
     * 根据 ID 查找 Provider
     * 支持 OmniClaw 的 normalizeProviderId 别名
     */
    fun findById(id: String): ProviderDefinition? {
        val normalized = normalizeProviderId(id)
        return BY_ID[normalized]
    }

    /**
     * 对齐 OmniClaw normalizeProviderId()
     * 来源: auth-profiles-UpqQjKB-.js normalizeProviderId()
     */
    fun normalizeProviderId(provider: String): String {
        val normalized = provider.trim().lowercase()
        return when (normalized) {
            "z.ai", "z-ai" -> "zai"
            "opencode-zen" -> "opencode"
            "qwen" -> "qwen-portal"
            "mimimax.cn", "mimimax" -> "mimimax"
            "kimi-code", "kimi-coding" -> "kimi-coding"
            "kimi" -> "moonshot"
            "moonshot-cn" -> "moonshot"
            "bedrock", "aws-bedrock" -> "amazon-bedrock"
            else -> normalized
        }
    }

    /**
     * 根据 ProviderDefinition 生成 ProviderConfig
     * 用于写入 xomniclaw.json
     */
    fun buildProviderConfig(
        definition: ProviderDefinition,
        apiKey: String?,
        baseUrl: String? = null,
        apiType: String? = null,
        selectedModels: List<PresetModel>? = null
    ): ProviderConfig {
        val effectiveBaseUrl = baseUrl?.takeIf { it.isNotBlank() } ?: definition.baseUrl
        val effectiveApi = apiType?.takeIf { it.isNotBlank() } ?: definition.api

        val models = (selectedModels ?: definition.presetModels).map { preset ->
            ModelDefinition(
                id = preset.id,
                name = preset.name,
                reasoning = preset.reasoning,
                input = preset.input,
                contextWindow = preset.contextWindow,
                maxTokens = preset.maxTokens
            )
        }

        return ProviderConfig(
            baseUrl = effectiveBaseUrl,
            apiKey = apiKey,
            api = effectiveApi,
            authHeader = definition.authHeader,
            headers = definition.headers,
            models = models
        )
    }

    /**
     * 生成完整的模型引用 ID
     * 格式: "provider/modelId"
     */
    fun buildModelRef(providerId: String, modelId: String): String {
        return "$providerId/$modelId"
    }

    /**
     * 自定义 Provider 支持的 API 类型列表（用于 Spinner）
     */
    val CUSTOM_API_TYPES = listOf(
        ModelApi.OPENAI_COMPLETIONS to "OpenAI Compatible",
        ModelApi.ANTHROPIC_MESSAGES to "Anthropic Compatible",
        ModelApi.OLLAMA to "Ollama"
    )
}
