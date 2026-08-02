package com.xiaomo.feishu

/**
 * 飞书配置（CareClaw 精简版）
 *
 * 只保留核心字段：连接模式（仅 WebSocket）、DM/群组权限策略、消息分块与媒体开关。
 * 已砍掉 wiki/drive/bitable/task/perm 工具开关、webhook 配置、会话模式、历史记录限制等。
 */
data class FeishuConfig(
    // ===== 基础配置 =====
    val enabled: Boolean = false,
    val appId: String,
    val appSecret: String,
    val encryptKey: String? = null,
    val verificationToken: String? = null,

    // ===== 域名配置 =====
    val domain: String = "feishu", // "feishu", "lark", or custom domain

    // ===== 连接模式 =====
    val connectionMode: ConnectionMode = ConnectionMode.WEBSOCKET,

    // ===== DM 策略 =====
    val dmPolicy: DmPolicy = DmPolicy.ALLOWLIST,
    val allowFrom: List<String> = emptyList(),

    // ===== 群组策略 =====
    val groupPolicy: GroupPolicy = GroupPolicy.ALLOWLIST,
    val groupAllowFrom: List<String> = emptyList(),
    val requireMention: Boolean = true,

    // ===== 消息分块 =====
    val textChunkLimit: Int = 4000,
    val chunkMode: ChunkMode = ChunkMode.LENGTH,
    val maxTablesPerCard: Int = 3,

    // ===== 工具开关（只保留 chat/doc/urgent/media）=====
    val enableDocTools: Boolean = true,
    val enableChatTools: Boolean = true,
    val enableUrgentTools: Boolean = true
) {
    enum class ConnectionMode {
        WEBSOCKET
    }

    enum class DmPolicy {
        OPEN, PAIRING, ALLOWLIST
    }

    enum class GroupPolicy {
        OPEN, ALLOWLIST, DISABLED
    }

    enum class ChunkMode {
        LENGTH, NEWLINE
    }

    /**
     * 获取 API 基础 URL
     */
    fun getApiBaseUrl(): String {
        return when (domain.lowercase()) {
            "feishu" -> "https://open.feishu.cn"
            "lark" -> "https://open.larksuite.com"
            else -> domain // 自定义域名
        }
    }

    /**
     * 验证配置
     */
    fun validate(): Result<Unit> {
        if (appId.isBlank()) {
            return Result.failure(IllegalArgumentException("appId is required"))
        }
        if (appSecret.isBlank()) {
            return Result.failure(IllegalArgumentException("appSecret is required"))
        }
        return Result.success(Unit)
    }
}
