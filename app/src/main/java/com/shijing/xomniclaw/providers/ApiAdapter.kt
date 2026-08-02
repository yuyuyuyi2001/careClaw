package com.shijing.xomniclaw.providers

/**
 * OmniClaw Source Reference:
 * - ../omniclaw/src/agents/pi-embedded-runner/(all)
 *
 * OmniClaw adaptation: provider auth/header/body request shaping.
 */


import android.util.Log
import com.shijing.xomniclaw.config.ModelApi
import com.shijing.xomniclaw.config.ModelDefinition
import com.shijing.xomniclaw.config.ProviderConfig
import com.shijing.xomniclaw.providers.llm.Message
import com.shijing.xomniclaw.providers.llm.ToolDefinition as NewToolDefinition
import okhttp3.Headers
import org.json.JSONArray
import org.json.JSONObject

/**
 * API 适配器
 * Responsible for converting generic request format to specific formats of different API providers
 *
 * Reference: OmniClaw src/agents/llm-adapters/
 */
object ApiAdapter {
    private const val JPEG_DATA_URL_PREFIX = "data:image/jpeg;base64,"
    private const val PNG_DATA_URL_PREFIX = "data:image/png;base64,"

    internal fun shouldUseNullContentForAssistantToolCall(message: Message): Boolean {
        return message.role == "assistant" &&
            !message.toolCalls.isNullOrEmpty() &&
            message.content.isEmpty()
    }

    /**
     * 统一图片字符串格式：
     * - 语音/视觉链路内部通常保留原始 base64
     * - 部分 OpenAI 兼容网关需 data URL，由 formatOpenAIImageUrl 等路径处理
     */
    internal fun stripImageDataUrlPrefix(image: String): String {
        val trimmed = image.trim()
        return if (trimmed.startsWith("data:", ignoreCase = true)) {
            trimmed.substringAfter(",", trimmed)
        } else {
            trimmed
        }
    }

    internal fun ensureJpegDataUrl(image: String): String {
        val rawBase64 = stripImageDataUrlPrefix(image)
        return "$JPEG_DATA_URL_PREFIX$rawBase64"
    }

    internal fun ensurePngDataUrl(image: String): String {
        val rawBase64 = stripImageDataUrlPrefix(image)
        return "$PNG_DATA_URL_PREFIX$rawBase64"
    }

    /**
     * 构建请求体
     */
    fun buildRequestBody(
        provider: ProviderConfig,
        model: ModelDefinition,
        messages: List<Message>,
        tools: List<NewToolDefinition>?,
        temperature: Double,
        maxTokens: Int?,
        reasoningEnabled: Boolean
    ): JSONObject {
        val api = model.api ?: provider.api

        return when (api) {
            ModelApi.ANTHROPIC_MESSAGES -> buildAnthropicRequest(
                model, messages, tools, temperature, maxTokens, reasoningEnabled
            )
            ModelApi.OPENAI_COMPLETIONS -> buildOpenAIRequest(
                model, messages, tools, temperature, maxTokens, reasoningEnabled, provider
            )
            ModelApi.OPENAI_RESPONSES,
            ModelApi.OPENAI_CODEX_RESPONSES -> buildOpenAIResponsesRequest(
                model, messages, tools, temperature, maxTokens, reasoningEnabled
            )
            ModelApi.OLLAMA -> buildOllamaRequest(
                provider, model, messages, tools, temperature, maxTokens
            )
            ModelApi.GITHUB_COPILOT -> buildCopilotRequest(
                model, messages, tools, temperature, maxTokens
            )
            ModelApi.MINIMAX_IMAGES_UNDERSTAND -> buildMiniMaxImagesUnderstandRequest(
                model = model,
                messages = messages
            )
            else -> {
                // 默认使用 OpenAI 兼容格式
                buildOpenAIRequest(model, messages, tools, temperature, maxTokens, reasoningEnabled, provider)
            }
        }
    }

    /**
     * 构建请求头
     */
    fun buildHeaders(
        provider: ProviderConfig,
        model: ModelDefinition
    ): Headers {
        val builder = Headers.Builder()

        // Provider-level custom headers
        provider.headers?.forEach { (key, value) ->
            builder.add(key, value)
        }

        // Model-level custom headers (higher priority)
        model.headers?.forEach { (key, value) ->
            builder.add(key, value)
        }

        // Add API Key (if authHeader is configured)
        val api = model.api ?: provider.api
        if (provider.authHeader && provider.apiKey != null) {
            when (api) {
                ModelApi.ANTHROPIC_MESSAGES -> {
                    builder.add("x-api-key", provider.apiKey)
                    builder.add("anthropic-version", "2023-06-01")
                }
                else -> {
                    // OpenAI-style Authorization header
                    builder.add("Authorization", "Bearer ${provider.apiKey}")
                }
            }
        }

        // Set Content-Type
        builder.add("Content-Type", "application/json")

        return builder.build()
    }

    /**
     * 解析响应
     */
    fun parseResponse(
        api: String,
        responseBody: String
    ): ParsedResponse {
        return when (api) {
            ModelApi.ANTHROPIC_MESSAGES -> parseAnthropicResponse(responseBody)
            ModelApi.MINIMAX_IMAGES_UNDERSTAND -> parseMiniMaxImagesUnderstandResponse(responseBody)
            ModelApi.OPENAI_COMPLETIONS,
            ModelApi.OLLAMA,
            ModelApi.GITHUB_COPILOT -> parseOpenAIResponse(responseBody)
            ModelApi.OPENAI_RESPONSES,
            ModelApi.OPENAI_CODEX_RESPONSES -> parseOpenAIResponsesResponse(responseBody)
            else -> parseOpenAIResponse(responseBody)  // Parse as OpenAI format by default
        }
    }

    // ============ Anthropic Messages API ============

    private fun buildAnthropicRequest(
        model: ModelDefinition,
        messages: List<Message>,
        tools: List<NewToolDefinition>?,
        temperature: Double,
        maxTokens: Int?,
        reasoningEnabled: Boolean
    ): JSONObject {
        val json = JSONObject()

        json.put("model", model.id)
        json.put("max_tokens", maxTokens ?: model.maxTokens)
        json.put("temperature", temperature)

        // Convert message format
        val anthropicMessages = JSONArray()
        var systemMessage: String? = null

        messages.forEach { message ->
            when (message.role) {
                "system" -> {
                    systemMessage = message.content
                }
                "user", "assistant" -> {
                    val msg = JSONObject()
                    msg.put("role", message.role)
                    val imageDataUrls = message.imageDataUrls.orEmpty()
                    if (message.role == "user" && imageDataUrls.isNotEmpty()) {
                        msg.put(
                            "content",
                            JSONArray().apply {
                                if (message.content.isNotBlank()) {
                                    put(
                                        JSONObject().apply {
                                            put("type", "text")
                                            put("text", message.content)
                                        }
                                    )
                                }
                                imageDataUrls.forEach { image ->
                                    put(
                                        JSONObject().apply {
                                            put("type", "image")
                                            put(
                                                "source",
                                                JSONObject().apply {
                                                    put("type", "base64")
                                                    put("media_type", "image/jpeg")
                                                    put("data", stripImageDataUrlPrefix(image))
                                                }
                                            )
                                        }
                                    )
                                }
                            }
                        )
                    } else {
                        msg.put("content", message.content)
                    }
                    anthropicMessages.put(msg)
                }
                "tool" -> {
                    // Anthropic 使用 tool_result 格式
                    val msg = JSONObject()
                    msg.put("role", "user")
                    msg.put("content", JSONArray().apply {
                        put(JSONObject().apply {
                            put("type", "tool_result")
                            put("tool_use_id", message.toolCallId ?: "")
                            put("content", message.content)
                        })
                    })
                    anthropicMessages.put(msg)
                }
            }
        }

        json.put("messages", anthropicMessages)

        // Add system message
        if (systemMessage != null) {
            json.put("system", systemMessage)
        }

        // Add tools (use buildToolJson for proper JSON escaping)
        if (!tools.isNullOrEmpty()) {
            val anthropicTools = JSONArray()
            tools.forEach { tool ->
                val toolJson = JSONObject()
                toolJson.put("name", tool.function.name)
                toolJson.put("description", tool.function.description)
                toolJson.put("input_schema", buildParametersJson(tool.function.parameters))
                anthropicTools.put(toolJson)
            }
            json.put("tools", anthropicTools)
        }

        // Extended Thinking support
        if (reasoningEnabled && model.reasoning) {
            json.put("thinking", JSONObject().apply {
                put("type", "enabled")
                put("budget_tokens", 10000)
            })
        }

        return json
    }

    private fun parseAnthropicResponse(responseBody: String): ParsedResponse {
        val json = JSONObject(responseBody)

        var content: String? = null
        val toolCalls = mutableListOf<ToolCall>()
        var thinkingContent: String? = null

        // Parse content array
        val contentArray = json.optJSONArray("content")
        if (contentArray != null) {
            for (i in 0 until contentArray.length()) {
                val block = contentArray.getJSONObject(i)
                when (block.getString("type")) {
                    "text" -> {
                        content = block.getString("text")
                    }
                    "thinking" -> {
                        thinkingContent = block.getString("thinking")
                    }
                    "tool_use" -> {
                        toolCalls.add(
                            ToolCall(
                                id = block.getString("id"),
                                name = block.getString("name"),
                                arguments = block.getJSONObject("input").toString()
                            )
                        )
                    }
                }
            }
        }

        // Parse usage
        val usage = json.optJSONObject("usage")?.let {
            Usage(
                promptTokens = it.optInt("input_tokens", 0),
                completionTokens = it.optInt("output_tokens", 0)
            )
        }

        return ParsedResponse(
            content = content,
            toolCalls = toolCalls.ifEmpty { null },
            thinkingContent = thinkingContent,
            usage = usage,
            finishReason = json.optString("stop_reason")
        )
    }

    /**
     * MiniMax 图像理解接口（/v1/images/understand）请求体：
     * - 必填：image_url（URL 或 base64）
     * - 常见：model=minimax/vision
     * - 可选：prompt（文本问题）
     */
    private fun buildMiniMaxImagesUnderstandRequest(
        model: ModelDefinition,
        messages: List<Message>
    ): JSONObject {
        val (prompt, image) = extractLatestUserPromptAndImage(messages)
        val imageUrl = image
            ?: throw LLMException("MiniMax images/understand 缺少 image_url：当前消息里没有可用图片")
        // MiniMax 图像理解链路实测要求 data:image/png;base64 包装；
        // 即使上游传入的是压缩后的 JPEG 字节，也保持该前缀以兼容中转识别。
        val normalizedImageUrl = if (imageUrl.trim().startsWith("data:", ignoreCase = true)) {
            imageUrl.trim()
        } else {
            ensurePngDataUrl(imageUrl)
        }
        // 该接口使用视觉模型更稳定；若外部误传聊天模型，这里强制兜底到 minimax/vision。
        val resolvedModelId = model.id
            .takeIf { it.isNotBlank() && it.contains("vision", ignoreCase = true) }
            ?: "minimax/vision"

        return JSONObject().apply {
            put("model", resolvedModelId)
            put("image_url", normalizedImageUrl)
            if (prompt.isNotBlank()) {
                put("prompt", prompt)
            }
        }
    }

    private fun extractLatestUserPromptAndImage(messages: List<Message>): Pair<String, String?> {
        var latestText = ""
        var latestImage: String? = null
        messages.forEach { m ->
            if (m.role != "user") return@forEach
            if (m.content.isNotBlank()) latestText = m.content
            val img = m.imageDataUrls?.lastOrNull()
            if (!img.isNullOrBlank()) latestImage = img
        }
        return latestText to latestImage
    }

    /**
     * `{ code, data: { choices, ... } }` 包装的 OpenAI Chat Completions 形状解析（部分国内网关使用）。
     * 同时支持两种 function call 格式：
     * 1. 标准 tool_calls
     * 2. 文本标记：<|FunctionCallBegin|>[...]<|FunctionCallEnd|>
     */
    private fun parseCodeWrappedOpenAiChatResponse(responseBody: String): ParsedResponse {
        val json = JSONObject(responseBody)

        val code = json.optInt("code", -1)
        if (code != 0) {
            val msg = json.optString("msg", "Unknown gateway error")
            Log.e("ApiAdapter", "Wrapped gateway error: code=$code, msg=$msg")
            val hint = when (code) {
                -30002 ->
                    " 提示：多为上游模型侧异常（返回体异常/超时/配额/风控/模型不可用）。" +
                        "可尝试缩短上下文、暂时减少工具定义，或更换 models 中的 model id。"
                else -> ""
            }
            throw LLMException("Gateway error [$code]: $msg$hint")
        }

        val data = json.optJSONObject("data")
            ?: throw LLMException("Gateway response missing 'data' field")

        val choices = data.optJSONArray("choices")
        if (choices == null || choices.length() == 0) {
            return ParsedResponse(content = null)
        }

        val choice = choices.getJSONObject(0)
        val message = choice.getJSONObject("message")
        val rawContent = if (message.isNull("content")) null else message.optString("content")
        val reasoningContent = message.optString("reasoningContent", null)

        // 优先解析标准 tool_calls（如果模型返回了结构化格式）
        var toolCalls: List<ToolCall>? = null
        val toolCallsArray = message.optJSONArray("tool_calls")
        if (toolCallsArray != null && toolCallsArray.length() > 0) {
            toolCalls = mutableListOf<ToolCall>().apply {
                for (i in 0 until toolCallsArray.length()) {
                    val tc = toolCallsArray.getJSONObject(i)
                    val function = tc.getJSONObject("function")
                    add(
                        ToolCall(
                            id = tc.optString("id", "call_botadaptor_$i"),
                            name = function.getString("name"),
                            arguments = function.optString("arguments", "{}")
                        )
                    )
                }
            }
        }

        // 如果标准格式没有 tool_calls，尝试从 content 中解析文本标记格式
        var cleanContent = rawContent
        if (toolCalls == null && rawContent != null) {
            val parsed = parseTextBasedFunctionCalls(rawContent)
            if (parsed.toolCalls.isNotEmpty()) {
                toolCalls = parsed.toolCalls
                cleanContent = parsed.remainingContent
            }
        }

        val usageObj = data.optJSONObject("usage")
        val usage = if (usageObj != null) {
            Usage(
                promptTokens = usageObj.optInt("promptTokens", 0),
                completionTokens = usageObj.optInt("completionTokens", 0)
            )
        } else null

        val finishReason = if (toolCalls != null) "tool_calls"
            else choice.optString("finish_reason", null)

        return ParsedResponse(
            content = cleanContent?.takeIf { it.isNotBlank() },
            toolCalls = toolCalls,
            thinkingContent = if (reasoningContent.isNullOrBlank()) null else reasoningContent,
            usage = usage,
            finishReason = finishReason
        )
    }

    /**
     * MiniMax images/understand 返回体兼容解析：
     * 常见字段：text/result/output/error/message 等。
     */
    private fun parseMiniMaxImagesUnderstandResponse(responseBody: String): ParsedResponse {
        val json = JSONObject(responseBody)
        // 兼容多种中转返回：有些会把成功状态放在 status 字段。
        val status = json.optString("status").lowercase()
        if (status == "error" || status == "failed") {
            val msg = json.optString("message").ifBlank {
                json.optString("msg", "Unknown MiniMax error")
            }
            throw LLMException("MiniMax images/understand error: $msg")
        }

        val code = json.optInt("code", 0)
        if (json.has("code") && code != 0) {
            val msg = json.optString("msg", json.optString("message", "Unknown MiniMax error"))
            throw LLMException("MiniMax images/understand error [$code]: $msg")
        }

        val errorObj = json.optJSONObject("error")
        if (errorObj != null) {
            val msg = errorObj.optString("message", "Unknown MiniMax error")
            throw LLMException("MiniMax images/understand error: $msg")
        }

        // 兼容 OpenAI 风格 content 数组：[{type:text,text:"..."}]
        fun extractTextFromContentArray(array: JSONArray?): String? {
            if (array == null) return null
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val text = item.optString("text").takeIf { it.isNotBlank() }
                if (!text.isNullOrBlank()) return text
            }
            return null
        }

        // 兼容 choices[0].message.content（string 或 array）结构
        fun extractTextFromChoices(choices: JSONArray?): String? {
            if (choices == null || choices.length() == 0) return null
            val first = choices.optJSONObject(0) ?: return null
            val message = first.optJSONObject("message") ?: return null
            val direct = message.optString("content").takeIf { it.isNotBlank() }
            if (!direct.isNullOrBlank()) return direct
            return extractTextFromContentArray(message.optJSONArray("content"))
        }

        val dataObj = json.optJSONObject("data")
        val text = json.optString("text").takeIf { it.isNotBlank() }
            ?: json.optString("content").takeIf { it.isNotBlank() }
            ?: json.optString("result").takeIf { it.isNotBlank() }
            ?: json.optString("output").takeIf { it.isNotBlank() }
            ?: json.optString("answer").takeIf { it.isNotBlank() }
            ?: json.optString("caption").takeIf { it.isNotBlank() }
            ?: dataObj?.optString("text")?.takeIf { it.isNotBlank() }
            ?: dataObj?.optString("content")?.takeIf { it.isNotBlank() }
            ?: dataObj?.optString("result")?.takeIf { it.isNotBlank() }
            ?: dataObj?.optString("output")?.takeIf { it.isNotBlank() }
            ?: dataObj?.optString("answer")?.takeIf { it.isNotBlank() }
            ?: dataObj?.optString("caption")?.takeIf { it.isNotBlank() }
            ?: extractTextFromChoices(json.optJSONArray("choices"))
            ?: extractTextFromChoices(dataObj?.optJSONArray("choices"))
            ?: extractTextFromContentArray(json.optJSONArray("content"))
            ?: extractTextFromContentArray(dataObj?.optJSONArray("content"))
            ?: json.optJSONArray("results")?.optJSONObject(0)?.optString("text")?.takeIf { it.isNotBlank() }
            ?: dataObj?.optJSONArray("results")?.optJSONObject(0)?.optString("text")?.takeIf { it.isNotBlank() }

        if (text.isNullOrBlank()) {
            val preview = responseBody
                .replace(Regex("\\s+"), " ")
                .take(240)
            val msg = json.optString("message").ifBlank {
                "MiniMax images/understand 返回为空（响应预览: $preview）"
            }
            throw LLMException(msg)
        }

        return ParsedResponse(content = text, finishReason = "stop")
    }

    /**
     * 解析文本格式的 function call（支持多种格式）
     *
     * 支持的格式：
     * 1. <|FunctionCallBegin|>[{"name":"xxx","parameters":{...}}]<|FunctionCallEnd|>
     * 2. ```json\n{"name":"xxx","parameters":{...}}\n```
     * 3. 纯 JSON（content 整体就是一个 {"name":"...","parameters":{...}} ）
     */
    private data class TextFunctionCallResult(
        val toolCalls: List<ToolCall>,
        val remainingContent: String?
    )

    private fun parseTextBasedFunctionCalls(content: String): TextFunctionCallResult {
        val toolCalls = mutableListOf<ToolCall>()
        var callIndex = 0
        var remaining = content

        // Pattern 1: <|FunctionCallBegin|>...<|FunctionCallEnd|>
        val tagPattern = Regex("""<\|FunctionCallBegin\|>(.*?)<\|FunctionCallEnd\|>""", RegexOption.DOT_MATCHES_ALL)
        val tagMatches = tagPattern.findAll(content).toList()
        if (tagMatches.isNotEmpty()) {
            for (match in tagMatches) {
                parseToolCallJson(match.groupValues[1].trim(), toolCalls, callIndex)
                callIndex = toolCalls.size
            }
            remaining = tagPattern.replace(content, "").trim()
        }

        // Pattern 2: ```json\n{...}\n``` 或 ```\n{...}\n```（markdown 代码块）
        if (toolCalls.isEmpty()) {
            val codeBlockPattern = Regex("""```(?:json)?\s*\n?(.*?)\n?```""", RegexOption.DOT_MATCHES_ALL)
            val codeBlockMatches = codeBlockPattern.findAll(content).toList()
            for (match in codeBlockMatches) {
                val jsonStr = match.groupValues[1].trim()
                if (looksLikeToolCall(jsonStr)) {
                    parseToolCallJson(jsonStr, toolCalls, callIndex)
                    callIndex = toolCalls.size
                }
            }
            if (toolCalls.isNotEmpty()) {
                remaining = codeBlockPattern.replace(content, "").trim()
            }
        }

        // Pattern 3: content 整体就是一个 JSON tool call（无任何包装）
        if (toolCalls.isEmpty()) {
            val trimmed = content.trim()
            if (trimmed.startsWith("{") && trimmed.endsWith("}") && looksLikeToolCall(trimmed)) {
                parseToolCallJson(trimmed, toolCalls, callIndex)
                if (toolCalls.isNotEmpty()) {
                    remaining = ""
                }
            }
        }

        return TextFunctionCallResult(
            toolCalls = toolCalls,
            remainingContent = remaining.takeIf { it.isNotBlank() }
        )
    }

    /**
     * 判断 JSON 字符串是否看起来像 tool call（包含 name 和 parameters/arguments）
     */
    private fun looksLikeToolCall(jsonStr: String): Boolean {
        return try {
            val obj = JSONObject(jsonStr)
            obj.has("name") && (obj.has("parameters") || obj.has("arguments"))
        } catch (e: Exception) {
            // 也可能是 JSON 数组
            try {
                val arr = JSONArray(jsonStr)
                arr.length() > 0 && arr.getJSONObject(0).has("name")
            } catch (e2: Exception) {
                false
            }
        }
    }

    /**
     * 解析 JSON 字符串为 ToolCall 并添加到列表
     */
    private fun parseToolCallJson(jsonStr: String, toolCalls: MutableList<ToolCall>, startIndex: Int) {
        var callIndex = startIndex
        // 尝试作为数组解析
        try {
            val jsonArray = JSONArray(jsonStr)
            for (i in 0 until jsonArray.length()) {
                val callObj = jsonArray.getJSONObject(i)
                extractToolCall(callObj, callIndex)?.let {
                    toolCalls.add(it)
                    callIndex++
                }
            }
            return
        } catch (_: Exception) {}

        // 尝试作为单个对象解析
        try {
            val callObj = JSONObject(jsonStr)
            extractToolCall(callObj, callIndex)?.let {
                toolCalls.add(it)
            }
        } catch (e: Exception) {
            Log.e("ApiAdapter", "Bot-Adaptor: 无法解析文本 function call: $jsonStr", e)
        }
    }

    /**
     * 从 JSONObject 提取 ToolCall
     */
    private fun extractToolCall(callObj: JSONObject, index: Int): ToolCall? {
        val name = callObj.optString("name", "")
        if (name.isBlank()) return null

        val params = callObj.optJSONObject("parameters")
            ?: callObj.optJSONObject("arguments")
            ?: JSONObject()

        return ToolCall(
            id = "call_botadaptor_$index",
            name = name,
            arguments = params.toString()
        )
    }

    // ============ OpenAI Chat Completions API ============

    private fun buildOpenAIRequest(
        model: ModelDefinition,
        messages: List<Message>,
        tools: List<NewToolDefinition>?,
        temperature: Double,
        maxTokens: Int?,
        reasoningEnabled: Boolean,
        provider: ProviderConfig? = null
    ): JSONObject {
        val json = JSONObject()

        json.put("model", model.id)
        json.put("temperature", temperature)

        // maxTokens field name (based on compatibility config + safe defaults)
        val modelIdLower = model.id.lowercase()
        val defaultMaxTokensField = when {
            modelIdLower.startsWith("gpt-5") -> "max_completion_tokens"
            modelIdLower.startsWith("o1") -> "max_completion_tokens"
            modelIdLower.startsWith("o3") -> "max_completion_tokens"
            modelIdLower.startsWith("gpt-4.1") -> "max_completion_tokens"
            else -> "max_tokens"
        }
        val maxTokensField = model.compat?.maxTokensField ?: defaultMaxTokensField
        json.put(maxTokensField, maxTokens ?: model.maxTokens)

        // Convert message format
        // 兼容严格网关（如部分阿里系上游）：system message 必须排在最前面。
        val normalizedMessages = normalizeSystemMessagesFirst(messages)
        val openaiMessages = JSONArray()
        normalizedMessages.forEach { message ->
            val msg = JSONObject()
            msg.put("role", message.role)
            val imageDataUrls = message.imageDataUrls.orEmpty()

            val hasToolCalls = !message.toolCalls.isNullOrEmpty()
            if (message.role == "user" && imageDataUrls.isNotEmpty()) {
                msg.put(
                    "content",
                    JSONArray().apply {
                        if (message.content.isNotBlank()) {
                            put(
                                JSONObject().apply {
                                    put("type", "text")
                                    put("text", message.content)
                                }
                            )
                        }
                        imageDataUrls.forEach { image ->
                            put(
                                JSONObject().apply {
                                    put("type", "image_url")
                                    put(
                                        "image_url",
                                        JSONObject().put("url", formatOpenAIImageUrl(image, provider))
                                    )
                                }
                            )
                        }
                    }
                )
            } else if (shouldUseNullContentForAssistantToolCall(message)) {
                // OpenAI-compatible tool call turns should send content=null rather than empty string.
                // Some providers reject the following tool result if the preceding assistant tool_calls
                // message used content="", then report: tool result's tool id not found.
                msg.put("content", JSONObject.NULL)
            } else {
                msg.put("content", message.content)
            }

            if (hasToolCalls) {
                val toolCallsArray = JSONArray()
                message.toolCalls!!.forEach { toolCall ->
                    toolCallsArray.put(JSONObject().apply {
                        put("id", toolCall.id)
                        put("type", "function")
                        put("function", JSONObject().apply {
                            put("name", toolCall.name)
                            put("arguments", toolCall.arguments)
                        })
                    })
                }
                msg.put("tool_calls", toolCallsArray)
            }

            if (message.toolCallId != null) {
                msg.put("tool_call_id", message.toolCallId)
            }

            openaiMessages.put(msg)
        }

        json.put("messages", openaiMessages)

        // Add tools (use Gson for proper JSON escaping — fixes description with special chars)
        if (!tools.isNullOrEmpty()) {
            val openaiTools = JSONArray()
            tools.forEach { tool ->
                openaiTools.put(buildToolJson(tool))
            }
            json.put("tools", openaiTools)
        }

        // Reasoning support (OpenAI o1/o3 models)
        if (reasoningEnabled && model.reasoning) {
            if (model.compat?.supportsReasoningEffort == true) {
                json.put("reasoning_effort", "medium")
            }
        }

        return json
    }

    /**
     * 规范化 system 消息：**合并所有 system 成 1 条** 并放到 messages 最前。
     *
     * 兼容动机（必须同时合并 + 前置，缺一不可）：
     * - OpenAI / Anthropic / Doubao / MiniMax 等大多数上游：允许 0~N 条 system，
     *   位置宽松（多数实现会自动拼接），仅前置即可正常工作。
     * - **Alibaba（阿里云通义）qwen 系列严格约束**：只允许 1 条 system，且必须
     *   在数组首位；多条 system（哪怕全部连续在最前）会被网关拒绝并返回 400：
     *   `InternalError.Algo.InvalidParameter: The provided messages input is invalid.
     *    The error info is [System message must be at the beginning.].`
     *   该错误典型出现在 OpenRouter 透传到 Alibaba（如 `qwen/qwen3.6-flash`）时。
     *
     * 合并语义保真度：N 条独立 system 与 1 条以双换行拼接的 system 在 LLM 视角下
     * 完全等价（都属于 system instructions 段），所以「合并」对 OpenAI 等宽松上游
     * 也不会引发回归——是兼容最严格上游的同时保持其他上游不变的最小修复。
     */
    private fun normalizeSystemMessagesFirst(messages: List<Message>): List<Message> {
        if (messages.size <= 1) return messages
        val systemMessages = messages.filter { it.role == "system" }
        if (systemMessages.isEmpty()) return messages
        val nonSystemMessages = messages.filter { it.role != "system" }
        // 仅 1 条时无需新建对象，避免不必要的字段拷贝丢失（如 name / toolCalls，虽然 system 通常不带）。
        val mergedSystem = if (systemMessages.size == 1) {
            systemMessages.first()
        } else {
            Message(
                role = "system",
                // 用空行分隔保留段落语义，便于 LLM 与日志肉眼区分原始来源。
                content = systemMessages.joinToString("\n\n") { it.content }
            )
        }
        return listOf(mergedSystem) + nonSystemMessages
    }

    /**
     * OpenRouter 图像输入要求使用 data URL（如 data:image/jpeg;base64,...）。
     * 其他 OpenAI 兼容链路沿用历史行为（纯 base64）以避免兼容性回归。
     */
    private fun formatOpenAIImageUrl(image: String, provider: ProviderConfig?): String {
        val base = provider?.baseUrl?.trim()?.lowercase().orEmpty()
        val isOpenRouter = base.contains("openrouter.ai")
        return if (isOpenRouter) {
            ensureJpegDataUrl(image)
        } else {
            stripImageDataUrlPrefix(image)
        }
    }

    /**
     * 解析标准 OpenAI chat.completions 形状；兼容外层包装 `{ code, data: { choices, ... } }`（部分聚合网关）。
     */
    private fun parseOpenAIResponse(responseBody: String): ParsedResponse {
        val json = JSONObject(responseBody)

        val payload = unwrapOpenAiChatCompletionPayload(json)
        if (payload != null) {
            return parseOpenAICompletionPayload(payload, json)
        }

        // 带 code 的整包（含 data 无 choices 等）走统一包装解析
        if (json.has("code")) {
            return parseCodeWrappedOpenAiChatResponse(responseBody)
        }

        throwMissingChoicesException(json, responseBody)
    }

    /** 返回含 choices 的对象：根上或 data 内；若发现 code!=0 则直接抛错 */
    private fun unwrapOpenAiChatCompletionPayload(json: JSONObject): JSONObject? {
        if (json.has("choices")) return json
        val data = json.optJSONObject("data") ?: return null
        if (!data.has("choices")) return null
        if (json.has("code")) {
            val c = json.optInt("code", 0)
            if (c != 0) {
                val msg = json.optString("msg", json.optString("message", "error"))
                Log.e("ApiAdapter", "Wrapped API error: code=$c msg=$msg")
                throw LLMException("API error [code=$c]: $msg")
            }
        }
        return data
    }

    private fun throwMissingChoicesException(json: JSONObject, responseBody: String): Nothing {
        val error = json.optJSONObject("error")
        if (error != null) {
            val msg = error.optString("message", "Unknown API error")
            val code = error.optString("code", "")
            Log.e("ApiAdapter", "API returned error instead of choices: [$code] $msg")
            throw LLMException("API error: $msg")
        }
        val topMsg = json.optString("message", "")
        if (topMsg.isNotBlank()) {
            throw LLMException("API error: $topMsg")
        }
        val keys = json.keys().asSequence().toList().take(16).joinToString(", ")
        val truncated = if (responseBody.length > 600) responseBody.substring(0, 600) + "..." else responseBody
        Log.e("ApiAdapter", "API response missing 'choices'. keys=[$keys] body=$truncated")
        throw LLMException(
            "API response missing 'choices' field. Top-level JSON keys: [$keys]. " +
                "若为 `{ code, data }` 包装的 Chat Completions，请确认 `data` 内包含 `choices` 且服务端返回成功 code。"
        )
    }

    /** 解析单个 OpenAI choices 对象（根或已解包的 data） */
    private fun parseOpenAICompletionPayload(payload: JSONObject, rootForUsage: JSONObject): ParsedResponse {
        if (!payload.has("choices")) {
            throwMissingChoicesException(payload, payload.toString())
        }

        val choices = payload.getJSONArray("choices")
        if (choices.length() == 0) {
            return ParsedResponse(content = null)
        }

        val choice = choices.getJSONObject(0)
        val message = choice.getJSONObject("message")

        val content = if (message.isNull("content")) null else message.optString("content")
        val toolCallsArray = message.optJSONArray("tool_calls")
        val toolCalls = if (toolCallsArray != null) {
            mutableListOf<ToolCall>().apply {
                for (i in 0 until toolCallsArray.length()) {
                    val tc = toolCallsArray.getJSONObject(i)
                    val function = tc.getJSONObject("function")
                    add(
                        ToolCall(
                            id = tc.getString("id"),
                            name = function.getString("name"),
                            arguments = function.optString("arguments", "{}")
                        )
                    )
                }
            }
        } else null

        val usageSource = if (payload.has("usage")) payload else rootForUsage
        val usage = usageSource.optJSONObject("usage")?.let {
            Usage(
                promptTokens = it.optInt("prompt_tokens", 0),
                completionTokens = it.optInt("completion_tokens", 0)
            )
        }

        return ParsedResponse(
            content = content,
            toolCalls = toolCalls,
            usage = usage,
            finishReason = choice.optString("finish_reason")
        )
    }

    private fun buildOpenAIResponsesRequest(
        model: ModelDefinition,
        messages: List<Message>,
        tools: List<NewToolDefinition>?,
        temperature: Double,
        maxTokens: Int?,
        reasoningEnabled: Boolean
    ): JSONObject {
        val json = JSONObject()
        json.put("model", model.id)
        json.put("temperature", temperature)
        json.put("max_output_tokens", maxTokens ?: model.maxTokens)

        val input = JSONArray()
        messages.forEach { message ->
            when (message.role) {
                "system" -> {
                    if (message.content.isNotBlank()) {
                        input.put(JSONObject().apply {
                            put("type", "message")
                            put("role", "system")
                            put("content", message.content)
                        })
                    }
                }
                "user" -> {
                    if (message.content.isNotBlank() || !message.imageDataUrls.isNullOrEmpty()) {
                        input.put(JSONObject().apply {
                            put("type", "message")
                            put("role", "user")
                            put(
                                "content",
                                JSONArray().apply {
                                    if (message.content.isNotBlank()) {
                                        put(
                                            JSONObject().apply {
                                                put("type", "input_text")
                                                put("text", message.content)
                                            }
                                        )
                                    }
                                    message.imageDataUrls.orEmpty().forEach { image ->
                                        put(
                                            JSONObject().apply {
                                                put("type", "input_image")
                                                put("image_url", stripImageDataUrlPrefix(image))
                                            }
                                        )
                                    }
                                }
                            )
                        })
                    }
                }
                "assistant" -> {
                    if (message.content.isNotBlank()) {
                        input.put(JSONObject().apply {
                            put("type", "message")
                            put("role", "assistant")
                            put("content", message.content)
                        })
                    }
                    buildResponsesFunctionCallItems(message).forEach { input.put(it) }
                }
                "tool" -> {
                    buildResponsesFunctionCallOutputItem(message)?.let { input.put(it) }
                }
            }
        }
        json.put("input", input)

        if (!tools.isNullOrEmpty()) {
            val responsesTools = JSONArray()
            tools.forEach { tool ->
                responsesTools.put(JSONObject().apply {
                    put("type", "function")
                    put("name", tool.function.name)
                    put("description", tool.function.description)
                    put("parameters", buildParametersJson(tool.function.parameters))
                })
            }
            json.put("tools", responsesTools)
        }

        if (reasoningEnabled && model.reasoning && model.compat?.supportsReasoningEffort == true) {
            json.put("reasoning", JSONObject().apply {
                put("effort", "medium")
            })
        }

        return json
    }

    internal data class ResponsesFunctionCallItem(
        val type: String,
        val callId: String,
        val name: String,
        val arguments: String
    )

    internal data class ResponsesFunctionCallOutputItem(
        val type: String,
        val callId: String,
        val output: String
    )

    internal fun buildResponsesFunctionCallItemsSpec(message: Message): List<ResponsesFunctionCallItem> {
        return message.toolCalls?.map { toolCall ->
            ResponsesFunctionCallItem(
                type = "function_call",
                callId = toolCall.id,
                name = toolCall.name,
                arguments = toolCall.arguments
            )
        } ?: emptyList()
    }

    internal fun buildResponsesFunctionCallOutputItemSpec(message: Message): ResponsesFunctionCallOutputItem? {
        if (message.role != "tool" || message.toolCallId.isNullOrBlank()) return null
        return ResponsesFunctionCallOutputItem(
            type = "function_call_output",
            callId = message.toolCallId,
            output = message.content
        )
    }

    internal fun buildResponsesFunctionCallItems(message: Message): List<JSONObject> {
        return buildResponsesFunctionCallItemsSpec(message).map { item ->
            JSONObject().apply {
                put("type", item.type)
                put("call_id", item.callId)
                put("name", item.name)
                put("arguments", item.arguments)
            }
        }
    }

    internal fun buildResponsesFunctionCallOutputItem(message: Message): JSONObject? {
        val item = buildResponsesFunctionCallOutputItemSpec(message) ?: return null
        return JSONObject().apply {
            put("type", item.type)
            put("call_id", item.callId)
            put("output", item.output)
        }
    }

    private fun parseOpenAIResponsesResponse(responseBody: String): ParsedResponse {
        val json = JSONObject(responseBody)

        val error = json.optJSONObject("error")
        if (error != null) {
            val msg = error.optString("message", "Unknown API error")
            throw LLMException("API error: $msg")
        }

        val output = json.optJSONArray("output") ?: JSONArray()
        var content: String? = null
        val toolCalls = mutableListOf<ToolCall>()

        for (i in 0 until output.length()) {
            val item = output.getJSONObject(i)
            when (item.optString("type")) {
                "message" -> {
                    val role = item.optString("role")
                    if (role == "assistant") {
                        val contentArray = item.optJSONArray("content")
                        if (contentArray != null) {
                            val text = buildString {
                                for (j in 0 until contentArray.length()) {
                                    val part = contentArray.getJSONObject(j)
                                    if (part.optString("type") == "output_text") {
                                        append(part.optString("text"))
                                    }
                                }
                            }.trim()
                            if (text.isNotEmpty()) {
                                content = if (content.isNullOrEmpty()) text else content + text
                            }
                        }
                    }
                }
                "function_call" -> {
                    val callId = item.optString("call_id")
                    val name = item.optString("name")
                    val arguments = item.optString("arguments", "{}")
                    if (callId.isNotBlank() && name.isNotBlank()) {
                        toolCalls.add(
                            ToolCall(
                                id = callId,
                                name = name,
                                arguments = arguments
                            )
                        )
                    }
                }
            }
        }

        val usage = json.optJSONObject("usage")?.let {
            Usage(
                promptTokens = it.optInt("input_tokens", 0),
                completionTokens = it.optInt("output_tokens", 0)
            )
        }

        val finishReason = when {
            toolCalls.isNotEmpty() -> "tool_calls"
            else -> json.optString("status")
        }

        return ParsedResponse(
            content = content,
            toolCalls = toolCalls.ifEmpty { null },
            usage = usage,
            finishReason = finishReason
        )
    }

    // ============ Ollama API ============

    private fun buildOllamaRequest(
        provider: ProviderConfig,
        model: ModelDefinition,
        messages: List<Message>,
        tools: List<NewToolDefinition>?,
        temperature: Double,
        maxTokens: Int?
    ): JSONObject {
        val json = buildOpenAIRequest(model, messages, tools, temperature, maxTokens, false)

        // Ollama special handling: may need to inject num_ctx
        if (provider.injectNumCtxForOpenAICompat == true) {
            json.put("options", JSONObject().apply {
                put("num_ctx", model.contextWindow)
            })
        }

        return json
    }

    // ============ GitHub Copilot API ============

    private fun buildCopilotRequest(
        model: ModelDefinition,
        messages: List<Message>,
        tools: List<NewToolDefinition>?,
        temperature: Double,
        maxTokens: Int?
    ): JSONObject {
        // GitHub Copilot uses OpenAI compatible format
        return buildOpenAIRequest(model, messages, tools, temperature, maxTokens, false)
    }

    /**
     * Build tool JSON with proper escaping (fixes description with special chars like quotes)
     * Replaces the broken tool.toString() → JSONObject approach
     */
    internal fun buildToolJson(tool: NewToolDefinition): JSONObject {
        val json = JSONObject()
        json.put("type", tool.type)

        val funcJson = JSONObject()
        funcJson.put("name", tool.function.name)
        funcJson.put("description", tool.function.description)  // JSONObject.put handles escaping
        val parametersJson = buildParametersJson(tool.function.parameters)
        funcJson.put("parameters", parametersJson)

        json.put("function", funcJson)
        return json
    }

    /**
     * Build parameters schema JSON with proper escaping
     */
    internal fun buildParametersJson(params: com.shijing.xomniclaw.providers.llm.ParametersSchema): JSONObject {
        val json = JSONObject()
        json.put("type", params.type)

        val propsJson = JSONObject()
        params.properties.forEach { (key, prop) ->
            val propJson = JSONObject()
            propJson.put("type", prop.type)
            propJson.put("description", prop.description)  // Properly escaped
            prop.enum?.let { enumList ->
                val enumArray = JSONArray()
                enumList.forEach { enumArray.put(it) }
                propJson.put("enum", enumArray)
            }
            prop.items?.let { items ->
                val itemsJson = JSONObject()
                itemsJson.put("type", items.type)
                itemsJson.put("description", items.description)
                propJson.put("items", itemsJson)
            }
            prop.properties?.let { nested ->
                val nestedJson = JSONObject()
                nested.forEach { (nk, nv) ->
                    val nvJson = JSONObject()
                    nvJson.put("type", nv.type)
                    nvJson.put("description", nv.description)
                    nestedJson.put(nk, nvJson)
                }
                propJson.put("properties", nestedJson)
            }
            propsJson.put(key, propJson)
        }
        json.put("properties", propsJson)

        if (params.required.isNotEmpty()) {
            val reqArray = JSONArray()
            params.required.forEach { reqArray.put(it) }
            json.put("required", reqArray)
        }

        return json
    }
}

/**
 * 解析后的响应
 */
data class ParsedResponse(
    val content: String?,
    val toolCalls: List<ToolCall>? = null,
    val thinkingContent: String? = null,
    val usage: Usage? = null,
    val finishReason: String? = null
)

/**
 * Tool Call
 */
data class ToolCall(
    val id: String,
    val name: String,
    val arguments: String
)

/**
 * Token 使用统计
 */
data class Usage(
    val promptTokens: Int,
    val completionTokens: Int
) {
    val totalTokens: Int get() = promptTokens + completionTokens
}
