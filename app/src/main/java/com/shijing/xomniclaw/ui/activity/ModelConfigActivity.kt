/**
 * OmniClaw Source Reference:
 * - ../omniclaw/src/gateway/(all)
 *
 * OmniClaw adaptation: Android UI layer.
 */
package com.shijing.xomniclaw.ui.activity

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.ArrayAdapter
import android.widget.RadioButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.shijing.xomniclaw.R
import com.shijing.xomniclaw.databinding.ActivityModelConfigBinding
import com.google.android.material.card.MaterialCardView
import com.google.android.material.textfield.TextInputEditText
import com.shijing.xomniclaw.config.*

/**
 * 模型配置页面 — 两页式设计
 *
 * Page 1: 选择 AI 服务商 (Provider)
 * Page 2: 填写服务商参数 + 选择模型
 *
 * 所有 Provider 定义来自 ProviderRegistry，与 OmniClaw 保持一致。
 */
class ModelConfigActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "ModelConfigActivity"
    }

    private lateinit var binding: ActivityModelConfigBinding
    private val configLoader by lazy { ConfigLoader(this) }

    // State
    private var selectedProvider: ProviderDefinition? = null
    private var selectedModelId: String? = null
    private var advancedExpanded = false
    private var configuredProviderIds = setOf<String>()
    private var currentModelRef: String? = null // "provider/modelId"

    // 用户通过「手动添加」追加的模型（与预置合并）
    private val userAddedModels = mutableListOf<PresetModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityModelConfigBinding.inflate(layoutInflater)
        setContentView(binding.root)

        loadCurrentConfig()
        setupToolbar()
        buildProviderList()
        setupSttProviderEntry()
        setupVlmProviderEntry()
    }

    override fun onResume() {
        super.onResume()
        // 从 STT 配置页返回时刷新主页面状态。
        loadCurrentConfig()
    }

    // ========== Config Loading ==========

    private fun loadCurrentConfig() {
        try {
            // 子页（STT/VLM）用另一个 ConfigLoader 实例保存后，本页实例的内存缓存仍为旧数据；
            // 必须强制从磁盘重载，否则「已配置」等文案要等离开再进才更新。
            val config = configLoader.reloadOmniClawConfig()
            val providers = config.resolveProviders()
            configuredProviderIds = providers.filter { (_, v) ->
                !v.apiKey.isNullOrBlank() && !v.apiKey.startsWith("\${") && v.apiKey != "未配置"
            }.keys

            // Resolve current model ref
            currentModelRef = config.agents?.defaults?.model?.primary

            binding.tvCurrentModel.text =
                "Agent：${currentModelRef ?: "未配置"}"
            // 当前模型卡片始终展示（布局顺序：STT → VLM → Agent）。
            binding.cardCurrentModel.visibility = View.VISIBLE

            // 卡片内同时展示 STT / VLM / Agent 摘要，便于一眼确认语音与视觉链路后再看主 Agent。
            val sttProvider = providers["stt"]
            val vlmProvider = providers["vlm"]
            val followAgentVlm = config.vision?.vlmUseAgentModel ?: true
            binding.tvCurrentSttSummary.text = buildSttSummary(sttProvider)
            binding.tvCurrentVlmSummary.text = buildVlmSummary(vlmProvider, currentModelRef, followAgentVlm)

            // STT Provider 状态在主页面独立展示，避免用户进二级页面才看到配置入口。
            val sttModelId = sttProvider?.models?.firstOrNull()?.id
            binding.tvSttProviderStatus.text = if (sttProvider == null || sttModelId.isNullOrBlank()) {
                "未配置（点击配置 STT）"
            } else {
                "已配置（stt/$sttModelId）"
            }
            binding.tvVlmProviderStatus.text = if (followAgentVlm) {
                "已启用跟随 Agent（已保存独立 VLM 配置）"
            } else if (vlmProvider == null) {
                "未配置（点击配置 VLM）"
            } else {
                val modelId = vlmProvider.models.firstOrNull()?.id ?: "未设置模型"
                "已配置（vlm/$modelId）"
            }

        } catch (e: Exception) {
            Log.w(TAG, "Failed to load config", e)
            configuredProviderIds = emptySet()
            currentModelRef = null
            // 即使配置读取失败，也保留总览卡片并给出降级文案。
            binding.cardCurrentModel.visibility = View.VISIBLE
            binding.tvCurrentModel.text = "Agent：未配置"
            binding.tvCurrentSttSummary.text = "STT：未配置（模型未设置）"
            binding.tvCurrentVlmSummary.text = "VLM：未配置（模型未设置）"
            binding.tvSttProviderStatus.text = "未配置（点击配置 STT Key）"
            binding.tvVlmProviderStatus.text = "未配置（点击配置 VLM）"
        }
    }

    /**
     * 汇总 STT 配置，放在“当前模型”卡片中展示。
     */
    private fun buildSttSummary(sttProvider: ProviderConfig?): String {
        val model = sttProvider?.models?.firstOrNull()?.id
        return if (model.isNullOrBlank()) {
            "STT：未配置"
        } else {
            "STT：stt/$model"
        }
    }

    /**
     * 汇总 VLM 配置，放在“当前模型”卡片中展示。
     */
    private fun buildVlmSummary(vlmProvider: ProviderConfig?, agentModelRef: String?, followAgentVlm: Boolean): String {
        if (followAgentVlm) {
            return "VLM：跟随 Agent（${agentModelRef ?: "未配置"}）"
        }
        val model = vlmProvider?.models?.firstOrNull()?.id
        return if (!model.isNullOrBlank()) {
            "VLM：vlm/$model"
        } else {
            "VLM：未配置"
        }
    }

    /**
     * 在「选择 AI 服务商」主页面增加 STT Provider 入口。
     */
    private fun setupSttProviderEntry() {
        binding.cardSttProvider.setOnClickListener {
            startActivity(Intent(this, SttProviderConfigActivity::class.java))
        }
    }

    /**
     * 在「选择 AI 服务商」主页面增加 VLM Provider 入口。
     */
    private fun setupVlmProviderEntry() {
        binding.cardVlmProvider.setOnClickListener {
            startActivity(Intent(this, VlmProviderConfigActivity::class.java))
        }
    }

    // ========== Toolbar ==========

    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener {
            if (binding.pageProviderDetail.visibility == View.VISIBLE) {
                showPage1()
            } else {
                finish()
            }
        }
    }

    // ========== Page Navigation ==========

    private fun showPage1() {
        binding.pageProviderList.visibility = View.VISIBLE
        binding.pageProviderDetail.visibility = View.GONE
        binding.toolbar.title = "模型配置"
    }

    private fun showPage2(provider: ProviderDefinition) {
        selectedProvider = provider
        selectedModelId = null

        // Pre-select current model if this is the active provider
        val modelRef = currentModelRef
        if (modelRef != null && modelRef.startsWith("${provider.id}/")) {
            selectedModelId = modelRef.removePrefix("${provider.id}/")
        }

        binding.pageProviderList.visibility = View.GONE
        binding.pageProviderDetail.visibility = View.VISIBLE
        binding.toolbar.title = provider.name

        setupPage2(provider)
    }

    // ========== Page 1: Provider List ==========

    private fun buildProviderList() {
        val inflater = LayoutInflater.from(this)

        // 标准服务商：PRIMARY + MORE 一次展示，按 order 排序
        binding.containerPrimaryProviders.removeAllViews()
        val allStandard = (ProviderRegistry.PRIMARY_PROVIDERS + ProviderRegistry.MORE_PROVIDERS)
            .sortedBy { it.order }
        for (provider in allStandard) {
            addProviderCard(inflater, binding.containerPrimaryProviders, provider)
        }
    }

    private fun addProviderCard(
        inflater: LayoutInflater,
        container: android.widget.LinearLayout,
        provider: ProviderDefinition
    ) {
        val card = inflater.inflate(R.layout.item_provider_card, container, false)

        card.findViewById<TextView>(R.id.tv_provider_name).text = provider.name
        card.findViewById<TextView>(R.id.tv_provider_desc).text = provider.description

        // Status indicator
        val statusView = card.findViewById<View>(R.id.view_status)
        val isConfigured = configuredProviderIds.contains(provider.id)
        val isCurrent = currentModelRef?.startsWith("${provider.id}/") == true
        if (isConfigured || isCurrent) {
            statusView.visibility = View.VISIBLE
            statusView.setBackgroundResource(R.drawable.bg_circle_green)
        }

        // Highlight current provider
        if (isCurrent) {
            (card as? MaterialCardView)?.apply {
                strokeColor = getColor(android.R.color.holo_green_dark)
                strokeWidth = 2
            }
        }

        card.setOnClickListener {
            Log.i(TAG, "Provider card clicked: ${provider.id} / ${provider.name}")
            try {
                showPage2(provider)
            } catch (e: Exception) {
                Log.e(TAG, "showPage2 crashed", e)
            }
        }

        container.addView(card)
    }

    // ========== Page 2: Provider Detail ==========

    private fun setupPage2(provider: ProviderDefinition) {
        // Provider name
        binding.tvProviderName.text = provider.name

        // Status
        val isConfigured = configuredProviderIds.contains(provider.id)
        binding.tvProviderStatus.visibility = if (isConfigured) View.VISIBLE else View.GONE

        // API Key
        binding.tilApiKey.hint = provider.keyHint
        binding.etApiKey.setText("")
        if (!provider.keyRequired) {
            binding.tilApiKey.helperText = "可选（有内置免费 Key）"
        } else {
            binding.tilApiKey.helperText = null
        }

        // Load existing key if configured
        try {
            val config = configLoader.loadOmniClawConfig()
            val existingProvider = config.resolveProviders()[provider.id]
            if (existingProvider != null) {
                val key = existingProvider.apiKey
                if (!key.isNullOrBlank() && !key.startsWith("\${")) {
                    binding.etApiKey.setText(key)
                }
            }
        } catch (_: Exception) {}

        // Tutorial
        if (provider.tutorialSteps.isNotEmpty()) {
            binding.cardTutorial.visibility = View.VISIBLE
            val steps = provider.tutorialSteps.mapIndexed { i, step ->
                "${i + 1}. $step"
            }.joinToString("\n")
            binding.tvTutorialSteps.text = steps

            if (provider.tutorialUrl.isNotBlank()) {
                binding.btnTutorialUrl.visibility = View.VISIBLE
                binding.btnTutorialUrl.setOnClickListener {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(provider.tutorialUrl)))
                }
            } else {
                binding.btnTutorialUrl.visibility = View.GONE
            }
        } else {
            binding.cardTutorial.visibility = View.GONE
        }

        // Preset models
        userAddedModels.clear()
        buildModelRadioGroup(provider.presetModels)

        // Manual add button
        binding.btnAddModel.setOnClickListener { showAddModelDialog() }

        // Advanced section
        advancedExpanded = false
        binding.layoutAdvanced.visibility = View.GONE
        binding.ivAdvancedArrow.rotation = 0f

        binding.cardAdvancedToggle.setOnClickListener {
            advancedExpanded = !advancedExpanded
            binding.layoutAdvanced.visibility =
                if (advancedExpanded) View.VISIBLE else View.GONE
            binding.ivAdvancedArrow.animate()
                .rotation(if (advancedExpanded) 180f else 0f)
                .setDuration(200)
                .start()
        }

        // Base URL (pre-filled)
        binding.etBaseUrl.setText(provider.baseUrl)

        // API type dropdown
        val apiTypeLabels = ProviderRegistry.CUSTOM_API_TYPES.map { it.second }
        val apiTypeAdapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, apiTypeLabels)
        binding.dropdownApiType.setAdapter(apiTypeAdapter)
        val currentApiIndex = ProviderRegistry.CUSTOM_API_TYPES.indexOfFirst { it.first == provider.api }
        if (currentApiIndex >= 0) {
            binding.dropdownApiType.setText(apiTypeLabels[currentApiIndex], false)
        }

        // Save button
        binding.btnSave.setOnClickListener { saveProviderConfig(provider) }
    }

    private fun buildModelRadioGroup(models: List<PresetModel>) {
        binding.containerPresetModels.removeAllViews()
        val inflater = LayoutInflater.from(this)

        // Auto-select first model
        if (models.isNotEmpty() && selectedModelId == null) {
            selectedModelId = models.first().id
        }

        for (model in models) {
            val view = inflater.inflate(R.layout.item_model_radio, binding.containerPresetModels, false)

            val radio = view.findViewById<RadioButton>(R.id.radio_model)
            val tvName = view.findViewById<TextView>(R.id.tv_model_name)
            val tvId = view.findViewById<TextView>(R.id.tv_model_id)
            val tvBadge = view.findViewById<TextView>(R.id.tv_model_badge)

            tvName.text = model.name
            tvId.text = model.id

            if (model.free) {
                tvBadge.visibility = View.VISIBLE
                tvBadge.text = "免费"
            } else if (model.reasoning) {
                tvBadge.visibility = View.VISIBLE
                tvBadge.text = "推理"
                tvBadge.setTextColor(getColor(android.R.color.holo_blue_dark))
            }

            radio.isChecked = model.id == selectedModelId

            // Click handler on entire row
            val clickHandler = View.OnClickListener {
                selectedModelId = model.id
                // Refresh all radios
                for (i in 0 until binding.containerPresetModels.childCount) {
                    val child = binding.containerPresetModels.getChildAt(i)
                    child.findViewById<RadioButton>(R.id.radio_model)?.isChecked =
                        child.findViewById<TextView>(R.id.tv_model_id)?.text == model.id
                }
            }
            radio.setOnClickListener(clickHandler)
            view.setOnClickListener(clickHandler)

            binding.containerPresetModels.addView(view)
        }
    }

    // ========== Manual Add Model ==========

    private fun showAddModelDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_add_model, null)

        val etModelId = dialogView.findViewById<TextInputEditText>(R.id.et_dialog_model_id)
        val etModelName = dialogView.findViewById<TextInputEditText>(R.id.et_dialog_model_name)
        val etContextWindow = dialogView.findViewById<TextInputEditText>(R.id.et_dialog_context_window)

        etContextWindow.setText("128000")

        AlertDialog.Builder(this)
            .setTitle("添加模型")
            .setView(dialogView)
            .setPositiveButton("添加") { _, _ ->
                val modelId = etModelId.text?.toString()?.trim() ?: ""
                if (modelId.isBlank()) {
                    Toast.makeText(this, "模型 ID 不能为空", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val modelName = etModelName.text?.toString()?.trim()?.takeIf { it.isNotBlank() } ?: modelId
                val ctxWindow = etContextWindow.text?.toString()?.toIntOrNull() ?: 128000

                val newModel = PresetModel(
                    id = modelId,
                    name = modelName,
                    contextWindow = ctxWindow,
                    maxTokens = 8192
                )
                userAddedModels.add(newModel)

                val provider = selectedProvider ?: return@setPositiveButton
                val allModels = provider.presetModels + userAddedModels.filter { m ->
                    provider.presetModels.none { it.id == m.id }
                }
                selectedModelId = modelId
                buildModelRadioGroup(allModels)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ========== Save ==========

    private fun saveProviderConfig(provider: ProviderDefinition) {
        val apiKey = binding.etApiKey.text?.toString()?.trim()

        // Validate
        if (provider.keyRequired && apiKey.isNullOrBlank()) {
            binding.tilApiKey.error = "请输入 API Key"
            return
        }
        binding.tilApiKey.error = null

        // Resolve model ID
        val modelId = selectedModelId

        if (modelId.isNullOrBlank()) {
            Toast.makeText(this, "请选择或输入模型", Toast.LENGTH_SHORT).show()
            return
        }

        // Resolve advanced params
        val customBaseUrl = if (advancedExpanded) {
            binding.etBaseUrl.text?.toString()?.trim()?.takeIf { it.isNotBlank() }
        } else null

        val customApiType = if (advancedExpanded) {
            val selectedLabel = binding.dropdownApiType.text?.toString()
            ProviderRegistry.CUSTOM_API_TYPES.find { it.second == selectedLabel }?.first
        } else null

        // Resolve selected models for this provider
        val allAvailable = provider.presetModels + userAddedModels
        val selectedModels = allAvailable.filter { it.id == modelId }
            .ifEmpty { listOf(PresetModel(id = modelId, name = modelId)) }

        try {
            // Build new provider config
            val providerConfig = ProviderRegistry.buildProviderConfig(
                definition = provider,
                apiKey = apiKey,
                baseUrl = customBaseUrl,
                apiType = customApiType,
                selectedModels = selectedModels
            )

            // Load and merge config
            val config = configLoader.loadOmniClawConfig()
            val existingProviders = config.models?.providers?.toMutableMap() ?: mutableMapOf()

            val providerKey = provider.id

            existingProviders[providerKey] = providerConfig

            // Update model ref
            val modelRef = ProviderRegistry.buildModelRef(providerKey, modelId)
            val currentAgents = config.agents ?: AgentsConfig()
            val updatedAgents = currentAgents.copy(
                defaults = currentAgents.defaults.copy(
                    model = ModelSelectionConfig(primary = modelRef)
                )
            )

            val updatedConfig = config.copy(
                models = (config.models ?: ModelsConfig()).copy(
                    providers = existingProviders
                ),
                agents = updatedAgents
            )

            configLoader.saveOmniClawConfig(updatedConfig)

            Toast.makeText(this, "✅ 已保存: $modelRef", Toast.LENGTH_SHORT).show()
            Log.i(TAG, "Saved provider=$providerKey model=$modelRef")

            // Return to list or finish
            setResult(RESULT_OK)
            finish()

        } catch (e: Exception) {
            Log.e(TAG, "Failed to save config", e)
            Toast.makeText(this, "保存失败: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onBackPressed() {
        if (binding.pageProviderDetail.visibility == View.VISIBLE) {
            showPage1()
        } else {
            super.onBackPressed()
        }
    }
}
