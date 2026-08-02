package com.shijing.xomniclaw.agent.tools

/**
 * Adapter: wraps SystemSettingsTool (Tool interface) as a Skill for AndroidToolRegistry.
 */

import android.content.Context
import com.shijing.xomniclaw.providers.ToolDefinition

class SystemSettingsSkill(context: Context) : Skill {
    private val systemSettingsTool = SystemSettingsTool(context)

    override val name: String = systemSettingsTool.name
    override val description: String = systemSettingsTool.description

    override fun getToolDefinition(): ToolDefinition = systemSettingsTool.getToolDefinition()

    override suspend fun execute(args: Map<String, Any?>): SkillResult {
        val result = systemSettingsTool.execute(args)
        return if (result.success) {
            SkillResult.success(result.content, result.metadata)
        } else {
            SkillResult.error(result.content)
        }
    }
}
