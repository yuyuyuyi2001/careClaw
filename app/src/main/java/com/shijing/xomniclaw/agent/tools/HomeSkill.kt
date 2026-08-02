package com.shijing.xomniclaw.agent.tools

/**
 * OmniClaw Source Reference:
 * - ../xomniclaw/src/agents/tools/(all)
 *
 * OmniClaw adaptation: agent tool implementation.
 */


import android.util.Log
import com.shijing.xomniclaw.accessibility.AccessibilityProxy
import com.shijing.xomniclaw.providers.FunctionDefinition
import com.shijing.xomniclaw.providers.ParametersSchema
import com.shijing.xomniclaw.providers.ToolDefinition

/**
 * Home Skill
 * Press Home button to return to main screen
 */
class HomeSkill : Skill {
    companion object {
        private const val TAG = "HomeSkill"
    }

    override val name = "home"
    override val description = "Press Home button to return to launcher"

    override fun getToolDefinition(): ToolDefinition {
        return ToolDefinition(
            type = "function",
            function = FunctionDefinition(
                name = name,
                description = description,
                parameters = ParametersSchema(
                    type = "object",
                    properties = emptyMap(),
                    required = emptyList()
                )
            )
        )
    }

    override suspend fun execute(args: Map<String, Any?>): SkillResult {
        if (!AccessibilityProxy.isConnected.value!!) {
            return SkillResult.error("Accessibility service not connected")
        }

        Log.d(TAG, "Pressing home button")
        return try {
            val success = AccessibilityProxy.pressHome()
            if (!success) {
                return SkillResult.error("Home button press failed")
            }

            // Wait for launcher to load
            kotlinx.coroutines.delay(300)

            SkillResult.success(
                "Home button pressed (waited 300ms for launcher)",
                mapOf("wait_time_ms" to 300)
            )
        } catch (e: Exception) {
            Log.e(TAG, "Home button press failed", e)
            SkillResult.error("Home button press failed: ${e.message}")
        }
    }
}
