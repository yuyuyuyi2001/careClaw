package com.shijing.xomniclaw.config

/**
 * XOmniClaw Source Reference:
 * - ../xomniclaw/src/config/(all)
 * - ../xomniclaw/types/(all).d.ts
 *
 * XOmniClaw adaptation: Kotlin config model aligned to XOmniClaw schema.
 */


/**
 * XOmniClaw Config — 对齐 XOmniClaw types.xomniclaw.d.ts
 *
 * 用户只写想覆盖的字段，其余全用默认值。
 * 解析由 ConfigLoader 的 JSONObject 处理。
 */

data class XOmniClawConfig(
    // ======= XOmniClaw 标准段 =======
    val thinking: ThinkingConfig = ThinkingConfig(),
    val models: ModelsConfig? = null,
    val agents: AgentsConfig? = null,
    val channels: ChannelsConfig = ChannelsConfig(),
    val gateway: GatewayConfig = GatewayConfig(),
    val skills: SkillsConfig = SkillsConfig(),
    val plugins: PluginsConfig = PluginsConfig(),
    val tools: ToolsConfig = ToolsConfig(),
    val memory: MemoryConfig = MemoryConfig(),
    val messages: MessagesConfig = MessagesConfig(),
    val session: SessionConfig = SessionConfig(),
    val logging: LoggingConfig = LoggingConfig(),
    val ui: UIConfig = UIConfig(),
    val vision: VisionConfig? = null,

    // ======= Android 扩展 =======
    val agent: AgentConfig = AgentConfig(),

    // ======= Legacy =======
    val providers: Map<String, ProviderConfig> = emptyMap()
) {
    /** 解析 providers：优先 models.providers；若为空则 fallback 到顶层 providers */
    fun resolveProviders(): Map<String, ProviderConfig> {
        val modelProviders = models?.providers
        return if (!modelProviders.isNullOrEmpty()) modelProviders else providers
    }

    /** 解析默认模型 */
    fun resolveDefaultModel(): String {
        return agents?.defaults?.model?.primary ?: agent.defaultModel
    }

    /** 兼容旧代码：gateway.feishu → channels.feishu */
    val feishuConfig: FeishuChannelConfig get() = channels.feishu
}

// ============ channels（对齐 types.channels.d.ts）============

data class ChannelsConfig(
    val feishu: FeishuChannelConfig = FeishuChannelConfig(),
    val discord: DiscordChannelConfig? = null
)

data class FeishuChannelConfig(
    // 基础
    val enabled: Boolean = false,
    val appId: String = "",
    val appSecret: String = "",
    val encryptKey: String? = null,
    val verificationToken: String? = null,
    val domain: String = "feishu",
    val connectionMode: String = "websocket",
    val webhookPath: String = "/feishu/events",
    val webhookHost: String? = null,
    val webhookPort: Int? = null,
    // 策略
    val dmPolicy: String = "open",
    val allowFrom: List<String> = emptyList(),
    val groupPolicy: String = "open",
    val groupAllowFrom: List<String> = emptyList(),
    val requireMention: Boolean = true,
    val groupCommandMentionBypass: String = "never",
    val allowMentionlessInMultiBotGroup: Boolean = false,
    val groupSessionScope: String? = null,
    val topicSessionMode: String = "disabled",
    val replyInThread: String = "disabled",
    // 历史 (aligned with OmniClaw: optional, no limit if not configured)
    val historyLimit: Int? = null,
    val dmHistoryLimit: Int? = null,
    // 消息
    val textChunkLimit: Int = 4000,
    val chunkMode: String = "length",
    val renderMode: String = "auto",
    val streaming: Boolean? = null,
    // 媒体
    val mediaMaxMb: Double = 20.0,
    // 工具
    val tools: FeishuToolsConfig = FeishuToolsConfig(),
    // 队列（Android 扩展）
    val queueMode: String? = "followup",
    val queueCap: Int = 10,
    val queueDropPolicy: String = "old",
    val queueDebounceMs: Int = 100,
    // UX
    val typingIndicator: Boolean = true,
    val resolveSenderNames: Boolean = true,
    val reactionNotifications: String = "own",
    val reactionDedup: Boolean = true,
    // 调试
    val debugMode: Boolean = false,
    // 多账号
    val accounts: Map<String, FeishuAccountConfig>? = null,
    val defaultAccount: String? = null
)

data class FeishuToolsConfig(
    val doc: Boolean = true,
    val chat: Boolean = true,
    val wiki: Boolean = true,
    val drive: Boolean = true,
    val perm: Boolean = false,
    val scopes: Boolean = true,
    val bitable: Boolean = true,
    val task: Boolean = true,
    val urgent: Boolean = true
)

data class FeishuAccountConfig(
    val enabled: Boolean = true,
    val name: String? = null,
    val appId: String? = null,
    val appSecret: String? = null,
    val domain: String? = null,
    val connectionMode: String? = null,
    val webhookPath: String? = null
)

data class DiscordChannelConfig(
    val enabled: Boolean = false,
    val token: String? = null,
    val name: String? = null,
    val dm: DmPolicyConfig? = null,
    val groupPolicy: String? = null,
    val guilds: Map<String, GuildPolicyConfig>? = null,
    val replyToMode: String? = null,
    val accounts: Map<String, DiscordAccountPolicyConfig>? = null
)

data class DmPolicyConfig(
    val policy: String? = "pairing",
    val allowFrom: List<String>? = null
)

data class GuildPolicyConfig(
    val channels: List<String>? = null,
    val requireMention: Boolean? = true,
    val toolPolicy: String? = null
)

data class DiscordAccountPolicyConfig(
    val enabled: Boolean? = true,
    val token: String? = null,
    val name: String? = null,
    val dm: DmPolicyConfig? = null,
    val guilds: Map<String, GuildPolicyConfig>? = null
)

// ============ gateway（对齐 types.gateway.d.ts）============

data class GatewayConfig(
    val port: Int = 18789,
    val mode: String = "local",
    val bind: String = "loopback",
    val auth: GatewayAuthConfig? = null
)

data class GatewayAuthConfig(
    val mode: String = "token",
    val token: String? = null
)

// ============ agents（对齐 types.agents.d.ts）============

data class AgentsConfig(
    val defaults: AgentDefaultsConfig = AgentDefaultsConfig()
)

data class AgentDefaultsConfig(
    val model: ModelSelectionConfig? = null,
    val bootstrapMaxChars: Int = 20_000,
    val bootstrapTotalMaxChars: Int = 150_000,
    val maxConcurrent: Int = 5
)

data class ModelSelectionConfig(
    val primary: String? = null,
    val fallbacks: List<String>? = null
)

    // ============ agent（Android 扩展，非 XOmniClaw 标准）============

data class AgentConfig(
    val maxIterations: Int = 40,
    val defaultModel: String = "openrouter/z-ai/glm-4.5-air:free",
    val timeout: Long = 300000,
    val retryOnError: Boolean = true,
    val maxRetries: Int = 3,
    val mode: String = "exploration"
)

// ============ skills（对齐 types.skills.d.ts）============

data class SkillsConfig(
    val allowBundled: List<String>? = null,
    val extraDirs: List<String> = emptyList(),
    val watch: Boolean = true,
    val watchDebounceMs: Long = 250,
    val entries: Map<String, SkillConfig> = emptyMap()
)

data class SkillConfig(
    val enabled: Boolean = true,
    val apiKey: Any? = null,
    val env: Map<String, String>? = null,
    val config: Map<String, Any>? = null
) {
    fun resolveApiKey(): String? {
        return when (apiKey) {
            is String -> apiKey
            is Map<*, *> -> {
                val source = apiKey["source"] as? String
                val id = apiKey["id"] as? String
                if (source == "env" && id != null) System.getenv(id) else null
            }
            else -> null
        }
    }
}

// ============ plugins（对齐 types.plugins.d.ts）============

data class PluginsConfig(
    val entries: Map<String, PluginEntry> = emptyMap()
)

data class PluginEntry(
    val enabled: Boolean = false,
    val skills: List<String> = emptyList()
)

// ============ tools（对齐 types.tools.d.ts）============

data class ToolsConfig(
    val screenshot: ScreenshotToolConfig = ScreenshotToolConfig()
)

data class ScreenshotToolConfig(
    val enabled: Boolean = true,
    val quality: Int = 85,
    val maxWidth: Int = 1080,
    val format: String = "jpeg"
)

// ============ messages ============

data class MessagesConfig(
    val ackReactionScope: String = "own"
)

// ============ memory（对齐 types.memory.d.ts）============

data class MemoryConfig(
    val enabled: Boolean = true,
    val path: String = "/sdcard/.xomniclaw/workspace/memory"
)

// ============ session / logging / ui ============

data class SessionConfig(
    val maxMessages: Int = 100
)

data class LoggingConfig(
    val level: String = "INFO",
    val logToFile: Boolean = true,
    /**
     * 调试开关：是否将每轮发给 LLM 的完整输入落盘到
     * /sdcard/.xomniclaw/workspace/logs/prompt-dumps/
     */
    val dumpPrompt: Boolean = true,
    /**
     * prompt dump 最大字符数（超过会截断，避免日志文件无限增长）。
     */
    val dumpPromptMaxChars: Int = 500_000
)

data class UIConfig(
    val theme: String = "auto",
    val language: String = "zh"
)

// ============ thinking ============

data class ThinkingConfig(
    val enabled: Boolean = true,
    val budgetTokens: Int = 10000
)

// ============ vision（摄像头/录屏帧率与 AEC 配置；STT/VLM provider 迁移到 models.providers）============

data class VisionConfig(
    val fps: Int = 1,
    val quality: Int = 80,
    /** VLM 是否跟随 Agent 主模型；为 false 时使用 models.providers.vlm。 */
    val vlmUseAgentModel: Boolean = true,
    /** 语音链路是否启用回声消除预处理。 */
    val aecEnabled: Boolean = true,
    /** 优先尝试 JNI / WebRTC AEC3 桥；未就绪时回退到 Kotlin 软件 AEC。 */
    val aecPreferWebRtc: Boolean = true,
    /** 使用 AudioPlaybackCapture 抓取系统播放音作为 far-end 参考信号。 */
    val aecPlaybackCaptureEnabled: Boolean = true,
    /** 若软件链路不可用，则继续尝试系统 AcousticEchoCanceler。 */
    val aecSystemFallbackEnabled: Boolean = true
)

