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
    }

    private val refMap = mutableMapOf<String, RefNode>()
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
     */
    fun resolveRefForTap(ref: String, screenWidth: Int, screenHeight: Int): Pair<Int, Int>? {
        lastTapAdjustTag = null
        val node = refMap[ref] ?: return null
        val rowCenter = Pair(node.bounds.centerX(), node.bounds.centerY())
        val h = screenHeight.coerceAtLeast(1)

        val nodeWidth = node.bounds.width()
        val isWideRow = nodeWidth >= (screenWidth * 0.60).toInt()
        val noExplicitLabel = node.text.isNullOrBlank()
        if (!isWideRow) return rowCenter
        if (!noExplicitLabel) return rowCenter

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

    fun getRefNode(ref: String): RefNode? = refMap[ref]

    /**
     * 在指定包内查找文案包含 [substring] 的节点 ref；多条时取 **最短 text**。
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
