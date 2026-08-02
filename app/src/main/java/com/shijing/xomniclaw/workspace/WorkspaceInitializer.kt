/**
 * OmniClaw Source Reference:
 * - ../xomniclaw/src/config/(all)
 *
 * OmniClaw adaptation: workspace initialization.
 */
package com.shijing.xomniclaw.workspace

import android.content.Context
import android.util.Log
import java.io.File
import java.util.UUID

/**
 * Workspace initializer
 * 对齐 OmniClaw 的 workspace 初始化逻辑
 *
 * Features:
 * - 创建 workspace 元数据目录
 * - Initialize workspace/ 文件 (AGENTS.md, OPS_GUIDE.md 等 bootstrap 文件)
 * - 生成 device-id 和元数据文件
 */
class WorkspaceInitializer(private val context: Context) {

    companion object {
        private const val TAG = "WorkspaceInit"

        // 主目录
        private const val ROOT_DIR = "/sdcard/.xomniclaw"

        // 子目录
        private const val CONFIG_DIR = "$ROOT_DIR/config"
        private const val WORKSPACE_DIR = "$ROOT_DIR/workspace"
        private const val WORKSPACE_MEMORY_DIR = "$WORKSPACE_DIR/memory"
        private const val WORKSPACE_META_DIR = "$WORKSPACE_DIR/.xomniclaw"
        private const val LEGACY_WORKSPACE_META_DIR = "$WORKSPACE_DIR/.omniclaw"
        private const val SKILLS_DIR = "$ROOT_DIR/skills"
        private const val LOGS_DIR = "$ROOT_DIR/logs"

        // 元数据文件
        private const val DEVICE_ID_FILE = "$ROOT_DIR/.device-id"
        private const val WORKSPACE_STATE_FILE = "$WORKSPACE_META_DIR/workspace-state.json"
        private val BOOTSTRAP_MEMORY_SPECS = listOf(
            BootstrapMemorySpec(
                displayName = "MEMORY.md",
                assetPath = "bootstrap/memory/MEMORY.md",
                targetFile = File(WORKSPACE_DIR, "MEMORY.md")
            ),
            BootstrapMemorySpec(
                displayName = "IMAGE-MEMORY.md",
                assetPath = "bootstrap/memory/IMAGE-MEMORY.md",
                targetFile = File(WORKSPACE_MEMORY_DIR, "IMAGE-MEMORY.md")
            ),
            BootstrapMemorySpec(
                displayName = "USER-PROFILE.md",
                assetPath = "bootstrap/memory/USER-PROFILE.md",
                targetFile = File(WORKSPACE_MEMORY_DIR, "USER-PROFILE.md")
            )
        )
    }

    /**
     * Initialize workspace (首次启动)
     * 对齐 OmniClaw 的初始化流程
     */
    fun initializeWorkspace(): Boolean {
        Log.i(TAG, "开始初始化 Workspace...")

        try {
            // 1. Create directory structure
            createDirectoryStructure()

            // 1.1 迁移历史元数据目录，避免 workspace 下长期并存 .omniclaw 和 .xomniclaw。
            migrateLegacyWorkspaceMetaDir()

            // 2. 生成 device-id
            ensureDeviceId()

            // 3. Initialize workspace 文件
            initializeWorkspaceFiles()
            ensureBootstrapMemoryFiles()

            // 4. 拷贝内置 skills 到用户可编辑目录
            // Aligned with OmniClaw: ~/.xomniclaw/skills/ → /sdcard/.xomniclaw/skills/
            copyBundledSkills()

            // 5. 创建 workspace 元数据
            createWorkspaceState()

            Log.i(TAG, "✅ Workspace 初始化完成")
            Log.i(TAG, "   位置: $ROOT_DIR")
            return true

        } catch (e: Exception) {
            Log.e(TAG, "❌ Workspace 初始化失败", e)
            return false
        }
    }

    /**
     * Check if workspace is already initialized
     */
    fun isWorkspaceInitialized(): Boolean {
        val rootDir = File(ROOT_DIR)
        val workspaceDir = File(WORKSPACE_DIR)
        val deviceIdFile = File(DEVICE_ID_FILE)

        return rootDir.exists() &&
                workspaceDir.exists() &&
                deviceIdFile.exists()
    }

    /**
     * 获取 workspace 路径
     */
    fun getWorkspacePath(): String = WORKSPACE_DIR

    /**
     * 获取 device ID
     */
    fun getDeviceId(): String? {
        val file = File(DEVICE_ID_FILE)
        return if (file.exists()) {
            file.readText().trim()
        } else {
            null
        }
    }

    /**
     * Ensure bundled skills are deployed.
     * Call this on every app start — only copies missing skills, won't overwrite.
     */
    fun ensureBundledSkills() {
        try {
            File(SKILLS_DIR).mkdirs()
            copyBundledSkills()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to ensure bundled skills: ${e.message}")
        }
    }

    /**
     * Ensure the three core memory files exist.
     *
     * The bundled templates live under assets/bootstrap/memory. Runtime locations are:
     * - MEMORY.md -> workspace root
     * - IMAGE-MEMORY.md / USER-PROFILE.md -> workspace/memory
     *
     * Existing files are never overwritten because they contain user history.
     */
    fun ensureBootstrapMemoryFiles() {
        try {
            File(WORKSPACE_DIR).mkdirs()
            File(WORKSPACE_MEMORY_DIR).mkdirs()

            var initializedCount = 0
            var skippedCount = 0
            BOOTSTRAP_MEMORY_SPECS.forEach { spec ->
                when (copyBootstrapMemoryFileIfMissing(spec)) {
                    BootstrapMemoryInitResult.INITIALIZED -> initializedCount++
                    BootstrapMemoryInitResult.SKIPPED_HAS_CONTENT -> skippedCount++
                }
            }

            Log.i(TAG, "🧠 记忆初始化文件: 初始化 $initializedCount 个, 跳过 $skippedCount 个(已有内容)")
        } catch (e: Exception) {
            Log.e(TAG, "确保记忆初始化文件失败", e)
        }
    }

    /**
     * Initialize one core memory file on demand from the Status page.
     *
     * This is intentionally non-destructive: existing non-blank memory files are kept as-is.
     * Blank placeholder files are filled because users expect “初始化” to create usable templates.
     */
    fun ensureBootstrapMemoryFile(fileName: String): Boolean {
        val spec = BOOTSTRAP_MEMORY_SPECS.firstOrNull { it.displayName == fileName }
            ?: throw IllegalArgumentException("Unsupported memory file: $fileName")
        return copyBootstrapMemoryFileIfMissing(spec) == BootstrapMemoryInitResult.INITIALIZED
    }

    /**
     * Restore the three core memory files from bundled templates.
     *
     * This is intentionally destructive and is only used by the explicit Status-page
     * maintenance action when the user wants to reset Memory to its initial state.
     */
    fun restoreBootstrapMemoryFiles(): Int {
        File(WORKSPACE_DIR).mkdirs()
        File(WORKSPACE_MEMORY_DIR).mkdirs()
        var restoredCount = 0
        BOOTSTRAP_MEMORY_SPECS.forEach { spec ->
            restoreBootstrapMemoryFile(spec)
            restoredCount++
        }
        Log.i(TAG, "🧠 记忆文件已恢复初始状态: $restoredCount 个")
        return restoredCount
    }

    /**
     * Restore one core memory file from its bundled template, overwriting current content.
     */
    fun restoreBootstrapMemoryFile(fileName: String) {
        val spec = BOOTSTRAP_MEMORY_SPECS.firstOrNull { it.displayName == fileName }
            ?: throw IllegalArgumentException("Unsupported memory file: $fileName")
        File(WORKSPACE_DIR).mkdirs()
        File(WORKSPACE_MEMORY_DIR).mkdirs()
        restoreBootstrapMemoryFile(spec)
    }

    // ==================== 私有方法 ====================

    /**
     * Create directory structure
     */
    private fun createDirectoryStructure() {
        val dirs = listOf(
            ROOT_DIR,
            CONFIG_DIR,
            WORKSPACE_DIR,
            WORKSPACE_MEMORY_DIR,
            WORKSPACE_META_DIR,
            SKILLS_DIR,
            LOGS_DIR
        )

        for (dir in dirs) {
            val file = File(dir)
            if (!file.exists()) {
                file.mkdirs()
                Log.d(TAG, "创建目录: $dir")
            }
        }
    }

    /**
     * 兼容旧版本把 workspace 元数据写到 `.omniclaw/` 的情况。
     *
     * 现在统一写入 `.xomniclaw/`，避免在 workspace 下出现两个隐藏目录。
     */
    private fun migrateLegacyWorkspaceMetaDir() {
        val legacyDir = File(LEGACY_WORKSPACE_META_DIR)
        if (!legacyDir.exists()) {
            return
        }

        val newDir = File(WORKSPACE_META_DIR)
        if (!newDir.exists()) {
            newDir.mkdirs()
        }

        val legacyStateFile = File(legacyDir, "workspace-state.json")
        val newStateFile = File(WORKSPACE_STATE_FILE)
        if (legacyStateFile.exists() && !newStateFile.exists()) {
            legacyStateFile.copyTo(newStateFile, overwrite = false)
            Log.d(TAG, "迁移 workspace-state.json 到 $WORKSPACE_META_DIR")
        }

        // 仅在目录已经没有其它文件时才删除，避免误删未知调试产物。
        if ((legacyDir.listFiles()?.isEmpty() == true) ||
            (legacyDir.listFiles()?.all { it.name == "workspace-state.json" } == true &&
                legacyStateFile.delete())
        ) {
            legacyDir.delete()
            Log.d(TAG, "清理遗留目录: $LEGACY_WORKSPACE_META_DIR")
        }
    }

    /**
     * 生成或加载 device-id
     */
    private fun ensureDeviceId() {
        val file = File(DEVICE_ID_FILE)
        if (!file.exists()) {
            val deviceId = UUID.randomUUID().toString()
            file.writeText(deviceId)
            Log.d(TAG, "生成 device-id: $deviceId")
        } else {
            Log.d(TAG, "device-id 已存在: ${file.readText().trim()}")
        }
    }

    /**
     * Initialize workspace 文件 (对齐 OmniClaw)
     * 从 assets/bootstrap 目录读取初始文件内容
     */
    private fun initializeWorkspaceFiles() {
        val workspaceDir = File(WORKSPACE_DIR)
        val assetManager = context.assets

        try {
            // 获取 assets/bootstrap 目录下的所有文件
            val bootstrapFiles = assetManager.list("bootstrap") ?: emptyArray()
            var copiedCount = 0
            var skippedCount = 0

            for (fileName in bootstrapFiles) {
                // 只处理 .md 文件
                if (!fileName.endsWith(".md")) continue

                val targetFile = File(workspaceDir, fileName)

                // 如果文件已存在，跳过（不覆盖用户修改）
                if (targetFile.exists()) {
                    skippedCount++
                    continue
                }

                // 从 assets 读取内容并写入 workspace
                try {
                    val content = assetManager.open("bootstrap/$fileName").use { input ->
                        input.bufferedReader().readText()
                    }
                    targetFile.writeText(content)
                    copiedCount++
                    Log.d(TAG, "从 assets/bootstrap 复制 $fileName")
                } catch (e: Exception) {
                    Log.w(TAG, "复制 $fileName 失败: ${e.message}")
                }
            }

            Log.i(TAG, "📁 Workspace 文件: 复制 $copiedCount 个, 跳过 $skippedCount 个(已存在)")
        } catch (e: Exception) {
            Log.e(TAG, "初始化 workspace 文件失败: ${e.message}", e)
        }
    }

    private fun copyBootstrapMemoryFileIfMissing(spec: BootstrapMemorySpec): BootstrapMemoryInitResult {
        if (spec.targetFile.exists() && spec.targetFile.readText().isNotBlank()) {
            return BootstrapMemoryInitResult.SKIPPED_HAS_CONTENT
        }

        return try {
            val content = context.assets.open(spec.assetPath).bufferedReader().use { it.readText() }
            spec.targetFile.parentFile?.mkdirs()
            spec.targetFile.writeText(content)
            Log.d(TAG, "初始化记忆文件: ${spec.targetFile.absolutePath}")
            BootstrapMemoryInitResult.INITIALIZED
        } catch (e: Exception) {
            Log.w(TAG, "初始化记忆文件失败 ${spec.assetPath}: ${e.message}")
            throw e
        }
    }

    private fun restoreBootstrapMemoryFile(spec: BootstrapMemorySpec) {
        try {
            val content = context.assets.open(spec.assetPath).bufferedReader().use { it.readText() }
            spec.targetFile.parentFile?.mkdirs()
            spec.targetFile.writeText(content)
            Log.d(TAG, "恢复记忆文件初始内容: ${spec.targetFile.absolutePath}")
        } catch (e: Exception) {
            Log.w(TAG, "恢复记忆文件失败 ${spec.assetPath}: ${e.message}")
            throw e
        }
    }

    private data class BootstrapMemorySpec(
        val displayName: String,
        val assetPath: String,
        val targetFile: File
    )

    private enum class BootstrapMemoryInitResult {
        INITIALIZED,
        SKIPPED_HAS_CONTENT
    }

    /**
     * 创建 workspace 元数据
     */
    private fun createWorkspaceState() {
        val stateFile = File(WORKSPACE_STATE_FILE)
        stateFile.parentFile?.mkdirs()
        if (!stateFile.exists()) {
            val timestamp = java.time.Instant.now().toString()
            val state = """
            {
              "version": 1,
              "bootstrapSeededAt": "$timestamp",
              "platform": "android"
            }
            """.trimIndent()
            stateFile.writeText(state)
            Log.d(TAG, "创建 workspace-state.json")
        }
    }

    /**
     * Copy bundled skills from assets to user-editable /sdcard/.xomniclaw/skills/
     * 
     * Aligned with OmniClaw: skills live in ~/.xomniclaw/skills/ where users can
     * customize, add, or remove them. Bundled skills are copied on first init only.
     * Existing user-modified skills are NOT overwritten.
     */
    private fun copyBundledSkills() {
        val skillsDir = File(SKILLS_DIR)
        val assetManager = context.assets

        try {
            val bundledSkills = assetManager.list("skills") ?: return
            var copiedCount = 0
            var skippedCount = 0

            for (skillName in bundledSkills) {
                // Skip non-directory entries
                val skillFiles = try {
                    assetManager.list("skills/$skillName")
                } catch (_: Exception) { null }

                if (skillFiles.isNullOrEmpty()) continue

                val targetDir = File(skillsDir, skillName)

                // Don't overwrite existing user-modified skills
                val skillMd = File(targetDir, "SKILL.md")
                if (skillMd.exists()) {
                    skippedCount++
                    continue
                }

                // Create skill directory and copy files
                targetDir.mkdirs()
                for (fileName in skillFiles) {
                    try {
                        val inputStream = assetManager.open("skills/$skillName/$fileName")
                        val targetFile = File(targetDir, fileName)
                        targetFile.outputStream().use { out ->
                            inputStream.copyTo(out)
                        }
                        inputStream.close()
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to copy skill file: skills/$skillName/$fileName: ${e.message}")
                    }
                }
                copiedCount++
            }

            if (copiedCount > 0 || skippedCount > 0) {
                Log.i(TAG, "📦 Skills: copied $copiedCount, skipped $skippedCount (already exist)")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to copy bundled skills: ${e.message}")
        }
    }
}
