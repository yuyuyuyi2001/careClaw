package com.shijing.xomniclaw.agent.tools

/**
 * OmniClaw Source Reference:
 * - ../xomniclaw/src/agents/tools/(all)
 *
 * OmniClaw adaptation: unified function-call dispatcher across universal tools and Android tools.
 */

import android.util.Log

/**
 * Unified tool/function dispatcher.
 *
 * Goal:
 * - Keep function-call scheduling closer to OmniClaw's single dispatch entry
 * - Avoid duplicating tool routing logic inside AgentLoop
 * - Keep universal tools separate from Android-specific skills
 */
class ToolCallDispatcher(
    private val toolRegistry: ToolRegistry,
    private val androidToolRegistry: AndroidToolRegistry
) {
    companion object {
        private const val TAG = "ToolCallDispatcher"
    }

    fun resolve(name: String): DispatchTarget? {
        return when {
            toolRegistry.contains(name) -> DispatchTarget.Universal(name)
            androidToolRegistry.contains(name) -> DispatchTarget.Android(name)
            else -> null
        }
    }

    suspend fun execute(name: String, args: Map<String, Any?>): SkillResult {
        return when (val target = resolve(name)) {
            is DispatchTarget.Universal -> {
                Log.d(TAG, "Dispatch → universal tool: ${target.name}")
                toolRegistry.execute(target.name, args)
            }
            is DispatchTarget.Android -> {
                Log.d(TAG, "Dispatch → android tool: ${target.name}")
                androidToolRegistry.execute(target.name, args)
            }
            null -> {
                Log.e(TAG, "Unknown function: $name")
                SkillResult.error("Unknown function: $name")
            }
        }
    }

    sealed class DispatchTarget(open val name: String) {
        data class Universal(override val name: String) : DispatchTarget(name)
        data class Android(override val name: String) : DispatchTarget(name)
    }
}
