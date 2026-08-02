package com.shijing.xomniclaw.agent.tools.device.yolo

/**
 * 将 YOLO 原始检测结果转成紧凑文本，直接附加给大模型。
 *
 * 这里刻意不输出融合后的 Accessibility 语义，避免提前替模型做判断；
 * 模型可以自己结合 UI tree 与 YOLO box/ocr 结果做点击决策。
 */
object UiDetectionFormatter {
    private const val MAX_OUTPUT_NODES = 18

    fun toSnapshotAppendix(result: UiDetectionSnapshotResult): String {
        return buildString {
            append("[yolo_detection_summary status=${result.status}")
            append(" model=${result.modelAssetName}")
            result.screenshotPath?.let { append(" screenshot=").append(it) }
            result.rawDebugImagePath?.let { append(" raw_debug_image=").append(it) }
            result.fusedDebugImagePath?.let { append(" fused_debug_image=").append(it) }
            append(" raw=").append(result.rawDetections.size)
            append(" fused=").append(result.fusedDetections.count { !it.fusedText.isNullOrBlank() || !it.fusedContentDesc.isNullOrBlank() || !it.fusedResourceId.isNullOrBlank() })
            result.timing?.let {
                append(" preprocess_ms=${it.preprocessMs}")
                append(" infer_ms=${it.inferenceMs}")
                append(" post_ms=${it.postprocessMs}")
            }
            appendLine("]")
            result.message?.let { appendLine(it) }
            appendLine("[yolo_raw_detections]")

            val visibleNodes = result.rawDetections
                .sortedByDescending { it.score }
                .take(MAX_OUTPUT_NODES)
            if (visibleNodes.isEmpty()) {
                append("(no detections)")
                return@buildString
            }

            visibleNodes.forEachIndexed { index, detection ->
                val scoreText = "%.2f".format(detection.score)
                val saliencyText = "%.2f".format(detection.saliencyScore)
                append("det#").append(index + 1)
                    .append(" class_id=").append(detection.classId)
                    .append(" class='").append(detection.className).append("'")
                    .append(" score=").append(scoreText)
                    .append(" saliency=").append(saliencyText)
                    .append(" box=[").append(detection.left).append(",").append(detection.top)
                    .append(",").append(detection.right).append(",").append(detection.bottom).append("]")
                    .append(" center=[").append(detection.centerX).append(",").append(detection.centerY).append("]")
                    .append(" recognized_text='").append(escapeText(detection.recognizedText)).append("'")
                if (detection.isHighSaliency) {
                    append(" saliency_focus=true")
                }
                appendLine()
            }

            if (result.rawDetections.size > visibleNodes.size) {
                append("omitted=").append(result.rawDetections.size - visibleNodes.size)
                    .appendLine(" lower-score detections")
            }
        }.trimEnd()
    }

    private fun escapeText(value: String?): String {
        return value.orEmpty()
            .replace('\n', ' ')
            .replace('\r', ' ')
            .take(120)
    }
}
