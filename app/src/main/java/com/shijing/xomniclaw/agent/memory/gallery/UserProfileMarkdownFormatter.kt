package com.shijing.xomniclaw.agent.memory.gallery

/**
 * 用户画像 Markdown 格式化器。
 *
 * 约定：
 * - `Recent Signals Details` 之后的内容不作为默认 bootstrap 加载目标；
 * - `ContextBuilder` 可利用该边界只加载稳定画像与短摘要。
 */
object UserProfileMarkdownFormatter {
    const val RECENT_SIGNALS_DETAILS_HEADER = "## Recent Signals Details"
    private const val MAX_PROFILE_CHARS = 6_000

    fun toMarkdown(profile: UserProfileV2): String {
        val content = buildString {
            appendLine("# USER-PROFILE")
            appendLine()
            appendLine("updated: ${profile.lastUpdated}")
            appendLine("version: compact-v3")
            appendLine("confidence: ${profile.confidenceNote}")
            appendLine()

            appendLine("## Stable")
            appendLine("- name: ${profile.identitySnapshot.preferredName}")
            appendLine("- roles: ${formatList(profile.identitySnapshot.likelyRoles.take(3))}")
            appendLine("- language: ${profile.identitySnapshot.primaryLanguage}")
            appendLine("- timezone: ${profile.identitySnapshot.timezone}")
            appendLine("- style: ${profile.stablePreferences.responseStyle}")
            appendLine("- topics: ${formatList(profile.stablePreferences.preferredTopics.take(5))}")
            appendLine("- content: ${formatList(profile.stablePreferences.contentPreferences.take(4))}")
            appendLine("- needs: ${formatList(profile.stablePreferences.recurringNeeds.take(5))}")
            appendLine("- routine: ${profile.lifestyleAndRoutine.activeTimePattern} / ${profile.lifestyleAndRoutine.planningStyle}")
            appendLine("- hints: ${formatList(profile.importantContextHints.take(5))}")
            appendLine("- risks: ${formatList(profile.privacyAndRiskNotes.avoidTopics.take(8))}")
            appendLine()

            appendLine("## Recent")
            appendLine("- topics_7d: ${formatList(profile.recentSignalsSummary.last7dTopics.take(4))}")
            appendLine("- gallery: ${profile.recentSignalsSummary.lastSyncSummary}")
            appendLine()

            appendLine("## Sources")
            appendLine("- image_memories_count: ${profile.provenance.imageMemoriesCount}")
            appendLine("- task_memory_available: ${profile.provenance.longTermMemoryAvailable}")
            appendLine("- recent_logs: ${profile.provenance.recentLogsLoaded}")
            appendLine("- files:")
            profile.provenance.generatedFrom.forEach { source ->
                appendLine("  - $source")
            }
            appendLine()

            appendLine(RECENT_SIGNALS_DETAILS_HEADER)
            appendLine("- focus:")
            if (profile.recentSignalsDetails.recentFocuses.isEmpty()) {
                appendLine("  - unknown")
            } else {
                profile.recentSignalsDetails.recentFocuses.take(4).forEach { focus ->
                    appendLine("  - $focus")
                }
            }
            appendLine("- highlights:")
            if (profile.recentSignalsDetails.latestMemoryHighlights.isEmpty()) {
                appendLine("  - unknown")
            } else {
                profile.recentSignalsDetails.latestMemoryHighlights.take(5).forEach { highlight ->
                    appendLine("  - $highlight")
                }
            }
        }
        return if (content.length <= MAX_PROFILE_CHARS) {
            content
        } else {
            content.take(MAX_PROFILE_CHARS).trimEnd() + "\n"
        }
    }

    private fun formatList(values: List<String>): String {
        if (values.isEmpty()) {
            return "[unknown]"
        }
        return values.joinToString(prefix = "[", postfix = "]", separator = ", ")
    }
}
