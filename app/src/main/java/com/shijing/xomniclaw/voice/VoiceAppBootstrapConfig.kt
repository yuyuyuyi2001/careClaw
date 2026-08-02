package com.shijing.xomniclaw.voice

import android.content.Context
import android.util.Log
import org.json.JSONObject

/**
 * 从 assets/bootstrap/APP_CONFIG.json 加载语音快捷规则（应用名到包名映射）。
 * 便于与 bootstrap 内其它可热更新资源一并维护，避免在 Kotlin 中硬编码长表。
 */
data class VoiceAppBootstrapConfig(
    val appPackages: Map<String, String>,
) {
    companion object {
        private const val TAG = "VoiceAppBootstrap"
        private const val ASSET_PATH = "bootstrap/APP_CONFIG.json"

        fun load(context: Context): VoiceAppBootstrapConfig {
            return try {
                context.assets.open(ASSET_PATH).bufferedReader().use { reader ->
                    parse(JSONObject(reader.readText()))
                }
            } catch (e: Exception) {
                Log.w(TAG, "load $ASSET_PATH failed: ${e.message}")
                VoiceAppBootstrapConfig(emptyMap())
            }
        }

        private fun parse(root: JSONObject): VoiceAppBootstrapConfig {
            val appMap = mutableMapOf<String, String>()
            root.optJSONObject("appPackages")?.let { obj ->
                val it = obj.keys()
                while (it.hasNext()) {
                    val k = it.next()
                    val v = obj.optString(k, "").trim()
                    if (k.isNotBlank() && v.isNotBlank()) appMap[k] = v
                }
            }
            return VoiceAppBootstrapConfig(appMap)
        }
    }
}
