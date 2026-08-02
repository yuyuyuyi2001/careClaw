package com.shijing.xomniclaw.agent.tools.device

/**
 * OmniClaw Source Reference:
 * - ../xomniclaw/src/agents/tools/browser/(all)
 *
 * OmniClaw adaptation: format accessibility tree output aligned with
 * Playwright's snapshot format (aria refs).
 */

import kotlin.math.max

object SnapshotFormatter {

    /** Matches CapCut-style duration overlay on video thumbs, e.g. 00:22, 1:05 */
    private val DURATION_LIKE_TEXT = Regex("^\\d{1,2}:\\d{2}(\\.\\d+)?$")

    /** Status bar clock / small overlays also look like mm:ss — ignore top/bottom strips for hints & banner. */
    private fun contentBandY(screenHeight: Int): IntRange {
        val top = max(160, (screenHeight * 0.075f).toInt())
        val bottom = screenHeight - max(80, (screenHeight * 0.05f).toInt())
        return top..bottom
    }

    private fun nodeCenterYInContentBand(n: RefNode, screenHeight: Int): Boolean {
        val cy = n.bounds.centerY()
        return cy in contentBandY(screenHeight)
    }

    private fun textLooksLikeDuration(t: String): Boolean = t.trim().matches(DURATION_LIKE_TEXT)

    /** 剪映勾选圈 link：无字，或选中顺序序号 '1'/'2'… */
    private val CAPCUT_GRID_LINK_ORDINAL = Regex("^\\d{1,2}$")

    private fun isCapCutGridCheckboxLink(node: RefNode): Boolean {
        if (node.role != "link") return false
        val t = node.text?.trim().orEmpty()
        return t.isEmpty() || t.matches(CAPCUT_GRID_LINK_ORDINAL)
    }

    /** 扁平遍历顺序下，各「无文案 button + 勾选 link」成对里的 link ref（左→右 ≈ 格序）。 */
    private fun collectCapCutPairCheckboxLinkRefs(nodes: List<RefNode>): List<String> {
        val out = mutableListOf<String>()
        var i = 0
        while (i < nodes.size - 1) {
            val a = nodes[i]
            val b = nodes[i + 1]
            if (a.role == "button" && a.text.isNullOrBlank() && isCapCutGridCheckboxLink(b)) {
                out.add(b.ref)
                i += 2
            } else {
                i++
            }
        }
        return out
    }

    /**
     * 主题相册 / 换来源：必须点 **link「照片视频」**，勿用顶部无文案 button（易被 RefManager 误判为缩略图并「圈坐标校正」）。
     */
    fun capCutAlbumSourceDropdownHint(nodes: List<RefNode>): String? {
        val n = nodes.firstOrNull { node ->
            node.packageName == "com.lemon.lv" &&
                node.role == "link" &&
                node.clickable &&
                node.text?.contains("照片视频") == true
        } ?: return null
        val label = n.text?.trim().orEmpty().ifEmpty { "照片视频" }
        return "[剪映·相册来源] 要选 **`A_latest`** 等相册：请 **tap** **link '$label' [ref=${n.ref}]**（ref **以当前 snapshot 为准**）。" +
            "**禁止**用同区域 **无文案 `button`**（树上常在附近，如 **e5**）代替——易被引擎当作「大缩略图」触发 **圈坐标校正**，误点下面素材格的勾选圈。"
    }

    /**
     * 相册下拉列表里每行常为：`button`（整行）→ `text '相册名'` → `text '数量'`。
     * 模型易把 **`text 'A_latest'` 下面下一行的 `button`** 当成 A_latest（实为 Camera 等下一相册）。
     */
    fun capCutA_latestAlbumListRowHint(nodes: List<RefNode>): String? {
        val pkg = "com.lemon.lv"
        val idx = nodes.indexOfFirst { n ->
            n.packageName == pkg && n.text?.trim() == "A_latest"
        }
        if (idx <= 0) return null
        val rowButton = nodes[idx - 1]
        if (rowButton.packageName != pkg || rowButton.role != "button" || !rowButton.clickable) return null
        val nameNode = nodes[idx]
        var nextRowWarn = ""
        if (idx + 2 < nodes.size) {
            val nextBtn = nodes[idx + 2]
            if (nextBtn.role == "button" && nextBtn.clickable && nextBtn.packageName == pkg) {
                val nextAlbumName = nodes.getOrNull(idx + 3)?.text?.trim().orEmpty()
                nextRowWarn = " **勿点** 数量文案之后的 **`button [ref=${nextBtn.ref}]`**（下一相册整行" +
                    (if (nextAlbumName.isNotEmpty()) "，约 **$nextAlbumName**" else "") + "）。"
            }
        }
        return "[剪映·选 A_latest 行] 在列表中找到 **`text 'A_latest' [ref=${nameNode.ref}]`**：**必须 `tap` 该行** **`button [ref=${rowButton.ref}]`**（在相册名**上一节点**，整行热区）。$nextRowWarn"
    }

    /**
     * When many **content-area** nodes expose mm:ss (video thumb overlays), the picker is likely video-heavy.
     * Excludes status bar clock (e.g. 19:22) and edge UI so 剪映 home does not falsely show this banner.
     */
    fun mediaPickerBannerIfNeeded(nodes: List<RefNode>, screenHeight: Int): String? {
        val durCount = nodes.count { n ->
            val t = n.text?.trim() ?: return@count false
            textLooksLikeDuration(t) && nodeCenterYInContentBand(n, screenHeight)
        }
        if (durCount < 2) return null
        return "[素材网格] 当前可见 $durCount 处「分:秒」文案（如 00:22）→ 多为视频缩略图。" +
            "若任务要静态照片：先点顶部「照片」分类再选。" +
            "节点上 checked/selected 表示已选中；同一勾选区域再点一次常会取消选中。" +
            "勿点大块无文案 button（多为整格缩略图，会进预览）；只点每格右上角小圈（剪映当前多为 link，可带 selected/序号）；" +
            "每点一次后 snapshot 核对「下一步 (N)」的 N 是否增加。"
    }

    /**
     * 当「分:秒」横幅已出现且顶部仍为「视频」Tab 时，强调格序上全是视频格，避免与 `list_gallery_images` 的 .jpg 任务混淆。
     */
    fun capCutVideoTabSelectedPhotoReminder(nodes: List<RefNode>, screenHeight: Int): String? {
        if (mediaPickerBannerIfNeeded(nodes, screenHeight) == null) return null
        if (!nodes.any { it.text?.trim() == "视频" && it.selected }) return null
        val photoRef = nodes.asSequence()
            .filter { it.text?.trim() == "照片" && it.clickable }
            .minWithOrNull(
                compareBy<RefNode> { if (it.role == "button") 0 else 1 }.thenBy { it.ref }
            )?.ref ?: return null
        return "[剪映·静态图前置] 当前为 **视频** Tab，网格多为 **视频** 缩略图。若任务要 **照片**（如用户要今天拍的照、`list_gallery_images` 为 .jpg）：**禁止**直接按下方「格序」点 link（会选中视频）。**请先** `tap` 顶部「照片」Tab（例 **ref=$photoRef**），**再 snapshot** 确认 **`照片` 为选中态** 后再勾选。"
    }

    /**
     * 剪映底部常出现「选择3个以上素材…」——模型易误以为选满 3 张即可下一步，与 `list_gallery_images` 的 K 不一致。
     */
    fun capCutThreePlusSuggestionReminder(nodes: List<RefNode>): String? {
        val hit = nodes.any { n ->
            val t = n.text?.trim().orEmpty()
            t.contains("3个以上") && (t.contains("素材") || t.contains("成片"))
        }
        if (!hit) return null
        return "[剪映·目标张数] 界面「选择3个以上…」只是 **≥3 张的推荐**，**不是**任务结束条件。" +
            "若已调用 `list_gallery_images`，目标 **K = 该次返回条数**（Skill §1）：**必须**继续勾选（可滚动后再 snapshot）直到 **`下一步 (N)` 的 N = K** 再点「下一步」；" +
            "**禁止**在 **N < K** 时仅因「已 ≥3」就点「下一步」。"
    }

    /**
     * 剪映相册网格：常见为「无文案 button（整格）+ link（勾选圈）」成对出现。
     * 勾选圈当前多为 link，但 **ref 编号无奇偶规律**；每次 snapshot 重排，须看树或「格序」行。
     */
    fun capCutGridPairingHint(nodes: List<RefNode>): String? {
        if (nodes.size < 6) return null
        val linkRefs = collectCapCutPairCheckboxLinkRefs(nodes)
        if (linkRefs.size < 4) return null
        return "[剪映选图] 网格多为「大块无文案 button + 小圈（多为 link）」成对；第 N 张 = **从左到右第 N 格**的小圈 ref（见下条「格序」）。" +
            "**e 后数字奇偶与是否 link/button 无关**，每次 snapshot 都会变；勿把大块 button 当勾选、勿猜 ref 规律。" +
            "圈上「1」仅表示**该格**已选，**非整行**；同排空圈仍未选，第2张多为**已选格右侧下一格**。"
    }

    /**
     * 列出前几格勾选 link 的 ref，避免模型把「第2张」点成第5格的 link（如误点 e25 而非 e19）。
     */
    fun capCutGridOrderedLinkRefsLine(nodes: List<RefNode>): String? {
        val list = collectCapCutPairCheckboxLinkRefs(nodes)
        if (list.size < 4) return null
        val head = list.take(8).joinToString("、")
        val ellipsis = if (list.size > 8) "…" else ""
        val second = list[1]
        val antiConfuse = if (list.size >= 5) {
            "第2张务必 **$second**，勿点 **${list[4]}**（第5格）。"
        } else {
            "第 M 张 = 上列第 M 个 ref。"
        }
        return "[剪映选图·格序] 从左到右各格勾选 link：$head$ellipsis。$antiConfuse"
    }

    private fun formatVisibleText(node: RefNode, screenHeight: Int): String {
        val text = node.text
        if (text.isNullOrBlank()) return ""
        val t = text.trim().take(100)
        val hint = if (textLooksLikeDuration(t) && nodeCenterYInContentBand(node, screenHeight)) {
            " [≈视频时长]"
        } else {
            ""
        }
        return " '$t'$hint"
    }

    /**
     * Compact format (default) — aligned with Playwright ai snapshot.
     * Shows role, text, ref, and interactive flags.
     */
    fun compact(nodes: List<RefNode>, screenWidth: Int, screenHeight: Int, appName: String?): String {
        val sb = StringBuilder()
        sb.appendLine("[Screen: ${screenWidth}x${screenHeight}${appName?.let { " $it" } ?: ""}]")
        sb.appendLine()

        for (node in nodes) {
            val indent = "  ".repeat(node.depth.coerceAtMost(6))
            val flags = buildList {
                if (node.clickable) add("clickable")
                if (node.editable) add("editable")
                if (node.scrollable) add("scrollable")
                if (node.focusable && node.editable) { /* already shown as editable */ }
                else if (node.focusable) add("focusable")
                if (node.checkable) add("checkable")
                if (node.checked) add("checked")
                if (node.selected) add("selected")
            }
            val flagStr = if (flags.isNotEmpty()) " (${flags.joinToString(", ")})" else ""
            val textStr = formatVisibleText(node, screenHeight)

            sb.appendLine("$indent${node.role}$textStr [ref=${node.ref}]$flagStr")
        }

        return sb.toString().trimEnd()
    }

    /**
     * Interactive format — only interactive elements, with coordinates.
     * Best for quick action selection.
     */
    fun interactive(nodes: List<RefNode>, appName: String?, screenHeight: Int): String {
        val sb = StringBuilder()
        sb.appendLine("[Screen: ${appName ?: "Android"}] Interactive elements:")
        sb.appendLine()

        val interactiveNodes = nodes.filter { it.clickable || it.editable || it.scrollable }
        for (node in interactiveNodes) {
            val textStr = formatVisibleText(node, screenHeight)
            val cx = node.bounds.centerX()
            val cy = node.bounds.centerY()
            val state = buildList {
                if (node.checkable) add("checkable")
                if (node.checked) add("checked")
                if (node.selected) add("selected")
            }.joinToString(",").let { if (it.isNotEmpty()) " [$it]" else "" }
            sb.appendLine("[${node.ref}] ${node.role}$textStr$state ($cx, $cy)")
        }

        if (interactiveNodes.isEmpty()) {
            sb.appendLine("(no interactive elements found)")
        }

        return sb.toString().trimEnd()
    }

    /**
     * Tree format — full hierarchy with bounds.
     */
    fun tree(nodes: List<RefNode>, screenWidth: Int, screenHeight: Int, appName: String?): String {
        val sb = StringBuilder()
        sb.appendLine("[Screen: ${screenWidth}x${screenHeight}${appName?.let { " $it" } ?: ""}]")
        sb.appendLine()

        for (node in nodes) {
            val indent = "│  ".repeat(node.depth.coerceAtMost(6))
            val prefix = if (node.depth > 0) "├─ " else ""
            val b = node.bounds
            val flags = buildList {
                if (node.clickable) add("clickable")
                if (node.editable) add("editable")
                if (node.scrollable) add("scrollable")
                if (node.checkable) add("checkable")
                if (node.checked) add("checked")
                if (node.selected) add("selected")
            }.joinToString(" ")
            val textStr = formatVisibleText(node, screenHeight)

            sb.appendLine("$indent$prefix${node.role}$textStr [ref=${node.ref}] bounds=(${b.left},${b.top},${b.right},${b.bottom}) $flags".trimEnd())
        }

        return sb.toString().trimEnd()
    }
}
