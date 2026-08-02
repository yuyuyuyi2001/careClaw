package com.shijing.xomniclaw.agent.tools

/**
 * OmniClaw Source Reference:
 * - ../xomniclaw/src/agents/tools/(all)
 *
 * OmniClaw adaptation: agent tool implementation.
 */


import android.content.Context
import android.util.Log
import com.shijing.xomniclaw.accessibility.AccessibilityProxy
import com.shijing.xomniclaw.core.MyApplication
import com.shijing.xomniclaw.providers.FunctionDefinition
import com.shijing.xomniclaw.providers.ParametersSchema
import com.shijing.xomniclaw.providers.PropertySchema
import com.shijing.xomniclaw.providers.ToolDefinition
import java.io.File

/**
 * Send Image Skill
 *
 * Purpose: Agent calls this tool to show images to the current user.
 * Scenario:
 * 1. In OmniClaw main UI, return local image path so chat bubble can preview it directly.
 * 2. In Feishu conversation context, send image to current Feishu conversation.
 *
 * Implementation:
 * - Prefer in-app preview when user is chatting inside OmniClaw main UI.
 * - Fallback to Feishu send only when Feishu channel is active and context matches.
 */
class FeishuSendImageSkill(private val context: Context) : Skill {
    companion object {
        private const val TAG = "FeishuSendImageSkill"
        private const val LLM_FUNCTION_DESCRIPTION = "Show a local image to the user. In OmniClaw main UI, prefer in-app preview from image_path. " +
            "Only send via Feishu when a Feishu channel is active. image_path is a readable file path (e.g. from screenshot flow)."
    }

    override val name = "send_image"
    override val description = "In-app image preview or Feishu send. See LLM_FUNCTION_DESCRIPTION in getToolDefinition."

    override fun getToolDefinition(): ToolDefinition {
        return ToolDefinition(
            type = "function",
            function = FunctionDefinition(
                name = name,
                description = LLM_FUNCTION_DESCRIPTION,
                parameters = ParametersSchema(
                    type = "object",
                    properties = mapOf(
                        "image_path" to PropertySchema(
                            type = "string",
                            description = "—"
                        )
                    ),
                    required = listOf("image_path")
                )
            )
        )
    }

    override suspend fun execute(args: Map<String, Any?>): SkillResult {
        val imagePath = args["image_path"] as? String
            ?: return SkillResult.error("Missing required parameter: image_path")

        Log.d(TAG, "Sending image: $imagePath")

        try {
            // Check file
            val imageFile = File(imagePath)
            if (!imageFile.exists()) {
                return SkillResult.error("Image file not found: $imagePath")
            }

            if (!imageFile.canRead()) {
                return SkillResult.error("Cannot read image file: $imagePath")
            }

            if (shouldUseInAppPreview()) {
                Log.i(TAG, "🖼️ Using in-app preview for image: ${imageFile.absolutePath}")
                return SkillResult.success(
                    content = buildString {
                        appendLine("Image ready for in-app preview:")
                        append(imageFile.absolutePath)
                    },
                    metadata = mapOf(
                        "delivery" to "in_app_preview",
                        "image_path" to imageFile.absolutePath,
                        "file_size" to imageFile.length(),
                        "file_name" to imageFile.name
                    )
                )
            }

            // Get FeishuChannel
            val feishuChannel = MyApplication.getFeishuChannel()
            if (feishuChannel == null) {
                Log.w(TAG, "⚠️ Feishu channel not active, fallback to in-app preview")
                return SkillResult.success(
                    content = buildString {
                        appendLine("Image ready for in-app preview:")
                        append(imageFile.absolutePath)
                    },
                    metadata = mapOf(
                        "delivery" to "in_app_preview_fallback",
                        "image_path" to imageFile.absolutePath,
                        "file_size" to imageFile.length(),
                        "file_name" to imageFile.name
                    )
                )
            }

            // Send image to current conversation
            Log.i(TAG, "📤 Sending image to current chat: ${imageFile.name} (${imageFile.length()} bytes)")
            val result = feishuChannel.sendImageToCurrentChat(imageFile)

            if (result.isSuccess) {
                val messageId = result.getOrNull()
                Log.i(TAG, "✅ Image sent successfully. message_id: $messageId")
                return SkillResult.success(
                    content = "Image sent successfully to Feishu. message_id: $messageId",
                    metadata = mapOf(
                        "message_id" to (messageId ?: "unknown"),
                        "file_size" to imageFile.length(),
                        "file_name" to imageFile.name
                    )
                )
            } else {
                val error = result.exceptionOrNull()
                Log.e(TAG, "❌ Failed to send image", error)
                return SkillResult.error("Failed to send image: ${error?.message ?: "Unknown error"}")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to send image", e)
            return SkillResult.error("Failed to send image: ${e.message}")
        }
    }

    /**
     * 在主 UI 里，用户说“发一个看看”通常是让当前聊天界面直接显示，
     * 不应被误解成“发到飞书”。
     */
    private suspend fun shouldUseInAppPreview(): Boolean {
        val currentPackage = runCatching { AccessibilityProxy.getCurrentPackageName() }
            .getOrDefault("")
            .trim()
        return currentPackage == context.packageName
    }
}
