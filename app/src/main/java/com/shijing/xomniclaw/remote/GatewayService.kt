package com.shijing.xomniclaw.remote

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log

/**
 * 承载 [GatewayHttp] 的后台服务。
 * 由 MyApplication 在配置开启时启动；停止时销毁服务。
 */
class GatewayService : Service() {
    companion object {
        private const val TAG = "GatewayService"
        private const val EXTRA_TOKEN = "auth_token"

        fun start(context: android.content.Context, authToken: String?) {
            val intent = Intent(context, GatewayService::class.java)
            intent.putExtra(EXTRA_TOKEN, authToken)
            context.startService(intent)
        }

        fun stop(context: android.content.Context) {
            context.stopService(Intent(context, GatewayService::class.java))
        }
    }

    private var server: GatewayHttp? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (server == null) {
            val token = intent?.getStringExtra(EXTRA_TOKEN)
            server = GatewayHttp(this, authToken = token).also { s ->
                try {
                    s.start()
                    Log.i(TAG, "HTTP 入口已启动: http://0.0.0.0:${GatewayHttp.DEFAULT_PORT}")
                } catch (e: Exception) {
                    Log.e(TAG, "HTTP 入口启动失败", e)
                    stopSelf()
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        server?.stop()
        server = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
