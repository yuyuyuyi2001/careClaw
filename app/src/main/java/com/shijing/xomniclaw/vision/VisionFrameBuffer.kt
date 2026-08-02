package com.shijing.xomniclaw.vision

import java.util.ArrayDeque
import kotlin.math.abs

/**
 * 环形缓冲最近若干帧 JPEG，供手机端本地 VLM（对齐 PC Hub 的 video_buffer_brain）。
 * 与 [CameraFramePusher] / [ScreenFrameSampler] 共同写入。
 */
object VisionFrameBuffer {
    private const val MAX_FRAMES = 30

    private val deque: ArrayDeque<FrameEntry> = ArrayDeque(MAX_FRAMES + 1)

    data class FrameEntry(val ts: Long, val jpeg: ByteArray)

    @Synchronized
    fun offer(jpeg: ByteArray, ts: Long = System.currentTimeMillis()) {
        while (deque.size >= MAX_FRAMES) {
            deque.removeFirst()
        }
        deque.addLast(FrameEntry(ts, jpeg))
    }

    @Synchronized
    fun clear() {
        deque.clear()
    }

    /**
     * 返回时间戳最接近 [targetTs] 的帧。
     * 当距离相同的时候，优先选择更晚的那一帧，尽量贴近“按下说话”的瞬间。
     */
    @Synchronized
    fun getFrameClosestTo(targetTs: Long): ByteArray? {
        var bestEntry: FrameEntry? = null
        var bestDistance = Long.MAX_VALUE
        for (entry in deque) {
            val distance = abs(entry.ts - targetTs)
            if (distance < bestDistance) {
                bestEntry = entry
                bestDistance = distance
                continue
            }
            if (distance == bestDistance && (bestEntry == null || entry.ts > bestEntry.ts)) {
                bestEntry = entry
            }
        }
        return bestEntry?.jpeg
    }

    /**
     * 返回目标时间点之前最近的一帧。
     * 若缓冲里没有更早的帧，则回退到 [getFrameClosestTo]，避免语音流程整体失败。
     */
    @Synchronized
    fun getLatestFrameAtOrBefore(targetTs: Long): ByteArray? {
        var candidate: FrameEntry? = null
        for (entry in deque) {
            if (entry.ts <= targetTs) {
                candidate = entry
            } else {
                break
            }
        }
        return candidate?.jpeg ?: getFrameClosestTo(targetTs)
    }

    /**
     * 与 omniclaw_hub_v2 一致：含「刚才/过程」等则取最近 10 帧，否则取最近 1 帧。
     */
    @Synchronized
    fun selectFramesForQuery(userText: String): List<ByteArray> {
        val isProcessQuery = listOf("刚才", "过程", "怎么做的", "动态").any { userText.contains(it) }
        val n = if (isProcessQuery) 10 else 1
        if (deque.isEmpty()) return emptyList()
        return deque.toList().takeLast(n).map { it.jpeg }
    }
}
