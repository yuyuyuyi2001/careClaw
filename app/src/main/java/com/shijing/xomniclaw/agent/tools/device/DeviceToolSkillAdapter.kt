package com.shijing.xomniclaw.agent.tools.device

/**
 * Adapter: wraps DeviceTool (Tool interface) as a Skill for AndroidToolRegistry.
 */

import android.content.Context
import com.shijing.xomniclaw.agent.tools.Skill
import com.shijing.xomniclaw.agent.tools.SkillResult
import com.shijing.xomniclaw.providers.ToolDefinition

class DeviceToolSkillAdapter(context: Context) : Skill {
    private val deviceTool = DeviceTool(context)

    override val name: String = deviceTool.name
    override val description: String = deviceTool.description

    override fun getToolDefinition(): ToolDefinition = deviceTool.getToolDefinition()

    override suspend fun execute(args: Map<String, Any?>): SkillResult {
        // ToolResult 即 SkillResult；原实现丢弃 metadata（如 multi_tap 的 tap_count/refs），须完整透传
        return deviceTool.execute(args)
    }
}
