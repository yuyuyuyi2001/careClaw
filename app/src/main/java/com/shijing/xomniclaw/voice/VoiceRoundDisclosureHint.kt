package com.shijing.xomniclaw.voice

/**
 * 主界面「按住说话」与全屏 [CameraPreviewOverlay] 共用同一套 [VoiceRecorderManager]，
 * 需在开始录音前标记：本轮是否来自用户主动打开的视觉叠加层（摄像头 / 屏幕推流）。
 *
 * 纯主界面语音仍走 [VoiceIntentAtoms.impliesAlignedScreenshotDisclosureForChat]；
 * 叠加层内语音则始终允许在对话中展示对齐截图，避免「屏外摄像头」场景被关键词过滤掉。
 */
object VoiceRoundDisclosureHint {

    /**
     * 由 UI 在 [VoiceRecorderManager.startListening] 之前写入；
     * 由 [VoiceRecorderManager] 在 [startListening] 入口复制到本轮快照，避免推理过程中 overlay 关闭导致误判。
     */
    @Volatile
    var visionOverlayActiveForNextRecording: Boolean = false
}
