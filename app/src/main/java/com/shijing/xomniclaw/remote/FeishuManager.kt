package com.shijing.xomniclaw.remote

import android.content.Context
import android.util.Log
import com.shijing.xomniclaw.core.MainEntryNew
import com.shijing.xomniclaw.safety.SafetyPolicy
import com.shijing.xomniclaw.util.MMKVKeys
import com.tencent.mmkv.MMKV
import com.xiaomo.feishu.FeishuChannel
import com.xiaomo.feishu.FeishuConfig
import com.xiaomo.feishu.FeishuEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * 飞书渠道管理（CareClaw 精简版）
 *
 * 职责：
 * 1. 从 MMKV 读取飞书配置（appId/appSecret/allowlist），未配置或未启用则不启动。
 * 2. 启动 FeishuChannel（WebSocket 长连接）并收集消息事件。
 * 3. 权限校验：DM 走 allowlist；群聊要求 @机器人 且发送者在群组 allowlist。
 * 4. 安全层：所有指令经 SafetyPolicy 白名单校验，审计写入 audit.log。
 * 5. 放行后路由到 MainEntryNew.runWithSession，最终回复通过 remoteReply 回执到飞书。
 *
 * 已砍掉：bitable/wiki/drive/task/perm 工具、会话管理、Webhook 模式、Typing/Reaction。
 */
object FeishuManager {
    private const val TAG = "FeishuManager"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var channel: FeishuChannel? = null

    /**
     * 应用启动时调用；按 MMKV 配置决定是否拉起飞书长连接。
     */
    fun start(context: Context) {
        if (channel != null) {
            Log.i(TAG, "飞书渠道已在运行，跳过重复启动")
            return
        }

        val mmkv = MMKV.defaultMMKV()
        if (!mmkv.decodeBool(MMKVKeys.FEISHU_ENABLED.key, false)) {
            Log.i(TAG, "飞书渠道未启用（feishu_enabled=false），跳过启动")
            return
        }

        val appId = mmkv.decodeString(MMKVKeys.FEISHU_APP_ID.key, "").orEmpty()
        val appSecret = mmkv.decodeString(MMKVKeys.FEISHU_APP_SECRET.key, "").orEmpty()
        if (appId.isBlank() || appSecret.isBlank()) {
            Log.w(TAG, "飞书渠道已启用但 appId/appSecret 未配置，跳过启动")
            return
        }

        // 允许列表：逗号分隔的 open_id（子女/家人的飞书 open_id）
        val allowFrom = mmkv.decodeString(MMKVKeys.FEISHU_ALLOW_FROM.key, "")
            .orEmpty()
            .split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        val config = FeishuConfig(
            enabled = true,
            appId = appId,
            appSecret = appSecret,
            connectionMode = FeishuConfig.ConnectionMode.WEBSOCKET,
            dmPolicy = FeishuConfig.DmPolicy.ALLOWLIST,
            allowFrom = allowFrom,
            groupPolicy = FeishuConfig.GroupPolicy.ALLOWLIST,
            groupAllowFrom = allowFrom,
            requireMention = true,
            enableDocTools = true,
            enableChatTools = true,
            enableUrgentTools = true
        )

        scope.launch {
            val c = FeishuChannel(config)
            channel = c

            val result = c.start()
            if (result.isFailure) {
                Log.e(TAG, "飞书渠道启动失败: ${result.exceptionOrNull()?.message}")
                SafetyPolicy.audit("feishu_start_failed", result.exceptionOrNull()?.message.orEmpty())
                channel = null
                return@launch
            }

            Log.i(TAG, "✅ 飞书渠道已启动（allowlist=${allowFrom.size} 人）")
            SafetyPolicy.audit("feishu_started", "allowlist=${allowFrom.size}")

            c.eventFlow.collectLatest { event -> handleEvent(context.applicationContext, c, event) }
        }
    }

    /**
     * 停止飞书渠道（备用，暂未接线到 UI）。
     */
    fun stop() {
        channel?.stop()
        channel = null
        Log.i(TAG, "飞书渠道已停止")
    }

    private suspend fun handleEvent(app: Context, c: FeishuChannel, event: FeishuEvent) {
        when (event) {
            is FeishuEvent.Message -> handleMessage(app, c, event)
            is FeishuEvent.Error -> Log.e(TAG, "飞书渠道错误", event.error)
            FeishuEvent.Connected -> Log.i(TAG, "飞书渠道已连接")
            FeishuEvent.Disconnected -> Log.w(TAG, "飞书渠道连接断开")
        }
    }

    private suspend fun handleMessage(app: Context, c: FeishuChannel, event: FeishuEvent.Message) {
        // 只处理文本消息
        if (event.msgType != "text" || event.content.isBlank()) return

        // 记录当前对话上下文（供 Agent 回复回执定位）
        c.updateCurrentChatContext(event.chatId, "chat_id", event.messageId)

        // 1. 发送方权限校验
        val authorized = when (event.chatType) {
            "p2p" -> isDmAuthorized(c, event)
            else -> isGroupAuthorized(c, event)
        }
        if (!authorized) {
            Log.w(TAG, "未授权发送者 ${event.senderId} 于 ${event.chatId} 发送指令")
            SafetyPolicy.audit("feishu_denied", "sender=${event.senderId} chat=${event.chatId} content=${event.content.take(60)}")
            c.sender.sendTextMessage(event.chatId, "抱歉，当前设备未授权您使用远程助手。如需使用，请让设备所有者将您的 open_id 加入白名单。", "chat_id")
            return
        }

        // 2. 安全层白名单校验
        val decision = SafetyPolicy.checkRemoteCommand(event.content)
        if (!decision.allowed) {
            SafetyPolicy.audit("feishu_blocked", "sender=${event.senderId} reason=${decision.reason}")
            c.sender.sendTextMessage(event.chatId, "❌ 指令被安全策略拦截：${decision.reason}", "chat_id")
            return
        }

        SafetyPolicy.audit(
            "feishu_command",
            "sender=${event.senderId} chat=${event.chatId} action=matched confirm=${decision.requireConfirm} content=${event.content.take(120)}"
        )

        // 3. 回执 + 路由到 AgentLoop
        c.sender.sendTextMessage(event.chatId, "🔄 收到指令，正在执行：${event.content.take(80)}", "chat_id")
        MainEntryNew.runWithSession(
            userInput = event.content,
            sessionId = "feishu:${event.chatId}",
            application = app as android.app.Application,
            remoteReply = { reply ->
                scope.launch {
                    if (reply.isNotBlank()) {
                        c.sender.sendTextMessage(event.chatId, reply, "chat_id")
                    }
                }
            }
        )
    }

    private fun isDmAuthorized(c: FeishuChannel, event: FeishuEvent.Message): Boolean {
        return event.senderId in (c.getConfigAllowFrom())
    }

    private fun isGroupAuthorized(c: FeishuChannel, event: FeishuEvent.Message): Boolean {
        // 群聊：要求 @机器人（或 @all），且发送者在群组 allowlist
        val botOpenId = c.getBotOpenId()
        val mentioned = event.mentions.any { it == botOpenId || it == "all" }
        return mentioned && event.senderId in (c.getConfigAllowFrom())
    }
}

/**
 * 读取当前渠道配置的 allowlist（FeishuChannel 未暴露 config，这里从 MMKV 直接读取）。
 */
private fun FeishuChannel.getConfigAllowFrom(): List<String> {
    return MMKV.defaultMMKV()
        .decodeString(MMKVKeys.FEISHU_ALLOW_FROM.key, "")
        .orEmpty()
        .split(",")
        .map { it.trim() }
        .filter { it.isNotEmpty() }
}
