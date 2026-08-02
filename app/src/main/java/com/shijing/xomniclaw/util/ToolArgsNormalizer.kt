package com.shijing.xomniclaw.util

import com.google.gson.JsonParser

/**
 * 统一清洗模型输出的 tool args。
 *
 * 典型脏数据形态：
 * - "\n  \"act\"\n"
 * - "\n  \"tap\"\n"
 *
 * 这里递归清洗 Map / List / String，保证下游工具分发拿到稳定值。
 */
object ToolArgsNormalizer {

    @JvmStatic
    fun normalize(raw: Map<String, Any?>): Map<String, Any?> {
        return raw.mapValues { (_, value) -> normalizeValue(value) }
    }

    private fun normalizeValue(value: Any?): Any? {
        return when (value) {
            is Map<*, *> -> value.entries
                .mapNotNull { (key, nestedValue) ->
                    (key as? String)?.let { safeKey ->
                        safeKey to normalizeValue(nestedValue)
                    }
                }
                .toMap()
            is List<*> -> value.map { item -> normalizeValue(item) }
            is String -> normalizeStringScalar(value)
            else -> value
        }
    }

    @JvmStatic
    fun normalizeStringScalar(value: String): String {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return trimmed

        // 处理模型把标量又包成 JSON 字符串字面量的情况。
        if (trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
            val parsed = runCatching { JsonParser.parseString(trimmed) }.getOrNull()
            if (parsed != null && parsed.isJsonPrimitive && parsed.asJsonPrimitive.isString) {
                return parsed.asString.trim()
            }
        }

        // 仅裁掉控制字符导致的噪声，不擅自改普通文本。
        return if (value.any { it == '\n' || it == '\r' || it == '\t' }) {
            trimmed
        } else {
            value
        }
    }
}
