package com.shijing.xomniclaw.deeplink

import com.shijing.xomniclaw.deeplink.model.DeeplinkBookmark
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 将 Deeplink 收藏导出为 Skill。
 *
 * 生成的 SKILL.md 格式与 clipboard-to-shortcut 保持一致，
 * 用户说出 Skill 名称即可一键直达对应页面。
 */
object DeeplinkSkillExporter {
    private const val WORKSPACE_SKILLS_DIR = "/sdcard/.xomniclaw/workspace/skills"
    private val dateFormatter = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    data class ExportResult(
        val success: Boolean,
        val skillName: String,
        val skillDir: File? = null,
        val skillFile: File? = null,
        val message: String
    )

    /**
     * App 包名 -> (emoji, 中文名称) 映射
     */
    private val appMapping = mapOf(
        "com.sankuai.meituan" to ("🍜" to "美团"),
        "com.taobao.taobao" to ("🛒" to "淘宝"),
        "com.xunmeng.pinduoduo" to ("🍊" to "拼多多"),
        "com.ss.android.ugc.aweme" to ("🎵" to "抖音"),
        "com.smile.gifmaker" to ("🎬" to "快手"),
        "com.zhihu.android" to ("💡" to "知乎"),
        "com.xingin.xhs" to ("📕" to "小红书"),
        "tv.danmaku.bili" to ("📺" to "哔哩哔哩"),
        "com.autonavi.minimap" to ("🗺️" to "高德"),
        "com.tencent.qqmusic" to ("🎶" to "QQ音乐"),
        "com.eg.android.AlipayGphone" to ("💰" to "支付宝"),
        "com.baidu.searchbox" to ("🔍" to "百度"),
        "com.dianping.v1" to ("🍜" to "大众点评"),
        "com.jingdong.app.mall" to ("🛍️" to "京东"),
        "com.tencent.mm" to ("💬" to "微信"),
        "com.alibaba.android.rimet" to ("💼" to "钉钉"),
        "com.ss.android.lark" to ("🐦" to "飞书")
    )

    /**
     * 根据 DeeplinkBookmark 生成一个不超过10字的 Skill 名称。
     *
     * 策略：
     * 1. 优先使用 pageTitle（截取前面有意义的部分）
     * 2. 如果 pageTitle 太短或无意义，使用 appName + 页面类型
     * 3. 最终限制在10个字符内
     */
    fun generateSkillName(bookmark: DeeplinkBookmark): String {
        val appName = appMapping[bookmark.packageName]?.second ?: bookmark.appName
        val pageTitle = bookmark.pageTitle.trim()

        // 尝试从 pageTitle 提取有意义的名称
        val cleanTitle = pageTitle
            .replace(Regex("^(美团|淘宝|拼多多|抖音|快手|知乎|小红书|哔哩哔哩|高德|QQ音乐|支付宝|百度|大众点评|京东|微信|钉钉|飞书)[-_\\s]*"), "")
            .replace(Regex("[-_|｜·].*$"), "") // 移除后缀分隔符后的内容
            .trim()

        // 从 dataUri 中提取页面类型信息
        val pageType = extractPageTypeFromUri(bookmark.dataUri)

        val skillName = when {
            cleanTitle.length in 2..8 -> {
                // pageTitle 有意义且长度合适
                if (cleanTitle.contains(appName)) cleanTitle else "$appName$cleanTitle"
            }
            pageType.isNotBlank() -> {
                // 从 URI 中提取的页面类型
                "$appName$pageType"
            }
            cleanTitle.isNotBlank() -> {
                // pageTitle 太长，截取前面部分
                val prefix = cleanTitle.take(6)
                if (prefix.contains(appName)) prefix else "$appName${prefix.take(4)}"
            }
            else -> {
                // 兜底：使用 appName + 页面
                "${appName}页面"
            }
        }

        // 确保不超过10个字符
        return skillName.take(10)
    }

    /**
     * 从 dataUri 中提取页面类型关键词
     */
    private fun extractPageTypeFromUri(dataUri: String): String {
        if (dataUri.isBlank()) return ""

        // 美团相关
        if (dataUri.contains("meituan")) {
            return when {
                dataUri.contains("seckill", ignoreCase = true) || 
                    dataUri.contains("SECOND_KILL") -> "秒杀"
                dataUri.contains("deal-detail") -> "团购"
                dataUri.contains("food", ignoreCase = true) -> "美食"
                dataUri.contains("hotel", ignoreCase = true) -> "酒店"
                dataUri.contains("movie", ignoreCase = true) -> "电影"
                dataUri.contains("shop", ignoreCase = true) -> "商家"
                else -> ""
            }
        }

        // 淘宝相关
        if (dataUri.contains("taobao") || dataUri.contains("tb.cn")) {
            return when {
                dataUri.contains("search") -> "搜索"
                dataUri.contains("detail") -> "商品"
                dataUri.contains("shop") -> "店铺"
                dataUri.contains("cart") -> "购物车"
                else -> ""
            }
        }

        // 抖音相关
        if (dataUri.contains("douyin")) {
            return when {
                dataUri.contains("video") -> "视频"
                dataUri.contains("live") -> "直播"
                dataUri.contains("user") -> "主页"
                else -> ""
            }
        }

        return ""
    }

    /**
     * 将 DeeplinkBookmark 导出为 Skill
     */
    fun export(bookmark: DeeplinkBookmark, customName: String? = null): ExportResult {
        val skillName = customName?.take(10) ?: generateSkillName(bookmark)

        // 检查 deeplink 是否有效
        if (bookmark.dataUri.isBlank() && bookmark.intentCommand.isBlank()) {
            return ExportResult(
                success = false,
                skillName = skillName,
                message = "该收藏没有有效的 Deeplink，无法导出为 Skill"
            )
        }

        val skillDir = File(WORKSPACE_SKILLS_DIR, skillName)
        val skillFile = File(skillDir, "SKILL.md")

        return try {
            if (!skillDir.exists()) {
                skillDir.mkdirs()
            }
            val content = buildSkillMarkdown(bookmark, skillName)
            skillFile.writeText(content)

            ExportResult(
                success = true,
                skillName = skillName,
                skillDir = skillDir,
                skillFile = skillFile,
                message = "已成功创建快捷指令「$skillName」"
            )
        } catch (e: Exception) {
            ExportResult(
                success = false,
                skillName = skillName,
                message = "导出失败: ${e.message}"
            )
        }
    }

    /**
     * 检查 Skill 名称是否已存在
     */
    fun skillExists(skillName: String): Boolean {
        val skillDir = File(WORKSPACE_SKILLS_DIR, skillName)
        return skillDir.exists() && File(skillDir, "SKILL.md").exists()
    }

    /**
     * 构建符合 clipboard-to-shortcut 格式的 SKILL.md 内容
     */
    private fun buildSkillMarkdown(bookmark: DeeplinkBookmark, skillName: String): String {
        val (emoji, appDisplayName) = appMapping[bookmark.packageName] ?: ("🔗" to bookmark.appName)
        val packageName = bookmark.packageName
        val hasDeeplink = bookmark.dataUri.isNotBlank()
        val uri = bookmark.dataUri.ifBlank { "" }
        val amCommand = bookmark.intentCommand.ifBlank { bookmark.effectiveAmCommand }
        val createdDate = dateFormatter.format(Date())
        val pageDesc = bookmark.pageTitle.ifBlank { bookmark.shortActivityName }

        return buildString {
            // YAML Frontmatter
            appendLine("---")
            appendLine("name: $skillName")
            appendLine("description: |")
            appendLine("  一键直达${appDisplayName}指定页面的快捷指令。从 Deeplink 收藏自动创建。")
            appendLine("  当用户说\"打开$skillName\"、\"使用$skillName\"、\"$skillName\"时激活。")
            appendLine("metadata:")
            appendLine("  {")
            appendLine("    \"xomniclaw\": {")
            appendLine("      \"always\": false,")
            appendLine("      \"emoji\": \"$emoji\",")
            appendLine("      \"version\": \"1.0.0\",")
            appendLine("      \"category\": \"navigation\"")
            appendLine("    }")
            appendLine("  }")
            appendLine("---")
            appendLine()

            // 标题
            appendLine("# $skillName")
            appendLine()
            appendLine("一键直达${appDisplayName}「$pageDesc」页面。")
            appendLine()

            // 跳转方式说明
            if (hasDeeplink) {
                appendLine("## 🚫 绝对禁止（违反必失败 / 必崩溃）")
                appendLine()
                appendLine("> **禁令 1：exec / shell（含 `am start`）** —— 100% 报 SecurityException。")
                appendLine(">")
                appendLine("> **禁令 2：`start_activity`（无论何种参数组合）** —— 这是本类技能最常见的失败模式。")
                appendLine("> 该工具入参**无法承载 deeplink 的查询参数**（如 `mrn_biz` / `mrn_entry` / `mrn_component` / `poiidEncrypt` 等），")
                appendLine("> 即使 `${bookmark.activityName}` 被成功启动，也只是该 Activity 的「裸壳」——")
                appendLine("> 没有业务参数 → 回退到 ${appDisplayName} 默认页 / 首页，**看起来跳转成功，实则没到目标页**。")
                appendLine(">")
                appendLine("> | 操作 | ✅ 必须使用 | ❌ 绝对禁止 |")
                appendLine("> |---|---|---|")
                appendLine("> | 跳转到本快捷指令的目标页 | `device(action=\"open\", uri=\"<完整 deeplink>\", package_name=\"$packageName\")` | `start_activity(component=...)` / `start_activity(package=…, activity=…)` / `start_activity(uri=…)` / `am start …` |")
                appendLine(">")
                appendLine("> **✅ 唯一正确方式（必须带 package_name + 完整 uri）：**")
                appendLine("> ```kotlin")
                appendLine("> device(action = \"open\", uri = \"<完整 deeplink，包含 ?key=val&… 全部查询参数>\", package_name = \"$packageName\")")
                appendLine("> ```")
                appendLine(">")
                appendLine("> **think → action 一致性自检（每次 emit function call 前再读一遍）**：")
                appendLine("> 1. 即使 `<think>` 里写过\"使用 `device(action=\\\"open\\\")`\"，真正的 function call 也务必调 `device`，**不要换成 `start_activity`**。")
                appendLine("> 2. uri 字段必须**原样复制**下方「Deeplink 详情」节里的完整字符串（含全部 `?key=val&…` 查询参数），")
                appendLine(">    一个参数都不能省略——丢失任何一个都会 fallback 回 ${appDisplayName} 首页。")
            } else {
                appendLine("## ⚠️ Root 权限要求")
                appendLine()
                appendLine("> 该页面没有 Deeplink，需要通过 **root shell** 启动指定 Activity。")
                appendLine("> 请确保设备已获取 root 权限。")
                appendLine(">")
                appendLine("> **✅ 跳转指定页面方式（use_root=true）：**")
                appendLine("> ```kotlin")
                appendLine("> device(action = \"open\", package_name = \"$packageName\", class_name = \"${bookmark.activityName}\", use_root = true)")
                appendLine("> ```")
            }
            appendLine()

            // 执行步骤
            appendLine("## 📋 执行步骤")
            appendLine()
            appendLine("### Step 1: 跳转目标页面")
            appendLine()

            if (hasDeeplink) {
                appendLine("```kotlin")
                appendLine("device(action = \"open\", uri = \"$uri\", package_name = \"$packageName\")")
                appendLine("```")
            } else {
                appendLine("```kotlin")
                appendLine("// 该页面没有 Deeplink，通过 root shell 启动指定 Activity")
                appendLine("device(action = \"open\", package_name = \"$packageName\", class_name = \"${bookmark.activityName}\", use_root = true)")
                appendLine("```")
            }
            appendLine()

            appendLine("### Step 2: 结束任务")
            appendLine()
            appendLine("跳转完成后，**直接向用户回复**：")
            appendLine()
            appendLine("```")
            appendLine("已跳转到${appDisplayName}「${pageDesc}」页面。")
            appendLine("```")
            appendLine()
            appendLine("> **⚠️ 重要：不要执行 snapshot 或其他后续操作，任务到此结束。**")
            appendLine()

            // 注意事项
            appendLine("## ⚠️ 注意事项")
            appendLine()
            if (hasDeeplink) {
                appendLine("- 必须使用 `device(action=\"open\", uri=\"...\", package_name=\"$packageName\")` 跳转，不带 package_name 会跳浏览器")
                appendLine("- ⛔ 下方记录的 `Activity：${bookmark.activityName}` **仅用于审计溯源**，不是给模型调用的入口；")
                appendLine("  绝不要根据这一行去 `start_activity(component=…/${bookmark.activityName.substringAfterLast('.')})` —— 那只会启动空参数的 Activity 并 fallback 回 ${appDisplayName} 首页。")
            } else {
                appendLine("- 该页面无 Deeplink，必须使用 `device(action=\"open\", ..., use_root=true)` 通过 root shell 跳转")
                appendLine("- 需要设备已获取 root 权限，否则无法启动该 Activity")
            }
            appendLine("- 原始来源：${bookmark.sourceActionSummary.ifBlank { "Deeplink 收藏导出" }}")
            appendLine("- 原始页面：${bookmark.appName} / ${bookmark.displayName}")
            appendLine("- Activity：${bookmark.activityName}")
            appendLine("- 创建时间：$createdDate")

            if (hasDeeplink) {
                appendLine()
                appendLine("### Deeplink 详情")
                appendLine()
                appendLine("```")
                appendLine(uri)
                appendLine("```")
            }
        }
    }
}
