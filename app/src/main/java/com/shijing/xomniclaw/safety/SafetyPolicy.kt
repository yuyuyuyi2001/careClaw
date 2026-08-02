package com.shijing.xomniclaw.safety

import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 安全策略（CareClaw 核心安全层）
 *
 * 远程渠道（HTTP/飞书）收到的指令统一经过本策略校验：
 * 1. 白名单：只允许 CareClaw 支持的指令动作，拦截无关/危险指令。
 * 2. 二次确认：高危动作（安装/卸载/删除/外发）必须显式确认后才执行。
 * 3. 审计日志：所有远程指令与关键操作追加写入 /sdcard/.xomniclaw/audit.log。
 */
object SafetyPolicy {
    private const val TAG = "SafetyPolicy"

    /** 远程指令白名单：允许的动作关键词（大小写不敏感） */
    private val ALLOWED_ACTIONS = setOf(
        "install", "search", "open", "list", "check", "status",
        "memory", "gallery", "update", "backup", "stop", "help",
        "scan", "summary", "evolution"
    )

    /** 高危动作：执行前必须二次确认 */
    private val HIGH_RISK_ACTIONS = setOf(
        "install", "uninstall", "delete", "remove", "send", "pay"
    )

    /** 审计日志路径（与 workspace 同级，便于子女远程拉取排查） */
    private const val AUDIT_LOG_PATH = "/sdcard/.xomniclaw/audit.log"

    data class Decision(
        val allowed: Boolean,
        val reason: String,
        val requireConfirm: Boolean = false
    ) {
        companion object {
            fun allow(requireConfirm: Boolean = false, reason: String = "allowed"): Decision =
                Decision(true, reason, requireConfirm)

            fun deny(reason: String): Decision = Decision(false, reason, false)
        }
    }

    /**
     * 校验远程指令是否放行。
     * @param command 远程指令原文（如 "帮我安装微信" / "install wechat"）
     */
    fun checkRemoteCommand(command: String): Decision {
        val lower = command.lowercase(Locale.ROOT)
        val matchedAction = ALLOWED_ACTIONS.firstOrNull { lower.contains(it) }
            ?: return Decision.deny("指令不在白名单内（未识别到允许的动作关键词）")

        audit("remote_check", "action=$matchedAction allowed=true command=${command.take(120)}")

        val risk = HIGH_RISK_ACTIONS.any { lower.contains(it) }
        return Decision.allow(
            requireConfirm = risk,
            reason = "匹配白名单动作 $matchedAction${if (risk) "（高危，需二次确认）" else ""}"
        )
    }

    /** 高危动作判断（供远程渠道在回执里提示"请回复确认后执行"） */
    fun requiresConfirmation(command: String): Boolean {
        val lower = command.lowercase(Locale.ROOT)
        return HIGH_RISK_ACTIONS.any { lower.contains(it) }
    }

    /**
     * 追加审计日志。所有远程指令、高危操作、启动/停止事件都写这里。
     */
    fun audit(event: String, detail: String) {
        try {
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date())
            val line = "[$timestamp] [$event] $detail\n"
            val file = File(AUDIT_LOG_PATH)
            if (!file.parentFile.exists()) file.parentFile?.mkdirs()
            file.appendText(line)
            Log.d(TAG, "audit: $event $detail")
        } catch (e: Exception) {
            Log.e(TAG, "审计日志写入失败", e)
        }
    }
}
