package com.shijing.xomniclaw.agent.tools

/**
 * OmniClaw Source Reference:
 * - ../xomniclaw/src/gateway/(all)
 *
 * OmniClaw adaptation: explicit config write tool.
 */


import com.shijing.xomniclaw.gateway.methods.ConfigMethods
import com.shijing.xomniclaw.providers.FunctionDefinition
import com.shijing.xomniclaw.providers.ParametersSchema
import com.shijing.xomniclaw.providers.PropertySchema
import com.shijing.xomniclaw.providers.ToolDefinition

/**
 * Write value to /sdcard/.xomniclaw/xomniclaw.json by path.
 */
class ConfigSetTool(
    private val configMethods: ConfigMethods
) : Tool {
    override val name = "config_set"
    override val description = "Set a configuration value in xomniclaw.json by dot path"

    override fun getToolDefinition(): ToolDefinition {
        return ToolDefinition(
            type = "function",
            function = FunctionDefinition(
                name = name,
                description = description,
                parameters = ParametersSchema(
                    type = "object",
                    properties = mapOf(
                        "path" to PropertySchema("string", "Dot path, e.g. gateway.port"),
                        "value" to PropertySchema("string", "Value to write; strings like true/false are also accepted")
                    ),
                    required = listOf("path", "value")
                )
            )
        )
    }

    override suspend fun execute(args: Map<String, Any?>): ToolResult {
        val path = args["path"] as? String
            ?: return ToolResult.error("Missing required parameter: path")
        val rawValue = args["value"]
            ?: return ToolResult.error("Missing required parameter: value")

        val value: Any? = when (rawValue) {
            is String -> when (rawValue.lowercase()) {
                "true" -> true
                "false" -> false
                else -> rawValue
            }
            else -> rawValue
        }

        val result = configMethods.configSet(mapOf("path" to path, "value" to value))
        return if (result.success) {
            ToolResult.success(result.message)
        } else {
            ToolResult.error(result.message)
        }
    }
}
