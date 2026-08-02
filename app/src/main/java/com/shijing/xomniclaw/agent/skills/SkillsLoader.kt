package com.shijing.xomniclaw.agent.skills

/**
 * OmniClaw Source Reference:
 * - ../xomniclaw/src/skills/(all)
 *
 * OmniClaw adaptation: bundled/managed/workspace skill discovery and cache.
 */


import android.content.Context
import android.os.FileObserver
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.shijing.xomniclaw.config.ConfigLoader
import com.shijing.xomniclaw.workspace.WorkspaceInitializer
import java.io.File

/**
 * Skills Loader — unified skill loading with full OmniClaw alignment.
 *
 * Loading priority (higher overrides lower, by name dedup):
 * 1. extraDirs (lowest) — skills.extraDirs config
 * 2. Bundled Skills — assets/skills/
 * 3. Managed Skills — /sdcard/.xomniclaw/skills/
 * 4. Plugin Skills — enabled plugin skill directories
 * 5. Workspace Skills (highest) — /sdcard/.xomniclaw/workspace/skills/
 *
 * Features aligned with OmniClaw:
 * - extraDirs support (skills.extraDirs)
 * - Plugin skills (plugins.entries.<name>.skills dirs)
 * - Environment injection (skills.entries.<key>.env / apiKey)
 * - Hot reload with debounce (skills.watch / skills.watchDebounceMs)
 * - Managed + Workspace directory monitoring
 * - Unified SkillParser (no duplicate parsers)
 * - Consistent managed path (skills/ not .skills/)
 */
class SkillsLoader(private val context: Context) {
    companion object {
        private const val TAG = "SkillsLoader"

        // Three-tier Skills directories (aligns with OmniClaw architecture)
        private const val BUNDLED_SKILLS_PATH = "skills"  // assets path
        private const val MANAGED_SKILLS_DIR = "/sdcard/.xomniclaw/skills"  // aligns with ~/.xomniclaw/skills/
        private const val WORKSPACE_SKILLS_DIR = "/sdcard/.xomniclaw/workspace/skills"  // aligns with ~/.xomniclaw/workspace/

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

    // Skills cache
    private val skillsCache = mutableMapOf<String, SkillDocument>()
    private var cacheValid = false

    /**
     * 用户可写技能目录（managed / workspace）的指纹快照。
     *
     * 设计动机：
     * - `MainEntryNew` 是进程级单例，`ContextBuilder.skillsLoader` 整个 App 生命周期只有一份；
     *   首次 `loadSkills()` 后 `cacheValid=true` 会永久锁住。
     * - 现实中 `enableHotReload()` 没有任何调用方，且 `FileObserver` 不递归监听子目录，
     *   导致用户在 `/sdcard/.xomniclaw/workspace/skills/<name>/SKILL.md` 新增的技能
     *   永远进不了 catalog，必须重启 App 才能生效。
     * - 这里改用 mtime 指纹做"懒失效"：每次 `loadSkills()` 入口低成本采样
     *   managed/workspace 顶层 + 各子目录 + 各 SKILL.md 的 lastModified()，
     *   一旦与上次记录不同就主动 invalidate cache 并全量重扫。
     *   单次会话内的多次 loadSkills 仍走缓存，无重复磁盘 IO 风险。
     */
    @Volatile
    private var lastUserDirsFingerprint: Long = Long.MIN_VALUE

    // Config reference
    private val configLoader = ConfigLoader(context)

    // File monitoring (hot reload with debounce)
    private val fileObservers = mutableListOf<FileObserver>()
    private var hotReloadEnabled = false
    private val handler = Handler(Looper.getMainLooper())
    private var pendingReload: Runnable? = null

    /**
     * Load all Skills
     * Priority override: Workspace > Managed > Bundled > extraDirs
     *
     * @return Map<name, SkillDocument>
     */
    fun loadSkills(): Map<String, SkillDocument> {
        // 指纹校验：managed / workspace 目录任何 SKILL.md 增删改都会让指纹变化，
        // 这样无需重启 App 也无需 FileObserver，新录制的技能下一次会话立即生效。
        val currentFingerprint = computeUserDirsFingerprint()
        val cacheUsable = cacheValid &&
            skillsCache.isNotEmpty() &&
            currentFingerprint == lastUserDirsFingerprint
        if (cacheUsable) {
            Log.d(TAG, "返回缓存的 Skills (${skillsCache.size} 个)")
            return skillsCache.toMap()
        }
        if (cacheValid && currentFingerprint != lastUserDirsFingerprint) {
            Log.i(
                TAG,
                "检测到 Managed/Workspace skills 目录变化 (fp: $lastUserDirsFingerprint -> $currentFingerprint)，重新扫描"
            )
        }

        Log.d(TAG, "开始加载 Skills...")
        skillsCache.clear()

        // 与 MyApplication 双保险：首次/缓存失效时先把 APK assets 里缺的技能文件补拷到
        // /sdcard/.xomniclaw/skills/，避免「进程未冷启动、或初始化顺序」导致 managed 目录空壳。
        try {
            WorkspaceInitializer(context).ensureBundledSkills()
        } catch (e: Exception) {
            Log.w(TAG, "ensureBundledSkills（loadSkills 前）失败: ${e.message}")
        }

        val config = configLoader.loadOmniClawConfig()

        // Load by priority (lowest first, higher overrides)
        val extraCount = loadExtraDirsSkills(skillsCache, config.skills.extraDirs)
        val bundledCount = loadBundledSkills(skillsCache)
        val managedCount = loadManagedSkills(skillsCache)
        val pluginCount = loadPluginSkills(skillsCache, config)
        val workspaceCount = loadWorkspaceSkills(skillsCache)

        cacheValid = true
        // 重扫完成后用「重扫前采样的指纹」作为基线：若用户恰好在加载过程中又写盘，
        // 下次调用时新指纹会与基线不同 → 再次触发失效，不会漏吃改动。
        lastUserDirsFingerprint = currentFingerprint

        Log.i(TAG, "Skills 加载完成: 总计 ${skillsCache.size} 个")
        Log.i(TAG, "  - extraDirs: $extraCount")
        Log.i(TAG, "  - Bundled: $bundledCount")
        Log.i(TAG, "  - Managed: $managedCount (覆盖)")
        Log.i(TAG, "  - Plugin: $pluginCount (覆盖)")
        Log.i(TAG, "  - Workspace: $workspaceCount (覆盖)")

        return skillsCache.toMap()
    }

    /**
     * 计算 managed + workspace 两个用户可写目录的「轻量指纹」。
     *
     * 采样维度：
     *  - 顶层目录 mtime（子目录被增/删时变化）
     *  - 每个 skill 子目录 mtime（其内 SKILL.md 被增/删时变化）
     *  - 每个 SKILL.md mtime（文件内容被改写时变化）
     *  - 子目录名（极端场景：删旧 + 新建同名子目录、mtime 一致）
     *
     * 仅做磁盘元数据查询（不读文件内容），开销远小于一次 LLM 调用。
     * extraDirs/plugin 来源属高级配置，刷新该来源仍需 reload()/重启。
     */
    private fun computeUserDirsFingerprint(): Long {
        var hash = 1125899906842597L  // FNV-1a offset basis (64-bit)
        hash = mixDirIntoFingerprint(File(MANAGED_SKILLS_DIR), hash)
        hash = mixDirIntoFingerprint(File(WORKSPACE_SKILLS_DIR), hash)
        return hash
    }

    private fun mixDirIntoFingerprint(dir: File, seed: Long): Long {
        if (!dir.exists()) {
            // 用 absolutePath.hashCode() 区分 "managed 缺失" / "workspace 缺失"，
            // 避免两者都不存在时指纹完全相同。
            return seed * 31 + dir.absolutePath.hashCode().toLong()
        }
        var h = seed * 31 + dir.lastModified()
        // 仅枚举 SKILL.md 所在的子目录；按名字排序保证指纹与 listFiles 实现顺序无关。
        val children = (dir.listFiles { f -> f.isDirectory } ?: emptyArray())
            .sortedBy { it.name }
        for (child in children) {
            h = h * 31 + child.name.hashCode().toLong()
            h = h * 31 + child.lastModified()
            val skillFile = File(child, SKILL_FILE_NAME)
            if (skillFile.exists()) {
                h = h * 31 + skillFile.lastModified()
            }
        }
        return h
    }

    /**
     * Reload Skills (clear cache)
     */
    fun reload() {
        Log.i(TAG, "重新加载 Skills...")
        cacheValid = false
        loadSkills()
    }

    /**
     * Enable hot reload with debounce.
     * Monitors Workspace + Managed directories.
     * Aligns with OmniClaw: skills.watch + skills.watchDebounceMs
     */
    fun enableHotReload() {
        if (hotReloadEnabled) {
            Log.d(TAG, "热重载已启用")
            return
        }

        val config = configLoader.loadOmniClawConfig()
        if (!config.skills.watch) {
            Log.d(TAG, "热重载已在配置中禁用 (skills.watch=false)")
            return
        }

        val debounceMs = config.skills.watchDebounceMs

        // Monitor both Workspace and Managed directories
        val dirsToWatch = mutableListOf<File>()
        File(WORKSPACE_SKILLS_DIR).let { if (it.exists()) dirsToWatch.add(it) }
        File(MANAGED_SKILLS_DIR).let { if (it.exists()) dirsToWatch.add(it) }

        // Also monitor extraDirs
        config.skills.extraDirs.forEach { dir ->
            File(dir).let { if (it.exists()) dirsToWatch.add(it) }
        }

        if (dirsToWatch.isEmpty()) {
            Log.w(TAG, "没有可监控的 Skills 目录")
            return
        }

        for (dir in dirsToWatch) {
            try {
                val observer = object : FileObserver(dir, CREATE or MODIFY or DELETE) {
                    override fun onEvent(event: Int, path: String?) {
                        if (path != null && path.endsWith(SKILL_FILE_NAME)) {
                            Log.i(TAG, "检测到 Skill 文件变化: ${dir.name}/$path")
                            scheduleReload(debounceMs)
                        }
                    }
                }
                observer.startWatching()
                fileObservers.add(observer)
                Log.i(TAG, "✅ 监控: ${dir.absolutePath}")
            } catch (e: Exception) {
                Log.e(TAG, "启用热重载失败: ${dir.absolutePath}", e)
            }
        }

        hotReloadEnabled = true
        Log.i(TAG, "✅ 热重载已启用 (debounce=${debounceMs}ms, 监控 ${dirsToWatch.size} 个目录)")
    }

    /**
     * Schedule a debounced reload
     */
    private fun scheduleReload(debounceMs: Long) {
        // Cancel any pending reload
        pendingReload?.let { handler.removeCallbacks(it) }

        // Schedule new reload
        val runnable = Runnable {
            Log.i(TAG, "Debounce 完成，执行重新加载...")
            reload()
        }
        pendingReload = runnable
        handler.postDelayed(runnable, debounceMs)
    }

    /**
     * Disable hot reload
     */
    fun disableHotReload() {
        pendingReload?.let { handler.removeCallbacks(it) }
        pendingReload = null
        fileObservers.forEach { it.stopWatching() }
        fileObservers.clear()
        hotReloadEnabled = false
        Log.i(TAG, "热重载已禁用")
    }

    /**
     * Check if hot reload is enabled
     */
    fun isHotReloadEnabled(): Boolean = hotReloadEnabled

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
     * Load skills from extraDirs (lowest priority)
     * Aligns with OmniClaw: skills.load.extraDirs
     */
    private fun loadExtraDirsSkills(
        skills: MutableMap<String, SkillDocument>,
        extraDirs: List<String>
    ): Int {
        var count = 0
        for (dirPath in extraDirs) {
            val dir = File(dirPath)
            if (!dir.exists() || !dir.isDirectory) {
                Log.w(TAG, "extraDirs 目录不存在: $dirPath")
                continue
            }
            count += loadSkillsFromDirectory(dir, SkillSource.EXTRA, skills)
        }
        return count
    }

    /**
     * Load bundled Skills from assets/skills/
     */
    private fun loadBundledSkills(skills: MutableMap<String, SkillDocument>): Int {
        var count = 0
        val loadedNames = mutableListOf<String>()

        try {
            val skillDirs = context.assets.list(BUNDLED_SKILLS_PATH) ?: emptyArray()
            Log.d(TAG, "扫描 Bundled Skills: ${skillDirs.size} 个目录")

            for (dir in skillDirs) {
                val filesInDir = try {
                    context.assets.list("$BUNDLED_SKILLS_PATH/$dir") ?: emptyArray()
                } catch (_: Exception) {
                    emptyArray()
                }
                // 只处理真正的技能子目录（含 SKILL.md），忽略 assets/skills 下的杂项文件
                if (SKILL_FILE_NAME !in filesInDir) continue

                val skillPath = "$BUNDLED_SKILLS_PATH/$dir/$SKILL_FILE_NAME"
                try {
                    val content = context.assets.open(skillPath)
                        .bufferedReader().use { it.readText() }

                    val skill = SkillParser.parse(content, "assets://$skillPath")
                        .copy(source = SkillSource.BUNDLED, filePath = "assets://$skillPath")
                    skills[skill.name] = skill
                    loadedNames.add(skill.name)
                    count++
                } catch (e: Exception) {
                    Log.w(
                        TAG,
                        "⚠️ Bundled skill「$dir」缺少或无法读取 assets/$skillPath（${e.javaClass.simpleName}: ${e.message}）。请检查 APK 内是否包含 $SKILL_FILE_NAME。"
                    )
                }
            }
            if (loadedNames.isNotEmpty()) {
                Log.i(
                    TAG,
                    "Bundled skills 已从 APK 加载 ${loadedNames.size} 个: ${loadedNames.sorted().joinToString(", ")}"
                )
            } else {
                Log.w(TAG, "Bundled skills: 0 个成功解析（APK 的 assets/$BUNDLED_SKILLS_PATH 下无有效 SKILL.md）")
            }
        } catch (e: Exception) {
            Log.e(TAG, "扫描 Bundled Skills 失败", e)
        }

        return count
    }

    /**
     * Load managed Skills from /sdcard/.xomniclaw/skills/
     */
    private fun loadManagedSkills(skills: MutableMap<String, SkillDocument>): Int {
        val managedDir = File(MANAGED_SKILLS_DIR)
        if (!managedDir.exists()) {
            Log.d(TAG, "Managed Skills 目录不存在: $MANAGED_SKILLS_DIR")
            return 0
        }
        return loadSkillsFromDirectory(managedDir, SkillSource.MANAGED, skills)
    }

    /**
     * Load plugin Skills from enabled plugins.
     *
     * Aligns with OmniClaw: plugins can ship skills by declaring `skills` dirs
     * in omniclaw.plugin.json. On Android, we read plugins.entries from config
     * and scan each enabled plugin's skill directories.
     *
     * Plugin skill directories are resolved relative to the extensions base path
     * (assets://extensions/<pluginName>/ for bundled, or filesystem for installed).
     */
    private fun loadPluginSkills(
        skills: MutableMap<String, SkillDocument>,
        config: com.shijing.xomniclaw.config.XOmniClawConfig
    ): Int {
        var count = 0

        for ((pluginName, pluginEntry) in config.plugins.entries) {
            if (!pluginEntry.enabled) continue

            // Determine skill dirs for this plugin
            val skillDirs = pluginEntry.skills.ifEmpty { listOf("skills") }

            for (skillDir in skillDirs) {
                // Try bundled assets first (assets://extensions/<plugin>/<dir>/)
                val assetsPath = "extensions/$pluginName/$skillDir"
                try {
                    val assetDirs = context.assets.list(assetsPath)
                    if (assetDirs != null && assetDirs.isNotEmpty()) {
                        for (dir in assetDirs) {
                            val skillMdPath = "$assetsPath/$dir/$SKILL_FILE_NAME"
                            try {
                                val content = context.assets.open(skillMdPath)
                                    .bufferedReader().use { it.readText() }
                                val skill = SkillParser.parse(content, "assets://$skillMdPath")
                                    .copy(source = SkillSource.PLUGIN, filePath = "assets://$skillMdPath")

                                val isOverride = skills.containsKey(skill.name)
                                skills[skill.name] = skill
                                count++

                                val action = if (isOverride) "覆盖" else "新增"
                                Log.d(TAG, "✅ Plugin/$pluginName ($action): ${skill.name}")
                            } catch (e: Exception) {
                                // Not a valid skill dir, skip
                            }
                        }
                        continue // Found in assets, don't check filesystem
                    }
                } catch (e: Exception) {
                    // Not in assets, try filesystem
                }

                // Try filesystem (installed plugins)
                val fsPath = File("/sdcard/.xomniclaw/extensions/$pluginName/$skillDir")
                if (fsPath.exists() && fsPath.isDirectory) {
                    count += loadSkillsFromDirectory(fsPath, SkillSource.PLUGIN, skills)
                }
            }
        }

        if (count > 0) {
            Log.i(TAG, "Plugin Skills: $count 个加载完成")
        }

        return count
    }

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
                if (source == SkillSource.MANAGED || source == SkillSource.WORKSPACE) {
                    val matchesBundledAssetName = try {
                        context.assets.list(BUNDLED_SKILLS_PATH)?.contains(skillDir.name) == true
                    } catch (_: Exception) {
                        false
                    }
                    val bundledHint = if (matchesBundledAssetName) {
                        " [bundled 同名：APK 内有该技能，多为 Managed 补拷不完整；重启应用触发 ensureBundledSkills]"
                    } else {
                        ""
                    }
                    Log.w(
                        TAG,
                        "⚠️ ${source.displayName} 技能目录「${skillDir.name}」存在但缺少 $SKILL_FILE_NAME: " +
                            "${skillDir.absolutePath}.$bundledHint"
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
            "capcut-theme-video" -> {
                keywords.contains("主题") && (keywords.contains("一键成片") || keywords.contains("剪映") || keywords.contains("capcut")) ||
                    keywords.contains("a_latest") ||
                    keywords.contains("image-memories") ||
                    keywords.contains("相册记忆") ||
                    (keywords.contains("风景") && (keywords.contains("成片") || keywords.contains("剪映")))
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

        if (keywords.contains("定时") || keywords.contains("每天") ||
            keywords.contains("每晚") || keywords.contains("每早") ||
            keywords.contains("晚上") || keywords.contains("早上") ||
            keywords.contains("中午") || keywords.contains("凌晨") ||
            keywords.contains("闹钟") || keywords.contains("准时") ||
            keywords.contains("按时") || keywords.contains("定点") ||
            keywords.contains("提醒我打开") || keywords.contains("自动打开")) {
            recommendedSkills.add("scheduled-automation")
        }

        if (hasGalleryQaConsumptionIntent(keywords)) {
            recommendedSkills.add("gallery-qa")
        }

        if (hasGalleryMemoryMaintenanceIntent(keywords)) {
            recommendedSkills.add("gallery-memory")
        }

        if (keywords.contains("剪映") || keywords.contains("capcut") ||
            keywords.contains("一键成片") || keywords.contains("jianying")) {
            val themeIntent = keywords.contains("主题") || keywords.contains("风景") ||
                keywords.contains("image-memories") || keywords.contains("相册记忆") ||
                keywords.contains("a_latest")
            if (themeIntent) {
                recommendedSkills.add(0, "capcut-theme-video")
            }
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
                parts.size >= 2 && parts[0] in listOf("gateway", "channels") -> {
                    when (parts.getOrNull(1)) {
                        "enabled" -> true
                        "feishu" -> config.channels.feishu.enabled
                        "discord" -> config.channels.discord?.enabled ?: false
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
