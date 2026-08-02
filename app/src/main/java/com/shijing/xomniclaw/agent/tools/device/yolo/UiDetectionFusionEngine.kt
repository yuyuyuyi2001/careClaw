package com.shijing.xomniclaw.agent.tools.device.yolo

import android.graphics.Rect
import com.shijing.xomniclaw.accessibility.service.ViewNode
import com.shijing.xomniclaw.agent.tools.device.RefNode
import kotlin.math.hypot

/**
 * 用空间重叠与中心点关系把 YOLO 检测结果映射回无障碍节点。
 *
 * 目标不是替换 Accessibility，而是用 YOLO 修正几何位置，并尽量把无障碍语义补回检测框。
 */
object UiDetectionFusionEngine {
    private const val DIRECT_IOU_THRESHOLD = 0.15f
    private const val NEAREST_CENTER_MAX_DISTANCE_PX = 160f

    fun fuse(
        detections: List<UiDetectionNode>,
        viewNodes: List<ViewNode>,
        refNodes: List<RefNode>
    ): List<UiFusedDetectionNode> {
        if (detections.isEmpty()) return emptyList()
        return detections.map { detection ->
            val match = findBestAccessibilityMatch(detection, viewNodes, refNodes)
            UiFusedDetectionNode(
                detection = detection,
                matchedAccessibilityRef = match?.matchedRef,
                fusedText = match?.viewNode?.text,
                fusedContentDesc = match?.viewNode?.contentDesc,
                fusedResourceId = match?.viewNode?.resourceId,
                fusedClassName = match?.viewNode?.className,
                fusedPackageName = match?.viewNode?.packageName,
                matchMode = match?.matchMode,
                matchIou = match?.matchIou ?: 0f,
                centerDistancePx = match?.centerDistancePx
            )
        }
    }

    private data class AccessibilityMatch(
        val viewNode: ViewNode,
        val matchedRef: String?,
        val matchMode: String,
        val matchIou: Float,
        val centerDistancePx: Float?
    )

    private fun findBestAccessibilityMatch(
        detection: UiDetectionNode,
        viewNodes: List<ViewNode>,
        refNodes: List<RefNode>
    ): AccessibilityMatch? {
        val detectionRect = Rect(detection.left, detection.top, detection.right, detection.bottom)
        val validViewNodes = viewNodes.filter { it.right > it.left && it.bottom > it.top }
        if (validViewNodes.isEmpty()) return null

        val iouMatch = validViewNodes
            .map { viewNode ->
                val viewRect = Rect(viewNode.left, viewNode.top, viewNode.right, viewNode.bottom)
                val iou = iou(detectionRect, viewRect)
                viewNode to iou
            }
            .maxByOrNull { it.second }

        if (iouMatch != null && iouMatch.second >= DIRECT_IOU_THRESHOLD) {
            val matchedRef = findMatchingRef(iouMatch.first, refNodes)
            return AccessibilityMatch(
                viewNode = iouMatch.first,
                matchedRef = matchedRef,
                matchMode = "iou",
                matchIou = iouMatch.second,
                centerDistancePx = centerDistance(detection, iouMatch.first)
            )
        }

        val containingNode = validViewNodes.firstOrNull { viewNode ->
            detection.centerX in viewNode.left..viewNode.right &&
                detection.centerY in viewNode.top..viewNode.bottom
        }
        if (containingNode != null) {
            return AccessibilityMatch(
                viewNode = containingNode,
                matchedRef = findMatchingRef(containingNode, refNodes),
                matchMode = "center_contains",
                matchIou = iou(detectionRect, Rect(containingNode.left, containingNode.top, containingNode.right, containingNode.bottom)),
                centerDistancePx = centerDistance(detection, containingNode)
            )
        }

        val nearestNode = validViewNodes
            .map { viewNode -> viewNode to centerDistance(detection, viewNode) }
            .minByOrNull { it.second }
            ?: return null
        if (nearestNode.second > NEAREST_CENTER_MAX_DISTANCE_PX) {
            return null
        }

        return AccessibilityMatch(
            viewNode = nearestNode.first,
            matchedRef = findMatchingRef(nearestNode.first, refNodes),
            matchMode = "nearest_center",
            matchIou = iou(detectionRect, Rect(nearestNode.first.left, nearestNode.first.top, nearestNode.first.right, nearestNode.first.bottom)),
            centerDistancePx = nearestNode.second
        )
    }

    private fun findMatchingRef(viewNode: ViewNode, refNodes: List<RefNode>): String? {
        return refNodes.firstOrNull { refNode ->
            refNode.bounds.left == viewNode.left &&
                refNode.bounds.top == viewNode.top &&
                refNode.bounds.right == viewNode.right &&
                refNode.bounds.bottom == viewNode.bottom
        }?.ref ?: refNodes.firstOrNull { refNode ->
            refNode.bounds.contains(viewNode.point.x, viewNode.point.y)
        }?.ref
    }

    private fun centerDistance(detection: UiDetectionNode, viewNode: ViewNode): Float {
        return hypot(
            (detection.centerX - viewNode.point.x).toFloat(),
            (detection.centerY - viewNode.point.y).toFloat()
        )
    }

    private fun iou(a: Rect, b: Rect): Float {
        val interLeft = maxOf(a.left, b.left)
        val interTop = maxOf(a.top, b.top)
        val interRight = minOf(a.right, b.right)
        val interBottom = minOf(a.bottom, b.bottom)
        val interWidth = maxOf(0, interRight - interLeft)
        val interHeight = maxOf(0, interBottom - interTop)
        val intersection = interWidth * interHeight.toFloat()
        val areaA = maxOf(0, a.width()) * maxOf(0, a.height()).toFloat()
        val areaB = maxOf(0, b.width()) * maxOf(0, b.height()).toFloat()
        val union = areaA + areaB - intersection + 1e-6f
        return intersection / union
    }
}
