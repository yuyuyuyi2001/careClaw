package com.shijing.xomniclaw.util

import org.json.JSONObject

/**
 * 在发往 LLM 的文本中减少「无效 token 形状」的噪音：
 * 无障碍 / WebView 常带入 NBSP、全角空格等，对模型与计数益处不大，统一为半角空格。
 */
object LlmTextNormalize {

    /**
     * 将各类 Unicode 空白/不换行空白规范为普通 ASCII 空格，便于分词、略省 token。
     */
    fun forToolObservation(text: String): String {
        if (text.isEmpty()) return text
        return buildString(text.length) {
            for (c in text) {
                when (c) {
                    // NBSP、窄 NBSP、图空格等 → 半角空格
                    '\u00A0', '\u2007', '\u202F', '\u2009', '\u200A' -> append(' ')
                    // 全角空格 CJK
                    '\u3000' -> append(' ')
                    // BOM
                    '\uFEFF' -> { }
                    else -> append(c)
                }
            }
        }
    }
}

/**
 * [JSONObject.toString] 会把字符串内的 `/` 序列化为 `\/`（JSON 允许但无必要），多占 token。
 * 发出前压平为 `/` 仍为合法 JSON。依赖 body 的签名须使用本函数结果与请求体一致。
 */
fun JSONObject.requestBodyForWire(): String = toString().replace("\\/", "/")
