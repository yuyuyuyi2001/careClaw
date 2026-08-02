package com.shijing.xomniclaw.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale
import java.util.UUID

/**
 * 替身模式专用的 TTS 管理器。
 *
 * 单独抽出来是为了避免在控制器里直接堆积引擎状态和去重逻辑。
 */
class CompanionTtsManager(context: Context) : TextToSpeech.OnInitListener {
    private val appContext = context.applicationContext
    private val tts = TextToSpeech(appContext, this)

    @Volatile
    private var initialized = false
    private var pendingText: String? = null
    private var lastSpokenText: String? = null

    override fun onInit(status: Int) {
        initialized = status == TextToSpeech.SUCCESS
        if (!initialized) {
            Log.w(TAG, "Companion TTS init failed: status=$status")
            return
        }
        // 与主聊天页 TTS 使用同一套语速/音高策略，避免「偶发极快」听感。
        tts.applyStableSpeechParams(Locale.CHINA)
        pendingText?.let {
            pendingText = null
            speak(it)
        }
    }

    fun speak(text: String) {
        // 只朗读自然语言说明，不朗读 JSON 指令块。
        val normalized = sanitizeForTts(text)
        if (normalized.isBlank() || normalized == lastSpokenText) {
            return
        }
        if (!initialized) {
            pendingText = normalized
            return
        }
        lastSpokenText = normalized
        tts.speak(normalized, TextToSpeech.QUEUE_FLUSH, null, UUID.randomUUID().toString())
    }

    fun resetDedup() {
        lastSpokenText = null
        pendingText = null
    }

    /**
     * 用户开始新一轮说话时立即打断当前播报，避免“边播边录”。
     */
    fun stop(resetDedup: Boolean = false) {
        pendingText = null
        if (resetDedup) {
            lastSpokenText = null
        }
        if (initialized) {
            tts.stop()
        }
    }

    fun shutdown() {
        pendingText = null
        lastSpokenText = null
        initialized = false
        tts.stop()
        tts.shutdown()
    }

    companion object {
        private const val TAG = "CompanionTtsManager"
    }
}
