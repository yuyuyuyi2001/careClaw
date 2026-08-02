package com.shijing.xomniclaw.ui.compose

import android.view.ViewGroup
import androidx.camera.core.CameraSelector
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.shijing.xomniclaw.vision.CameraFramePusher
import com.shijing.xomniclaw.vision.ScreenFrameSampler
import com.shijing.xomniclaw.vision.VisionFrameBuffer

/** 语音视觉模式的画面来源：后置 / 前置 / 屏幕截屏流 */
enum class VisionFrameSource {
    CAMERA_BACK,
    CAMERA_FRONT,
    SCREEN_CAPTURE
}

/**
 * 全屏视觉叠加层：摄像头预览推流，或屏幕 MediaProjection 采样推流。
 */
@Composable
fun CameraPreviewOverlay(
    framePusher: CameraFramePusher,
    screenSampler: ScreenFrameSampler,
    source: VisionFrameSource,
    onClose: () -> Unit,
    onSwitchCamera: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    isVoiceListening: Boolean = false,
    onVoicePressStart: (() -> Unit)? = null,
    onVoicePressEnd: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    val isRunning by framePusher.isRunning.collectAsState()
    val frameCount by framePusher.frameCount.collectAsState()
    val lastPushMs by framePusher.lastPushDurationMs.collectAsState()

    val previewView = remember {
        PreviewView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }

    DisposableEffect(source) {
        VisionFrameBuffer.clear()
        when (source) {
            VisionFrameSource.CAMERA_BACK -> {
                framePusher.lensFacing = CameraSelector.LENS_FACING_BACK
                framePusher.start(lifecycleOwner, previewView)
                onDispose {
                    framePusher.stop()
                }
            }
            VisionFrameSource.CAMERA_FRONT -> {
                framePusher.lensFacing = CameraSelector.LENS_FACING_FRONT
                framePusher.start(lifecycleOwner, previewView)
                onDispose {
                    framePusher.stop()
                }
            }
            VisionFrameSource.SCREEN_CAPTURE -> {
                screenSampler.start(scope)
                onDispose {
                    screenSampler.stop()
                }
            }
        }
    }

    val sourceLabel = when (source) {
        VisionFrameSource.CAMERA_BACK -> "后置摄像头"
        VisionFrameSource.CAMERA_FRONT -> "前置摄像头"
        VisionFrameSource.SCREEN_CAPTURE -> "屏幕画面"
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(if (source == VisionFrameSource.SCREEN_CAPTURE) Color.Transparent else Color.Black)
    ) {
        when (source) {
            VisionFrameSource.CAMERA_BACK, VisionFrameSource.CAMERA_FRONT -> {
                AndroidView(
                    factory = { previewView },
                    modifier = Modifier.fillMaxSize()
                )
            }
            // 屏幕采集时保持透明覆盖，减少对当前画面的遮挡。
            VisionFrameSource.SCREEN_CAPTURE -> {}
        }

        Surface(
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(start = 12.dp, top = 12.dp),
            color = Color.Black.copy(alpha = 0.45f),
            shape = RoundedCornerShape(14.dp)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(
                            when (source) {
                                VisionFrameSource.SCREEN_CAPTURE -> Color(0xFF2196F3)
                                else -> if (isRunning) Color.Red else Color.Gray
                            }
                        )
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    // 视频帧仅写入 VisionFrameBuffer，无远端 Hub
                    val modeNote = "端侧缓冲"
                    Text(
                        text = when (source) {
                            VisionFrameSource.SCREEN_CAPTURE -> "屏幕采集 → $modeNote"
                            else -> "$sourceLabel ${framePusher.fps}fps → $modeNote"
                        },
                        style = TextStyle(
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    )
                    Text(
                        text = when (source) {
                            VisionFrameSource.SCREEN_CAPTURE -> "右侧按住语音"
                            else -> "已推送 $frameCount 帧 | ${lastPushMs}ms"
                        },
                        style = TextStyle(
                            color = Color.White.copy(alpha = 0.75f),
                            fontSize = 10.sp
                        )
                    )
                }
            }
        }

        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(end = 12.dp, top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (source != VisionFrameSource.SCREEN_CAPTURE && onSwitchCamera != null) {
                IconButton(
                    onClick = onSwitchCamera,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.4f))
                ) {
                    Icon(
                        imageVector = Icons.Default.Cameraswitch,
                        contentDescription = "切换前后摄像头",
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            IconButton(
                onClick = onClose,
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.4f))
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "关闭",
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }
        }

        val voicePressStart = onVoicePressStart
        val voicePressEnd = onVoicePressEnd
        val pressStartLatest = rememberUpdatedState(voicePressStart)
        val pressEndLatest = rememberUpdatedState(voicePressEnd)
        if (voicePressStart != null && voicePressEnd != null) {
            Surface(
                modifier = Modifier
                    .align(
                        if (source == VisionFrameSource.SCREEN_CAPTURE) Alignment.CenterEnd
                        else Alignment.BottomEnd
                    )
                    .padding(
                        end = 12.dp,
                        bottom = if (source == VisionFrameSource.SCREEN_CAPTURE) 24.dp else 96.dp
                    )
                    .navigationBarsPadding()
                    .size(52.dp)
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onPress = {
                                pressStartLatest.value?.invoke()
                                try {
                                    tryAwaitRelease()
                                } finally {
                                    pressEndLatest.value?.invoke()
                                }
                            }
                        )
                    },
                shape = CircleShape,
                color = if (isVoiceListening) Color(0xFFFF3B30) else Color.Black.copy(alpha = 0.45f)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.Mic,
                        contentDescription = if (isVoiceListening) "语音识别中" else "按住语音输入",
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }

        if (source != VisionFrameSource.SCREEN_CAPTURE) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter),
                color = Color.Black.copy(alpha = 0.5f),
                shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                        .navigationBarsPadding(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Videocam,
                            contentDescription = null,
                            tint = Color.White.copy(alpha = 0.8f),
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (isVoiceListening) "正在识别语音..." else "右侧悬浮麦克风按住说话",
                            style = TextStyle(
                                color = Color.White.copy(alpha = 0.8f),
                                fontSize = 13.sp
                            )
                        )
                    }
                }
            }
        }
    }
}
