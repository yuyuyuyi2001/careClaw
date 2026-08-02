/**
 * OmniClaw Source Reference:
 * - ../xomniclaw/src/gateway/(all)
 *
 * OmniClaw adaptation: Android UI layer.
 */
package com.shijing.xomniclaw.ui.activity

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.shijing.xomniclaw.R
import com.shijing.xomniclaw.databinding.ActivityModelSetupBinding
import com.shijing.xomniclaw.config.ConfigLoader
import com.shijing.xomniclaw.config.ModelDefinition
import com.shijing.xomniclaw.config.ModelsConfig
import com.shijing.xomniclaw.config.ProviderConfig

/**
 * Model Setup Guide — simplified first-run wizard.
 *
 * Default flow: user only needs to paste an OpenRouter API Key.
 * Advanced: tap "使用其他服务商" to switch to Anthropic/OpenAI/Google/OpenRouter.
 */
class ModelSetupActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "ModelSetupActivity"
        const val EXTRA_MANUAL = "manual"

        fun isNeeded(context: android.content.Context): Boolean {
            try {
                val configFile = java.io.File("/sdcard/.xomniclaw/xomniclaw.json")
                if (!configFile.exists() || configFile.length() == 0L) {
                    Log.i(TAG, "xomniclaw.json missing, model setup is needed")
                    return true
                }

                val configLoader = ConfigLoader(context)
                val config = configLoader.loadOmniClawConfig()
                val providers = config.resolveProviders()
                val hasRealKey = providers.values.any { provider ->
                    val key = provider.apiKey
                    !key.isNullOrBlank() &&
                            !key.startsWith("\${") &&
                            key != "未配置"
                }
                return !hasRealKey
            } catch (e: Exception) {
                Log.w(TAG, "Error checking setup need, assuming needed", e)
                return true  // Config parse error → probably not configured properly → show setup
            }
        }

        // Provider presets
        private val PROVIDERS = mapOf(
            "openrouter" to ProviderPreset(
                name = "OpenRouter",
                baseUrl = "https://openrouter.ai/api/v1",
                api = "openai-completions",
                hint = "OpenRouter 聚合了 Claude、GPT、Gemini 等多个模型，一个 Key 即可使用全部。\n注册即可免费使用，无需充值！",
                models = listOf(
                    ModelPreset("moonshotai/kimi-k2.6", "Kimi K2.6 (付费)", reasoning = true, contextWindow = 262144, maxTokens = 16384),
                    ModelPreset("z-ai/glm-5v-turbo", "GLM 5V Turbo (付费)", contextWindow = 131072, maxTokens = 8192),
                    ModelPreset("qwen/qwen3.6-flash", "Qwen 3.6 Flash (付费，推荐)", reasoning = true, contextWindow = 200000, maxTokens = 16384),
                    ModelPreset("xiaomi/mimo-v2.5", "Xiaomi Mimo v2.5 (付费)", contextWindow = 200000, maxTokens = 16384),
                    ModelPreset("openai/gpt-5.5", "GPT-5.5 (付费)", reasoning = true, contextWindow = 1048576, maxTokens = 32768),
                ),
                authHeader = true
            ),
            "anthropic" to ProviderPreset(
                name = "Anthropic",
                baseUrl = "https://api.anthropic.com/v1",
                api = "anthropic-messages",
                hint = "Anthropic 官方 API，直连 Claude。注册: console.anthropic.com",
                models = listOf(
                    ModelPreset("claude-sonnet-4-20250514", "Claude Sonnet 4 (推荐)"),
                    ModelPreset("claude-opus-4-20250514", "Claude Opus 4"),
                    ModelPreset("claude-haiku-3-5-20241022", "Claude 3.5 Haiku (快速)")
                )
            ),
            "openai" to ProviderPreset(
                name = "OpenAI",
                baseUrl = "https://api.openai.com/v1",
                api = "openai-completions",
                hint = "OpenAI 官方 API。注册: platform.openai.com",
                models = listOf(
                    ModelPreset("gpt-4.1", "GPT-4.1 (推荐)"),
                    ModelPreset("gpt-4.1-mini", "GPT-4.1 Mini (快速)"),
                    ModelPreset("o3", "o3 (推理)")
                )
            )
        )
    }

    private lateinit var binding: ActivityModelSetupBinding
    private val configLoader by lazy { ConfigLoader(this) }
    private var selectedProvider = "openrouter"
    private var advancedExpanded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityModelSetupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            title = "模型设置"
        }

        setupDefaultMode()
        setupAdvancedToggle()
        setupProviderSelection()
        setupButtons()
    }

    /**
     * Default mode: quick setup only asks for API key.
     */
    private fun setupDefaultMode() {
        binding.tilModel.visibility = View.GONE

        // 默认：OpenRouter（与欢迎文案、chip 默认选中一致）
        applyProviderPreset("openrouter")

        // 打开 OpenRouter Keys 页
        binding.tvOpenOpenrouter.setOnClickListener {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://openrouter.ai/keys")))
            } catch (e: Exception) {
                Toast.makeText(this, "无法打开浏览器", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * Toggle advanced options (other providers).
     */
    private fun setupAdvancedToggle() {
        binding.tvAdvanced.setOnClickListener {
            advancedExpanded = !advancedExpanded
            binding.layoutAdvanced.visibility = if (advancedExpanded) View.VISIBLE else View.GONE
            binding.tvAdvanced.text = if (advancedExpanded) {
                "⚙️ 收起高级选项"
            } else {
                "⚙️ 使用其他服务商（Anthropic / OpenAI 等）"
            }

            // 收起高级选项时恢复为与首次引导一致：OpenRouter
            if (!advancedExpanded && selectedProvider != "openrouter") {
                selectedProvider = "openrouter"
                applyProviderPreset("openrouter")
            }
        }
    }

    private fun setupProviderSelection() {
        binding.chipGroupProvider.setOnCheckedStateChangeListener { _, checkedIds ->
            val provider = when {
                checkedIds.contains(R.id.chip_openrouter) -> "openrouter"
                checkedIds.contains(R.id.chip_anthropic) -> "anthropic"
                checkedIds.contains(R.id.chip_openai) -> "openai"
                else -> "openrouter"
            }
            selectedProvider = provider
            applyProviderPreset(provider)
        }
    }

    private fun bindOpenRouterModelPicker() {
        val models = openRouterStaticModels()
        val modelNames = models.map { it.displayName }
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_dropdown_item_1line,
            modelNames
        )
        binding.actModel.setAdapter(adapter)
        if (modelNames.isNotEmpty()) {
            binding.actModel.setText(modelNames[0], false)
        }
    }

    private fun openRouterStaticModels(): List<ModelPreset> =
        PROVIDERS["openrouter"]?.models.orEmpty()

    private fun applyProviderPreset(providerKey: String) {
        val preset = PROVIDERS[providerKey] ?: return

        binding.apply {
            // API Key hint
            tilApiKey.hint = when (providerKey) {
                "openrouter" -> "OpenRouter API Key"
                "anthropic" -> "Anthropic API Key"
                "openai" -> "OpenAI API Key"
                else -> "API Key"
            }
            (tilApiKey as? com.google.android.material.textfield.TextInputLayout)?.helperText = when (providerKey) {
                "openrouter" -> "以 sk-or-v1- 开头"
                "anthropic" -> "以 sk-ant- 开头"
                "openai" -> "以 sk- 开头"
                else -> null
            }

            // Base URL（高级选项下仍隐藏，由各预设固定）
            etSetupApiBase.setText(preset.baseUrl)
            tilApiBase.visibility = View.GONE
            etSetupApiBase.isEnabled = false

            // Provider hint
            tvProviderHint.text = preset.hint
            tvProviderHint.visibility = if (advancedExpanded) View.VISIBLE else View.GONE

            // 模型选择：仅 OpenRouter 在高级选项下展示；其余保持隐藏（与历史行为一致）
            if (providerKey == "openrouter") {
                tilModel.visibility = if (advancedExpanded) View.VISIBLE else View.GONE
                actModel.inputType = android.text.InputType.TYPE_NULL
                actModel.threshold = 1
                bindOpenRouterModelPicker()
            } else {
                val modelNames = preset.models.map { it.displayName }
                val adapter = ArrayAdapter(
                    this@ModelSetupActivity,
                    android.R.layout.simple_dropdown_item_1line,
                    modelNames
                )
                actModel.setAdapter(adapter)
                if (modelNames.isNotEmpty()) {
                    actModel.setText(modelNames[0], false)
                }
                tilModel.visibility = View.GONE
                actModel.inputType = android.text.InputType.TYPE_NULL
                actModel.threshold = 1
            }
        }
    }

    private fun setupButtons() {
        binding.btnSkip.setOnClickListener {
            Log.i(TAG, "用户跳过模型配置引导，使用默认配置")
            saveDefaultAndFinish()
        }

        binding.btnStart.setOnClickListener {
            saveAndFinish()
        }
    }

    private fun saveDefaultAndFinish() {
        try {
            if (!ensureStoragePermissionForDefaultConfig()) {
                return
            }

            // 跳过引导时直接写入随包默认配置：
            // 这样即使用户不填 API Key，也会使用 xomniclaw.json.default.txt 里的预置 key。
            val configDir = java.io.File("/sdcard/.xomniclaw")
            if (!configDir.exists()) {
                configDir.mkdirs()
            }
            val target = java.io.File(configDir, "xomniclaw.json")
            val bundledDefault = assets.open("xomniclaw.json.default.txt")
                .bufferedReader()
                .use { it.readText() }
            target.writeText(bundledDefault)

            // 刷新配置缓存，确保后续页面读取到刚写入的默认值。
            configLoader.reloadOmniClawConfig()

            Log.i(TAG, "用户跳过模型配置，已写入内置默认配置: ${target.absolutePath}")
            markSetupSeen()
            Toast.makeText(this, "已使用默认配置", Toast.LENGTH_SHORT).show()
            finish()
        } catch (e: Exception) {
            Log.e(TAG, "跳过并写入默认配置失败", e)
            Toast.makeText(this, "跳过失败，请先授予文件管理权限后重试", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Android 11+ 写 /sdcard/.xomniclaw 需要 MANAGE_EXTERNAL_STORAGE。
     * 若未授权，则先引导用户进入系统设置授权，再由用户重试“跳过”。
     */
    private fun ensureStoragePermissionForDefaultConfig(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return true
        }
        if (Environment.isExternalStorageManager()) {
            return true
        }
        try {
            startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                data = Uri.parse("package:$packageName")
            })
        } catch (e: Exception) {
            Log.w(TAG, "无法打开应用级文件管理权限页，改为通用权限页", e)
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            } catch (e2: Exception) {
                Log.e(TAG, "无法打开文件管理权限设置页", e2)
            }
        }
        Toast.makeText(this, "请先授予“所有文件访问权限”，然后再点一次“跳过”", Toast.LENGTH_LONG).show()
        return false
    }

    private fun saveAndFinish() {
        val userInputKey = binding.etSetupApiKey.text?.toString()?.trim()
        val selectedModelDisplay = binding.actModel.text?.toString()?.trim()

        // If user provided a key, use it; otherwise use the built-in encrypted key
        val apiKey = if (userInputKey.isNullOrEmpty()) {
            val builtInKey = com.shijing.xomniclaw.config.BuiltInKeyProvider.getKey()
            if (builtInKey.isNullOrEmpty()) {
                binding.tilApiKey.error = "请输入 API Key"
                return
            }
            builtInKey
        } else {
            userInputKey
        }
        binding.tilApiKey.error = null

        val apiBase = if (advancedExpanded) {
            binding.etSetupApiBase.text?.toString()?.trim()
        } else {
            null
        }

        val preset = PROVIDERS[selectedProvider] ?: return
        val choice: ModelPreset? = when (selectedProvider) {
            "openrouter" -> {
                openRouterStaticModels().find { it.displayName == selectedModelDisplay }
            }
            else -> preset.models.find { it.displayName == selectedModelDisplay } ?: preset.models.firstOrNull()
        }
        val modelId = choice?.id ?: (preset.models.firstOrNull()?.id ?: "")
        val matchedPreset = choice

        binding.tilModel.error = null

        try {
            val config = configLoader.loadOmniClawConfig()

            val providerName = selectedProvider
            val newProvider = ProviderConfig(
                baseUrl = apiBase ?: preset.baseUrl,
                apiKey = apiKey,
                api = preset.api,
                models = listOf(
                    ModelDefinition(
                        id = modelId,
                        name = selectedModelDisplay ?: modelId,
                        reasoning = matchedPreset?.reasoning ?: (modelId.contains("o3") || modelId.contains("r1") || modelId.contains("opus")),
                        contextWindow = matchedPreset?.contextWindow ?: 200000,
                        maxTokens = matchedPreset?.maxTokens ?: 16384
                    )
                ),
                authHeader = preset.authHeader
            )

            val existingModels = config.models ?: ModelsConfig()
            val updatedProviders = existingModels.providers.toMutableMap()
            updatedProviders[providerName] = newProvider

            val defaultModelId = if (modelId.startsWith("$providerName/")) {
                // modelId 已为 OpenRouter 完整路由时可含多级路径 (e.g. "z-ai/glm-4.5-air:free")
                modelId
            } else {
                "$providerName/$modelId"
            }

            val modelSelection = com.shijing.xomniclaw.config.ModelSelectionConfig(primary = defaultModelId)
            val existingAgents = config.agents ?: com.shijing.xomniclaw.config.AgentsConfig()
            val updatedDefaults = existingAgents.defaults.copy(model = modelSelection)
            val updatedAgents = existingAgents.copy(defaults = updatedDefaults)

            val updatedConfig = config.copy(
                models = existingModels.copy(providers = updatedProviders),
                agents = updatedAgents
            )

            configLoader.saveOmniClawConfig(updatedConfig)

            Log.i(TAG, "✅ 模型配置已保存: provider=$providerName, model=$modelId")
            markSetupSeen()
            Toast.makeText(this, "✅ 配置完成！", Toast.LENGTH_SHORT).show()
            finish()

        } catch (e: Exception) {
            Log.e(TAG, "保存配置失败", e)
            Toast.makeText(this, "保存失败: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun markSetupSeen() {
        try {
            val mmkv = com.tencent.mmkv.MMKV.defaultMMKV()
            mmkv.encode("model_setup_completed", true)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to mark setup as seen", e)
        }
    }

    private data class ProviderPreset(
        val name: String,
        val baseUrl: String,
        val api: String,
        val hint: String,
        val models: List<ModelPreset>,
        val authHeader: Boolean = true
    )

    private data class ModelPreset(
        val id: String,
        val displayName: String,
        val reasoning: Boolean = false,
        val contextWindow: Int = 200000,
        val maxTokens: Int = 16384
    )

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
