package com.shijing.xomniclaw.remote

import android.content.Context
import android.util.Log
import com.shijing.xomniclaw.core.MyApplication
import com.shijing.xomniclaw.safety.SafetyPolicy
import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject

/**
 * HTTP 入口（备用通道，CareClaw 精简版）
 *
 * 提供两个最小编接口：
 * - GET /api/status  ：状态查询（服务存活、版本、当前任务是否运行中）
 * - POST /api/inject ：手动指令注入（经过 SafetyPolicy 白名单 + 二次确认标记）
 *
 * 已删除原版 WebSocket 推送、WebUI 静态服务、RPC 协议全家桶。
 * 鉴权：请求头 `Authorization: Bearer <token>`，token 与飞书渠道共用安全配置。
 */
class GatewayHttp(
    context: Context,
    port: Int = DEFAULT_PORT,
    private val authToken: String? = null
) : NanoHTTPD(port) {

    companion object {
        private const val TAG = "GatewayHttp"
        const val DEFAULT_PORT = 8765
        private const val AUTH_HEADER_PREFIX = "Bearer "
    }

    private val appContext = context.applicationContext

    override fun serve(session: IHTTPSession): Response {
        return try {
            if (!authorize(session)) {
                return json(401, JSONObject().put("error", "unauthorized"))
            }
            when {
                session.method == Method.GET && session.uri == "/api/status" -> handleStatus()
                session.method == Method.POST && session.uri == "/api/inject" -> handleInject(session)
                else -> json(404, JSONObject().put("error", "not found: ${session.method} ${session.uri}"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "serve error", e)
            json(500, JSONObject().put("error", e.message ?: "internal error"))
        }
    }

    private fun authorize(session: IHTTPSession): Boolean {
        if (authToken.isNullOrBlank()) return true
        val header = session.headers["authorization"] ?: return false
        return header.startsWith(AUTH_HEADER_PREFIX) &&
            header.removePrefix(AUTH_HEADER_PREFIX) == authToken
    }

    private fun handleStatus(): Response {
        val running = runCatching {
            com.shijing.xomniclaw.data.model.TaskDataManager.getInstance()
                .getCurrentTaskData()?.getIsRunning() == true
        }.getOrDefault(false)
        return json(200, JSONObject().apply {
            put("status", "ok")
            put("service", "careclaw-gateway-http")
            put("version", "1.0.19")
            put("taskRunning", running)
            put("time", System.currentTimeMillis())
        })
    }

    private fun handleInject(session: IHTTPSession): Response {
        val body = readBody(session)
        val message = runCatching { JSONObject(body).optString("message") }
            .getOrElse { body.trim() }

        if (message.isBlank()) {
            return json(400, JSONObject().put("error", "empty message"))
        }

        // 安全层校验：白名单 + 高危标记
        val decision = SafetyPolicy.checkRemoteCommand(message)
        if (!decision.allowed) {
            SafetyPolicy.audit("http_inject_blocked", message.take(120))
            return json(403, JSONObject().apply {
                put("error", "blocked by safety policy")
                put("reason", decision.reason)
            })
        }

        // 复用聊天广播注入路径（与 ADB 测试接口一致）
        MyApplication.handleChatBroadcast(message)
        SafetyPolicy.audit("http_inject", "allowed=${decision.allowed} confirm=${decision.requireConfirm}")

        return json(200, JSONObject().apply {
            put("accepted", true)
            put("requireConfirm", decision.requireConfirm)
            put("reason", decision.reason)
        })
    }

    private fun readBody(session: IHTTPSession): String {
        val buffer = ByteArray(1024 * 64)
        val size = session.inputStream.read(buffer)
        return if (size > 0) String(buffer, 0, size, Charsets.UTF_8) else ""
    }

    private fun json(code: Int, obj: JSONObject): Response {
        return newFixedLengthResponse(
            Response.Status.lookup(code),
            "application/json; charset=utf-8",
            obj.toString()
        )
    }
}
