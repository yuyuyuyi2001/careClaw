package com.shijing.xomniclaw.agent.tools.device

/**
 * OmniClaw Source Reference:
 * - ../xomniclaw/src/agents/tools/(device / ref resolution)
 *
 * OmniClaw adaptation: ref ID management for Playwright-aligned device tool.
 * Maps accessibility tree nodes to stable ref IDs (e1, e2, ...) and resolves
 * refs back to screen coordinates for action execution.
 */

import android.graphics.Rect
import android.util.Log
import kotlin.math.abs
import kotlin.math.max

data class RefNode(
    val ref: String,
    val role: String,        // Button, Input, Text, List, Image, etc.
    val text: String?,       // Visible text or content description
    val bounds: Rect,        // Screen bounds
    val clickable: Boolean = false,
    val editable: Boolean = false,
    val scrollable: Boolean = false,
    val focusable: Boolean = false,
    val checkable: Boolean = false,
    val checked: Boolean = false,
    val selected: Boolean = false,
    val depth: Int = 0,
    val className: String? = null,
    val packageName: String? = null
)

class RefManager {
    companion object {
        private const val TAG = "RefManager"
        private const val CAPCUT_PKG = "com.lemon.lv"
        /**
         * 顶部工具条/相册条里的无文案 button 易被误判为「大缩略图」触发圈坐标校正，点到网格勾选圈。
         * 该区域内的 button→link 配对也与「整格缩略图+圈」不同，一律不做剪映网格类重定向。
         */
        /** 含状态栏+相册来源条+视频/照片 Tab；略放大避免 y≈600 的工具钮仍被当成缩略图做圈校正 */
        private const val CAPCUT_TOP_CHROME_FRACTION = 0.24f
    }

    private val refMap = mutableMapOf<String, RefNode>()
    /** Same order as snapshot / e1,e2,… — used for 剪映 button→link pairing. */
    private var snapshotNodesInOrder: List<RefNode> = emptyList()
    private var lastSnapshotTime = 0L
    private var snapshotSeq = 0L
    private var lastSnapshotId = "s0"
    private var lastSnapshotPackage: String? = null
    /** Why resolveRefForTap changed coordinates (for tap result message). Cleared each resolve. */
    private var lastTapAdjustTag: String? = null

    /**
     * @param foregroundPackage 当前前台 Activity 包名（与 snapshot 首行 `package=` 一致）。
     * 若省略，则退回为节点数最多的包（旧行为；会与 OEM 权限弹窗+底层 App 合并树时产生误导）。
     */
    fun updateRefs(nodes: List<RefNode>, foregroundPackage: String? = null): String {
        refMap.clear()
        snapshotNodesInOrder = nodes.toList()
        nodes.forEach { refMap[it.ref] = it }
        lastSnapshotTime = System.currentTimeMillis()
        snapshotSeq += 1
        lastSnapshotId = "s$snapshotSeq"

        val pkgCounts = mutableMapOf<String, Int>()
        nodes.mapNotNull { it.packageName?.trim()?.takeIf(String::isNotBlank) }.forEach { p ->
            pkgCounts[p] = (pkgCounts[p] ?: 0) + 1
        }
        val majorityPkg = pkgCounts.maxByOrNull { it.value }?.key
        val fg = foregroundPackage?.trim()?.takeIf { it.isNotBlank() && it != "unknown" }
        lastSnapshotPackage = fg ?: majorityPkg

        Log.d(
            TAG,
            "Updated ${nodes.size} refs (snapshotId=$lastSnapshotId, pkg=${lastSnapshotPackage ?: "unknown"}, " +
                "foreground=$fg majority=$majorityPkg)"
        )
        return lastSnapshotId
    }

    /** Consume tag set by last `resolveRefForTap` (capcut_pair / capcut_corner / settings_switch). */
    fun takeTapAdjustTag(): String? {
        val t = lastTapAdjustTag
        lastTapAdjustTag = null
        return t
    }

    fun resolveRef(ref: String): Pair<Int, Int>? {
        val node = refMap[ref] ?: return null
        return Pair(node.bounds.centerX(), node.bounds.centerY())
    }

    /**
     * Adaptive tap coordinate for list-like settings pages:
     * if target ref is a wide row container, prefer a right-side switch node
     * in the same row to avoid entering detail page by mistake.
     *
     * CapCut (剪映): (1) 「一键成片」— prefer the labeled text hit target over blank row buttons;
     * (2) media grid — large thumbnail/link taps are redirected to a small control in the top-right
     * zone of that thumb (checkbox), avoiding preview-open taps.
     */
    fun resolveRefForTap(ref: String, screenWidth: Int, screenHeight: Int): Pair<Int, Int>? {
        lastTapAdjustTag = null
        val node = refMap[ref] ?: return null
        val rowCenter = Pair(node.bounds.centerX(), node.bounds.centerY())
        val h = screenHeight.coerceAtLeast(1)

        capcutOneTapEntryPreference(ref, screenWidth)?.let { return it }
        if (!isCapCutTopChromeGridExemptZone(node, h)) {
            capcutPairedLinkAfterThumbnailButton(ref)?.let {
                lastTapAdjustTag = "capcut_pair"
                return it
            }
            capcutRedirectThumbToCornerCheckbox(ref, h)?.let {
                lastTapAdjustTag = "capcut_corner"
                return it
            }
        }

        val nodeWidth = node.bounds.width()
        val isWideRow = nodeWidth >= (screenWidth * 0.60).toInt()
        val noExplicitLabel = node.text.isNullOrBlank()
        if (!isWideRow) return rowCenter
        if (!noExplicitLabel) return rowCenter

        // CapCut large media tiles are wide and unlabeled — do not hijack them to an unrelated switch.
        if (isCapCutLargeMediaThumb(node)) return rowCenter

        val nodeCenterY = node.bounds.centerY()
        val nodeHeight = node.bounds.height().coerceAtLeast(1)

        val switchCandidate = refMap.values
            .asSequence()
            .filter { it.ref != ref }
            .filter { !AdUiGuard.isLikelyAdvertisement(it) }
            .filter {
                val cls = it.className.orEmpty()
                it.role.equals("switch", ignoreCase = true) ||
                    cls.contains("Switch", ignoreCase = true)
            }
            .filter {
                val h = it.bounds.height().coerceAtLeast(1)
                val centerGap = kotlin.math.abs(it.bounds.centerY() - nodeCenterY)
                // same visual row
                centerGap <= kotlin.math.max(nodeHeight, h)
            }
            .filter {
                // switch is expected on the right side
                it.bounds.centerX() >= node.bounds.centerX()
            }
            .minByOrNull {
                kotlin.math.abs(it.bounds.centerY() - nodeCenterY) * 10 +
                    kotlin.math.abs(it.bounds.centerX() - node.bounds.right)
            }

        if (switchCandidate != null) {
            lastTapAdjustTag = "settings_switch"
            Log.d(TAG, "Adaptive tap: ref=$ref redirected to switch ${switchCandidate.ref}")
            return Pair(switchCandidate.bounds.centerX(), switchCandidate.bounds.centerY())
        }

        return rowCenter
    }

    /** 剪映选素材页顶部条（相册来源、Tab）：不做缩略图→勾选圈类重定向。 */
    private fun isCapCutTopChromeGridExemptZone(node: RefNode, screenHeight: Int): Boolean {
        if (node.packageName != CAPCUT_PKG) return false
        val cy = node.bounds.centerY()
        return cy < screenHeight * CAPCUT_TOP_CHROME_FRACTION
    }

    /**
     * 剪映选图：遍历顺序里常为「无文案 button」+「link」同格成对；点 button 时直接改点到紧随的 link（圈），
     * 比纯几何角区更稳，且明确对应「第几格」。
     */
    private fun capcutPairedLinkAfterThumbnailButton(ref: String): Pair<Int, Int>? {
        val idx = snapshotNodesInOrder.indexOfFirst { it.ref == ref }
        if (idx < 0 || idx >= snapshotNodesInOrder.lastIndex) return null
        val cur = snapshotNodesInOrder[idx]
        val next = snapshotNodesInOrder[idx + 1]
        if (cur.packageName != CAPCUT_PKG) return null
        if (cur.role != "button" || !cur.clickable || !cur.text.isNullOrBlank()) return null
        if (next.role != "link" || !next.clickable) return null
        val maxH = max(cur.bounds.height(), next.bounds.height()).coerceAtLeast(1)
        if (abs(cur.bounds.centerY() - next.bounds.centerY()) > maxH + 48) return null
        Log.d(TAG, "CapCut paired: button $ref -> link ${next.ref}")
        return Pair(next.bounds.centerX(), next.bounds.centerY())
    }

    /** True for CapCut picker cells: big tile (opens preview) — image, wide link, or blank large button. */
    private fun isCapCutLargeMediaThumb(node: RefNode): Boolean {
        if (node.packageName != CAPCUT_PKG || !node.clickable) return false
        val area = node.bounds.width() * node.bounds.height()
        return when (node.role) {
            "image" -> area >= 8_000
            "link" -> node.bounds.width() >= 100 && area >= 6_000
            // Current 剪映: full thumbnail is often a large faceless `button`; small circle is `link`.
            "button" -> node.text.isNullOrBlank() && area >= 8_000
            else -> false
        }
    }

    /**
     * Home / discovery: hit 「一键成片」 reliably by using the text node's center or the smallest
     * clickable that contains that point (not a huge anonymous button beside it).
     */
    private fun capcutOneTapEntryPreference(ref: String, screenWidth: Int): Pair<Int, Int>? {
        val node = refMap[ref] ?: return null
        val label = refMap.values.find {
            it.packageName == CAPCUT_PKG && it.text?.contains("一键成片") == true
        } ?: return null
        if (node.packageName != CAPCUT_PKG) return null

        val lx = label.bounds.centerX()
        val ly = label.bounds.centerY()

        fun smallestClickableContaining(x: Int, y: Int): RefNode? =
            refMap.values
                .filter { it.clickable && it.bounds.contains(x, y) }
                .minByOrNull { it.bounds.width() * it.bounds.height() }

        if (node.ref == label.ref) {
            if (label.clickable) return Pair(label.bounds.centerX(), label.bounds.centerY())
            return smallestClickableContaining(lx, ly)?.let { Pair(it.bounds.centerX(), it.bounds.centerY()) }
                ?: Pair(lx, ly)
        }

        val vSlop = (max(node.bounds.height(), label.bounds.height()) * 0.75).toInt().coerceIn(36, 140)
        val sameRow = abs(node.bounds.centerY() - ly) <= vSlop
        val bulky = node.bounds.width() >= screenWidth / 4 && node.bounds.height() >= 44
        if (node.packageName == CAPCUT_PKG && sameRow && bulky && node.text.isNullOrBlank()) {
            if (label.clickable) return Pair(label.bounds.centerX(), label.bounds.centerY())
            return smallestClickableContaining(lx, ly)?.let { Pair(it.bounds.centerX(), it.bounds.centerY()) }
                ?: Pair(lx, ly)
        }

        return null
    }

    /**
     * Picker: redirect tap on a large thumbnail / preview to a small clickable in the top-right
     * band of that tile (typical multi-select checkbox).
     */
    private fun capcutRedirectThumbToCornerCheckbox(ref: String, screenHeight: Int): Pair<Int, Int>? {
        val thumb = refMap[ref] ?: return null
        if (thumb.packageName != CAPCUT_PKG || !thumb.clickable) return null
        if (isCapCutTopChromeGridExemptZone(thumb, screenHeight)) return null
        val area = thumb.bounds.width() * thumb.bounds.height()
        val looksLikeThumb = when (thumb.role) {
            "image" -> area >= 8_000
            "link" -> thumb.bounds.width() >= 100 && area >= 6_000
            "button" -> thumb.text.isNullOrBlank() && area >= 8_000
            else -> false
        }
        if (!looksLikeThumb) return null

        val pick = findCapCutCornerCheckboxNear(thumb) ?: return null
        Log.d(TAG, "CapCut tile $ref -> selection control ${pick.ref} at (${pick.bounds.centerX()},${pick.bounds.centerY()})")
        return Pair(pick.bounds.centerX(), pick.bounds.centerY())
    }

    private fun findCapCutCornerCheckboxNear(thumb: RefNode): RefNode? {
        val B = thumb.bounds
        val spanX = max(B.width() / 3, 56)
        val spanY = max(B.height() / 3, 56)
        val xMin = B.right - spanX - 36
        val xMax = B.right + 48
        val yMin = B.top - 16
        val yMax = B.top + spanY + 16

        fun distToTopRight(n: RefNode): Int {
            val dx = n.bounds.centerX() - B.right
            val dy = n.bounds.centerY() - B.top
            return dx * dx + dy * dy
        }

        return refMap.values
            .asSequence()
            .filter { it.ref != thumb.ref }
            .filter { it.clickable }
            .filter { !AdUiGuard.isLikelyAdvertisement(it) }
            .filter {
                val cx = it.bounds.centerX()
                val cy = it.bounds.centerY()
                cx in xMin..xMax && cy in yMin..yMax
            }
            .filter {
                val a = it.bounds.width() * it.bounds.height()
                a in 400..55_000
            }
            .minWithOrNull(
                compareBy<RefNode>(
                    // 剪映常见：右上角圈是 `link`（选中时 link '1' + selected）；旧版可能是小 button
                    { n ->
                        when {
                            n.role == "link" -> 0
                            n.checkable || n.role == "checkbox" -> 1
                            else -> 2
                        }
                    },
                    { it.bounds.width() * it.bounds.height() },
                    { distToTopRight(it) }
                )
            )
            ?: refMap.values
                .asSequence()
                .filter { it.ref != thumb.ref }
                .filter { it.clickable && !AdUiGuard.isLikelyAdvertisement(it) }
                .filter {
                    it.role == "link" || it.checkable || it.role == "checkbox"
                }
                .filter { thumb.bounds.contains(it.bounds.centerX(), it.bounds.centerY()) }
                .minByOrNull { it.bounds.width() * it.bounds.height() }
    }

    fun getRefNode(ref: String): RefNode? = refMap[ref]

    /**
     * 在指定包内查找文案包含 [substring] 的节点 ref；多条时取 **最短 text**（避免长营销句抢过「一键成片」等短标签）。
     * 最短并列时 **优先可点击**（避免落到纯 `text` 节点导致点不中 Tab）。
     */
    fun findRefForLabelTextContaining(substring: String, packageName: String): String? {
        val cands = refMap.values.filter {
            it.packageName == packageName && it.text?.contains(substring) == true
        }
        if (cands.isEmpty()) return null
        val minLen = cands.minOf { (it.text ?: "").length }
        val shortest = cands.filter { (it.text ?: "").length == minLen }
        return shortest.minWithOrNull(
            compareBy<RefNode> { if (it.clickable) 0 else 1 }
                .thenBy { it.ref }
        )?.ref
    }

    /** 剪映素材页：同时出现「照片」「视频」标签时，视为顶部 Tab 栏（与首页区分）。 */
    fun hasCapCutMediaPickerTabBar(): Boolean {
        val texts = refMap.values
            .filter { it.packageName == CAPCUT_PKG }
            .mapNotNull { it.text?.trim() }
            .toSet()
        return texts.contains("照片") && texts.contains("视频")
    }

    /**
     * 在聊天页面中尽量稳地找到「发送」按钮：
     * 1) 优先命中文案包含“发送/send”的可点击节点；
     * 2) 对无文案图标按钮，优先选择“输入框右侧且靠近底部”的候选。
     */
    fun findLikelyChatSendButtonRef(packageName: String): String? {
        val pkgNodes = refMap.values.filter { it.packageName == packageName }
        if (pkgNodes.isEmpty()) return null

        val screenRight = pkgNodes.maxOfOrNull { it.bounds.right } ?: return null
        val screenBottom = pkgNodes.maxOfOrNull { it.bounds.bottom } ?: return null
        val editableNodes = pkgNodes.filter { it.editable }
        val rightMostEditable = editableNodes.maxByOrNull { it.bounds.centerX() }
        val editableY = rightMostEditable?.bounds?.centerY()

        val sendTextHints = listOf("发送", "发 送", "send")
        val avoidHints = listOf("语音", "表情", "加号", "更多", "附件", "拍照", "相册", "emoji")

        data class Scored(val node: RefNode, val score: Int)

        val candidates = pkgNodes.asSequence()
            .filter { it.clickable && !it.editable && !it.scrollable }
            .map { node ->
                val text = node.text?.trim().orEmpty()
                val lower = text.lowercase()
                var score = 0

                if (sendTextHints.any { hint -> lower.contains(hint.lowercase()) }) {
                    score += 120
                }
                if (avoidHints.any { hint -> lower.contains(hint.lowercase()) }) {
                    score -= 80
                }

                // 常见发送按钮位于右下区域；无文案按钮也可通过位置命中。
                if (node.bounds.centerX() > (screenRight * 0.55f).toInt()) score += 25
                if (node.bounds.centerY() > (screenBottom * 0.52f).toInt()) score += 20

                // 若存在输入框，优先输入框右侧、同一行附近按钮。
                if (rightMostEditable != null && editableY != null) {
                    if (node.bounds.centerX() > rightMostEditable.bounds.centerX()) score += 35
                    if (abs(node.bounds.centerY() - editableY) <= max(42, rightMostEditable.bounds.height())) {
                        score += 28
                    }
                }

                val roleLower = node.role.lowercase()
                if (roleLower == "button" || roleLower == "link") score += 10
                if (node.className?.contains("ImageButton", ignoreCase = true) == true) score += 8

                // 过大的整块容器通常不是发送按钮，适当降权。
                val area = node.bounds.width() * node.bounds.height()
                val screenArea = (screenRight * screenBottom).coerceAtLeast(1)
                if (area > screenArea * 0.20) score -= 40

                Scored(node, score)
            }
            .filter { it.score >= 35 }
            .sortedWith(
                compareByDescending<Scored> { it.score }
                    .thenByDescending { it.node.bounds.centerY() }
                    .thenByDescending { it.node.bounds.centerX() }
            )
            .toList()

        return candidates.firstOrNull()?.node?.ref
    }

    fun isStale(maxAgeMs: Long = 10_000): Boolean {
        return System.currentTimeMillis() - lastSnapshotTime > maxAgeMs
    }

    fun getSnapshotAgeMs(): Long {
        if (lastSnapshotTime <= 0L) return Long.MAX_VALUE
        return System.currentTimeMillis() - lastSnapshotTime
    }

    fun getSnapshotId(): String = lastSnapshotId

    fun getSnapshotPackage(): String? = lastSnapshotPackage

    fun getRefCount(): Int = refMap.size

    /**
     * 返回目标 ref 边界框内的可见文案（按快照顺序）。
     * 主要用于「无文案可点击容器」的语义补全与误触防护。
     */
    fun collectTextsInsideRefBounds(ref: String, maxCount: Int = 3): List<String> {
        val host = refMap[ref] ?: return emptyList()
        if (maxCount <= 0) return emptyList()

        return snapshotNodesInOrder.asSequence()
            .filter { n ->
                n.ref != ref &&
                    !n.text.isNullOrBlank() &&
                    host.bounds.contains(n.bounds.centerX(), n.bounds.centerY())
            }
            .mapNotNull { it.text?.trim()?.takeIf(String::isNotEmpty) }
            .distinct()
            .take(maxCount)
            .toList()
    }

    /**
     * 某些动作（输入、点击、滚动、返回等）执行后，旧 ref 很可能已经失去定位意义。
     * 递增内部序号并将缓存标为过期，促使下一轮先 snapshot；不对模型传入的 snapshot_id 做任何比对。
     */
    fun invalidateSnapshotAfterMutation() {
        snapshotSeq += 1
        lastSnapshotId = "s${snapshotSeq}"
        lastSnapshotTime = 0L
        lastTapAdjustTag = null
        Log.d(TAG, "Snapshot cache invalidated after mutation (seq=$lastSnapshotId)")
    }

    fun clear() {
        refMap.clear()
        snapshotNodesInOrder = emptyList()
        lastSnapshotTime = 0
        snapshotSeq = 0L
        lastSnapshotId = "s0"
        lastSnapshotPackage = null
        lastTapAdjustTag = null
    }
}
