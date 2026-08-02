package com.shijing.xomniclaw.agent.tools

/**
 * OmniClaw Source Reference:
 * - ../xomniclaw/src/agents/tools/(all)
 *
 * OmniClaw adaptation: agent tool implementation.
 */


import android.util.Log
import com.shijing.xomniclaw.data.model.TaskDataManager
import com.shijing.xomniclaw.providers.FunctionDefinition
import com.shijing.xomniclaw.providers.ParametersSchema
import com.shijing.xomniclaw.providers.PropertySchema
import com.shijing.xomniclaw.providers.ToolDefinition

/**
 * Stop Skill
 * Stop current task execution
 */
class StopSkill(private val taskDataManager: TaskDataManager) : Skill {
    companion object {
        private const val TAG = "StopSkill"
    }

    override val name = "stop"
    override val description = "Stop current task execution"

    override fun getToolDefinition(): ToolDefinition {
        return ToolDefinition(
            type = "function",
            function = FunctionDefinition(
                name = name,
                description = description,
                parameters = ParametersSchema(
                    type = "object",
                    properties = mapOf(
                        "reason" to PropertySchema("string", "停止的原因")
                    ),
                    required = emptyList()
                )
            )
        )
    }

    override suspend fun execute(args: Map<String, Any?>): SkillResult {
        val reason = args["reason"] as? String ?: "Task completed"

        Log.d(TAG, "Stopping task: $reason")
        return try {
            // Set task status to stopped
            val taskData = taskDataManager.getCurrentTaskData()
            taskData?.stopRunning(reason)
            SkillResult.success(
                "Task stopped: $reason",
                mapOf("stopped" to true)
            )
        } catch (e: Exception) {
            Log.e(TAG, "Stop failed", e)
            SkillResult.error("Stop failed: ${e.message}")
        }
    }
}
