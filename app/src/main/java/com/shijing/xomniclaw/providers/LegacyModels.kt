package com.shijing.xomniclaw.providers

/**
 * OmniClaw Source Reference:
 * - ../omniclaw/src/agents/model-(all)
 *
 * OmniClaw adaptation: provider dispatch and compatibility.
 */


import com.google.gson.annotations.SerializedName

/**
 * Legacy LLM API 数据模型
 * Supports Claude Opus 4.6 with Extended Thinking
 */

// ============= Request Models =============

data class LegacyMessage(
    val role: String,  // "system", "user", "assistant", "tool"
    val content: Any?,  // String
    val name: String? = null,  // tool name for tool role
    @SerializedName("tool_call_id")
    val toolCallId: String? = null,  // for tool role
    @SerializedName("tool_calls")
    val toolCalls: List<LegacyToolCall>? = null  // for assistant with tool calls
)

data class LegacyToolCall(
    val id: String,
    val type: String = "function",
    val function: LegacyFunction
)

data class LegacyFunction(
    val name: String,
    val arguments: String  // JSON string
)

// ============= Tool Definition Models =============

data class ToolDefinition(
    val type: String = "function",
    val function: FunctionDefinition
)

data class FunctionDefinition(
    val name: String,
    val description: String,
    val parameters: ParametersSchema
)

data class ParametersSchema(
    val type: String = "object",
    val properties: Map<String, PropertySchema>,
    val required: List<String> = emptyList()
)

data class PropertySchema(
    val type: String,  // "string", "number", "boolean", "array", "object"
    val description: String,
    val enum: List<String>? = null,
    val items: PropertySchema? = null,  // for array type
    val properties: Map<String, PropertySchema>? = null  // for object type
)


