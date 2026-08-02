package com.shijing.xomniclaw.voice

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import com.shijing.xomniclaw.accessibility.audio.AudioPreprocessorConfig
import com.shijing.xomniclaw.accessibility.audio.ObserverAudioPreprocessor
import com.shijing.xomniclaw.config.VisionConfig
import com.shijing.xomniclaw.util.MediaProjectionHelper
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 语音录制管理器
 * 使用 AudioRecord 录制 PCM；结束后在端侧经 [LocalVoiceVisionHub] 完成 STT 与多模态推理。
 * 不依赖 Google SpeechRecognizer，兼容国产 Android 设备。
 */
class VoiceRecorderManager(private val context: Context) {

    companion object {
        private const val TAG = "VoiceRecorderManager"
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    private val _isProcessing = MutableStateFlow(false)
    /** 录音结束后，STT/VLM 仍在处理中。 */
    val isProcessing: StateFlow<Boolean> = _isProcessing.asStateFlow()

    private val _recognizedText = MutableStateFlow("")
    /** STT 识别出的文字 */
    val recognizedText: StateFlow<String> = _recognizedText.asStateFlow()

    private val _aiReply = MutableStateFlow("")
    /** 端侧 VLM 推理返回的 AI 回复 */
    val aiReply: StateFlow<String> = _aiReply.asStateFlow()

    private val _directAction = MutableStateFlow<JSONObject?>(null)
    /** 端侧返回的设备操作指令（JSON），由 UI 层消费后执行 */
    val directAction: StateFlow<JSONObject?> = _directAction.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /**
     * 一次完整的语音处理结果事件，供替身模式这类后台控制器消费。
     * 保留 StateFlow 的同时再提供 SharedFlow，避免只能在 Compose 里用状态轮询。
     */
    enum class VoiceProcessingStage {
        TRANSCRIPTION_READY,
        COMPLETED,
        FAILED
    }

    data class VoiceProcessingResult(
        val stage: VoiceProcessingStage,
        val recognizedText: String,
        val aiReply: String,
        val directAction: JSONObject?,
        val errorMessage: String?,
        val pressStartTimestampMs: Long?,
        val alignedFramePath: String? = null
    )

    private val _voiceResultEvents = MutableSharedFlow<VoiceProcessingResult>(extraBufferCapacity = 4)
    val voiceResultEvents: SharedFlow<VoiceProcessingResult> = _voiceResultEvents.asSharedFlow()

    /** 完整 vision 配置（STT / VLM 等） */
    var visionConfig: VisionConfig = VisionConfig()
        set(value) {
            field = value
            localHub = LocalVoiceVisionHub(context.applicationContext, value)
        }

    /** 复用本地 Hub，保留最近对话上下文（对齐 xomniclaw_hub_v2.py 的 chat_history_brain）。 */
    private var localHub: LocalVoiceVisionHub = LocalVoiceVisionHub(context.applicationContext, visionConfig)
    private var audioPreprocessor: ObserverAudioPreprocessor? = null

    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    private val recordingScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var pcmBuffer: ByteArrayOutputStream? = null
    private var pressStartTimestampMs: Long? = null

    /** [startListening] 时快照：本轮是否在视觉叠加层（摄像头/屏幕推流）内按键说话。 */
    private var alignedFrameDisclosureSnapshot: Boolean = false

    /**
     * 开始录音
     */
    /** UI 层消费完 aiReply / directAction 后调用，防止重复触发 */
    fun consumeReply() {
        _recognizedText.value = ""
        _aiReply.value = ""
        _directAction.value = null
    }

    fun startListening(pressStartTs: Long = System.currentTimeMillis()) {
        if (_isListening.value) {
            Log.w(TAG, "Already listening, ignoring duplicate start")
            return
        }
        // 在任何失败路径之前快照，保证与本轮 STT/VLM 对齐。
        alignedFrameDisclosureSnapshot = VoiceRoundDisclosureHint.visionOverlayActiveForNextRecording
        _error.value = null
        _recognizedText.value = ""
        _aiReply.value = ""
        _directAction.value = null
        _isProcessing.value = false
        pressStartTimestampMs = pressStartTs

        val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        if (bufferSize == AudioRecord.ERROR || bufferSize == AudioRecord.ERROR_BAD_VALUE) {
            _error.value = "音频参数不支持"
            _voiceResultEvents.tryEmit(
                VoiceProcessingResult(
                    stage = VoiceProcessingStage.FAILED,
                    recognizedText = "",
                    aiReply = "",
                    directAction = null,
                    errorMessage = _error.value,
                    pressStartTimestampMs = pressStartTs
                )
            )
            Log.e(TAG, "Invalid buffer size: $bufferSize")
            return
        }

        try {
            val audioSource = if (visionConfig.aecEnabled) {
                MediaRecorder.AudioSource.VOICE_COMMUNICATION
            } else {
                MediaRecorder.AudioSource.MIC
            }
            audioRecord = AudioRecord(
                audioSource,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize * 2
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                _error.value = "录音器初始化失败"
                Log.e(TAG, "AudioRecord failed to initialize")
                audioRecord?.release()
                audioRecord = null
                return
            }

            pcmBuffer = ByteArrayOutputStream()
            audioPreprocessor?.release()
            audioPreprocessor = ObserverAudioPreprocessor(
                context = context.applicationContext,
                config = buildAudioPreprocessorConfig(visionConfig),
                mediaProjectionProvider = { MediaProjectionHelper.getMediaProjection() }
            ).also { preprocessor ->
                preprocessor.start(audioRecord?.audioSessionId ?: 0)
            }
            audioRecord?.startRecording()
            _isListening.value = true
            Log.i(TAG, "Started recording (PCM 16kHz mono, aec=${visionConfig.aecEnabled})")

            // 后台线程持续读取 PCM 数据
            recordingJob = recordingScope.launch {
                val buffer = ByteArray(bufferSize)
                while (isActive && _isListening.value) {
                    val read = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                    if (read > 0) {
                        val processed = audioPreprocessor?.processCapturePcm(buffer, read) ?: buffer.copyOf(read)
                        pcmBuffer?.write(processed, 0, processed.size)
                    }
                }
            }
        } catch (e: SecurityException) {
            _error.value = "录音权限不足"
            _voiceResultEvents.tryEmit(
                VoiceProcessingResult(
                    stage = VoiceProcessingStage.FAILED,
                    recognizedText = "",
                    aiReply = "",
                    directAction = null,
                    errorMessage = _error.value,
                    pressStartTimestampMs = pressStartTs
                )
            )
            Log.e(TAG, "SecurityException: ${e.message}")
        } catch (e: Exception) {
            _error.value = "录音启动失败: ${e.message}"
            _voiceResultEvents.tryEmit(
                VoiceProcessingResult(
                    stage = VoiceProcessingStage.FAILED,
                    recognizedText = "",
                    aiReply = "",
                    directAction = null,
                    errorMessage = _error.value,
                    pressStartTimestampMs = pressStartTs
                )
            )
            Log.e(TAG, "Failed to start recording", e)
        }
    }

    /**
     * 停止录音，将音频发送到 Hub 做 STT
     */
    fun stopListening() {
        if (!_isListening.value) return
        _isListening.value = false

        // 停止录音
        recordingJob?.cancel()
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping AudioRecord: ${e.message}")
        }
        audioPreprocessor?.stop()
        audioRecord = null
        Log.i(TAG, "Stopped recording")

        // 取出 PCM 数据并发送到 Hub 做 STT
        val pcmData = pcmBuffer?.toByteArray()
        pcmBuffer = null
        val captureStartedAt = pressStartTimestampMs
        pressStartTimestampMs = null

        if (pcmData == null || pcmData.size < 3200) {
            // 16kHz * 2bytes * 0.1s = 3200, 太短的录音忽略
            _error.value = "录音太短"
            _isProcessing.value = false
            _voiceResultEvents.tryEmit(
                VoiceProcessingResult(
                    stage = VoiceProcessingStage.FAILED,
                    recognizedText = "",
                    aiReply = "",
                    directAction = null,
                    errorMessage = _error.value,
                    pressStartTimestampMs = captureStartedAt
                )
            )
            Log.w(TAG, "Recording too short: ${pcmData?.size ?: 0} bytes")
            return
        }

        _isProcessing.value = true
        recordingScope.launch {
            sendAudioForSTT(pcmData, captureStartedAt)
        }
    }

    /**
     * 释放资源
     */
    fun destroy() {
        _isListening.value = false
        _isProcessing.value = false
        recordingJob?.cancel()
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (_: Exception) {}
        audioPreprocessor?.release()
        audioPreprocessor = null
        audioRecord = null
        pcmBuffer = null
        pressStartTimestampMs = null
        recordingScope.cancel()
    }

    /**
     * 将 Vision 配置转换为观察层音频预处理配置，避免录音管理器直接关心底层实现细节。
     */
    private fun buildAudioPreprocessorConfig(vision: VisionConfig): AudioPreprocessorConfig {
        return AudioPreprocessorConfig(
            enabled = vision.aecEnabled,
            preferWebRtcAec = vision.aecPreferWebRtc,
            enablePlaybackCapture = vision.aecPlaybackCaptureEnabled,
            enableSystemAecFallback = vision.aecSystemFallbackEnabled,
            sampleRateHz = SAMPLE_RATE,
            frameSizeSamples = 160
        )
    }

    /**
     * 端侧 [LocalVoiceVisionHub]：STT → 多模态理解与动作输出（与历史 PC Hub /sync_audio 响应形状一致）。
     */
    private suspend fun sendAudioForSTT(pcmData: ByteArray, captureStartedAt: Long?) {
        try {
            val wavData = pcmToWav(pcmData, SAMPLE_RATE, 1, 16)
            Log.i(TAG, "端侧语音管线, WAV ${wavData.size} bytes")
            val sttText = localHub.transcribeAudio(wavData)
            _recognizedText.value = sttText
            _voiceResultEvents.emit(
                VoiceProcessingResult(
                    stage = VoiceProcessingStage.TRANSCRIPTION_READY,
                    recognizedText = sttText,
                    aiReply = "",
                    directAction = null,
                    errorMessage = null,
                    pressStartTimestampMs = captureStartedAt
                )
            )
            val json = localHub.askFromTranscript(
                sttText,
                captureStartedAt,
                forceAlignedFrameDisclosure = alignedFrameDisclosureSnapshot,
                voiceStartedWithVision = alignedFrameDisclosureSnapshot
            )

            if (json.optString("status", "") == "error") {
                _error.value = json.optString("error", "本地语音管线失败")
                _voiceResultEvents.emit(
                    VoiceProcessingResult(
                        stage = VoiceProcessingStage.FAILED,
                        recognizedText = "",
                        aiReply = "",
                        directAction = null,
                        errorMessage = _error.value,
                        pressStartTimestampMs = captureStartedAt
                    )
                )
                return
            }

            val text = json.optString("text", "").trim()
            val reply = json.optString("reply", "").trim()

            if (text.isEmpty()) {
                _error.value = json.optString("error", "未识别到语音").ifBlank { "未识别到语音" }
                Log.w(TAG, "STT empty, json=$json")
                _voiceResultEvents.emit(
                    VoiceProcessingResult(
                        stage = VoiceProcessingStage.FAILED,
                        recognizedText = "",
                        aiReply = reply,
                        directAction = json.optJSONObject("direct_action"),
                        errorMessage = _error.value,
                        pressStartTimestampMs = captureStartedAt
                    )
                )
            } else {
                Log.i(TAG, "STT result: $text")
                _recognizedText.value = text
                if (reply.isNotEmpty()) {
                    Log.i(TAG, "AI reply: ${reply.take(100)}")
                    _aiReply.value = reply
                }
                val actionObj = json.optJSONObject("direct_action")
                if (actionObj != null) {
                    Log.i(TAG, "Direct action: $actionObj")
                    _directAction.value = actionObj
                }
                _voiceResultEvents.emit(
                    VoiceProcessingResult(
                        stage = VoiceProcessingStage.COMPLETED,
                        recognizedText = text,
                        aiReply = reply,
                        directAction = actionObj,
                        errorMessage = null,
                        pressStartTimestampMs = captureStartedAt,
                        alignedFramePath = json.optString("aligned_frame_path", "").ifBlank { null }
                    )
                )
            }
        } catch (e: Exception) {
            _error.value = "语音处理失败: ${e.message}"
            _voiceResultEvents.emit(
                VoiceProcessingResult(
                    stage = VoiceProcessingStage.FAILED,
                    recognizedText = "",
                    aiReply = "",
                    directAction = null,
                    errorMessage = _error.value,
                    pressStartTimestampMs = captureStartedAt
                )
            )
            Log.e(TAG, "sendAudioForSTT failed", e)
        } finally {
            _isProcessing.value = false
        }
    }

    /**
     * 将 PCM 裸数据封装为标准 WAV 格式
     */
    private fun pcmToWav(pcmData: ByteArray, sampleRate: Int, channels: Int, bitsPerSample: Int): ByteArray {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val dataSize = pcmData.size
        val totalSize = 36 + dataSize

        val buffer = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN)

        // RIFF header
        buffer.put("RIFF".toByteArray())
        buffer.putInt(totalSize)
        buffer.put("WAVE".toByteArray())

        // fmt chunk
        buffer.put("fmt ".toByteArray())
        buffer.putInt(16)              // chunk size
        buffer.putShort(1)             // PCM format
        buffer.putShort(channels.toShort())
        buffer.putInt(sampleRate)
        buffer.putInt(byteRate)
        buffer.putShort(blockAlign.toShort())
        buffer.putShort(bitsPerSample.toShort())

        // data chunk
        buffer.put("data".toByteArray())
        buffer.putInt(dataSize)
        buffer.put(pcmData)

        return buffer.array()
    }
}
