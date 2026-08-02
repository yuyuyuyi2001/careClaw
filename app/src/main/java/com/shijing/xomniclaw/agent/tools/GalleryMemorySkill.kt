package com.shijing.xomniclaw.agent.tools

import android.content.Context
import android.util.Log
import com.shijing.xomniclaw.agent.memory.MemoryManager
import com.shijing.xomniclaw.agent.memory.gallery.GalleryMemoryWorkflow
import com.shijing.xomniclaw.config.ConfigLoader
import com.shijing.xomniclaw.providers.FunctionDefinition
import com.shijing.xomniclaw.providers.ParametersSchema
import com.shijing.xomniclaw.providers.PropertySchema
import com.shijing.xomniclaw.providers.ToolDefinition
import java.util.Locale

/**
 * gallery_memory skill
 *
 * 用于相册图片 -> 记忆沉淀 -> 用户画像生成这条链路。
 */
class GalleryMemorySkill(private val context: Context) : Skill {
    companion object {
        private const val TAG = "GalleryMemorySkill"
        private const val WORKSPACE_PATH = "/sdcard/.xomniclaw/workspace"
        private const val LLM_FUNCTION_DESCRIPTION = "Sync gallery to memory/IMAGE-MEMORY.md, build USER-PROFILE.md, status, reset/ clear. " +
            "action: sync|build_profile|status|reset_cursor|clear_image_memories|clear_user_profile|reset_all. " +
            "sync: optional max_images, force_rescan, update_profile. Other actions use the enum only unless noted in code."
    }

    override val name = "gallery_memory"
    override val description = "Gallery → memory → profile. See getToolDefinition LLM block for actions."

    override fun getToolDefinition(): ToolDefinition {
        return ToolDefinition(
            type = "function",
            function = FunctionDefinition(
                name = name,
                description = LLM_FUNCTION_DESCRIPTION,
                parameters = ParametersSchema(
                    type = "object",
                    properties = mapOf(
                        "action" to PropertySchema(
                            type = "string",
                            description = "—",
                            enum = listOf(
                                "sync",
                                "build_profile",
                                "status",
                                "reset_cursor",
                                "clear_image_memories",
                                "clear_user_profile",
                                "reset_all"
                            )
                        ),
                        "max_images" to PropertySchema(type = "number", description = "—"),
                        "force_rescan" to PropertySchema(type = "boolean", description = "—"),
                        "update_profile" to PropertySchema(type = "boolean", description = "—")
                    ),
                    required = listOf("action")
                )
            )
        )
    }

    override suspend fun execute(args: Map<String, Any?>): SkillResult {
        // LLM / JSON 桥接偶发会把 action 包成 `"sync"`，或把布尔/数字参数转成字符串。
        // 这里统一做宽松归一化，避免因为入参形态差异导致工具误报不支持。
        val action = normalizeActionToken(args["action"])
            ?: return SkillResult.error("Missing required parameter: action")

        val workflow = buildWorkflow()
        return try {
            when (action) {
                "sync" -> syncGalleryMemory(args, workflow)
                "build_profile" -> buildProfile(workflow)
                "status" -> SkillResult.success(workflow.getStatus())
                "reset_cursor" -> {
                    workflow.resetCursor()
                    SkillResult.success("Gallery scan cursor has been reset.")
                }
                "clear_image_memories" -> {
                    workflow.clearImageMemories()
                    SkillResult.success("IMAGE-MEMORY.md has been cleared and reset to the template.")
                }
                "clear_user_profile" -> {
                    workflow.clearUserProfile()
                    SkillResult.success("USER-PROFILE.md has been cleared and reset to the template.")
                }
                "reset_all" -> {
                    workflow.resetAll()
                    SkillResult.success("Gallery memory cursor, image memories, and user profile have all been reset.")
                }
                else -> SkillResult.error("Unsupported action: $action")
            }
        } catch (e: Exception) {
            Log.e(TAG, "gallery_memory execution failed", e)
            SkillResult.error("Failed to execute gallery_memory: ${e.message}")
        }
    }

    private suspend fun syncGalleryMemory(
        args: Map<String, Any?>,
        workflow: GalleryMemoryWorkflow
    ): SkillResult {
        val report = workflow.syncGalleryMemories(
            maxImages = coercePositiveInt(args["max_images"], default = 10),
            forceRescan = coerceBoolean(args["force_rescan"], default = false),
            updateProfile = coerceBoolean(args["update_profile"], default = true)
        )

        return SkillResult.success(
            content = buildString {
                appendLine("Gallery memory sync finished.")
                appendLine("Inspected: ${report.inspectedCount}")
                appendLine("Scanned: ${report.scannedCount}")
                appendLine("Written: ${report.writtenCount}")
                appendLine("Skipped: ${report.skippedCount}")
                appendLine("Profile updated: ${report.profileUpdated}")
                append(report.message)
            },
            metadata = mapOf(
                "inspected_count" to report.inspectedCount,
                "scanned_count" to report.scannedCount,
                "written_count" to report.writtenCount,
                "skipped_count" to report.skippedCount,
                "profile_updated" to report.profileUpdated
            )
        )
    }

    private suspend fun buildProfile(workflow: GalleryMemoryWorkflow): SkillResult {
        val profile = workflow.rebuildUserProfile()
        return SkillResult.success(
            content = buildString {
                appendLine("User profile rebuilt successfully.")
                append(profile)
            },
            metadata = mapOf(
                "profile_generated" to true
            )
        )
    }

    private fun buildWorkflow(): GalleryMemoryWorkflow {
        val configLoader = ConfigLoader(context)
        val openClawCfg = configLoader.loadOmniClawConfig()
        val embeddingProviders = openClawCfg.resolveProviders()
        val embeddingBaseUrl = embeddingProviders.values.firstOrNull()?.baseUrl ?: ""
        val embeddingApiKey = embeddingProviders.values.firstOrNull()?.apiKey ?: ""
        val memoryManager = MemoryManager(
            workspacePath = WORKSPACE_PATH,
            context = context,
            embeddingBaseUrl = embeddingBaseUrl,
            embeddingApiKey = embeddingApiKey
        )
        return GalleryMemoryWorkflow(
            context = context,
            memoryManager = memoryManager
        )
    }

    private fun normalizeActionToken(raw: Any?): String? {
        val text = when (raw) {
            null -> return null
            is String -> raw
            else -> raw.toString()
        }
            .trim()
            .removePrefix("\uFEFF")
            .trim()
            .removeSurrounding("\"")
            .removeSurrounding("'")
            .trim()
            .lowercase(Locale.getDefault())

        return text.takeIf { it.isNotBlank() }
    }

    private fun coercePositiveInt(raw: Any?, default: Int): Int {
        val parsed = when (raw) {
            null -> default
            is Number -> raw.toInt()
            is String -> raw.trim().toDoubleOrNull()?.toInt() ?: default
            else -> default
        }
        return parsed.coerceAtLeast(1)
    }

    private fun coerceBoolean(raw: Any?, default: Boolean): Boolean {
        return when (raw) {
            null -> default
            is Boolean -> raw
            is Number -> raw.toInt() != 0
            is String -> when (raw.trim().lowercase(Locale.getDefault())) {
                "true", "1", "yes", "y", "on" -> true
                "false", "0", "no", "n", "off" -> false
                else -> default
            }
            else -> default
        }
    }
}
