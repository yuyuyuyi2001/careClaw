package com.shijing.xomniclaw.agent.tools.selfcontrol

/**
 * Upstream reference (OmniClaw):
 * - ../omniclaw/src/gateway/(all)
 *
 * CareClaw adaptation: self-control runtime support.
 */


import android.content.Context
import android.util.Log
import com.shijing.xomniclaw.agent.tools.Skill
import com.shijing.xomniclaw.agent.tools.SkillResult
import com.shijing.xomniclaw.providers.FunctionDefinition
import com.shijing.xomniclaw.providers.ParametersSchema
import com.shijing.xomniclaw.providers.PropertySchema
import com.shijing.xomniclaw.providers.ToolDefinition
import com.shijing.xomniclaw.ui.floatwindow.SessionFloatWindow

/**
 * Self-Control Service Management Skill
 *
 * 控制 CareClaw 的悬浮窗与无障碍服务，让 AI Agent 能够：
 * - 显示/隐藏悬浮窗
 * - 检查服务运行状态
 *
 * 使用场景：
 * - 截图前隐藏悬浮窗
 * - 任务完成后显示结果
 * - 远程服务管理
 */
class ServiceControlSkill(private val context: Context) : Skill {
    companion object {
        private const val TAG = "ServiceControlSkill"

        object Operations {
            const val SHOW_FLOAT = "show_float"        // 显示悬浮窗
            const val HIDE_FLOAT = "hide_float"        // 隐藏悬浮窗
            const val CHECK_STATUS = "check_status"    // 检查服务状态
        }
    }

    override val name = "control_service"

    override val description = """
        控制 CareClaw 的服务和 UI 组件。

        支持操作：
        - show_float: 显示悬浮窗
        - hide_float: 隐藏悬浮窗
        - check_status: 检查服务运行状态

        使用场景：
        - 截图前需要隐藏悬浮窗: {"operation": "hide_float"}
        - 任务完成后显示结果: {"operation": "show_float"}

        注意：显示悬浮窗需要悬浮窗权限。
    """.trimIndent()

    override fun getToolDefinition(): ToolDefinition {
        return ToolDefinition(
            type = "function",
            function = FunctionDefinition(
                name = name,
                description = description,
                parameters = ParametersSchema(
                    type = "object",
                    properties = mapOf(
                        "operation" to PropertySchema(
                            type = "string",
                            description = "操作类型",
                            enum = listOf(
                                Operations.SHOW_FLOAT,
                                Operations.HIDE_FLOAT,
                                Operations.CHECK_STATUS
                            )
                        )
                    ),
                    required = listOf("operation")
                )
            )
        )
    }

    override suspend fun execute(args: Map<String, Any?>): SkillResult {
        val operation = args["operation"] as? String
            ?: return SkillResult.error("Missing required parameter: operation")

        return try {
            when (operation) {
                Operations.SHOW_FLOAT -> handleShowFloat()
                Operations.HIDE_FLOAT -> handleHideFloat()
                Operations.CHECK_STATUS -> handleCheckStatus()
                else -> SkillResult.error("Unknown operation: $operation")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Service control failed: $operation", e)
            SkillResult.error("服务控制失败: ${e.message}")
        }
    }

    private fun handleShowFloat(): SkillResult {
        return try {
            SessionFloatWindow.setAgentRunning(true, context)
            SkillResult.success(
                "悬浮窗已显示",
                mapOf("operation" to "show_float")
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show floating window", e)
            SkillResult.error("显示悬浮窗失败: ${e.message}")
        }
    }

    private fun handleHideFloat(): SkillResult {
        return try {
            SessionFloatWindow.setAgentRunning(false, context)
            SkillResult.success(
                "悬浮窗已隐藏",
                mapOf("operation" to "hide_float")
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to hide floating window", e)
            SkillResult.error("隐藏悬浮窗失败: ${e.message}")
        }
    }

    private fun handleCheckStatus(): SkillResult {
        return try {
            // 通过 ActivityManager 检查服务状态
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val services = activityManager.getRunningServices(Integer.MAX_VALUE)

            val accessibilityRunning = services.any {
                it.service.className.contains("PhoneAccessibilityService")
            }

            val floatWindowEnabled = SessionFloatWindow.isEnabled()

            val status = buildString {
                appendLine("【服务状态】")
                appendLine("悬浮窗开关: ${if (floatWindowEnabled) "已开启 ✓" else "已关闭 ✗"}")
                appendLine("无障碍服务: ${if (accessibilityRunning) "运行中 ✓" else "已停止 ✗"}")
            }

            SkillResult.success(
                status,
                mapOf(
                    "floating_window_enabled" to floatWindowEnabled,
                    "accessibility" to accessibilityRunning
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check service status", e)
            SkillResult.error("检查服务状态失败: ${e.message}")
        }
    }
}

