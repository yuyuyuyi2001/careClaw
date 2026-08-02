package com.shijing.xomniclaw.ui.compose

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 按住录音按钮
 * 长按开始语音识别，松开后回调识别文本。
 * 按住时有红色脉冲动画和 "识别中..." 提示。
 */
@Composable
fun VoiceRecordButton(
    isListening: Boolean,
    onPressStart: () -> Unit,
    onPressEnd: () -> Unit,
    modifier: Modifier = Modifier
) {
    // 脉冲缩放动画
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.25f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    val bgColor = if (isListening) Color(0xFFFF3B30) else Color(0xFFE0E0E0)
    val iconTint = if (isListening) Color.White else Color(0xFF666666)
    val scaleValue = if (isListening) pulseScale else 1f

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
    ) {
        // "识别中..." 浮动提示
        if (isListening) {
            Text(
                text = "识别中...",
                style = TextStyle(
                    fontSize = 11.sp,
                    color = Color(0xFFFF3B30),
                    fontWeight = FontWeight.Medium
                ),
                modifier = Modifier.padding(bottom = 4.dp)
            )
        }

        Box(
            modifier = Modifier
                .size(44.dp)
                .scale(scaleValue)
                .clip(CircleShape)
                .background(bgColor)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onPress = {
                            onPressStart()
                            // 等待用户抬手
                            val released = tryAwaitRelease()
                            onPressEnd()
                        }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Mic,
                contentDescription = "语音输入",
                tint = iconTint,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}
