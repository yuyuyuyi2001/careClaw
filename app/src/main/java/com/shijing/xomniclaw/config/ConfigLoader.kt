package com.shijing.xomniclaw.config

/**
 * X-OmniClaw Source Reference:
 * - ../xomniclaw/src/config/(all)
 * - ../xomniclaw/docs/gateway/configuration-reference.md
 *
 * X-OmniClaw adaptation: load/save/observe xomniclaw.json on Android storage.
 */


import android.content.Context
import android.os.FileObserver
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 配置加载器 - 对齐 X-OmniClaw 的配置加载逻辑
 *
 * 使用 org.json.JSONObject 解析，缺失字段自动用 data class 默认值。
 * 用户 config 只需写想覆盖的字段，其他全用默认值。
 */
class ConfigLoader(private val context: Context) {

    companion object {
        private const val TAG = "ConfigLoader"
        private const val OPENCLAW_CONFIG_FILE = "xomniclaw.json"
        private const val LEGACY_CONFIG_FILE = "omniclaw.json"

        /** 旧版写在 vision 下的 STT/VLM 字段；已迁至 models.providers.stt / vlm，加载后迁移并从文件中剔除。 */
        private val LEGACY_VISION_PROVIDER_KEYS = listOf(
            "sttApiKey", "sttUrl", "sttModel",
            "vlmUrl", "vlmApiKey", "vlmAppId", "vlmSecretKey",
            "vlmModelRef", "vlmModelVision", "vlmModelText"
        )

        private const val DEFAULT_STT_ENDPOINT = "https://api.siliconflow.cn/v1/audio/transcriptions"
        private const val DEFAULT_STT_MODEL_ID = "FunAudioLLM/SenseVoiceSmall"
        /** 与内置 `xomniclaw.json.default.txt` 中 `models.providers.vlm` 默认保持一致 */
        private const val DEFAULT_VLM_ENDPOINT = "https://mimimax.cn/v1"
        private const val DEFAULT_VLM_MODEL_ID = "MiniMax-M2.7-highspeed"
    }

    private val configDir = File("/sdcard/.xomniclaw")
    private val legacyConfigDir = File("/sdcard/.omniclaw")
    private val omniclawConfigFile = File(configDir, OPENCLAW_CONFIG_FILE)

    // Config cache
    private var cachedOmniClawConfig: XOmniClawConfig? = null
    private var omniclawConfigCacheValid = false
    // 记录缓存对应的文件修改时间，避免不同 ConfigLoader 实例之间出现陈旧缓存。
    private var cachedConfigLastModifiedMs: Long = -1L

    // Hot reload support
    private var fileObserver: FileObserver? = null
    private var hotReloadEnabled = false
    private var reloadCallback: ((XOmniClawConfig) -> Unit)? = null
    /** 从安装包默认配置读取 vision 默认值，用于老用户配置缺字段时兜底。 */
    private val bundledVisionDefaults: JSONObject? by lazy { loadBundledVisionDefaults() }

    init {
        Log.d(TAG, "配置目录: ${configDir.absolutePath}")
    }

    private fun loadBundledVisionDefaults(): JSONObject? {
        return try {
            val raw = context.assets.open("xomniclaw.json.default.txt")
                .bufferedReader()
                .use { it.readText() }
            JSONObject(raw).optJSONObject("vision")
        } catch (e: Exception) {
            Log.w(TAG, "读取内置 vision 默认配置失败: ${e.message}")
            null
        }
    }

    /**
     * 加载 X-OmniClaw 主配置（带自动备份和恢复）
     */
    fun loadOmniClawConfig(): XOmniClawConfig {
        val currentLastModifiedMs = if (omniclawConfigFile.exists()) omniclawConfigFile.lastModified() else -1L
        if (
            omniclawConfigCacheValid &&
            cachedOmniClawConfig != null &&
            cachedConfigLastModifiedMs == currentLastModifiedMs
        ) {
            return cachedOmniClawConfig!!
        }
        // 文件已被其他模块/实例更新，当前实例缓存失效，需重新读取。
        if (omniclawConfigCacheValid && cachedConfigLastModifiedMs != currentLastModifiedMs) {
            omniclawConfigCacheValid = false
            cachedOmniClawConfig = null
        }

        val backupManager = ConfigBackupManager(context)
        val config = backupManager.loadConfigSafely {
            loadOmniClawConfigInternal()
        }

        if (config != null) {
            cachedOmniClawConfig = config
            omniclawConfigCacheValid = true
            cachedConfigLastModifiedMs = if (omniclawConfigFile.exists()) omniclawConfigFile.lastModified() else -1L
            return config
        } else {
            Log.w(TAG, "使用默认配置")
            val defaultConfig = XOmniClawConfig(vision = parseVisionConfig(JSONObject()))
            cachedOmniClawConfig = defaultConfig
            omniclawConfigCacheValid = true
            cachedConfigLastModifiedMs = if (omniclawConfigFile.exists()) omniclawConfigFile.lastModified() else -1L
            return defaultConfig
        }
    }

    private fun loadOmniClawConfigInternal(): XOmniClawConfig {
        ensureConfigDir()

        if (!omniclawConfigFile.exists()) {
            Log.i(TAG, "配置文件不存在，创建默认配置: ${omniclawConfigFile.absolutePath}")
            createDefaultConfig()
        }

        val configJson = omniclawConfigFile.readText()
        val processedJson = replaceEnvVars(configJson)
        val config = parseConfig(processedJson)
        val migrated = migrateProviderIds(config)
        val sanitized = validateAndSanitizeConfig(migrated)

        Log.i(TAG, "✅ 配置加载成功")
        return sanitized
    }

    /**
     * 历史 provider 迁移入口（当前无强制迁移规则，保持原样）。
     */
    private fun migrateProviderIds(config: XOmniClawConfig): XOmniClawConfig {
        return config
    }

    /**
     * 从 JSON 字符串解析完整配置
     * 所有缺失字段都用 data class 的默认值
     */
    private fun parseConfig(json: String): XOmniClawConfig {
        val root = JSONObject(json)

        // Models
        val modelsJson = root.optJSONObject("models")
        val models = modelsJson?.let { parseModelsConfig(it) }

        // Agents
        val agentsJson = root.optJSONObject("agents")
        val agents = agentsJson?.let { parseAgentsConfig(it) }

        // Agent (Android extension, legacy)
        val agentJson = root.optJSONObject("agent")
        val agent = agentJson?.let { parseAgentConfig(it) } ?: AgentConfig()

        // Channels（对齐 X-OmniClaw: channels.feishu / channels.discord）
        val channelsJson = root.optJSONObject("channels")
        // 兼容旧格式：如果 channels 没有 feishu，fallback 到 gateway.feishu
        val gatewayJson = root.optJSONObject("gateway")
        val channels = parseChannelsConfig(channelsJson, gatewayJson)

        // Gateway（对齐 X-OmniClaw: 只有 port/mode/bind/auth）
        val gateway = gatewayJson?.let { parseGatewayConfig(it) } ?: GatewayConfig()

        // Skills
        val skillsJson = root.optJSONObject("skills")
        val skills = skillsJson?.let { parseSkillsConfig(it) } ?: SkillsConfig()

        // Plugins
        val pluginsJson = root.optJSONObject("plugins")
        val plugins = pluginsJson?.let { parsePluginsConfig(it) } ?: PluginsConfig()

        // Tools
        val toolsJson = root.optJSONObject("tools")
        val tools = toolsJson?.let { parseToolsConfig(it) } ?: ToolsConfig()

        // UI
        val uiJson = root.optJSONObject("ui")
        val ui = uiJson?.let { parseUIConfig(it) } ?: UIConfig()

        // Logging
        val loggingJson = root.optJSONObject("logging")
        val logging = loggingJson?.let { parseLoggingConfig(it) } ?: LoggingConfig()

        // Memory
        val memoryJson = root.optJSONObject("memory")
        val memory = memoryJson?.let { parseMemoryConfig(it) } ?: MemoryConfig()

        // Messages
        val messagesJson = root.optJSONObject("messages")
        val messages = messagesJson?.let {
            MessagesConfig(ackReactionScope = it.optString("ackReactionScope", "own"))
        } ?: MessagesConfig()

        // Session
        val sessionJson = root.optJSONObject("session")
        val session = sessionJson?.let { parseSessionConfig(it) } ?: SessionConfig()

        // Vision (摄像头推流)
        val visionJson = root.optJSONObject("vision")
        val vision = parseVisionConfig(visionJson ?: JSONObject())

        // Legacy providers (top-level)
        val legacyProviders = root.optJSONObject("providers")?.let { parseProvidersMap(it) } ?: emptyMap()

        val parsed = XOmniClawConfig(
            models = models,
            agents = agents,
            channels = channels,
            gateway = gateway,
            skills = skills,
            plugins = plugins,
            tools = tools,
            memory = memory,
            messages = messages,
            session = session,
            logging = logging,
            ui = ui,
            vision = vision,
            agent = agent,
            providers = legacyProviders
        )
        // 兼容仍写在 vision 下的 STT/VLM（旧版），迁入 models.providers 单一来源。
        return migrateLegacyVisionProviderFields(parsed, root)
    }

    /**
     * 从旧版 `vision.stt*` / `vision.vlm*` 迁入 [models.providers]。
     *
     * 迁移后运行时只认 providers；下次 [saveOmniClawConfig] 时 [mergeConfigToJson] 会从 JSON 的 vision 段删除遗留键。
     */
    private fun migrateLegacyVisionProviderFields(config: XOmniClawConfig, root: JSONObject): XOmniClawConfig {
        val legacyVision = root.optJSONObject("vision") ?: return config
        if (!hasLegacyVisionProviderPayload(legacyVision)) {
            return config
        }

        val merged = config.resolveProviders().toMutableMap()
        var changed = false

        // ---------- STT ----------
        if (legacyVision.has("sttApiKey") || legacyVision.has("sttUrl") || legacyVision.has("sttModel")) {
            val sttKey = legacyVision.optString("sttApiKey").trim()
            val sttUrl = legacyVision.optString("sttUrl").trim()
            val sttModel = legacyVision.optString("sttModel").trim()
            val existing = merged["stt"]
            val hasLegacyContent = sttKey.isNotBlank() || sttUrl.isNotBlank() || sttModel.isNotBlank()
            val incomplete = existing == null ||
                existing.apiKey.isNullOrBlank() ||
                existing.baseUrl.isBlank() ||
                existing.models.firstOrNull()?.id.isNullOrBlank()

            if (hasLegacyContent && incomplete) {
                val effKey = sttKey.ifBlank { existing?.apiKey.orEmpty() }
                val effUrl = when {
                    sttUrl.isNotBlank() -> sttUrl
                    existing?.baseUrl?.isNotBlank() == true -> existing.baseUrl
                    else -> DEFAULT_STT_ENDPOINT
                }
                val effModelId = when {
                    sttModel.isNotBlank() -> sttModel
                    existing?.models?.firstOrNull()?.id?.isNotBlank() == true -> existing.models.first().id
                    else -> DEFAULT_STT_MODEL_ID
                }
                val prevName = existing?.models?.firstOrNull()?.name?.takeIf { it.isNotBlank() }
                merged["stt"] = ProviderConfig(
                    baseUrl = effUrl,
                    apiKey = effKey.takeIf { it.isNotBlank() },
                    api = existing?.api ?: ModelApi.OPENAI_COMPLETIONS,
                    auth = existing?.auth,
                    authHeader = existing?.authHeader ?: true,
                    headers = existing?.headers,
                    injectNumCtxForOpenAICompat = existing?.injectNumCtxForOpenAICompat,
                    models = listOf(
                        ModelDefinition(
                            id = effModelId,
                            name = prevName ?: effModelId,
                            contextWindow = existing?.models?.firstOrNull()?.contextWindow ?: 1,
                            maxTokens = existing?.models?.firstOrNull()?.maxTokens ?: 1
                        )
                    )
                )
                changed = true
                Log.i(TAG, "已从 vision 迁移 STT 至 models.providers.stt（请在配置中删除 vision 内重复字段）")
            }
        }

        // ---------- VLM ----------
        val hasVlmEndpoint =
            legacyVision.optString("vlmUrl").isNotBlank() ||
                legacyVision.optString("vlmApiKey").isNotBlank() ||
                legacyVision.optString("vlmAppId").isNotBlank() ||
                legacyVision.optString("vlmSecretKey").isNotBlank()
        val hasVlmModelHint =
            legacyVision.optString("vlmModelRef").isNotBlank() ||
                legacyVision.optString("vlmModelVision").isNotBlank() ||
                legacyVision.optString("vlmModelText").isNotBlank()

        if (hasVlmEndpoint || hasVlmModelHint) {
            val vlmUrl = legacyVision.optString("vlmUrl").trim()
            val vlmApiKey = legacyVision.optString("vlmApiKey").trim()
            val appId = legacyVision.optString("vlmAppId").trim()
            val secret = legacyVision.optString("vlmSecretKey").trim()
            val composedKey = when {
                vlmApiKey.isNotBlank() -> vlmApiKey
                appId.isNotBlank() && secret.isNotBlank() -> "$appId:$secret"
                else -> merged["mimimax"]?.apiKey.orEmpty()
            }
            val ref = legacyVision.optString("vlmModelRef").trim()
            val mv = legacyVision.optString("vlmModelVision").trim()
            val mt = legacyVision.optString("vlmModelText").trim()
            val existingVlm = merged["vlm"]
            val effModelId = when {
                ref.contains("/") -> ref.substringAfter("/").trim()
                ref.isNotBlank() -> ref
                mv.isNotBlank() -> mv
                mt.isNotBlank() -> mt
                existingVlm?.models?.firstOrNull()?.id?.isNotBlank() == true ->
                    existingVlm.models.first().id
                else -> DEFAULT_VLM_MODEL_ID
            }
            val effUrl = when {
                vlmUrl.isNotBlank() -> vlmUrl
                existingVlm?.baseUrl?.isNotBlank() == true -> existingVlm.baseUrl
                else -> DEFAULT_VLM_ENDPOINT
            }
            merged["vlm"] = ProviderConfig(
                baseUrl = effUrl,
                apiKey = composedKey.takeIf { it.isNotBlank() },
                api = existingVlm?.api ?: ModelApi.OPENAI_COMPLETIONS,
                auth = existingVlm?.auth,
                authHeader = existingVlm?.authHeader ?: true,
                headers = existingVlm?.headers,
                injectNumCtxForOpenAICompat = existingVlm?.injectNumCtxForOpenAICompat,
                models = listOf(
                    ModelDefinition(
                        id = effModelId,
                        name = existingVlm?.models?.firstOrNull()?.name?.takeIf { it.isNotBlank() }
                            ?: effModelId,
                        contextWindow = existingVlm?.models?.firstOrNull()?.contextWindow ?: 32768,
                        maxTokens = existingVlm?.models?.firstOrNull()?.maxTokens ?: 65536
                    )
                )
            )
            changed = true
            Log.i(TAG, "已从 vision 迁移 VLM 至 models.providers.vlm（请在配置中删除 vision 内重复字段）")
        }

        if (!changed) {
            return config
        }

        return config.copy(
            models = ModelsConfig(
                mode = config.models?.mode ?: "merge",
                providers = merged
            ),
            // 已全部合并进 models.providers，避免顶层 providers 与 models 分叉。
            providers = emptyMap()
        )
    }

    private fun hasLegacyVisionProviderPayload(vision: JSONObject): Boolean {
        return LEGACY_VISION_PROVIDER_KEYS.any { key ->
            vision.has(key) && vision.optString(key).isNotBlank()
        }
    }

    // ============ Section Parsers ============

    private fun parseModelsConfig(json: JSONObject): ModelsConfig {
        val providersJson = json.optJSONObject("providers")
        val providers = providersJson?.let { parseProvidersMap(it) } ?: emptyMap()
        return ModelsConfig(
            mode = json.optString("mode", "merge"),
            providers = providers
        )
    }

    private fun parseProvidersMap(json: JSONObject): Map<String, ProviderConfig> {
        val map = mutableMapOf<String, ProviderConfig>()
        json.keys().forEach { key ->
            json.optJSONObject(key)?.let { map[key] = parseProviderConfig(providerId = key, json = it) }
        }
        return map
    }

    private fun parseProviderConfig(providerId: String, json: JSONObject): ProviderConfig {
        val modelsArray = json.optJSONArray("models") ?: JSONArray()
        // 历史配置可能仍为 bot-adaptor，运行时按 OpenAI 兼容处理
        val rawApi = json.optString("api", ModelApi.OPENAI_COMPLETIONS)
        val defaultApi = if (rawApi == "bot-adaptor") ModelApi.OPENAI_COMPLETIONS else rawApi
        val models = (0 until modelsArray.length()).mapNotNull { i ->
            modelsArray.optJSONObject(i)?.let { parseModelDefinition(it, defaultApi) }
        }

        val headers = json.optJSONObject("headers")?.let { h ->
            h.keys().asSequence().associateWith { h.optString(it, "") }
        }

        return ProviderConfig(
            baseUrl = json.optString("baseUrl", ""),
            apiKey = json.optString("apiKey", null),
            api = defaultApi,
            auth = json.optString("auth", null),
            authHeader = json.optBoolean("authHeader", true),
            headers = headers,
            injectNumCtxForOpenAICompat = if (json.has("injectNumCtxForOpenAICompat")) json.optBoolean("injectNumCtxForOpenAICompat") else null,
            models = models
        )
    }

    private fun parseModelDefinition(json: JSONObject, defaultApi: String): ModelDefinition {
        val input: List<Any> = if (json.has("input")) {
            val arr = json.optJSONArray("input") ?: JSONArray()
            (0 until arr.length()).map { i ->
                val item = arr.get(i)
                if (item is JSONObject) item.toString() else item.toString()
            }
        } else listOf("text")

        val headers = json.optJSONObject("headers")?.let { h ->
            h.keys().asSequence().associateWith { h.optString(it, "") }
        }

        val cost = json.optJSONObject("cost")?.let { c ->
            CostConfig(
                input = c.optDouble("input", 0.0),
                output = c.optDouble("output", 0.0),
                cacheRead = c.optDouble("cacheRead", 0.0),
                cacheWrite = c.optDouble("cacheWrite", 0.0)
            )
        }

        val compat = json.optJSONObject("compat")?.let { c ->
            ModelCompatConfig(
                supportsStore = if (c.has("supportsStore")) c.optBoolean("supportsStore") else null,
                supportsReasoningEffort = if (c.has("supportsReasoningEffort")) c.optBoolean("supportsReasoningEffort") else null,
                maxTokensField = if (c.has("maxTokensField")) c.optString("maxTokensField") else null,
                thinkingFormat = if (c.has("thinkingFormat")) c.optString("thinkingFormat") else null,
                requiresToolResultName = if (c.has("requiresToolResultName")) c.optBoolean("requiresToolResultName") else null,
                requiresAssistantAfterToolResult = if (c.has("requiresAssistantAfterToolResult")) c.optBoolean("requiresAssistantAfterToolResult") else null
            )
        }

        val modelId = json.optString("id", "")
        val modelIdLower = modelId.lowercase()
        val compatWithDefaults = if (compat?.maxTokensField == null) {
            val defaultMaxTokensField = when {
                modelIdLower.startsWith("gpt-5") -> "max_completion_tokens"
                modelIdLower.startsWith("o1") -> "max_completion_tokens"
                modelIdLower.startsWith("o3") -> "max_completion_tokens"
                modelIdLower.startsWith("gpt-4.1") -> "max_completion_tokens"
                else -> null
            }
            if (defaultMaxTokensField != null) {
                (compat ?: ModelCompatConfig()).copy(maxTokensField = defaultMaxTokensField)
            } else {
                compat
            }
        } else {
            compat
        }

        val rawModelApi = if (json.has("api")) json.optString("api") else null
        val normalizedModelApi = when (rawModelApi) {
            "bot-adaptor" -> ModelApi.OPENAI_COMPLETIONS
            else -> rawModelApi?.takeIf { it.isNotBlank() }
        }

        return ModelDefinition(
            id = json.optString("id", ""),
            name = json.optString("name", ""),
            api = normalizedModelApi,
            reasoning = json.optBoolean("reasoning", false),
            input = input,
            cost = cost,
            contextWindow = json.optInt("contextWindow", 128000),
            maxTokens = json.optInt("maxTokens", 8192),
            headers = headers,
            compat = compatWithDefaults
        )
    }

    private fun parseAgentsConfig(json: JSONObject): AgentsConfig {
        val defaultsJson = json.optJSONObject("defaults")
        val defaults = if (defaultsJson != null) {
            val modelJson = defaultsJson.optJSONObject("model")
            val model = modelJson?.let {
                ModelSelectionConfig(
                    primary = it.optString("primary", null),
                    fallbacks = it.optJSONArray("fallbacks")?.let { arr ->
                        (0 until arr.length()).map { i -> arr.getString(i) }
                    }
                )
            }
            AgentDefaultsConfig(
                model = model,
                bootstrapMaxChars = defaultsJson.optInt("bootstrapMaxChars", 20_000),
                bootstrapTotalMaxChars = defaultsJson.optInt("bootstrapTotalMaxChars", 150_000)
            )
        } else AgentDefaultsConfig()
        return AgentsConfig(defaults = defaults)
    }

    private fun parseAgentConfig(json: JSONObject): AgentConfig {
        return AgentConfig(
            maxIterations = json.optInt("maxIterations", 40),
            defaultModel = json.optString("defaultModel", "anthropic/claude-opus-4.6"),
            timeout = json.optLong("timeout", 300000),
            retryOnError = json.optBoolean("retryOnError", true),
            maxRetries = json.optInt("maxRetries", 3),
            mode = json.optString("mode", "exploration")
        )
    }

    /**
     * 解析 channels 配置（对齐 X-OmniClaw: channels.feishu）
     * 兼容旧格式：如果 channels.feishu 不存在，fallback 到 gateway.feishu
     */
    private fun parseChannelsConfig(channelsJson: JSONObject?, gatewayJson: JSONObject?): ChannelsConfig {
        val feishuJson = channelsJson?.optJSONObject("feishu")
            ?: gatewayJson?.optJSONObject("feishu")  // legacy fallback
        val feishu = feishuJson?.let { parseFeishuConfig(it) } ?: FeishuChannelConfig()

        val discordJson = channelsJson?.optJSONObject("discord")
            ?: gatewayJson?.optJSONObject("discord")  // legacy fallback
        val discord = discordJson?.let { parseDiscordConfig(it) }

        return ChannelsConfig(feishu = feishu, discord = discord)
    }

    /**
     * 解析 gateway（对齐 X-OmniClaw: 只有 port/mode/bind/auth）
     */
    private fun parseGatewayConfig(json: JSONObject): GatewayConfig {
        val authJson = json.optJSONObject("auth")
        val auth = authJson?.let {
            GatewayAuthConfig(
                mode = it.optString("mode", "token"),
                token = if (it.has("token")) it.optString("token") else null
            )
        }

        return GatewayConfig(
            port = json.optInt("port", 18789),
            mode = json.optString("mode", "local"),
            bind = json.optString("bind", "loopback"),
            auth = auth
        )
    }

    private fun parseFeishuConfig(json: JSONObject): FeishuChannelConfig {
        // tools 子配置（对齐 X-OmniClaw FeishuToolsConfigSchema）
        val toolsJson = json.optJSONObject("tools")
        val tools = toolsJson?.let {
            FeishuToolsConfig(
                doc = it.optBoolean("doc", true),
                chat = it.optBoolean("chat", true),
                wiki = it.optBoolean("wiki", true),
                drive = it.optBoolean("drive", true),
                perm = it.optBoolean("perm", false),
                scopes = it.optBoolean("scopes", true),
                bitable = it.optBoolean("bitable", true),
                task = it.optBoolean("task", true),
                urgent = it.optBoolean("urgent", true)
            )
        } ?: FeishuToolsConfig()

        // 多账号
        val accountsJson = json.optJSONObject("accounts")
        val accounts = accountsJson?.let { a ->
            val map = mutableMapOf<String, FeishuAccountConfig>()
            a.keys().forEach { key ->
                a.optJSONObject(key)?.let { aj ->
                    map[key] = FeishuAccountConfig(
                        enabled = aj.optBoolean("enabled", true),
                        name = if (aj.has("name")) aj.optString("name") else null,
                        appId = if (aj.has("appId")) aj.optString("appId") else null,
                        appSecret = if (aj.has("appSecret")) aj.optString("appSecret") else null,
                        domain = if (aj.has("domain")) aj.optString("domain") else null,
                        connectionMode = if (aj.has("connectionMode")) aj.optString("connectionMode") else null,
                        webhookPath = if (aj.has("webhookPath")) aj.optString("webhookPath") else null
                    )
                }
            }
            map
        }

        return FeishuChannelConfig(
            enabled = json.optBoolean("enabled", false),
            appId = json.optString("appId", ""),
            appSecret = json.optString("appSecret", ""),
            encryptKey = if (json.has("encryptKey")) json.optString("encryptKey") else null,
            verificationToken = if (json.has("verificationToken")) json.optString("verificationToken") else null,
            domain = json.optString("domain", "feishu"),
            connectionMode = json.optString("connectionMode", "websocket"),
            webhookPath = json.optString("webhookPath", "/feishu/events"),
            webhookHost = if (json.has("webhookHost")) json.optString("webhookHost") else null,
            webhookPort = if (json.has("webhookPort")) json.optInt("webhookPort") else null,
            // 缺省与 FeishuChannelConfig 数据类一致：open / open。旧默认 pairing+allowlist 且白名单为空会导致私聊与群聊全部被策略拦截。
            dmPolicy = json.optString("dmPolicy", "open"),
            allowFrom = json.optJSONArray("allowFrom")?.let { arr ->
                (0 until arr.length()).map { arr.getString(it) }
            } ?: emptyList(),
            groupPolicy = json.optString("groupPolicy", "open"),
            groupAllowFrom = json.optJSONArray("groupAllowFrom")?.let { arr ->
                (0 until arr.length()).map { arr.getString(it) }
            } ?: emptyList(),
            requireMention = json.optBoolean("requireMention", true),
            groupCommandMentionBypass = json.optString("groupCommandMentionBypass", "never"),
            allowMentionlessInMultiBotGroup = json.optBoolean("allowMentionlessInMultiBotGroup", false),
            groupSessionScope = if (json.has("groupSessionScope")) json.optString("groupSessionScope") else null,
            topicSessionMode = json.optString("topicSessionMode", "disabled"),
            replyInThread = json.optString("replyInThread", "disabled"),
            historyLimit = json.optInt("historyLimit", 20),
            dmHistoryLimit = json.optInt("dmHistoryLimit", 100),
            textChunkLimit = json.optInt("textChunkLimit", 4000),
            chunkMode = json.optString("chunkMode", "length"),
            renderMode = json.optString("renderMode", "auto"),
            streaming = if (json.has("streaming")) json.optBoolean("streaming") else null,
            mediaMaxMb = json.optDouble("mediaMaxMb", 20.0),
            tools = tools,
            queueMode = if (json.has("queueMode")) json.optString("queueMode") else "followup",
            queueCap = json.optInt("queueCap", 10),
            queueDropPolicy = json.optString("queueDropPolicy", "old"),
            queueDebounceMs = json.optInt("queueDebounceMs", 100),
            typingIndicator = json.optBoolean("typingIndicator", true),
            resolveSenderNames = json.optBoolean("resolveSenderNames", true),
            reactionNotifications = json.optString("reactionNotifications", "own"),
            reactionDedup = json.optBoolean("reactionDedup", true),
            debugMode = json.optBoolean("debugMode", false),
            accounts = accounts,
            defaultAccount = if (json.has("defaultAccount")) json.optString("defaultAccount") else null
        )
    }

    private fun parseDiscordConfig(json: JSONObject): DiscordChannelConfig {
        val dm = json.optJSONObject("dm")?.let { d ->
            DmPolicyConfig(
                policy = d.optString("policy", "pairing"),
                allowFrom = d.optJSONArray("allowFrom")?.let { arr ->
                    (0 until arr.length()).map { arr.getString(it) }
                }
            )
        }

        val guilds = json.optJSONObject("guilds")?.let { g ->
            val map = mutableMapOf<String, GuildPolicyConfig>()
            g.keys().forEach { key ->
                g.optJSONObject(key)?.let { gj ->
                    map[key] = GuildPolicyConfig(
                        channels = gj.optJSONArray("channels")?.let { arr ->
                            (0 until arr.length()).map { arr.getString(it) }
                        },
                        requireMention = if (gj.has("requireMention")) gj.optBoolean("requireMention") else true,
                        toolPolicy = if (gj.has("toolPolicy")) gj.optString("toolPolicy") else null
                    )
                }
            }
            map
        }

        val accounts = json.optJSONObject("accounts")?.let { a ->
            val map = mutableMapOf<String, DiscordAccountPolicyConfig>()
            a.keys().forEach { key ->
                a.optJSONObject(key)?.let { aj ->
                    map[key] = DiscordAccountPolicyConfig(
                        enabled = aj.optBoolean("enabled", true),
                        token = if (aj.has("token")) aj.optString("token") else null,
                        name = if (aj.has("name")) aj.optString("name") else null,
                        dm = aj.optJSONObject("dm")?.let { d ->
                            DmPolicyConfig(
                                policy = d.optString("policy", "pairing"),
                                allowFrom = d.optJSONArray("allowFrom")?.let { arr ->
                                    (0 until arr.length()).map { arr.getString(it) }
                                }
                            )
                        },
                        guilds = aj.optJSONObject("guilds")?.let { g ->
                            val gmap = mutableMapOf<String, GuildPolicyConfig>()
                            g.keys().forEach { gk ->
                                g.optJSONObject(gk)?.let { gj ->
                                    gmap[gk] = GuildPolicyConfig(
                                        channels = gj.optJSONArray("channels")?.let { arr ->
                                            (0 until arr.length()).map { arr.getString(it) }
                                        }
                                    )
                                }
                            }
                            gmap
                        }
                    )
                }
            }
            map
        }

        return DiscordChannelConfig(
            enabled = json.optBoolean("enabled", false),
            token = if (json.has("token")) json.optString("token") else null,
            name = if (json.has("name")) json.optString("name") else null,
            dm = dm,
            groupPolicy = if (json.has("groupPolicy")) json.optString("groupPolicy") else null,
            guilds = guilds,
            replyToMode = if (json.has("replyToMode")) json.optString("replyToMode") else null,
            accounts = accounts
        )
    }

    private fun parseSkillsConfig(json: JSONObject): SkillsConfig {
        val entries = json.optJSONObject("entries")?.let { e ->
            val map = mutableMapOf<String, SkillConfig>()
            e.keys().forEach { key ->
                e.optJSONObject(key)?.let { sc ->
                    map[key] = SkillConfig(
                        enabled = sc.optBoolean("enabled", true),
                        apiKey = if (sc.has("apiKey")) sc.get("apiKey") else null,
                        env = sc.optJSONObject("env")?.let { envObj ->
                            envObj.keys().asSequence().associateWith { envObj.optString(it, "") }
                        }
                    )
                }
            }
            map
        } ?: emptyMap()

        val extraDirs = json.optJSONArray("extraDirs")?.let { arr ->
            (0 until arr.length()).map { arr.getString(it) }
        } ?: emptyList()

        return SkillsConfig(
            allowBundled = json.optJSONArray("allowBundled")?.let { arr ->
                (0 until arr.length()).map { arr.getString(it) }
            },
            extraDirs = extraDirs,
            watch = json.optBoolean("watch", true),
            watchDebounceMs = json.optLong("watchDebounceMs", 250),
            entries = entries
        )
    }

    private fun parsePluginsConfig(json: JSONObject): PluginsConfig {
        val entriesJson = json.optJSONObject("entries") ?: return PluginsConfig()
        val map = mutableMapOf<String, PluginEntry>()
        entriesJson.keys().forEach { key ->
            entriesJson.optJSONObject(key)?.let { pe ->
                val skills = pe.optJSONArray("skills")?.let { arr ->
                    (0 until arr.length()).map { arr.getString(it) }
                } ?: emptyList()
                map[key] = PluginEntry(
                    enabled = pe.optBoolean("enabled", false),
                    skills = skills
                )
            }
        }
        return PluginsConfig(entries = map)
    }

    private fun parseToolsConfig(json: JSONObject): ToolsConfig {
        val ssJson = json.optJSONObject("screenshot")
        val screenshot = ssJson?.let {
            ScreenshotToolConfig(
                enabled = it.optBoolean("enabled", true),
                quality = it.optInt("quality", 85),
                maxWidth = it.optInt("maxWidth", 1080),
                format = it.optString("format", "jpeg")
            )
        } ?: ScreenshotToolConfig()

        return ToolsConfig(screenshot = screenshot)
    }

    private fun parseThinkingConfig(json: JSONObject): ThinkingConfig {
        return ThinkingConfig(
            enabled = json.optBoolean("enabled", true),
            budgetTokens = json.optInt("budgetTokens", 10000)
        )
    }

    private fun parseUIConfig(json: JSONObject): UIConfig {
        return UIConfig(
            theme = json.optString("theme", "auto"),
            language = json.optString("language", "zh")
        )
    }

    private fun parseLoggingConfig(json: JSONObject): LoggingConfig {
        return LoggingConfig(
            level = json.optString("level", "INFO"),
            logToFile = json.optBoolean("logToFile", true),
            dumpPrompt = json.optBoolean("dumpPrompt", true),
            dumpPromptMaxChars = json.optInt("dumpPromptMaxChars", 500_000)
        )
    }

    private fun parseMemoryConfig(json: JSONObject): MemoryConfig {
        return MemoryConfig(
            enabled = json.optBoolean("enabled", true),
            path = json.optString("path", "/sdcard/.xomniclaw/workspace/memory")
        )
    }

    private fun parseSessionConfig(json: JSONObject): SessionConfig {
        return SessionConfig(
            maxMessages = json.optInt("maxMessages", 100)
        )
    }

    private fun parseVisionConfig(json: JSONObject): VisionConfig {
        val bundled = bundledVisionDefaults
        return VisionConfig(
            fps = if (json.has("fps")) json.optInt("fps", 1) else bundled?.optInt("fps", 1) ?: 1,
            quality = if (json.has("quality")) json.optInt("quality", 80) else bundled?.optInt("quality", 80) ?: 80,
            vlmUseAgentModel = if (json.has("vlmUseAgentModel")) {
                json.optBoolean("vlmUseAgentModel", true)
            } else {
                true
            },
            aecEnabled = if (json.has("aecEnabled")) json.optBoolean("aecEnabled", true)
                else bundled?.optBoolean("aecEnabled", true) ?: true,
            aecPreferWebRtc = if (json.has("aecPreferWebRtc")) json.optBoolean("aecPreferWebRtc", true)
                else bundled?.optBoolean("aecPreferWebRtc", true) ?: true,
            aecPlaybackCaptureEnabled = if (json.has("aecPlaybackCaptureEnabled")) {
                json.optBoolean("aecPlaybackCaptureEnabled", true)
            } else {
                bundled?.optBoolean("aecPlaybackCaptureEnabled", true) ?: true
            },
            aecSystemFallbackEnabled = if (json.has("aecSystemFallbackEnabled")) {
                json.optBoolean("aecSystemFallbackEnabled", true)
            } else {
                bundled?.optBoolean("aecSystemFallbackEnabled", true) ?: true
            }
        )
    }

    // ============ Public API ============

    fun getProviderConfig(providerName: String): ProviderConfig? {
        return loadOmniClawConfig().resolveProviders()[providerName]
    }

    fun getModelDefinition(providerName: String, modelId: String): ModelDefinition? {
        return getProviderConfig(providerName)?.models?.find { it.id == modelId }
    }

    fun listAllModels(): List<Pair<String, ModelDefinition>> {
        val config = loadOmniClawConfig()
        return config.resolveProviders().flatMap { (name, provider) ->
            provider.models.map { name to it }
        }
    }

    fun findProviderByModelId(modelId: String): String? {
        return loadOmniClawConfig().resolveProviders().entries.find { (_, provider) ->
            provider.models.any { it.id == modelId }
        }?.key
    }

    /**
     * 保存配置 - 用 JSONObject 序列化（不依赖 Gson）
     */
    fun saveOmniClawConfig(config: XOmniClawConfig): Boolean {
        return try {
            ensureConfigDir()
            // Read existing file to preserve unknown fields
            val existingJson = if (omniclawConfigFile.exists()) {
                JSONObject(omniclawConfigFile.readText())
            } else JSONObject()

            // Merge known fields into existing JSON
            mergeConfigToJson(existingJson, config)

            omniclawConfigFile.writeText(existingJson.toString(4))
            Log.i(TAG, "✅ 配置保存成功")
            omniclawConfigCacheValid = false
            cachedConfigLastModifiedMs = if (omniclawConfigFile.exists()) omniclawConfigFile.lastModified() else -1L
            true
        } catch (e: Exception) {
            Log.e(TAG, "❌ 配置保存失败: ${e.message}", e)
            false
        }
    }

    /**
     * 将 [FeishuChannelConfig] 完整写入 JSON，与 [parseFeishuConfig] 对称。
     * 之前 [mergeConfigToJson] 只写了少数键，保存模型等配置时会覆盖整个 `channels.feishu`，
     * 导致 encryptKey、verificationToken、groupAllowFrom 等丢失，飞书无法连接。
     */
    private fun feishuChannelConfigToJson(feishu: FeishuChannelConfig): JSONObject {
        val o = JSONObject()
        o.put("enabled", feishu.enabled)
        o.put("appId", feishu.appId)
        o.put("appSecret", feishu.appSecret)
        feishu.encryptKey?.let { o.put("encryptKey", it) }
        feishu.verificationToken?.let { o.put("verificationToken", it) }
        o.put("domain", feishu.domain)
        o.put("connectionMode", feishu.connectionMode)
        o.put("webhookPath", feishu.webhookPath)
        feishu.webhookHost?.let { o.put("webhookHost", it) }
        feishu.webhookPort?.let { o.put("webhookPort", it) }
        o.put("dmPolicy", feishu.dmPolicy)
        if (feishu.allowFrom.isNotEmpty()) {
            val arr = JSONArray()
            feishu.allowFrom.forEach { arr.put(it) }
            o.put("allowFrom", arr)
        }
        o.put("groupPolicy", feishu.groupPolicy)
        if (feishu.groupAllowFrom.isNotEmpty()) {
            val arr = JSONArray()
            feishu.groupAllowFrom.forEach { arr.put(it) }
            o.put("groupAllowFrom", arr)
        }
        o.put("requireMention", feishu.requireMention)
        o.put("groupCommandMentionBypass", feishu.groupCommandMentionBypass)
        o.put("allowMentionlessInMultiBotGroup", feishu.allowMentionlessInMultiBotGroup)
        feishu.groupSessionScope?.let { o.put("groupSessionScope", it) }
        o.put("topicSessionMode", feishu.topicSessionMode)
        o.put("replyInThread", feishu.replyInThread)
        feishu.historyLimit?.let { o.put("historyLimit", it) }
        feishu.dmHistoryLimit?.let { o.put("dmHistoryLimit", it) }
        o.put("textChunkLimit", feishu.textChunkLimit)
        o.put("chunkMode", feishu.chunkMode)
        o.put("renderMode", feishu.renderMode)
        feishu.streaming?.let { o.put("streaming", it) }
        o.put("mediaMaxMb", feishu.mediaMaxMb)
        val tools = feishu.tools
        val toolsObj = JSONObject()
        toolsObj.put("doc", tools.doc)
        toolsObj.put("chat", tools.chat)
        toolsObj.put("wiki", tools.wiki)
        toolsObj.put("drive", tools.drive)
        toolsObj.put("perm", tools.perm)
        toolsObj.put("scopes", tools.scopes)
        toolsObj.put("bitable", tools.bitable)
        toolsObj.put("task", tools.task)
        toolsObj.put("urgent", tools.urgent)
        o.put("tools", toolsObj)
        feishu.queueMode?.let { o.put("queueMode", it) }
        o.put("queueCap", feishu.queueCap)
        o.put("queueDropPolicy", feishu.queueDropPolicy)
        o.put("queueDebounceMs", feishu.queueDebounceMs)
        o.put("typingIndicator", feishu.typingIndicator)
        o.put("resolveSenderNames", feishu.resolveSenderNames)
        o.put("reactionNotifications", feishu.reactionNotifications)
        o.put("reactionDedup", feishu.reactionDedup)
        o.put("debugMode", feishu.debugMode)
        feishu.accounts?.takeIf { it.isNotEmpty() }?.let { map ->
            val accRoot = JSONObject()
            map.forEach { (key, ac) ->
                val aj = JSONObject()
                aj.put("enabled", ac.enabled)
                ac.name?.let { aj.put("name", it) }
                ac.appId?.let { aj.put("appId", it) }
                ac.appSecret?.let { aj.put("appSecret", it) }
                ac.domain?.let { aj.put("domain", it) }
                ac.connectionMode?.let { aj.put("connectionMode", it) }
                ac.webhookPath?.let { aj.put("webhookPath", it) }
                accRoot.put(key, aj)
            }
            o.put("accounts", accRoot)
        }
        feishu.defaultAccount?.let { o.put("defaultAccount", it) }
        return o
    }

    /**
     * 将 config 对象的关键字段写入 JSONObject（保留文件中其他字段）
     */
    private fun mergeConfigToJson(root: JSONObject, config: XOmniClawConfig) {
        // Models + providers
        config.models?.let { m ->
            val modelsObj = root.optJSONObject("models") ?: JSONObject()
            val providersObj = JSONObject()
            m.providers.forEach { (name, p) ->
                val pObj = JSONObject()
                pObj.put("baseUrl", p.baseUrl)
                if (p.apiKey != null) pObj.put("apiKey", p.apiKey)
                pObj.put("api", p.api)
                pObj.put("authHeader", p.authHeader)
                p.headers?.let { h -> pObj.put("headers", JSONObject(h)) }
                val modelsArr = JSONArray()
                p.models.forEach { md ->
                    val mObj = JSONObject()
                    mObj.put("id", md.id)
                    mObj.put("name", md.name)
                    mObj.put("reasoning", md.reasoning)
                    mObj.put("contextWindow", md.contextWindow)
                    mObj.put("maxTokens", md.maxTokens)
                    md.api?.let { mObj.put("api", it) }
                    modelsArr.put(mObj)
                }
                pObj.put("models", modelsArr)
                providersObj.put(name, pObj)
            }
            modelsObj.put("providers", providersObj)
            root.put("models", modelsObj)
        }

        // Agents
        config.agents?.let { a ->
            val agentsObj = root.optJSONObject("agents") ?: JSONObject()
            val defaultsObj = JSONObject()
            a.defaults.model?.let { model ->
                val modelObj = JSONObject()
                model.primary?.let { modelObj.put("primary", it) }
                defaultsObj.put("model", modelObj)
            }
            agentsObj.put("defaults", defaultsObj)
            root.put("agents", agentsObj)
        }

        // Agent (Android extension)
        // 与 [XOmniClawConfig.resolveDefaultModel] 一致：优先 agents.defaults.model.primary，避免仅改 primary 时本字段在 JSON 中仍显示旧值
        val agentObj = root.optJSONObject("agent") ?: JSONObject()
        agentObj.put("defaultModel", config.resolveDefaultModel())
        agentObj.put("maxIterations", config.agent.maxIterations)
        root.put("agent", agentObj)

        // Channels（feishu 必须完整写出，否则会覆盖磁盘上仅存于 feishu 对象内的密钥与策略字段）
        val channelsObj = root.optJSONObject("channels") ?: JSONObject()
        channelsObj.put("feishu", feishuChannelConfigToJson(config.channels.feishu))
        root.put("channels", channelsObj)

        // Vision（仅帧率 / 画质 / AEC；STT/VLM 已迁至 models.providers）
        config.vision?.let { v ->
            val visionObj = root.optJSONObject("vision") ?: JSONObject()
            visionObj.put("fps", v.fps)
            visionObj.put("quality", v.quality)
            visionObj.put("vlmUseAgentModel", v.vlmUseAgentModel)
            visionObj.put("aecEnabled", v.aecEnabled)
            visionObj.put("aecPreferWebRtc", v.aecPreferWebRtc)
            visionObj.put("aecPlaybackCaptureEnabled", v.aecPlaybackCaptureEnabled)
            visionObj.put("aecSystemFallbackEnabled", v.aecSystemFallbackEnabled)
            // 移除旧版写在 vision 下的 STT/VLM 字段，避免与 providers 重复。
            LEGACY_VISION_PROVIDER_KEYS.forEach { key -> visionObj.remove(key) }
            root.put("vision", visionObj)
        }

        // Gateway (对齐 X-OmniClaw: 只有 port/mode/bind/auth)
        val gwObj = root.optJSONObject("gateway") ?: JSONObject()
        gwObj.put("port", config.gateway.port)
        root.put("gateway", gwObj)
    }

    fun reloadOmniClawConfig(): XOmniClawConfig {
        Log.i(TAG, "重新加载配置...")
        omniclawConfigCacheValid = false
        return loadOmniClawConfig()
    }

    fun enableHotReload(callback: ((XOmniClawConfig) -> Unit)? = null) {
        if (hotReloadEnabled) return
        this.reloadCallback = callback
        try {
            ensureConfigDir()
            fileObserver = object : FileObserver(configDir, MODIFY or CREATE or DELETE) {
                override fun onEvent(event: Int, path: String?) {
                    if (path == OPENCLAW_CONFIG_FILE) {
                        Log.i(TAG, "检测到配置文件变化")
                        val newConfig = reloadOmniClawConfig()
                        reloadCallback?.invoke(newConfig)
                    }
                }
            }
            fileObserver?.startWatching()
            hotReloadEnabled = true
            Log.i(TAG, "✅ 配置热重载已启用")
        } catch (e: Exception) {
            Log.e(TAG, "启用热重载失败", e)
        }
    }

    fun disableHotReload() {
        fileObserver?.stopWatching()
        fileObserver = null
        reloadCallback = null
        hotReloadEnabled = false
    }

    fun isHotReloadEnabled(): Boolean = hotReloadEnabled

    fun getFeishuConfig(): com.xiaomo.feishu.FeishuConfig {
        return FeishuConfigAdapter.toFeishuConfig(loadOmniClawConfig().channels.feishu)
    }

    // ============ Private Helpers ============

    private fun ensureConfigDir() {
        migrateLegacyStorageIfNeeded()
        if (!configDir.exists()) configDir.mkdirs()
    }

    /**
     * 包名和配置目录改名后，首次启动时尽量把旧的 `.omniclaw` 目录迁移到新目录，
     * 避免已有配置、技能和工作区内容直接丢失。
     */
    private fun migrateLegacyStorageIfNeeded() {
        if (configDir.exists() || !legacyConfigDir.exists()) {
            return
        }

        try {
            legacyConfigDir.copyRecursively(configDir, overwrite = false)

            val legacyConfigFile = File(configDir, LEGACY_CONFIG_FILE)
            if (legacyConfigFile.exists() && !omniclawConfigFile.exists()) {
                legacyConfigFile.copyTo(omniclawConfigFile, overwrite = false)
            }

            Log.i(TAG, "✅ 已将旧配置目录迁移到 ${configDir.absolutePath}")
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ 迁移旧配置目录失败: ${e.message}")
        }
    }

    private fun createDefaultConfig() {
        try {
            val defaultConfig = context.assets.open("xomniclaw.json.default.txt")
                .bufferedReader().use { it.readText() }
            omniclawConfigFile.writeText(defaultConfig)
            Log.i(TAG, "✅ 创建默认配置: ${omniclawConfigFile.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "创建默认配置失败", e)
            throw e
        }
    }

    /**
     * 已知环境变量名 → Provider ID 映射
     * 来源: X-OmniClaw PROVIDER_ENV_API_KEY_CANDIDATES
     */
    private val ENV_VAR_TO_PROVIDER = mapOf(
        "OPENROUTER_API_KEY" to "openrouter",
        "ANTHROPIC_API_KEY" to "anthropic",
        "OPENAI_API_KEY" to "openai",
        "OLLAMA_API_KEY" to "ollama",
        "MOONSHOT_API_KEY" to "moonshot",
        "KIMI_API_KEY" to "moonshot",
        "KIMICODE_API_KEY" to "kimi-coding",
        "MINIMAX_API_KEY" to "minimax"
    )

    private fun replaceEnvVars(json: String): String {
        var result = json
        val pattern = Regex("""\$\{([A-Za-z_][A-Za-z0-9_]*)\}""")
        val unresolvedKnown = mutableListOf<String>()

        pattern.findAll(json).forEach { match ->
            val varName = match.groupValues[1]
            val value = System.getenv(varName)
            if (value != null) {
                result = result.replace("\${$varName}", value)
            } else {
                // Fallback 1: built-in key (currently only OpenRouter)
                val builtInValue = when (varName) {
                    "OPENROUTER_API_KEY" -> BuiltInKeyProvider.getKey()
                    else -> null
                }
                if (builtInValue != null) {
                    result = result.replace("\${$varName}", builtInValue)
                    Log.i(TAG, "🔑 使用内置 Key 替换: \${$varName}")
                } else {
                    val providerId = ENV_VAR_TO_PROVIDER[varName]
                    if (providerId != null) {
                        unresolvedKnown.add(varName)
                        Log.w(TAG, "⚠️ 环境变量 \${$varName} (provider: $providerId) 未设置。" +
                            "请在配置中直接填入 API Key。")
                    } else {
                        Log.w(TAG, "⚠️ 未知环境变量: \${$varName}")
                    }
                }
            }
        }

        // Strip unresolved known provider env vars from JSON to avoid sending literal
        // `${VAR}` as Bearer token (causes confusing 401 "Missing Authentication header").
        // After stripping, apiKey becomes null → config validation catches it with a clear message.
        if (unresolvedKnown.isNotEmpty()) {
            for (varName in unresolvedKnown) {
                // Match: "apiKey": "${VAR_NAME}" (with optional whitespace)
                val stripPattern = Regex("""("apiKey"\s*:\s*)"\$\{$varName\}"""")
                result = stripPattern.replace(result) { "${it.groupValues[1]}null" }
            }
            invalidateConfigCache()
        }

        return result
    }

    private fun invalidateConfigCache() {
        omniclawConfigCacheValid = false
        cachedOmniClawConfig = null
    }

    /**
     * 验证并修正配置。飞书若启用但凭证仍为占位符或未填，原先会 require 抛错导致整表加载失败，
     * 应用回退到空 XOmniClawConfig（无 models），表现为「没有可用的模型配置」。
     */
    private fun validateAndSanitizeConfig(config: XOmniClawConfig): XOmniClawConfig {
        var result = config
        val feishu = result.channels.feishu
        if (feishu.enabled) {
            val badId = feishu.appId.isBlank() || feishu.appId.startsWith("\${")
            val badSecret = feishu.appSecret.isBlank() || feishu.appSecret.startsWith("\${")
            if (badId || badSecret) {
                Log.w(
                    TAG,
                    "channels.feishu 已启用但 appId/appSecret 未配置或为 \${...} 占位符，" +
                        "已自动关闭飞书，避免整条配置被拒绝加载。"
                )
                result = result.copy(
                    channels = result.channels.copy(feishu = feishu.copy(enabled = false))
                )
            }
        }

        result.resolveProviders().forEach { (name, provider) ->
            require(provider.baseUrl.isNotBlank()) {
                "Provider '$name' 缺少 baseUrl"
            }
            val def = ProviderRegistry.findById(name)
            if (def?.keyRequired == true && provider.apiKey.isNullOrBlank()) {
                Log.w(
                    TAG,
                    "⚠️ Provider '${def.name}' 需要 API Key 但未配置。" +
                        "请在设置中填入 API Key，否则请求会返回 401。"
                )
            }
        }

        Log.i(TAG, "✅ 配置验证通过")
        return result
    }
}
