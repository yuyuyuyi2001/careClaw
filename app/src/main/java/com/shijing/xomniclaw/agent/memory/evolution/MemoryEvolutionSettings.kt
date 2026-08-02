package com.shijing.xomniclaw.agent.memory.evolution

import com.shijing.xomniclaw.util.MMKVKeys
import com.tencent.mmkv.MMKV

/**
 * 全局记忆进化设置，和相册记忆定时任务一样由 MMKV 维护用户侧开关与任务 ID。
 */
data class MemoryEvolutionSettings(
    val enabled: Boolean = true,
    val intervalMinutes: Int = 24 * 60,
    val maxGlobalChars: Int = 16_000,
    val maxPendingEventsPerRun: Int = 50,
    val automationTaskId: String? = null
)

class MemoryEvolutionSettingsStore(
    private val mmkv: MMKV? = MMKV.defaultMMKV()
) {
    fun load(): MemoryEvolutionSettings {
        return MemoryEvolutionSettings(
            enabled = mmkv?.decodeBool(MMKVKeys.MEMORY_EVOLUTION_ENABLED.key, true) ?: true,
            intervalMinutes = mmkv?.decodeInt(
                MMKVKeys.MEMORY_EVOLUTION_INTERVAL_MINUTES.key,
                24 * 60
            ) ?: (24 * 60),
            maxGlobalChars = mmkv?.decodeInt(
                MMKVKeys.MEMORY_EVOLUTION_MAX_GLOBAL_CHARS.key,
                16_000
            ) ?: 16_000,
            maxPendingEventsPerRun = mmkv?.decodeInt(
                MMKVKeys.MEMORY_EVOLUTION_MAX_PENDING_PER_RUN.key,
                50
            ) ?: 50,
            automationTaskId = mmkv?.decodeString(MMKVKeys.MEMORY_EVOLUTION_TASK_ID.key)
        )
    }

    fun save(settings: MemoryEvolutionSettings) {
        mmkv?.encode(MMKVKeys.MEMORY_EVOLUTION_ENABLED.key, settings.enabled)
        mmkv?.encode(MMKVKeys.MEMORY_EVOLUTION_INTERVAL_MINUTES.key, settings.intervalMinutes)
        mmkv?.encode(MMKVKeys.MEMORY_EVOLUTION_MAX_GLOBAL_CHARS.key, settings.maxGlobalChars)
        mmkv?.encode(MMKVKeys.MEMORY_EVOLUTION_MAX_PENDING_PER_RUN.key, settings.maxPendingEventsPerRun)
        mmkv?.encode(MMKVKeys.MEMORY_EVOLUTION_TASK_ID.key, settings.automationTaskId)
    }
}
