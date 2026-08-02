package com.shijing.xomniclaw.agent.skills

import com.shijing.xomniclaw.accessibility.service.AccessibilityEventDispatcher
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 把用户行为记录结果沉淀为工作区 Skill。
 *
 * 这里直接写 `workspace/skills/<slug>/SKILL.md`，这样 SkillsLoader 能立即识别并加载。
 */
object BehaviorSkillWriter {
    private const val WORKSPACE_SKILLS_DIR = "/sdcard/.xomniclaw/workspace/skills"
    private val timestampFormatter = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.getDefault())
    private val readableFormatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    data class WriteResult(
        val skillName: String,
        val skillDir: File,
        val skillFile: File
    )

    fun writeBehaviorSkill(
        events: List<AccessibilityEventDispatcher.RecordedAccessibilityEvent>
    ): WriteResult {
        val startedAt = events.firstOrNull()?.timestampMs ?: System.currentTimeMillis()
        val skillName = "behavior-record-${timestampFormatter.format(Date(startedAt))}"
        val skillDir = File(WORKSPACE_SKILLS_DIR, skillName)
        if (!skillDir.exists()) {
            skillDir.mkdirs()
        }
        val skillFile = File(skillDir, "SKILL.md")
        skillFile.writeText(buildSkillMarkdown(skillName, events))
        return WriteResult(
            skillName = skillName,
            skillDir = skillDir,
            skillFile = skillFile
        )
    }

    private fun buildSkillMarkdown(
        skillName: String,
        events: List<AccessibilityEventDispatcher.RecordedAccessibilityEvent>
    ): String {
        val eventLines = if (events.isEmpty()) {
            listOf("- 本次没有捕获到可用行为事件。")
        } else {
            events.mapIndexed { index, event ->
                val timeLabel = readableFormatter.format(Date(event.timestampMs))
                buildString {
                    append("${index + 1}. 时间：$timeLabel")
                    append("\n   - 动作：${event.actionSummary}")
                    append("\n   - 事件类型：${event.eventTypeName}")
                    append("\n   - 包名：${event.packageName.ifBlank { "(空)" }}")
                    append("\n   - 页面类：${event.className.ifBlank { "(空)" }}")
                    append("\n   - 文本：${event.text.ifBlank { "(空)" }}")
                    append("\n   - 描述：${event.contentDescription.ifBlank { "(空)" }}")
                }
            }
        }

        return buildString {
            appendLine("---")
            appendLine("name: $skillName")
            appendLine("description: 用户行为记录自动生成技能，可用于复盘真实操作步骤。")
            appendLine("---")
            appendLine()
            appendLine("# 行为记录技能")
            appendLine()
            appendLine("## 用途")
            appendLine()
            appendLine("- 该技能由 OmniClaw 自动生成，用于复盘用户真实操作行为。")
            appendLine("- 当后续任务与本次操作相似时，可以读取本技能，参考已记录的步骤、页面跳转和输入行为。")
            appendLine()
            appendLine("## 记录结果")
            appendLine()
            eventLines.forEach { line ->
                appendLine(line)
                appendLine()
            }
            appendLine("## 使用提示")
            appendLine()
            appendLine("- 先按顺序理解记录中的页面切换、点击、输入与滚动。")
            appendLine("- 若页面结构变化较大，应把本记录当作参考而不是绝对脚本。")
        }
    }
}
