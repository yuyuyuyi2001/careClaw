package com.shijing.xomniclaw.agent.tools

/**
 * OmniClaw Source Reference:
 * - ../xomniclaw/src/agents/tools/(all)
 *
 * OmniClaw adaptation: low-level file read tool.
 */


import android.content.Context
import android.util.Log
import com.shijing.xomniclaw.providers.FunctionDefinition
import com.shijing.xomniclaw.providers.ParametersSchema
import com.shijing.xomniclaw.providers.PropertySchema
import com.shijing.xomniclaw.providers.ToolDefinition
import java.io.File

/**
 * Read File Tool - Read file content
 * Reference: nanobot's ReadFileTool
 */
class ReadFileTool(
    private val context: Context? = null,
    private val workspace: File? = null,
    private val allowedDir: File? = null
) : Tool {
    companion object {
        private const val TAG = "ReadFileTool"
    }

    override val name = "read_file"
    override val description = "Read file contents"

    override fun getToolDefinition(): ToolDefinition {
        return ToolDefinition(
            type = "function",
            function = FunctionDefinition(
                name = name,
                description = description,
                parameters = ParametersSchema(
                    type = "object",
                    properties = mapOf(
                        "path" to PropertySchema(
                            "string",
                            "要读取的文件路径。支持 /sdcard/... 绝对路径；相对路径相对 workspace。" +
                                "内置技能可用 assets://skills/<name>/SKILL.md（直接读 APK assets）。"
                        )
                    ),
                    required = listOf("path")
                )
            )
        )
    }

    override suspend fun execute(args: Map<String, Any?>): ToolResult {
        val path = args["path"] as? String

        if (path == null) {
            return ToolResult.error("Missing required parameter: path")
        }

        val trimmed = path.trim()
        Log.d(TAG, "Reading file: $trimmed")
        return try {
            // 1) 显式 assets:// → 从 APK assets 读（不依赖磁盘部署）
            if (trimmed.startsWith("assets://", ignoreCase = true)) {
                val assetPath = trimmed.substring("assets://".length).trimStart('/')
                return readBundledAsset(assetPath, userPath = trimmed)
            }

            val file = resolvePath(trimmed)

            // Permission check
            if (allowedDir != null) {
                val canonicalFile = file.canonicalFile
                val canonicalAllowed = allowedDir.canonicalFile
                if (!canonicalFile.path.startsWith(canonicalAllowed.path)) {
                    return ToolResult.error("Path is outside allowed directory: $trimmed")
                }
            }

            if (file.exists() && file.isFile) {
                return ToolResult.success(file.readText(Charsets.UTF_8))
            }

            // 2) 技能目录空壳：/sdcard/.xomniclaw/workspace/skills/<name>/SKILL.md 缺失时回退 APK assets 种子
            val skillMatch = Regex("^/sdcard/\\.xomniclaw/workspace/skills/([^/]+)/SKILL\\.md$").find(trimmed)
            if (skillMatch != null && context != null) {
                val skillName = skillMatch.groupValues[1]
                val assetPath = "skills/$skillName/SKILL.md"
                val fromApk = readBundledAsset(assetPath, userPath = trimmed, logOnSuccess = true)
                if (fromApk.success) return fromApk
            }

            if (!file.exists()) {
                return ToolResult.error("File not found: $trimmed")
            }

            ToolResult.error("Not a file: $trimmed")
        } catch (e: Exception) {
            Log.e(TAG, "Read file failed", e)
            ToolResult.error("Read file failed: ${e.message}")
        }
    }

    private fun readBundledAsset(
        assetPath: String,
        userPath: String,
        logOnSuccess: Boolean = false
    ): ToolResult {
        val ctx = context
            ?: return ToolResult.error(
                "无法从 APK 读取 assets（缺少 Context）: $userPath"
            )
        return try {
            val text = ctx.assets.open(assetPath).bufferedReader().use { it.readText() }
            if (logOnSuccess) {
                Log.i(TAG, "read_file: 磁盘无文件，已从 APK assets/$assetPath 返回（请求路径=$userPath）")
            }
            ToolResult.success(text)
        } catch (e: Exception) {
            Log.w(TAG, "Bundled asset missing: $assetPath (${e.message})")
            ToolResult.error("File not found: $userPath (APK 内无 assets/$assetPath)")
        }
    }

    /**
     * Resolve path (relative paths are based on workspace)
     */
    private fun resolvePath(path: String): File {
        val file = File(path)
        return if (!file.isAbsolute && workspace != null) {
            File(workspace, path)
        } else {
            file
        }
    }
}
