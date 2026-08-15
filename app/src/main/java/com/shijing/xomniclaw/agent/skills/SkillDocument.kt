package com.shijing.xomniclaw.agent.skills

/**
 * OmniClaw Source Reference:
 * - ../xomniclaw/src/skills/(all)
 *
 * OmniClaw adaptation: loaded skill document model.
 */


/**
 * Skill Document Data Model
 * Corresponds to AgentSkills.io format
 *
 * File format:
 * ---
 * name: skill-name
 * description: Skill description
 * metadata:
 *   {
 *     "omniclaw": {
 *       "always": true,
 *       "emoji": "📱",
 *       "skillKey": "custom-key",
 *       "primaryEnv": "API_KEY",
 *       "homepage": "https://...",
 *       "os": ["darwin", "linux", "android"],
 *       "requires": {
 *         "bins": ["binary"],
 *         "anyBins": ["alt1", "alt2"],
 *         "env": ["ENV_VAR"],
 *         "config": ["config.key"]
 *       },
 *       "install": [...]
 *     }
 *   }
 * ---
 * # Skill Content
 * ...
 */
data class SkillDocument(
    /**
     * Skill name (unique identifier)
     * e.g.: "mobile-operations", "app-testing"
     */
    val name: String,

    /**
     * Skill description (1-2 sentences)
     * e.g.: "Core mobile device operation skills"
     */
    val description: String,

    /**
     * Skill metadata (parsed from metadata.omniclaw)
     */
    val metadata: SkillMetadata,

    /**
     * Skill body content (Markdown format)
     * This part will be injected into system prompt
     */
    val content: String,

    /**
     * Skill file path (for status reporting / debugging)
     */
    val filePath: String = "",

    /**
     * Skill source
     * "workspace" - /sdcard/.xomniclaw/workspace/skills/（技能唯一来源：内置种子 + 对话沉淀）
     */
    val source: SkillSource = SkillSource.WORKSPACE
) {
    /**
     * Get formatted content (with title)
     */
    fun getFormattedContent(): String {
        val emoji = metadata.emoji ?: ""
        val title = if (emoji.isNotEmpty()) "$emoji $name" else name
        return """
# $title

$content
        """.trim()
    }

    /**
     * Estimate token count (rough estimate: 1 token ≈ 4 characters)
     */
    fun estimateTokens(): Int {
        return (content.length / 4.0).toInt()
    }

    /**
     * Get the effective skill key (skillKey from metadata, fallback to name)
     * Aligns with OmniClaw: entries.<skillKey> maps to skill
     */
    fun effectiveSkillKey(): String {
        return metadata.skillKey ?: name
    }
}

/**
 * Skill Metadata — unified model covering all metadata.omniclaw fields.
 * Aligns with OmniClaw's OmniClawSkillMetadata.
 */
data class SkillMetadata(
    /**
     * Whether to always load (load at startup)
     * true: Load into all system prompts
     * false: Load on demand
     */
    val always: Boolean = false,

    /**
     * Custom skill key for config entries lookup
     * e.g.: entries.<skillKey>.enabled
     */
    val skillKey: String? = null,

    /**
     * Primary environment variable name
     * Used with apiKey convenience in config: entries.<key>.apiKey
     */
    val primaryEnv: String? = null,

    /**
     * Skill's emoji icon
     * e.g.: "📱", "🧪", "🐛"
     */
    val emoji: String? = null,

    /**
     * Homepage URL
     */
    val homepage: String? = null,

    /**
     * Supported OS platforms
     * e.g.: ["darwin", "linux", "win32", "android"]
     * null = no platform restriction
     */
    val os: List<String>? = null,

    /**
     * Skill dependency requirements
     */
    val requires: SkillRequires? = null,

    /**
     * Install specifications
     */
    val install: List<SkillInstallSpec>? = null
)

/**
 * Skill Source Enum
 * Aligns with OmniClaw's multi-tier architecture
 */
enum class SkillSource(val displayName: String) {
    WORKSPACE("workspace")   // /sdcard/.xomniclaw/workspace/skills/（技能唯一来源：内置种子 + 对话沉淀）
}

/**
 * Skill Dependency Requirements
 * Aligns with OmniClaw's requires field
 */
data class SkillRequires(
    /**
     * Required binary tools (all must exist)
     * e.g.: ["adb", "ffmpeg"]
     */
    val bins: List<String> = emptyList(),

    /**
     * At least one of these binaries must exist
     * e.g.: ["npm", "pnpm", "yarn"]
     */
    val anyBins: List<String> = emptyList(),

    /**
     * Required environment variables
     * e.g.: ["ANDROID_HOME", "PATH"]
     */
    val env: List<String> = emptyList(),

    /**
     * Required config paths (xomniclaw.json path checks)
     * e.g.: ["gateway.port"]
     */
    val config: List<String> = emptyList()
) {
    /**
     * Check if there are any dependencies
     */
    fun hasRequirements(): Boolean {
        return bins.isNotEmpty() || anyBins.isNotEmpty() || env.isNotEmpty() || config.isNotEmpty()
    }
}
