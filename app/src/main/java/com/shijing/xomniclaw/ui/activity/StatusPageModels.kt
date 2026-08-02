package com.shijing.xomniclaw.ui.activity

import com.shijing.xomniclaw.agent.memory.evolution.MemoryEvolutionStatus

/**
 * 状态页里的 Memory 概览。
 */
data class MemoryStatusSnapshot(
    val longTermMemoryExists: Boolean,
    val longTermMemoryLength: Int,
    val imageMemoriesExists: Boolean,
    val imageMemoriesLength: Int,
    val userProfileExists: Boolean,
    val userProfileLength: Int,
    val knowledgeFiles: List<String>,
    val dailyLogs: List<String>,
    val evolutionStatus: MemoryEvolutionStatus
)

/**
 * 相册记忆与画像设置的状态页快照。
 */
data class GalleryMemorySettingsState(
    val featureEnabled: Boolean,
    val profileLoadingEnabled: Boolean,
    val scanIntervalMinutes: Int,
    val manualSyncMaxImages: Int,
    val automationTaskSummary: String
)

/**
 * Memory 文件详情。
 */
data class MemoryDetailState(
    val title: String,
    val content: String
)

/**
 * 定时任务排序方式。
 */
