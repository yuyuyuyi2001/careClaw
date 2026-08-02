package com.shijing.xomniclaw.agent.memory.gallery

/**
 * 用户画像 v2 顶层模型。
 *
 * 采用显式数据模型而不是直接拼字符串，便于：
 * 1. 控制字段来源；
 * 2. 做单元测试；
 * 3. 后续按需裁剪默认加载内容。
 */
data class UserProfileV2(
    val lastUpdated: String,
    val profileVersion: String,
    val confidenceNote: String,
    val identitySnapshot: IdentitySnapshot,
    val stablePreferences: StablePreferences,
    val lifestyleAndRoutine: LifestyleAndRoutine,
    val interestDistribution: InterestDistribution,
    val importantContextHints: List<String>,
    val privacyAndRiskNotes: PrivacyAndRiskNotes,
    val recentSignalsSummary: RecentSignalsSummary,
    val provenance: Provenance,
    val recentSignalsDetails: RecentSignalsDetails
)

data class IdentitySnapshot(
    val preferredName: String,
    val likelyRoles: List<String>,
    val primaryLanguage: String,
    val timezone: String
)

data class StablePreferences(
    val responseStyle: String,
    val preferredTopics: List<String>,
    val contentPreferences: List<String>,
    val recurringNeeds: List<String>
)

data class LifestyleAndRoutine(
    val activeTimePattern: String,
    val likelyWorkLifeMode: String,
    val planningStyle: String
)

data class InterestDistribution(
    val topInterestTags: List<Pair<String, Int>>,
    val topGallerySources: List<Pair<String, Int>>
)

data class PrivacyAndRiskNotes(
    val highSensitivityRatio: String,
    val avoidTopics: List<String>,
    val profileGenerationMode: String
)

data class RecentSignalsSummary(
    val last7dTopics: List<String>,
    val lastSyncSummary: String
)

data class Provenance(
    val imageMemoriesCount: Int,
    val longTermMemoryAvailable: Boolean,
    val recentLogsLoaded: Int,
    val generatedFrom: List<String>
)

data class RecentSignalsDetails(
    val recentFocuses: List<String>,
    val latestMemoryHighlights: List<String>
)
