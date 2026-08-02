package com.shijing.xomniclaw.agent.loop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 最小闭环行为回归（P3.4）：
 * 验证 ToolLoopDetection 的「工具调用 → 结果回填 → 循环检测」链路，
 * 与 Python 版 agentloop 的循环检测语义对齐。
 */
class ToolLoopDetectionTest {

    private val params = mapOf("query" to "微信")

    /** 通用重复：相同工具 + 相同参数调用 10 次 → WARNING */
    @Test
    fun `generic repeat triggers warning after threshold`() {
        val state = ToolLoopDetection.SessionState()

        var detected: ToolLoopDetection.LoopDetectionResult = ToolLoopDetection.LoopDetectionResult.NoLoop
        repeat(11) { i ->
            val result = ToolLoopDetection.detectToolCallLoop(state, "search_app", params)
            if (result is ToolLoopDetection.LoopDetectionResult.LoopDetected) {
                detected = result
            }
            ToolLoopDetection.recordToolCall(state, "search_app", params, toolCallId = "call-$i")
            ToolLoopDetection.recordToolCallOutcome(state, "search_app", params, result = "no result", toolCallId = "call-$i")
        }

        assertTrue("应检测到循环", detected is ToolLoopDetection.LoopDetectionResult.LoopDetected)
        val d = detected as ToolLoopDetection.LoopDetectionResult.LoopDetected
        assertEquals(ToolLoopDetection.LoopDetectionResult.DetectorKind.GENERIC_REPEAT, d.detector)
        assertEquals(ToolLoopDetection.LoopDetectionResult.Level.WARNING, d.level)
    }

    /** 全局熔断：相同参数 + 相同结果（无进展）30 次 → CRITICAL */
    @Test
    fun `no progress streak triggers global circuit breaker`() {
        val state = ToolLoopDetection.SessionState()

        var detected: ToolLoopDetection.LoopDetectionResult = ToolLoopDetection.LoopDetectionResult.NoLoop
        repeat(31) { i ->
            val result = ToolLoopDetection.detectToolCallLoop(state, "search_app", params)
            if (result is ToolLoopDetection.LoopDetectionResult.LoopDetected) {
                detected = result
            }
            ToolLoopDetection.recordToolCall(state, "search_app", params, toolCallId = "call-$i")
            ToolLoopDetection.recordToolCallOutcome(state, "search_app", params, result = "stuck", toolCallId = "call-$i")
        }

        assertTrue("应触发全局熔断", detected is ToolLoopDetection.LoopDetectionResult.LoopDetected)
        val d = detected as ToolLoopDetection.LoopDetectionResult.LoopDetected
        assertEquals(ToolLoopDetection.LoopDetectionResult.DetectorKind.GLOBAL_CIRCUIT_BREAKER, d.detector)
        assertEquals(ToolLoopDetection.LoopDetectionResult.Level.CRITICAL, d.level)
    }

    /** 乒乓：两个工具交替调用且结果不变 → PING_PONG WARNING */
    @Test
    fun `alternating tools trigger ping pong warning`() {
        val state = ToolLoopDetection.SessionState()
        val paramsA = mapOf("action" to "tap")
        val paramsB = mapOf("action" to "back")

        var detected: ToolLoopDetection.LoopDetectionResult = ToolLoopDetection.LoopDetectionResult.NoLoop
        repeat(12) { i ->
            val (tool, p) = if (i % 2 == 0) "tap" to paramsA else "back" to paramsB
            val result = ToolLoopDetection.detectToolCallLoop(state, tool, p)
            if (result is ToolLoopDetection.LoopDetectionResult.LoopDetected) {
                detected = result
            }
            ToolLoopDetection.recordToolCall(state, tool, p, toolCallId = "call-$i")
            ToolLoopDetection.recordToolCallOutcome(state, tool, p, result = "nothing changed", toolCallId = "call-$i")
        }

        assertTrue("应检测到乒乓循环", detected is ToolLoopDetection.LoopDetectionResult.LoopDetected)
        val d = detected as ToolLoopDetection.LoopDetectionResult.LoopDetected
        assertEquals(ToolLoopDetection.LoopDetectionResult.DetectorKind.PING_PONG, d.detector)
        assertEquals(ToolLoopDetection.LoopDetectionResult.Level.WARNING, d.level)
    }
}
