package com.shijing.xomniclaw.agent.skills

/**
 * OmniClaw Source Reference:
 * - ../xomniclaw/src/skills/(all)
 *
 * OmniClaw adaptation: workspace-first skill loading.
 */


import android.content.Context
import android.util.Log
import com.shijing.xomniclaw.config.ConfigLoader
import com.shijing.xomniclaw.workspace.WorkspaceInitializer
import java.io.File

/**
 * Skills Loader — 精简版技能加载（单层 workspace）。
 *
 * 技能来源只有一层：/sdcard/.xomniclaw/workspace/skills/
 * - 内置种子（首次启动由 WorkspaceInitializer 从 assets 拷入，不覆盖）
 * - 对话操作沉淀（用户/Agent 写入即生效）
 *
 * 特性：
 * - 每次 loadSkills() 全量重扫，永远最新（技能文件小，成本可忽略）
 * - 环境变量注入（skills.entries.<key>.env / apiKey）
 * - 统一 SkillParser（单一解析器）
 */
class SkillsLoader(private val context: Context) {
    companion object {
        private const val TAG = "SkillsLoader"

        // 唯一技能目录：workspace/skills（内置种子 + 对话沉淀）
        private const val WORKSPACE_SKILLS_DIR = "/sdcard/.xomniclaw/workspace/skills"

        // Skill file name
        private const val SKILL_FILE_NAME = "SKILL.md"

        /**
         * 最近一次技能筛选的可读摘要。
         * 设计为静态快照，便于 AgentLoop/KotlinBridge 跨实例读取并转发到 Python agentloop 日志链路。
         */
        @Volatile
        private var lastSelectionTraceForBridge: String = ""

        @JvmStatic
        fun getLastSelectionTraceForBridge(): String = lastSelectionTraceForBridge
    }

    // Config reference（供 resolveSkillEnv / checkRequirements 读取）
    private val configLoader = ConfigLoader(context)

    /**
     * Load all Skills
     * Source: workspace/skills only
     *
     * @return Map<name, SkillDocument>
     */
    fun loadSkills(): Map<String, SkillDocument> {
        // 每次全量重扫：技能文件小（单个几 KB），全量读取解析成本远小于一次 LLM 调用，
        // 换来「永远最新、无需缓存/指纹/监听」的最简实现。新写入的技能下次调用即生效。
        val skills = mutableMapOf<String, SkillDocument>()

        // 与 MyApplication 双保险：把 APK assets 里缺的种子技能补拷到 workspace/skills，
        // 避免「进程未冷启动、或初始化顺序」导致技能目录空壳。
        try {
            WorkspaceInitializer(context).ensureSeedSkills()
        } catch (e: Exception) {
            Log.w(TAG, "ensureSeedSkills（loadSkills 前）失败: ${e.message}")
        }

        val workspaceCount = loadWorkspaceSkills(skills)

        Log.i(TAG, "Skills 加载完成: 总计 ${skills.size} 个")
        Log.i(TAG, "  - Workspace: $workspaceCount")

        return skills
    }

    /**
     * Get all loaded skills
     */
    fun getAllSkills(): List<SkillDocument> {
        return loadSkills().values.toList()
    }

    /**
     * Get Always Skills (always-loaded skills)
     * These skills are loaded into system prompt at startup
     */
    fun getAlwaysSkills(): List<SkillDocument> {
        val allSkills = loadSkills()
        val alwaysSkills = allSkills.values.filter { it.metadata.always }
        Log.d(TAG, "Always Skills: ${alwaysSkills.size} 个")
        return alwaysSkills
    }

    /**
     * Select relevant Skills based on user goal.
     *
     * 设计变更（去正则化）：不再用关键词/正则做「是否注入」的硬过滤，
     * 凡 OS / requirements 满足的技能都会作为 catalog 候选进入 system prompt，
     * 由 LLM 自行根据描述选择 read_file 哪一条 SKILL.md。
     * 旧的 [identifyTaskType] / [matchesKeywords] 仅降级为「打分排序」用，
     * 让明显强相关的技能在 catalog 中靠前展示，弱模型也能优先看到。
     *
     * @param userGoal User goal/instruction
     * @param excludeAlways 排除 always 技能（catalog 列表去重时使用）。
     * @return 全量满足 requirements 的技能列表，按相关性打分降序。
     */
    fun selectRelevantSkills(
        userGoal: String,
        excludeAlways: Boolean = true
    ): List<SkillDocument> {
        val allSkills = loadSkills()
        val keywords = userGoal.lowercase()

        // 1. 候选 = 满足 requirements + 是否排除 always；不再按关键词过滤。
        val candidates = allSkills.values.filter { skill ->
            if (excludeAlways && skill.metadata.always) return@filter false
            checkRequirements(skill) is RequirementsCheckResult.Satisfied
        }

        // 2. 旧启发式只用来打分排序，便于弱模型优先看到高相关条目。
        val recommendedSkillNames = identifyTaskType(userGoal)
        val recommendedNameSet = recommendedSkillNames.toSet()
        val sorted = candidates.sortedByDescending { skill ->
            var score = 0
            if (recommendedNameSet.contains(skill.name)) score += 100
            if (keywords.contains(skill.name.lowercase())) score += 10
            if (skill.description.lowercase().contains(keywords)) score += 8
            if (matchesKeywords(skill, keywords)) score += 5
            score
        }

        val shortGoal = shortenForLog(userGoal)
        val header =
            "Skill catalog | goal=\"$shortGoal\" | total=${sorted.size}/${allSkills.size} | top_hint=${recommendedSkillNames.ifEmpty { listOf("<none>") }}"
        Log.i(TAG, header)
        val previewNames = sorted.take(5).map { it.name }
        if (previewNames.isNotEmpty()) {
            previewNames.forEach { Log.i(TAG, "  • $it") }
        } else {
            Log.i(TAG, "  (no candidate skills)")
        }
        // 给 Python agentloop 文件日志准备同款摘要，避免只能在 logcat 查看。
        lastSelectionTraceForBridge = buildString {
            appendLine(header)
            if (previewNames.isNotEmpty()) {
                previewNames.forEach { appendLine("  • $it") }
            } else {
                appendLine("  (no candidate skills)")
            }
        }.trimEnd()

        return sorted
    }

    private data class SkillSelectionDecision(
        val skill: SkillDocument,
        val matched: Boolean,
        val reason: String
    )

    /**
     * 输出可解释的命中原因，便于线上日志直接定位「为什么命中/漏命中」。
     */
    private fun evaluateSkillSelection(
        skill: SkillDocument,
        keywords: String,
        recommendedSkillNames: Set<String>,
        excludeAlways: Boolean
    ): SkillSelectionDecision {
        if (excludeAlways && skill.metadata.always) {
            return SkillSelectionDecision(
                skill = skill,
                matched = false,
                reason = "excluded_always"
            )
        }

        if (recommendedSkillNames.contains(skill.name)) {
            return SkillSelectionDecision(
                skill = skill,
                matched = true,
                reason = "task_type_recommend"
            )
        }

        if (keywords.contains(skill.name.lowercase())) {
            return SkillSelectionDecision(
                skill = skill,
                matched = true,
                reason = "skill_name_contains"
            )
        }

        if (skill.description.lowercase().contains(keywords)) {
            return SkillSelectionDecision(
                skill = skill,
                matched = true,
                reason = "description_contains_goal"
            )
        }

        if (matchesKeywords(skill, keywords)) {
            return SkillSelectionDecision(
                skill = skill,
                matched = true,
                reason = "keyword_heuristics"
            )
        }

        return SkillSelectionDecision(
            skill = skill,
            matched = false,
            reason = "no_match"
        )
    }

    /**
     * 避免日志里打印过长 query，保留首段关键语义即可。
     */
    private fun shortenForLog(text: String, maxLen: Int = 120): String {
        val normalized = text.replace(Regex("\\s+"), " ").trim()
        if (normalized.length <= maxLen) return normalized
        return normalized.take(maxLen - 1) + "…"
    }

    /**
     * Resolve environment variables for a skill from config entries.
     *
     * Aligns with OmniClaw: skills.entries.<key>.env and skills.entries.<key>.apiKey.
     * Returns a map of env vars to inject. Only includes vars not already set.
     *
     * @param skill The skill to resolve env for
     * @return Map of environment variable name -> value to inject
     */
    fun resolveSkillEnv(skill: SkillDocument): Map<String, String> {
        val config = configLoader.loadOmniClawConfig()
        val skillKey = skill.effectiveSkillKey()
        val skillConfig = config.skills.entries[skillKey] ?: return emptyMap()

        val result = mutableMapOf<String, String>()

        // 1. Apply env map (only if not already set in system env)
        skillConfig.env?.forEach { (key, value) ->
            if (System.getenv(key).isNullOrEmpty()) {
                result[key] = value
            }
        }

        // 2. Apply apiKey convenience (maps to primaryEnv)
        val primaryEnv = skill.metadata.primaryEnv
        val apiKeyValue = skillConfig.resolveApiKey()
        if (primaryEnv != null && apiKeyValue != null && System.getenv(primaryEnv).isNullOrEmpty()) {
            result[primaryEnv] = apiKeyValue
        }

        if (result.isNotEmpty()) {
            Log.d(TAG, "Skill '$skillKey' env injection: ${result.keys.joinToString()}")
        }

        return result
    }

    /**
     * Apply environment variables for a skill into the given env map.
     * Call this before launching an agent run.
     *
     * @param skill The skill
     * @param targetEnv The mutable environment map to inject into
     */
    fun applySkillEnv(skill: SkillDocument, targetEnv: MutableMap<String, String>) {
        val envVars = resolveSkillEnv(skill)
        targetEnv.putAll(envVars)
    }

    /**
     * Resolve and apply env vars for ALL loaded skills into a target env map.
     * Useful before starting an agent session.
     */
    fun applyAllSkillsEnv(targetEnv: MutableMap<String, String>) {
        val allSkills = loadSkills()
        allSkills.values.forEach { skill ->
            applySkillEnv(skill, targetEnv)
        }
    }

    /**
     * Check if Skill's dependency requirements are met
     */
    fun checkRequirements(skill: SkillDocument): RequirementsCheckResult {
        val requires = skill.metadata.requires
            ?: return RequirementsCheckResult.Satisfied

        if (!requires.hasRequirements()) {
            return RequirementsCheckResult.Satisfied
        }

        val missingBins = requires.bins.filter { !isBinaryAvailable(it) }
        val missingEnv = requires.env.filter { System.getenv(it) == null }
        val missingConfig = requires.config.filter { !isConfigAvailable(it) }

        // anyBins: at least one must be available
        val anyBinsMissing = if (requires.anyBins.isNotEmpty()) {
            requires.anyBins.none { isBinaryAvailable(it) }
        } else {
            false
        }

        if (missingBins.isEmpty() && missingEnv.isEmpty() && missingConfig.isEmpty() && !anyBinsMissing) {
            return RequirementsCheckResult.Satisfied
        }

        return RequirementsCheckResult.Unsatisfied(
            missingBins = missingBins,
            missingAnyBins = if (anyBinsMissing) requires.anyBins else emptyList(),
            missingEnv = missingEnv,
            missingConfig = missingConfig
        )
    }

    /**
     * Get Skill statistics
     */
    fun getStatistics(): SkillsStatistics {
        val skills = loadSkills()
        val alwaysSkills = skills.values.count { it.metadata.always }
        val onDemandSkills = skills.size - alwaysSkills
        val totalTokens = skills.values.sumOf { it.estimateTokens() }
        val alwaysTokens = skills.values.filter { it.metadata.always }.sumOf { it.estimateTokens() }

        return SkillsStatistics(
            totalSkills = skills.size,
            alwaysSkills = alwaysSkills,
            onDemandSkills = onDemandSkills,
            totalTokens = totalTokens,
            alwaysTokens = alwaysTokens
        )
    }

    // ==================== Private: Loading ====================

    /**
     * Load workspace Skills from /sdcard/.xomniclaw/workspace/skills/
     */
    private fun loadWorkspaceSkills(skills: MutableMap<String, SkillDocument>): Int {
        val workspaceDir = File(WORKSPACE_SKILLS_DIR)
        if (!workspaceDir.exists()) {
            Log.d(TAG, "Workspace Skills 目录不存在: $WORKSPACE_SKILLS_DIR")
            return 0
        }
        return loadSkillsFromDirectory(workspaceDir, SkillSource.WORKSPACE, skills)
    }

    /**
     * Generic: Load skills from a filesystem directory
     */
    private fun loadSkillsFromDirectory(
        dir: File,
        source: SkillSource,
        skills: MutableMap<String, SkillDocument>
    ): Int {
        var count = 0
        val skillDirs = dir.listFiles { file -> file.isDirectory } ?: emptyArray()
        Log.d(TAG, "扫描 ${source.displayName} Skills: ${skillDirs.size} 个目录 (${dir.absolutePath})")

        for (skillDir in skillDirs) {
            val skillFile = File(skillDir, SKILL_FILE_NAME)
            if (!skillFile.exists()) {
                if (source == SkillSource.WORKSPACE) {
                    Log.w(
                        TAG,
                        "⚠️ ${source.displayName} 技能目录「${skillDir.name}」存在但缺少 $SKILL_FILE_NAME: " +
                            "${skillDir.absolutePath}"
                    )
                }
                continue
            }

            try {
                val content = skillFile.readText()
                val skill = SkillParser.parse(content, skillFile.absolutePath)
                    .copy(source = source, filePath = skillFile.absolutePath)

                val isOverride = skills.containsKey(skill.name)
                skills[skill.name] = skill
                count++

                val action = if (isOverride) "覆盖" else "新增"
                Log.d(TAG, "✅ ${source.displayName} ($action): ${skill.name}")
            } catch (e: Exception) {
                Log.w(TAG, "❌ 加载 ${source.displayName} Skill 失败: ${skillDir.name} - ${e.message}")
            }
        }

        return count
    }

    // ==================== Private: Keyword Matching ====================

    /**
     * Keyword matching for skill selection
     */
    private fun matchesKeywords(skill: SkillDocument, keywords: String): Boolean {
        val matched = when (skill.name) {
            "gallery-qa" -> {
                hasGalleryQaConsumptionIntent(keywords)
            }
            "gallery-memory" -> {
                hasGalleryMemoryMaintenanceIntent(keywords)
            }
            "taobao-search" -> {
                // skill name 是英文 token，纯中文 query 走不到通用 nameTokens 兜底，
                // 这里给一个中文等价的命中：提到「淘宝/taobao」+「搜/找/比价/购物/商品/价格」等其一。
                (keywords.contains("淘宝") || keywords.contains("taobao")) &&
                    listOf(
                        "搜", "搜索", "查", "查找", "找", "看看",
                        "比价", "购物", "买", "下单",
                        "新款", "型号", "价格", "多少钱", "商品",
                    ).any { keywords.contains(it) }
            }
            else -> false
        }

        if (matched) return true

        // Generic fallback: match skill name tokens in user goal
        val nameTokens = skill.name.lowercase().split("-", "_")
        return nameTokens.any { token -> token.length >= 3 && keywords.contains(token) }
    }

    /**
     * Task type identification
     */
    private fun identifyTaskType(userGoal: String): List<String> {
        val keywords = userGoal.lowercase()
        val recommendedSkills = mutableListOf<String>()

        // app-search 是“跨 APP 搜索”聚合技能，需要在任务识别阶段优先命中，
        // 避免仅依赖通用 name token 匹配导致漏选。
        val appSearchAppHints = listOf(
            "拼多多", "美团", "高德", "高德地图",
            "抖音", "快手", "小红书", "红书",
            "哔哩哔哩", "b站", "bilibili",
            "知乎", "百度",
            "oppo音乐", "oppo软件商店", "软件商店", "应用商店"
        )
        val appSearchIntentHints = listOf("搜", "搜索", "查", "查找", "找")
        if (appSearchAppHints.any { keywords.contains(it.lowercase()) } &&
            appSearchIntentHints.any { keywords.contains(it) }) {
            recommendedSkills.add("app-search")
        }

        // taobao-search 是淘宝独立技能（不在 app-search 聚合里）。
        // 摄像头模式下「去淘宝上搜一下这个商品」会被 LocalVoiceVisionHub 拼成
        // "打开淘宝，搜索 xxx" 这样的 task 交给 AgentLoop；若不在此显式推荐，
        // 该 skill 在 catalog 里得分恒为 0，弱模型不会去 read_file SKILL.md，
        // 只会硬开包名再手动定位搜索框，反复多轮仍达不到 deep link 直达效果。
        val taobaoIntentHints = listOf(
            "搜", "搜索", "查", "查找", "找", "看看",
            "比价", "购物", "买", "下单",
            "新款", "型号", "价格", "多少钱", "商品",
        )
        if ((keywords.contains("淘宝") || keywords.contains("taobao")) &&
            taobaoIntentHints.any { keywords.contains(it) }
        ) {
            recommendedSkills.add("taobao-search")
        }

        if (keywords.contains("剪切板") || keywords.contains("剪贴板") || keywords.contains("clipboard")) {
            recommendedSkills.add("clipboard-to-shortcut")
        }

        if (hasGalleryQaConsumptionIntent(keywords)) {
            recommendedSkills.add("gallery-qa")
        }

        if (hasGalleryMemoryMaintenanceIntent(keywords)) {
            recommendedSkills.add("gallery-memory")
        }

        return recommendedSkills
    }

    /**
     * 判断用户是否在谈论图片/照片/截图等相册对象。
     */
    private fun hasPhotoRelatedTerms(keywords: String): Boolean {
        return keywords.contains("相册") || keywords.contains("照片") ||
            keywords.contains("图片") || keywords.contains("截图") ||
            keywords.contains("相片") || keywords.contains("拍了") ||
            keywords.contains("拍过") || keywords.contains("拍的")
    }

    /**
     * gallery-qa 是新的统一消费入口：只要是图片相关且不是维护意图，就优先走它。
     */
    private fun hasGalleryQaConsumptionIntent(keywords: String): Boolean {
        val hasOrganizationWords = keywords.contains("文件夹") ||
            keywords.contains("相册") ||
            keywords.contains("归档") ||
            keywords.contains("复制") ||
            keywords.contains("移动") ||
            keywords.contains("添加")
        return (hasPhotoRelatedTerms(keywords) || hasOrganizationWords) &&
            !hasGalleryMemoryMaintenanceIntent(keywords)
    }

    /**
     * 相册维护意图：用户要同步、重扫、画像更新、清空或重置。
     */
    private fun hasGalleryMemoryMaintenanceIntent(keywords: String): Boolean {
        return keywords.contains("扫描相册") ||
            keywords.contains("同步相册") ||
            keywords.contains("同步图片") ||
            keywords.contains("重扫") ||
            keywords.contains("重建画像") ||
            keywords.contains("更新画像") ||
            keywords.contains("用户画像") ||
            keywords.contains("清空") ||
            keywords.contains("重置") ||
            keywords.contains("gallery_memory") ||
            keywords.contains("ocr")
    }

    // ==================== Private: Requirements Checking ====================

    /**
     * Check if binary tool is available
     */
    private fun isBinaryAvailable(bin: String): Boolean {
        return try {
            val process = Runtime.getRuntime().exec("which $bin")
            val exitCode = process.waitFor()
            exitCode == 0
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Check if config item is available
     */
    private fun isConfigAvailable(configKey: String): Boolean {
        return try {
            val config = configLoader.loadOmniClawConfig()
            // Use dot-path resolution
            val parts = configKey.split(".")
            when {
                parts.size >= 2 && parts[0] == "gateway" -> {
                    when (parts.getOrNull(1)) {
                        "enabled" -> true
                        else -> false
                    }
                }
                else -> false
            }
        } catch (e: Exception) {
            false
        }
    }
}

/**
 * Requirements check result
 */
sealed class RequirementsCheckResult {
    object Satisfied : RequirementsCheckResult()

    data class Unsatisfied(
        val missingBins: List<String>,
        val missingAnyBins: List<String> = emptyList(),
        val missingEnv: List<String>,
        val missingConfig: List<String>
    ) : RequirementsCheckResult() {
        fun getErrorMessage(): String {
            val parts = mutableListOf<String>()
            if (missingBins.isNotEmpty()) {
                parts.add("缺少二进制工具: ${missingBins.joinToString()}")
            }
            if (missingAnyBins.isNotEmpty()) {
                parts.add("至少需要一个: ${missingAnyBins.joinToString()}")
            }
            if (missingEnv.isNotEmpty()) {
                parts.add("缺少环境变量: ${missingEnv.joinToString()}")
            }
            if (missingConfig.isNotEmpty()) {
                parts.add("缺少配置项: ${missingConfig.joinToString()}")
            }
            return parts.joinToString("; ")
        }
    }
}

/**
 * Skills statistics
 */
data class SkillsStatistics(
    val totalSkills: Int,
    val alwaysSkills: Int,
    val onDemandSkills: Int,
    val totalTokens: Int,
    val alwaysTokens: Int
) {
    fun getReport(): String {
        return """
Skills 统计:
  - 总计: $totalSkills 个
  - Always: $alwaysSkills 个
  - On-Demand: $onDemandSkills 个
  - Token 总量: $totalTokens tokens
  - Always Token: $alwaysTokens tokens
        """.trimIndent()
    }
}
