package com.shijing.xomniclaw.agent.tools

/**
 * OmniClaw Source Reference:
 * - ../xomniclaw/src/agents/tools/(all)
 *
 * OmniClaw adaptation: single exec entry using internal Android shell.
 */

import com.shijing.xomniclaw.providers.FunctionDefinition
import com.shijing.xomniclaw.providers.ParametersSchema
import com.shijing.xomniclaw.providers.ToolDefinition

/**
 * `exec` tool — runs shell commands via Android's local Runtime.exec().
 *
 * All commands execute in the internal Android shell (fast, no SSH overhead).
 */
class ExecFacadeTool(
    workingDir: String? = null
) : Tool {

    private val internalExec: Tool = ExecTool(workingDir = workingDir)

    override val name: String = "exec"
    override val description: String = "Run shell commands on the Android device using internal shell."

    override fun getToolDefinition(): ToolDefinition {
        val base = internalExec.getToolDefinition()
        return ToolDefinition(
            type = base.type,
            function = FunctionDefinition(
                name = name,
                description = description,
                parameters = ParametersSchema(
                    type = base.function.parameters.type,
                    properties = base.function.parameters.properties,
                    required = base.function.parameters.required
                )
            )
        )
    }

    override suspend fun execute(args: Map<String, Any?>): ToolResult {
        return internalExec.execute(args)
    }
}
