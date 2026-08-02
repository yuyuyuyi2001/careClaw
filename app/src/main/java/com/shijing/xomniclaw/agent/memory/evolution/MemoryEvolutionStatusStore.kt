package com.shijing.xomniclaw.agent.memory.evolution

import com.shijing.xomniclaw.util.MMKVKeys
import com.tencent.mmkv.MMKV
import org.json.JSONObject

/**
 * 记录最近一次全局记忆进化运行结果，供状态页展示和排查使用。
 */
class MemoryEvolutionStatusStore(
    private val mmkv: MMKV? = MMKV.defaultMMKV()
) {
    fun load(): MemoryEvolutionStatus {
        val raw = mmkv?.decodeString(MMKVKeys.MEMORY_EVOLUTION_STATUS_JSON.key).orEmpty()
        if (raw.isBlank()) {
            return MemoryEvolutionStatus(
                lastRunAtMs = 0L,
                processedEvents = 0,
                acceptedCandidates = 0,
                globalMemoryChars = 0,
                userProfileChars = 0,
                pendingEvents = 0,
                lastMessage = "尚未运行"
            )
        }
        return runCatching {
            val json = JSONObject(raw)
            MemoryEvolutionStatus(
                lastRunAtMs = json.optLong("lastRunAtMs", 0L),
                processedEvents = json.optInt("processedEvents", 0),
                acceptedCandidates = json.optInt("acceptedCandidates", 0),
                globalMemoryChars = json.optInt("globalMemoryChars", 0),
                userProfileChars = json.optInt("userProfileChars", 0),
                pendingEvents = json.optInt("pendingEvents", 0),
                lastMessage = json.optString("lastMessage", "unknown")
            )
        }.getOrDefault(
            MemoryEvolutionStatus(
                lastRunAtMs = 0L,
                processedEvents = 0,
                acceptedCandidates = 0,
                globalMemoryChars = 0,
                userProfileChars = 0,
                pendingEvents = 0,
                lastMessage = "状态解析失败"
            )
        )
    }

    fun save(status: MemoryEvolutionStatus) {
        val json = JSONObject()
            .put("lastRunAtMs", status.lastRunAtMs)
            .put("processedEvents", status.processedEvents)
            .put("acceptedCandidates", status.acceptedCandidates)
            .put("globalMemoryChars", status.globalMemoryChars)
            .put("userProfileChars", status.userProfileChars)
            .put("pendingEvents", status.pendingEvents)
            .put("lastMessage", status.lastMessage)
        mmkv?.encode(MMKVKeys.MEMORY_EVOLUTION_STATUS_JSON.key, json.toString())
    }
}
