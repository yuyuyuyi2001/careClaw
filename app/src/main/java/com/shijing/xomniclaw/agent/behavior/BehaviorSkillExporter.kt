package com.shijing.xomniclaw.agent.behavior

/**
 * Upstream reference (OmniClaw):
 * - ../xomniclaw/src/gateway/(all)
 *
 * CareClaw adaptation: behavior teaching core chain.
 *
 * 将录制到的行为导出为 SKILL.md，供 SkillSystem 热加载。
 * 生成位置：/sdcard/.xomniclaw/workspace/skills/<name>/SKILL.md
 */
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object BehaviorSkillExporter {
    private const val WORKSPACE_SKILLS_DIR = "/sdcard/.xomniclaw/workspace/skills"
    private val dateFormatter = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    data class ExportResult(
        val success: Boolean,
        val skillName: String,
        val skillFile: File? = null,
        val message: String
    )

    /**
     * 生成不超过 10 字的技能名（应用名 + 页面名，非法文件字符剔除）。
     */
    fun generateSkillName(appName: String, pageTitle: String): String {
        val base = if (pageTitle.isNotBlank()) {
            appName + pageTitle
        } else {
            "${appName}页面"
        }
        var name = base.replace(Regex("[\\\\/:*?\"<>|\\s]"), "")
        if (name.isBlank()) {
            name = "快捷指令"
        }
        return name.take(10)
    }

    fun export(
        appName: String,
        pageTitle: String,
        packageName: String,
        activityName: String,
        dataUri: String
    ): ExportResult {
        val skillName = generateSkillName(appName, pageTitle)
        val skillFile = File(File(WORKSPACE_SKILLS_DIR, skillName), "SKILL.md")

        return try {
            skillFile.parentFile?.mkdirs()
            skillFile.writeText(
                buildSkillMarkdown(
                    skillName = skillName,
                    appName = appName,
                    pageTitle = pageTitle,
                    packageName = packageName,
                    activityName = activityName,
                    dataUri = dataUri
                )
            )
            ExportResult(
                success = true,
                skillName = skillName,
                skillFile = skillFile,
                message = "已创建快捷指令「$skillName」"
            )
        } catch (e: Exception) {
            ExportResult(
                success = false,
                skillName = skillName,
                message = "导出失败: ${e.message}"
            )
        }
    }

    private fun buildSkillMarkdown(
        skillName: String,
        appName: String,
        pageTitle: String,
        packageName: String,
        activityName: String,
        dataUri: String
    ): String {
        val pageDesc = pageTitle.ifBlank { activityName.substringAfterLast(".") }
        val createdDate = dateFormatter.format(Date())

        return buildString {
            appendLine("---")
            appendLine("name: $skillName")
            appendLine("description: |")
            appendLine("  一键直达${appName}指定页面的快捷指令。由 CareClaw 行为录制自动创建。")
            appendLine("  当用户说\"打开$skillName\"、\"使用$skillName\"、\"$skillName\"时激活。")
            appendLine("metadata:")
            appendLine("  {")
            appendLine("    \"xomniclaw\": {")
            appendLine("      \"always\": false,")
            appendLine("      \"version\": \"1.0.0\",")
            appendLine("      \"category\": \"navigation\"")
            appendLine("    }")
            appendLine("  }")
            appendLine("---")
            appendLine()
            appendLine("# $skillName")
            appendLine()
            appendLine("一键直达${appName}「$pageDesc」页面。")
            appendLine()
            appendLine("## 执行步骤")
            appendLine()
            appendLine("### Step 1: 跳转目标页面")
            appendLine()
            appendLine("```kotlin")
            if (dataUri.isNotBlank()) {
                appendLine("device(action = \"open\", uri = \"$dataUri\", package_name = \"$packageName\")")
            } else {
                appendLine("device(action = \"open\", package_name = \"$packageName\", class_name = \"$activityName\")")
            }
            appendLine("```")
            appendLine()
            appendLine("### Step 2: 结束任务")
            appendLine()
            appendLine("跳转完成后，**直接向用户回复**：")
            appendLine()
            appendLine("```")
            appendLine("已跳转到${appName}「$pageDesc」页面。")
            appendLine("```")
            appendLine()
            appendLine("> **⚠️ 重要：不要执行 snapshot 或其他后续操作，任务到此结束。**")
            appendLine()
            appendLine("## 注意事项")
            appendLine()
            if (dataUri.isNotBlank()) {
                appendLine("- 必须使用 `device(action=\"open\", uri=\"...\", package_name=\"$packageName\")` 跳转，不带 package_name 会跳浏览器")
            } else {
                appendLine("- 该页面未捕获到 deeplink，使用 Activity 兜底跳转；若 Activity 未导出可能失败")
            }
            appendLine("- 原始页面：${appName} / $activityName")
            appendLine("- 创建时间：$createdDate")
        }
    }
}

