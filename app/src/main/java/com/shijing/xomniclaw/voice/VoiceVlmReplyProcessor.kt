package com.shijing.xomniclaw.voice

import org.json.JSONObject

/**
 * VLM 回复的单次流水线：对同一 [rawAiReply] 顺序做「解析 JSON → 剥离展示用 JSON 块 → 编排体改写」，
 * 避免在 Hub 内对同一字符串反复跑多组正则。
 */
internal object VoiceVlmReplyProcessor {

    private val fenceJson = Regex("""```(?:json)?\s*(\{[\s\S]*?\})\s*```""", RegexOption.IGNORE_CASE)
    private val bareAction = Regex("""\{[^{}]*"action"[^{}]*\}""")

    private val looseCoordinate = Regex(""""coordinate"\s*:\s*\[\s*(-?\d+)\s+(-?\d+)\s*]""")

    data class SanitizedVlm(
        /** 从原文解析出的 JSON 指令，可能为 null。 */
        val command: JSONObject?,
        /** 去掉 ```json``` / 行内 action JSON 后的纯文本（供编排 hint、日志）。 */
        val plainNoJson: String,
        /**
         * 进入 UI / 后续 resolve 前的用户可见文案种子（已按是否编排做过一次体裁处理）。
         */
        val replySeed: String,
    )

    /**
     * 对原始 VLM 输出跑完整条流水线，每个阶段只处理必要字段各一次。
     */
    fun sanitize(
        rawAiReply: String,
        wantsOrchestration: Boolean,
        userInput: String,
        looksLikeQuiz: (String) -> Boolean,
    ): SanitizedVlm {
        val command = extractJsonCommand(rawAiReply)
        var plainNoJson = stripJsonLikeBlocks(rawAiReply)
        // 常见根因：模型仅输出 JSON（或因围栏/换行导致剥离后为空），日志里会看到 vlm_raw 为空。
        // 此时用语义预览补齐，避免后续链路把「空正文」误判为模型无输出。
        if (plainNoJson.isBlank() && command != null) {
            plainNoJson = buildInstructionPreviewFromCommand(command)
        }
        val replySeed = buildReplySeed(
            rawAiReply = rawAiReply,
            plainNoJson = plainNoJson,
            wantsOrchestration = wantsOrchestration,
            userInput = userInput,
            looksLikeQuiz = looksLikeQuiz,
            parsedCommand = command,
        )
        return SanitizedVlm(command = command, plainNoJson = plainNoJson, replySeed = replySeed)
    }

    /**
     * 当模型输出可被解析为 JSON 指令且剥离后没有自然语言正文时，
     * 生成一段短预览供日志与用户侧种子文案使用。
     */
    private fun buildInstructionPreviewFromCommand(command: JSONObject): String {
        val action = command.optString("action", "").trim()
        return when (action) {
            "agent_task" -> {
                val task = command.optString("task", "").trim()
                val short = if (task.length > 240) task.take(240) + "…" else task
                "[指令预览|agent_task] ${short.ifBlank { "(task 为空)" }}"
            }
            "open" -> {
                val pkg = command.optString("package_name", "").trim()
                val uri = command.optString("uri", "").trim()
                when {
                    pkg.isNotBlank() -> "[指令预览|open] package_name=$pkg"
                    uri.isNotBlank() -> "[指令预览|open] uri=${uri.take(160)}${if (uri.length > 160) "…" else ""}"
                    else -> "[指令预览|open] (参数为空)"
                }
            }
            "act" -> {
                val kind = command.optString("kind", "").trim()
                val ref = command.optString("ref", "").trim()
                val target = command.optString("target", "").trim()
                buildString {
                    append("[指令预览|act] kind=").append(kind.ifBlank { "?" })
                    if (ref.isNotBlank()) append(" ref=").append(ref)
                    if (target.isNotBlank()) append(" target=").append(target.take(80))
                }
            }
            else -> "[指令预览|$action] ${command.toString().take(240)}"
        }
    }

    private fun buildReplySeed(
        rawAiReply: String,
        plainNoJson: String,
        wantsOrchestration: Boolean,
        userInput: String,
        looksLikeQuiz: (String) -> Boolean,
        parsedCommand: JSONObject?,
    ): String {
        val base = if (wantsOrchestration) {
            val shaped = ensureAgentPromptStyleForOrchestration(plainNoJson, userInput, looksLikeQuiz)
            stripJsonLikeBlocks(shaped).ifBlank { shaped.trim() }
        } else {
            plainNoJson
        }
        if (parsedCommand != null && base.isBlank()) {
            return when (parsedCommand.optString("action", "")) {
                "agent_task" -> "好的，我来帮你完成这个任务"
                else -> "好的，正在执行"
            }
        }
        return base
    }

    /**
     * 编排模式下将 VLM 口语整理成带「任务理解 / 执行约束」结构的提示体（输入已为无 JSON 块文本）。
     */
    fun ensureAgentPromptStyleForOrchestration(
        vlmPlainNoJson: String,
        userInput: String,
        looksLikeQuiz: (String) -> Boolean,
    ): String {
        val plain = vlmPlainNoJson.trim()
        if (plain.contains("任务理解") && plain.contains("执行约束")) return plain
        val prompt = StringBuilder()
        prompt.append("任务理解：\n")
        prompt.append("- 用户希望我在当前屏幕上下文中完成目标：").append(userInput.trim()).append("\n\n")
        prompt.append("执行约束：\n")
        prompt.append("1. 每一轮先 snapshot 再决策，不可盲操作。\n")
        if (looksLikeQuiz(userInput) || looksLikeQuiz(plain)) {
            prompt.append("2. 题型可能包含单选/多选/填空，逐题阅读后再作答。\n")
            prompt.append("3. 中途某题失败不要停止，继续下一题直到全部完成。\n")
            prompt.append("4. 未明确“只做一道/只做第N题”时默认整题完成。\n")
            prompt.append("5. 全部完成后若还能继续，先询问用户是否继续。\n")
            prompt.append("6. 视觉补充信息（仅供参考）：").append(plain.ifBlank { "（无）" }).append("\n")
        } else {
            prompt.append("2. 视觉补充信息（仅供参考）：").append(plain.ifBlank { "（无）" }).append("\n")
            prompt.append("3. 遇到失败时执行恢复策略并继续推进目标。\n")
        }
        prompt.append("\n完成标准：达到目标状态后再向用户汇报结果。")
        return prompt.toString()
    }

    fun stripJsonLikeBlocks(text: String): String {
        var s = fenceJson.replace(text, "").trim()
        s = bareAction.replace(s, "").trim()
        return s
    }

    private fun extractJsonCommand(text: String): JSONObject? {
        val candidates = linkedSetOf<String>()
        fenceJson.findAll(text).forEach { m ->
            m.groupValues.getOrNull(1)?.trim()?.let { if (it.isNotEmpty()) candidates.add(it) }
        }
        bareAction.findAll(text).forEach { m ->
            m.value.trim().let { if (it.isNotEmpty()) candidates.add(it) }
        }
        for (raw in candidates) {
            tryParseJsonObject(raw)?.let { return it }
            tryParseJsonObject(normalizeLooseCoordinateJson(raw))?.let { return it }
        }
        return null
    }

    private fun normalizeLooseCoordinateJson(jsonStr: String): String =
        looseCoordinate.replace(jsonStr) { m ->
            """"coordinate":[${m.groupValues[1]},${m.groupValues[2]}]"""
        }

    private fun tryParseJsonObject(jsonStr: String): JSONObject? = try {
        JSONObject(jsonStr)
    } catch (_: Exception) {
        null
    }

    /**
     * 答题范围默认规则：在流水线末尾对 [command] 与 [reply] 各做一次结构化修正（与 Hub 原逻辑一致）。
     */
    fun applyQuizScopeDefault(
        userInput: String,
        command: JSONObject?,
        currentReply: String,
        looksLikeQuiz: (String) -> Boolean,
        hasExplicitSingleQuestionScope: (String) -> Boolean,
    ): Pair<JSONObject?, String> {
        if (command == null || command.optString("action", "") != "agent_task") {
            return command to currentReply
        }
        val originalTask = command.optString("task", "")
        val isQuizTask = looksLikeQuiz(userInput) || looksLikeQuiz(originalTask)
        if (!isQuizTask || hasExplicitSingleQuestionScope(userInput)) {
            return command to currentReply
        }
        val normalizedTask = buildString {
            append(originalTask.trim())
            append("\n\n【答题范围默认规则】用户未明确要求“只做一道/只做第N题”，")
            append("请默认完成当前流程中的全部题目（含翻页后的后续题目），直到整题流程结束。")
        }
        val patchedCommand = JSONObject(command.toString()).put("task", normalizedTask)
        val normalizedReply = normalizeReplyForFullQuizScope(currentReply)
        return patchedCommand to normalizedReply
    }

    /**
     * 仅做用语中性化（避免单题口吻）；不向用户追加「默认全题/除非单题」类播报句，该规则已在 task 补丁中给 Agent。
     */
    private fun normalizeReplyForFullQuizScope(reply: String): String {
        val text = reply.trim()
        if (text.isBlank()) {
            return "好的，我来安排主助手执行。"
        }
        return text
            .replace("当前这道", "当前流程中的全部题目")
            .replace("这道题", "整题流程")
            .replace("本题", "整题流程")
    }
}
