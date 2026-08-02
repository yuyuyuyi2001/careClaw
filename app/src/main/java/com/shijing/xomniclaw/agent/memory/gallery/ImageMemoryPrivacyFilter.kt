package com.shijing.xomniclaw.agent.memory.gallery

/**
 * 图片记忆隐私过滤器。
 *
 * 目标不是做绝对准确的 DLP，而是先提供一层保守过滤，避免高敏感内容直接进入长期记忆。
 */
object ImageMemoryPrivacyFilter {
    private val idCardPattern = Regex("\\b\\d{17}[\\dXx]\\b")
    private val phonePattern = Regex("\\b1\\d{10}\\b")
    private val bankCardPattern = Regex("\\b\\d{15,19}\\b")
    private val emailPattern = Regex("\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}\\b")

    private val highRiskKeywords = listOf(
        "身份证", "银行卡", "支付", "转账", "验证码", "密码", "账单", "收款",
        "订单号", "住址", "发票", "护照", "社保", "公积金", "聊天记录"
    )

    data class FilterResult(
        val sanitizedText: String,
        val sensitivity: String,
        val redacted: Boolean
    )

    fun sanitize(text: String): FilterResult {
        if (text.isBlank()) {
            return FilterResult(
                sanitizedText = "",
                sensitivity = "low",
                redacted = false
            )
        }

        var sanitized = text
        var redacted = false

        listOf(idCardPattern, phonePattern, bankCardPattern, emailPattern).forEach { pattern ->
            if (pattern.containsMatchIn(sanitized)) {
                sanitized = sanitized.replace(pattern, "[REDACTED]")
                redacted = true
            }
        }

        val normalized = sanitized.lowercase()
        val hasHighRiskKeywords = highRiskKeywords.any { normalized.contains(it.lowercase()) }
        val sensitivity = when {
            hasHighRiskKeywords -> "high"
            redacted -> "medium"
            else -> "low"
        }

        return FilterResult(
            sanitizedText = sanitized.trim(),
            sensitivity = sensitivity,
            redacted = redacted
        )
    }

    fun deriveTags(record: AlbumImageRecord, summary: String): List<String> {
        val tags = linkedSetOf<String>()
        val bucket = record.bucketName.orEmpty().lowercase()
        val text = summary.lowercase()

        if (bucket.contains("screenshot") || bucket.contains("截图")) tags += "screenshot"
        if (bucket.contains("camera") || bucket.contains("相机")) tags += "camera"
        if (text.contains("工作") || text.contains("会议") || text.contains("文档")) tags += "work"
        if (text.contains("学习") || text.contains("课程") || text.contains("知识")) tags += "learning"
        if (text.contains("购物") || text.contains("商品") || text.contains("订单")) tags += "shopping"
        if (text.contains("旅行") || text.contains("酒店") || text.contains("机票")) tags += "travel"
        if (text.contains("聊天") || text.contains("消息") || text.contains("社交")) tags += "social"

        if (tags.isEmpty()) {
            tags += "uncategorized"
        }

        return tags.toList()
    }
}
