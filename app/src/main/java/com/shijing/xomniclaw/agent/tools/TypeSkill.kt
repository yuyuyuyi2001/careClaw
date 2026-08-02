package com.shijing.xomniclaw.agent.tools

/**
 * OmniClaw Source Reference:
 * - ../xomniclaw/src/agents/tools/(all)
 *
 * OmniClaw adaptation: agent tool implementation.
 */


import android.content.Context
import android.util.Log
import com.shijing.xomniclaw.DeviceController
import com.shijing.xomniclaw.providers.FunctionDefinition
import com.shijing.xomniclaw.providers.ParametersSchema
import com.shijing.xomniclaw.providers.PropertySchema
import com.shijing.xomniclaw.providers.ToolDefinition

/**
 * Type Skill
 * Type text into the currently focused input field
 */
class TypeSkill(private val context: Context) : Skill {
    companion object {
        private const val TAG = "TypeSkill"
    }

    override val name = "type"
    override val description: String
        get() {
            val isAccessibilityEnabled = com.shijing.xomniclaw.accessibility.AccessibilityProxy.isConnected.value == true &&
                                        com.shijing.xomniclaw.accessibility.AccessibilityProxy.isServiceReady()
            val statusNote = when {
                isAccessibilityEnabled -> " ✅ 无障碍可用（中文依赖焦点控件）"
                else -> " ⚠️ 请先聚焦输入框并开启无障碍，否则中文可能失败"
            }
            return "Type text into focused input field (tap the field first).$statusNote"
        }

    override fun getToolDefinition(): ToolDefinition {
        return ToolDefinition(
            type = "function",
            function = FunctionDefinition(
                name = name,
                description = description,
                parameters = ParametersSchema(
                    type = "object",
                    properties = mapOf(
                        "text" to PropertySchema("string", "要输入的文本内容")
                    ),
                    required = listOf("text")
                )
            )
        )
    }

    override suspend fun execute(args: Map<String, Any?>): SkillResult {
        val text = args["text"] as? String

        if (text == null) {
            return SkillResult.error("Missing required parameter: text")
        }

        Log.d(TAG, "Typing text: $text")
        return try {
            // Type text
            DeviceController.inputText(text)

            // Wait for input completion + IME response (dynamically adjusted by text length)
            val waitTime = 100L + (text.length * 5L).coerceAtMost(300L) // Min 100ms, max 400ms
            kotlinx.coroutines.delay(waitTime)

            SkillResult.success(
                "Typed: $text (${text.length} chars)",
                mapOf(
                    "text" to text,
                    "length" to text.length,
                    "wait_time_ms" to waitTime
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Type failed", e)
            SkillResult.error("Type failed: ${e.message}")
        }
    }
}
