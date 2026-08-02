package com.shijing.xomniclaw.agent.memory.evolution

import java.util.Locale

/**
 * 全局记忆进化的纯策略函数，便于单元测试，避免关键过滤逻辑散落在管理器中。
 */
object MemoryEvolutionPolicy {
    fun normalizeForDedupe(text: String): String {
        return text.lowercase(Locale.getDefault())
            .replace(Regex("\\s+"), "")
            .take(120)
    }

    fun isSensitive(text: String): Boolean {
        val sensitiveTerms = listOf("密码", "验证码", "token", "api key", "apikey", "secret", "身份证", "银行卡")
        return sensitiveTerms.any { text.contains(it, ignoreCase = true) }
    }

    fun compactGlobalMemory(content: String, maxChars: Int): String {
        if (content.length <= maxChars) {
            return content
        }
        val output = mutableListOf<String>()
        val sectionItems = linkedMapOf<String, MutableList<String>>()
        var currentSection: String? = null
        content.lines().forEach { line ->
            if (line.startsWith("## ")) {
                currentSection = line
                sectionItems.getOrPut(line) { mutableListOf() }
            } else if (line.startsWith("- ") && currentSection != null && !line.contains("暂无稳定记忆")) {
                sectionItems.getOrPut(currentSection!!) { mutableListOf() }.add(line)
            }
        }

        val requiredSkeleton = buildString {
            appendLine("# X-OmniClaw 全局记忆")
            appendLine()
            appendLine("此文件已按预算压缩，仅保留最近和最稳定的任务记忆。")
            MemoryCategory.values().forEach { category ->
                appendLine()
                appendLine(category.sectionTitle)
                appendLine("- 暂无稳定记忆。")
            }
        }.trim()
        if (requiredSkeleton.length >= maxChars) {
            return requiredSkeleton.take(maxChars)
        }

        output += "# X-OmniClaw 全局记忆"
        output += ""
        output += "此文件已按预算压缩，仅保留最近和最稳定的任务记忆。"
        val categories = MemoryCategory.values().toList()
        categories.forEachIndexed { index, category ->
            output += ""
            output += category.sectionTitle
            val kept = sectionItems[category.sectionTitle].orEmpty().takeLast(18)
            var wroteItem = false
            kept.forEach { item ->
                val futureSkeleton = categories.drop(index + 1).joinToString("\n") { nextCategory ->
                    "\n${nextCategory.sectionTitle}\n- 暂无稳定记忆。"
                }
                val candidate = (output + item).joinToString("\n")
                if (candidate.length + futureSkeleton.length <= maxChars) {
                    output += item
                    wroteItem = true
                }
            }
            if (!wroteItem) {
                output += "- 暂无稳定记忆。"
            }
        }
        return output.joinToString("\n").take(maxChars)
    }
}
