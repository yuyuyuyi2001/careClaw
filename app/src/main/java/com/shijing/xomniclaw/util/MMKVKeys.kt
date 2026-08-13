/**
 * OmniClaw Source Reference:
 * - ../xomniclaw/src/agents/(all)
 *
 * OmniClaw adaptation: utility helpers.
 */
package com.shijing.xomniclaw.util

/**
 * MMKV configuration keys
 * New architecture focus: AgentLoop + Tools
 */
enum class MMKVKeys(val key: String) {
    // ========== Retained features ==========
    // Floating window display switch (EasyFloat)
    FLOAT_WINDOW_ENABLED("float_window_enabled"),
    // Prompt dumps 开关（设置页控制；默认关闭）
    PROMPT_DUMPS_ENABLED("prompt_dumps_enabled"),
    // 将实际上传 wire JSON 分段打 logcat，并落盘到 logs/llm-full-request/（标签 LLMFullRequest，默认关闭）
    LLM_FULL_REQUEST_LOGCAT("llm_full_request_logcat"),

    // Gallery memory settings
    GALLERY_MEMORY_ENABLED("gallery_memory_enabled"),
    GALLERY_PROFILE_LOADING_ENABLED("gallery_profile_loading_enabled"),
    GALLERY_MEMORY_SCAN_INTERVAL_MINUTES("gallery_memory_scan_interval_minutes"),
    GALLERY_MEMORY_MANUAL_SYNC_MAX_IMAGES("gallery_memory_manual_sync_max_images"),
    GALLERY_MEMORY_TASK_ID("gallery_memory_task_id"),
    GALLERY_MEMORY_SYNC_STATUS_JSON("gallery_memory_sync_status_json"),

    // Global memory evolution settings
    MEMORY_EVOLUTION_ENABLED("memory_evolution_enabled"),
    MEMORY_EVOLUTION_INTERVAL_MINUTES("memory_evolution_interval_minutes"),
    MEMORY_EVOLUTION_MAX_GLOBAL_CHARS("memory_evolution_max_global_chars"),
    MEMORY_EVOLUTION_MAX_PENDING_PER_RUN("memory_evolution_max_pending_per_run"),
    MEMORY_EVOLUTION_TASK_ID("memory_evolution_task_id"),
    MEMORY_EVOLUTION_STATUS_JSON("memory_evolution_status_json"),

    // Token usage aggregation (global persisted counters)
    GLOBAL_TOKEN_PROMPT_TOTAL("global_token_prompt_total"),
    GLOBAL_TOKEN_COMPLETION_TOTAL("global_token_completion_total"),
    GLOBAL_TOKEN_TOTAL("global_token_total"),

    // HTTP 入口鉴权 token（备用远程通道，P4）
    GATEWAY_AUTH_TOKEN("gateway_auth_token")
}
