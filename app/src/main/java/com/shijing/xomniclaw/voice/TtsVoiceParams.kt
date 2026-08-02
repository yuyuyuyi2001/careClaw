package com.shijing.xomniclaw.voice

import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * 统一 TTS 语速与音高，避免部分系统语音引擎默认语速过快或不稳定。
 *
 * Android 文档约定 1.0f 为正常语速；部分机型/离线包会偏快，略压到 0.92f。
 */
fun TextToSpeech.applyStableSpeechParams(preferredLocale: Locale = Locale.CHINA): TextToSpeech {
    val localeResult = setLanguage(preferredLocale)
    if (localeResult == TextToSpeech.LANG_MISSING_DATA ||
        localeResult == TextToSpeech.LANG_NOT_SUPPORTED
    ) {
        setLanguage(Locale.getDefault())
    }
    setSpeechRate(0.92f)
    setPitch(1.0f)
    return this
}

private val TTS_CODE_FENCE_BLOCK = Regex("""```[\s\S]*?```""", RegexOption.IGNORE_CASE)
private val TTS_INLINE_ACTION_JSON = Regex("""\{[^{}]*"action"[^{}]*\}""", RegexOption.IGNORE_CASE)

/**
 * 清洗要朗读的文本：
 * 1) 移除 ```json ...``` 代码块；
 * 2) 移除行内 action JSON；
 * 3) 合并多余空白，避免引擎逐字符拼读符号导致“语速异常”观感。
 */
fun sanitizeForTts(rawText: String): String {
    var cleaned = TTS_CODE_FENCE_BLOCK.replace(rawText, " ").trim()
    cleaned = TTS_INLINE_ACTION_JSON.replace(cleaned, " ").trim()
    cleaned = cleaned
        .replace(Regex("""[ \t]+"""), " ")
        .replace(Regex("""\n{2,}"""), "\n")
        .trim()
    return cleaned
}
