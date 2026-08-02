package com.shijing.xomniclaw.agent.behavior

/**
 * Upstream reference (OmniClaw):
 * - ../xomniclaw/src/gateway/(all)
 *
 * CareClaw adaptation: behavior teaching core chain.
 *
 * 演示 Skill：录制当前页面行为 → 解析 Intent → 生成 SKILL.md。
 * 用户说出技能名即可一键直达同一页面。
 */
import android.content.Context
import com.shijing.xomniclaw.agent.tools.Skill
import com.shijing.xomniclaw.agent.tools.SkillResult
import com.shijing.xomniclaw.providers.FunctionDefinition
import com.shijing.xomniclaw.providers.ParametersSchema
import com.shijing.xomniclaw.providers.PropertySchema
import com.shijing.xomniclaw.providers.ToolDefinition

class CaptureBehaviorSkill(private val context: Context) : Skill {
    override val name = "capture_behavior"

    override val description = """
        录制当前页面行为并生成快捷指令（SKILL.md）。

        从无障碍服务读取当前前台应用，用 dumpsys 捕获完整 Intent（deeplink），
        生成技能文件到 /sdcard/.xomniclaw/workspace/skills/<名称>/SKILL.md。

        使用方式：
        - 直接调用: 录制当前前台页面
        - 指定参数: {"package_name": "com.example.app", "activity_name": "com.example.MainActivity"}

        使用场景：
        - 教 CareClaw 记住常用页面（如长辈常用的挂号、缴费、视频通话入口）
        - 把一次手动操作固化成一条语音指令
    """.trimIndent()

    override fun getToolDefinition(): ToolDefinition {
        return ToolDefinition(
            type = "function",
            function = FunctionDefinition(
                name = name,
                description = description,
                parameters = ParametersSchema(
                    type = "object",
                    properties = mapOf(
                        "package_name" to PropertySchema(
                            type = "string",
                            description = "目标应用包名（可选，默认取当前前台应用）"
                        ),
                        "activity_name" to PropertySchema(
                            type = "string",
                            description = "目标 Activity 类名（可选，默认取当前前台 Activity）"
                        )
                    ),
                    required = emptyList()
                )
            )
        )
    }

    override suspend fun execute(args: Map<String, Any?>): SkillResult {
        // 1. 确定前台页面（参数优先，其次无障碍服务）
        val foreground = BehaviorCapture.foregroundPage(context)
        val packageName = (args["package_name"] as? String)?.takeIf { it.isNotBlank() }
            ?: foreground?.packageName
            ?: return SkillResult.error("无法确定当前前台应用，请先打开目标页面或传入 package_name")

        val activityName = (args["activity_name"] as? String)?.takeIf { it.isNotBlank() }
            ?: foreground?.activityName
            ?: return SkillResult.error("无法确定当前前台 Activity，请先打开目标页面或传入 activity_name")

        val appName = foreground?.appName ?: BehaviorCapture.appNameOf(context, packageName)

        // 2. 非 root 捕获 Intent
        val spec = BehaviorCapture.captureIntent(packageName, activityName)
        val dataUri = spec?.dataUri.orEmpty()

        // 3. 生成 SKILL.md
        val pageTitle = activityName.substringAfterLast(".")
        val result = BehaviorSkillExporter.export(
            appName = appName,
            pageTitle = pageTitle,
            packageName = packageName,
            activityName = activityName,
            dataUri = dataUri
        )

        return if (result.success) {
            val detail = if (dataUri.isNotBlank()) {
                "已捕获 deeplink，可一键直达"
            } else {
                "未捕获 deeplink（ROM 限制），已用 Activity 兜底跳转"
            }
            SkillResult.success(
                "${result.message}，技能文件: ${result.skillFile?.absolutePath}\n$detail",
                mapOf(
                    "skill_name" to result.skillName,
                    "skill_file" to result.skillFile?.absolutePath,
                    "package_name" to packageName,
                    "activity_name" to activityName,
                    "data_uri" to dataUri
                )
            )
        } else {
            SkillResult.error(result.message)
        }
    }
}

