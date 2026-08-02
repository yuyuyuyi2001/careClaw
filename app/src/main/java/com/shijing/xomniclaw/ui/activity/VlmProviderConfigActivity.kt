package com.shijing.xomniclaw.ui.activity

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.shijing.xomniclaw.config.ConfigLoader
import com.shijing.xomniclaw.config.ModelApi
import com.shijing.xomniclaw.config.ModelDefinition
import com.shijing.xomniclaw.config.ModelsConfig
import com.shijing.xomniclaw.config.ProviderConfig
import com.shijing.xomniclaw.config.VisionConfig
import com.shijing.xomniclaw.databinding.ActivityVlmProviderConfigBinding

/**
 * VLM Provider 独立配置页（与 Agent 模型 provider 平行）。
 */
class VlmProviderConfigActivity : AppCompatActivity() {
    companion object {
        private const val VLM_PROVIDER_ID = "vlm"
        /** 与内置 `xomniclaw.json.default.txt` 中 vlm.baseUrl 对齐 */
        private const val DEFAULT_VLM_BASE_URL = "https://mimimax.cn/v1"
        private const val DEFAULT_VLM_MODEL = "MiniMax-M2.7-highspeed"
    }

    private lateinit var binding: ActivityVlmProviderConfigBinding
    private val configLoader by lazy { ConfigLoader(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVlmProviderConfigBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }
        loadCurrentConfig()
        setupListeners()
    }

    private fun loadCurrentConfig() {
        val config = try {
            configLoader.loadOmniClawConfig()
        } catch (_: Exception) {
            null
        }
        val provider = config?.resolveProviders()?.get(VLM_PROVIDER_ID)
        val followAgent = config?.vision?.vlmUseAgentModel ?: (provider == null)
        val modelId = provider?.models?.firstOrNull()?.id.orEmpty()
        binding.switchFollowAgent.isChecked = followAgent
        binding.etVlmModelRef.setText(if (modelId.isBlank()) "" else "vlm/$modelId")
        binding.etVlmUrl.setText(provider?.baseUrl.orEmpty())
        // 独立 VLM 的 apiKey；加载失败时保持为空（保存时再解析默认继承）
        binding.etVlmApiKey.setText(provider?.apiKey.orEmpty())
        applyFollowAgentUi(followAgent)
    }

    private fun setupListeners() {
        binding.switchFollowAgent.setOnCheckedChangeListener { _, isChecked ->
            applyFollowAgentUi(isChecked)
            persistFollowAgentFlag(isChecked)
        }
        binding.btnSave.setOnClickListener { saveConfig() }
    }

    /**
     * 跟随 Agent 时隐藏手动字段，避免误配置。
     */
    private fun applyFollowAgentUi(followAgent: Boolean) {
        binding.layoutManual.visibility = if (followAgent) android.view.View.GONE else android.view.View.VISIBLE
        binding.tvFollowHint.text = if (followAgent) {
            "已启用：VLM 将复用 Agent provider/model。"
        } else {
            "已启用独立 VLM：请填写独立 provider/model 或其他参数。"
        }
    }

    private fun saveConfig() {
        val followAgent = binding.switchFollowAgent.isChecked
        val modelRefInput = binding.etVlmModelRef.text?.toString()?.trim().orEmpty()
        val urlInput = binding.etVlmUrl.text?.toString()?.trim().orEmpty()
        val apiKeyInput = binding.etVlmApiKey.text?.toString()?.trim().orEmpty()

        try {
            val config = configLoader.loadOmniClawConfig()
            val providers = config.resolveProviders().toMutableMap()
            if (followAgent) {
                val synced = buildVlmProviderFromAgent(config, providers)
                if (synced == null) {
                    Toast.makeText(this, "保存失败：无法解析 Agent 当前模型配置", Toast.LENGTH_LONG).show()
                    return
                }
                providers[VLM_PROVIDER_ID] = synced
            } else {
                val existing = providers[VLM_PROVIDER_ID]
                // 用户显式填写优先；留空则继承已有 vlm 或主推理线路 mimimax（与默认捆绑示例一致）
                val fallbackKey = existing?.apiKey ?: config.resolveProviders()["mimimax"]?.apiKey
                val resolvedApiKey = apiKeyInput.ifBlank { fallbackKey }
                val rawBaseUrl = if (urlInput.isBlank()) existing?.baseUrl ?: DEFAULT_VLM_BASE_URL else urlInput
                val resolvedBaseUrl = normalizeMimimaxBaseUrl(rawBaseUrl)
                val resolvedApi = inferVlmApi(
                    baseUrl = resolvedBaseUrl,
                    previousApi = existing?.api
                )
                val defaultModelId = if (resolvedApi == ModelApi.MINIMAX_IMAGES_UNDERSTAND) {
                    // mimimax 默认模型按用户要求使用聊天高速度模型；
                    // 真正请求 /images/understand 时会在 ApiAdapter 层自动改写为 minimax/vision。
                    "MiniMax-M2.7-highspeed"
                } else {
                    DEFAULT_VLM_MODEL
                }
                // 单一模型 ID：与运行时 DualTrack / LocalVoiceVisionHub 使用的 models[0].id 一致。
                val rawModel = when {
                    modelRefInput.contains("/") -> modelRefInput.substringAfter("/")
                    modelRefInput.isNotBlank() -> modelRefInput
                    else -> existing?.models?.firstOrNull()?.id ?: defaultModelId
                }
                // 仅在候选模型为空时兜底默认模型，避免覆盖用户显式选择的 mimimax 默认模型。
                val normalizedModelId = rawModel.ifBlank { defaultModelId }.let { candidate ->
                    candidate
                }
                val provider = ProviderConfig(
                    baseUrl = resolvedBaseUrl,
                    apiKey = resolvedApiKey,
                    api = resolvedApi,
                    auth = existing?.auth,
                    authHeader = existing?.authHeader ?: true,
                    headers = existing?.headers,
                    injectNumCtxForOpenAICompat = existing?.injectNumCtxForOpenAICompat,
                    models = listOf(
                        ModelDefinition(
                            id = normalizedModelId,
                            name = normalizedModelId,
                            contextWindow = 32768,
                            maxTokens = 65536
                        )
                    )
                )
                providers[VLM_PROVIDER_ID] = provider
            }
            val currentVision = config.vision ?: VisionConfig()
            val saved = configLoader.saveOmniClawConfig(
                config.copy(
                    vision = currentVision.copy(vlmUseAgentModel = followAgent),
                    models = (config.models ?: ModelsConfig()).copy(
                        providers = providers
                    )
                )
            )
            if (!saved) {
                Toast.makeText(this, "保存失败：配置文件写入失败", Toast.LENGTH_LONG).show()
                return
            }
            Toast.makeText(this, "VLM Provider 已保存", Toast.LENGTH_SHORT).show()
            finish()
        } catch (e: Exception) {
            Toast.makeText(this, "保存失败: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * “跟随 Agent”开关单独实时落盘，避免用户只切换开关时 json 未及时更新。
     */
    private fun persistFollowAgentFlag(followAgent: Boolean) {
        runCatching {
            val config = configLoader.loadOmniClawConfig()
            val providers = config.resolveProviders().toMutableMap()
            if (followAgent) {
                buildVlmProviderFromAgent(config, providers)?.let { synced ->
                    providers[VLM_PROVIDER_ID] = synced
                }
            }
            val currentVision = config.vision ?: VisionConfig()
            val updated = config.copy(
                vision = currentVision.copy(vlmUseAgentModel = followAgent),
                models = (config.models ?: ModelsConfig()).copy(
                    providers = providers
                )
            )
            val saved = configLoader.saveOmniClawConfig(updated)
            if (!saved) {
                Toast.makeText(this, "跟随开关保存失败：配置写入失败", Toast.LENGTH_LONG).show()
            }
        }.onFailure { e ->
            Toast.makeText(this, "跟随开关保存失败: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * 将 VLM provider 同步到 Agent 当前主模型对应的 provider/model 配置。
     * 目标：勾选“与 Agent 一致”后，json 中 models.providers.vlm 立即可见且与 Agent 对齐。
     */
    private fun buildVlmProviderFromAgent(
        config: com.shijing.xomniclaw.config.XOmniClawConfig,
        providers: Map<String, ProviderConfig>
    ): ProviderConfig? {
        val agentModelRef = config.resolveDefaultModel().trim()
        if (agentModelRef.isBlank()) return null

        val pair = resolveProviderAndModel(agentModelRef, providers) ?: return null
        val providerId = pair.first
        val modelId = pair.second
        val sourceProvider = providers[providerId] ?: return null
        val sourceModel = sourceProvider.models.firstOrNull { it.id == modelId } ?: return null

        return sourceProvider.copy(
            models = listOf(
                sourceModel.copy(
                    // 统一展示名，避免沿用某些 provider 的空 name。
                    name = sourceModel.name.ifBlank { sourceModel.id }
                )
            )
        )
    }

    /**
     * 解析 modelRef（支持 provider/model 与纯 modelId 两种形式）。
     */
    private fun resolveProviderAndModel(
        modelRef: String,
        providers: Map<String, ProviderConfig>
    ): Pair<String, String>? {
        val parts = modelRef.split("/", limit = 2)
        if (parts.size == 2 && providers.containsKey(parts[0])) {
            return parts[0] to parts[1]
        }
        val byModelId = providers.entries.firstOrNull { (_, provider) ->
            provider.models.any { it.id == modelRef }
        }?.key
        return if (byModelId != null) byModelId to modelRef else null
    }

    /**
     * VLM API 类型自动推断：
     * - mimimax.cn => minimax-images-understand（走 /v1/images/understand）
     * - Ollama /api/chat => ollama
     * - 其余默认 OpenAI 兼容；若历史配置里已是有效 api 则保留
     */
    private fun inferVlmApi(baseUrl: String, previousApi: String?): String {
        val normalized = baseUrl.trim().lowercase()
        val looksLikeMimimaxRelay = normalized.contains("mimimax.cn")
        val looksLikeOllama = normalized.contains("/api/chat") || normalized.contains("ollama")

        return when {
            looksLikeMimimaxRelay -> ModelApi.MINIMAX_IMAGES_UNDERSTAND
            looksLikeOllama -> ModelApi.OLLAMA
            !previousApi.isNullOrBlank() &&
                previousApi != ModelApi.MINIMAX_IMAGES_UNDERSTAND &&
                previousApi != ModelApi.OLLAMA -> previousApi
            else -> ModelApi.OPENAI_COMPLETIONS
        }
    }

    /**
     * mimimax.cn 统一归一到 /v1 根路径，后续由 API 路由补到 /images/understand。
     */
    private fun normalizeMimimaxBaseUrl(baseUrl: String): String {
        val trimmed = baseUrl.trim().trimEnd('/')
        val lower = trimmed.lowercase()
        if (!lower.contains("mimimax.cn")) return trimmed
        return when {
            lower.endsWith("/v1") -> trimmed
            lower.contains("/v1/images/understand") -> trimmed.substringBefore("/images/understand")
            lower.contains("/v1/chat/completions") -> trimmed.substringBefore("/chat/completions")
            else -> "$trimmed/v1"
        }
    }
}
