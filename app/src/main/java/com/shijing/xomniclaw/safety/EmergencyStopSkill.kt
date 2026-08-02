package com.shijing.xomniclaw.safety

import com.shijing.xomniclaw.data.model.TaskDataManager
import com.shijing.xomniclaw.providers.FunctionDefinition
import com.shijing.xomniclaw.providers.ParametersSchema
import com.shijing.xomniclaw.providers.PropertySchema
import com.shijing.xomniclaw.providers.ToolDefinition
import com.shijing.xomniclaw.agent.tools.Skill
import com.shijing.xomniclaw.agent.tools.SkillResult

/**
 * 一键停止（安全层）：远程/本地任何入口调用，立即终止当前任务。
 * 与 [StopSkill] 的区别：不经 LLM 工具调度，由远程渠道直接触发，保证「紧急停止」永远可用。
 */
class EmergencyStopSkill(private val taskDataManager: TaskDataManager) : Skill {
    override val name = "emergency_stop"
    override val description = "立即停止当前任务（紧急停止，不等待 LLM 决策）"

    override fun getToolDefinition(): ToolDefinition {
        return ToolDefinition(
            type = "function",
            function = FunctionDefinition(
                name = name,
                description = description,
                parameters = ParametersSchema(
                    type = "object",
                    properties = mapOf(
                        "reason" to PropertySchema("string", "停止原因")
                    ),
                    required = emptyList()
                )
            )
        )
    }

    override suspend fun execute(args: Map<String, Any?>): SkillResult {
        val reason = args["reason"] as? String ?: "紧急停止"
        SafetyPolicy.audit("emergency_stop", reason)
        val taskData = taskDataManager.getCurrentTaskData()
        taskData?.stopRunning(reason)
        return SkillResult.success("已紧急停止: $reason", mapOf("stopped" to true))
    }
}
