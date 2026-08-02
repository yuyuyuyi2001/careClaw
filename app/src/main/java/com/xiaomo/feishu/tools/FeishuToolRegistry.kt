package com.xiaomo.feishu.tools

import com.xiaomo.feishu.FeishuClient
import com.xiaomo.feishu.FeishuConfig
import com.xiaomo.feishu.tools.chat.FeishuChatTools
import com.xiaomo.feishu.tools.media.FeishuMediaTools

/**
 * 飞书工具注册中心（CareClaw 精简版）
 *
 * 只保留 chat / media 两类工具；
 * 已砍掉 wiki/drive/bitable/task/perm/doc/urgent 工具集。
 */
class FeishuToolRegistry(
    private val config: FeishuConfig,
    private val client: FeishuClient
) {
    private val chatTools = FeishuChatTools(config, client)
    private val mediaTools = FeishuMediaTools(config, client)

    /**
     * 获取所有工具
     */
    fun getAllTools(): List<FeishuToolBase> {
        return buildList {
            addAll(chatTools.getAllTools())
            addAll(mediaTools.getAllTools())
        }
    }

    /**
     * 获取所有启用的工具定义（用于 LLM）
     */
    fun getToolDefinitions(): List<ToolDefinition> {
        return getAllTools()
            .filter { it.isEnabled() }
            .map { it.getToolDefinition() }
    }

    /**
     * 根据名称获取工具
     */
    fun getTool(name: String): FeishuToolBase? {
        return getAllTools().find { it.name == name }
    }

    /**
     * 执行工具
     */
    suspend fun execute(name: String, args: Map<String, Any?>): ToolResult {
        val tool = getTool(name)
            ?: return ToolResult.error("Tool not found: $name")

        if (!tool.isEnabled()) {
            return ToolResult.error("Tool is disabled: $name")
        }

        return tool.execute(args)
    }

    /**
     * 获取工具统计
     */
    fun getStats(): ToolStats {
        val allTools = getAllTools()
        val enabledTools = allTools.filter { it.isEnabled() }

        return ToolStats(
            totalTools = allTools.size,
            enabledTools = enabledTools.size,
            toolsByCategory = mapOf(
                "chat" to chatTools.getAllTools().size,
                "media" to mediaTools.getAllTools().size
            )
        )
    }
}

/**
 * 工具统计
 */
data class ToolStats(
    val totalTools: Int,
    val enabledTools: Int,
    val toolsByCategory: Map<String, Int>
)
