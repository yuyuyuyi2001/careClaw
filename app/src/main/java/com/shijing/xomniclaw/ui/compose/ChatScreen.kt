/**
 * OmniClaw Source Reference:
 * - ../xomniclaw/src/gateway/(all)
 *
 * OmniClaw adaptation: Android UI layer.
 */
package com.shijing.xomniclaw.ui.compose

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.BitmapFactory
import android.widget.Toast
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.shijing.xomniclaw.ui.session.SessionManager
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * Chat interface - Inspired by Stream Chat Android UI design style
 *
 * Features:
 * - Markdown rendering (headings, bold, italic, code blocks, lists, quotes)
 * - Long message collapse/expand
 * - Long press to copy
 * - Wider message bubbles
 * - Auto-scroll to bottom
 */

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val content: String,
    val isUser: Boolean,
    val timestamp: Long = System.currentTimeMillis(),
    val status: MessageStatus = MessageStatus.SENT,
    val kind: ChatMessageKind = if (isUser) ChatMessageKind.USER else ChatMessageKind.ASSISTANT,
    val eventKey: String? = null
)

/**
 * 顶部“执行中任务”状态条使用的数据模型。
 */
data class RunningTaskStatus(
    val sessionId: String,
    val title: String
)

enum class MessageStatus {
    SENDING,
    SENT,
    ERROR
}

enum class ChatMessageKind {
    USER,
    ASSISTANT,
    THINKING,
    BLOCK_REPLY,
    TOOL_CALL,
    TOOL_RESULT,
    ERROR,
    SYSTEM
}

/** Max chars before collapsing */
private const val COLLAPSE_THRESHOLD = 300

// CoPaw 风格主色：浅灰侧栏 + 紫强调色
private val ChatPurple = Color(0xFF7C3AED)
private val ChatSidebarBg = Color(0xFFF3F4F6)
private val ChatSurface = Color(0xFFFFFFFF)
private val ChatDivider = Color(0xFFE5E7EB)

/** 主界面不展示的消息类型：工具调用与工具结果。 */
private val HiddenInMainTimelineKinds = setOf(
    ChatMessageKind.TOOL_CALL,
    ChatMessageKind.TOOL_RESULT
)
/** 需要聚合进“执行轨迹大卡片”的消息类型。 */
private val GroupedTimelineKinds = setOf(
    ChatMessageKind.THINKING,
    ChatMessageKind.BLOCK_REPLY,
    ChatMessageKind.ERROR
)

/** 对话列表渲染模型：普通消息 + 一轮执行轨迹卡片。 */
private sealed interface ConversationRenderItem {
    val key: String

    data class SingleMessage(
        val message: ChatMessage
    ) : ConversationRenderItem {
        override val key: String = "msg_${message.id}"
    }

    data class AgentRoundGroup(
        val roundIndex: Int,
        val steps: List<ChatMessage>
    ) : ConversationRenderItem {
        override val key: String = buildString {
            append("round_")
            append(roundIndex)
            append("_")
            append(steps.firstOrNull()?.id ?: "empty")
            append("_")
            append(steps.size)
        }
    }
}

/**
 * 把同一轮（由用户消息触发）的思考/中间回复/错误聚合成一个大卡片，帮助用户理解步骤关系。
 */
private fun buildConversationRenderItems(messages: List<ChatMessage>): List<ConversationRenderItem> {
    if (messages.isEmpty()) return emptyList()
    val result = mutableListOf<ConversationRenderItem>()
    var roundIndex = 0
    var groupedRoundIndex = 0
    val pendingSteps = mutableListOf<ChatMessage>()

    fun flushPendingSteps() {
        if (pendingSteps.isEmpty()) return
        result += ConversationRenderItem.AgentRoundGroup(
            roundIndex = groupedRoundIndex.coerceAtLeast(1),
            steps = pendingSteps.toList()
        )
        pendingSteps.clear()
    }

    messages.forEach { message ->
        if (message.isUser) {
            flushPendingSteps()
            roundIndex += 1
            result += ConversationRenderItem.SingleMessage(message)
            return@forEach
        }
        if (message.kind in GroupedTimelineKinds) {
            groupedRoundIndex = roundIndex
            pendingSteps += message
            return@forEach
        }
        flushPendingSteps()
        result += ConversationRenderItem.SingleMessage(message)
    }

    flushPendingSteps()
    return result
}

@Composable
fun ChatScreen(
    messages: List<ChatMessage>,
    onSendMessage: (String) -> Unit,
    modifier: Modifier = Modifier,
    isLoading: Boolean = false,
    memoryWindowInfo: String = "",
    runningTasks: List<RunningTaskStatus> = emptyList(),
    permissionStatusInfo: String = "",
    permissionStatusHealthy: Boolean = true,
    currentModelInfo: String = "",
    sessions: List<SessionManager.Session> = emptyList(),
    currentSession: SessionManager.Session? = null,
    onSessionChange: (String) -> Unit = {},
    onRunningTaskClick: (String) -> Unit = {},
    onNewSession: () -> Unit = {},
    onDeleteSession: (String) -> Unit = {},
    onStopAgent: (() -> Unit)? = null,
    /** 由上层（如 MainScreen）持有，避免切换底部导航后 ChatScreen 重进时丢失「语音/键盘」态。 */
    isVoiceMode: Boolean,
    onVoiceModeChange: (Boolean) -> Unit,
    isVoiceListening: Boolean = false,
    isVoiceProcessing: Boolean = false,
    onVoicePressStart: (() -> Unit)? = null,
    onVoicePressEnd: (() -> Unit)? = null
) {
    var inputText by remember { mutableStateOf("") }
    // 顶部会话下拉框展开态：用下拉替代左侧固定栏，给主对话更多空间。
    var sessionMenuExpanded by remember { mutableStateOf(false) }
    var pendingDeleteSession by remember { mutableStateOf<SessionManager.Session?>(null) }
    val sortedSessions = remember(sessions) { sessions.sortedByDescending { it.createdAt } }
    val currentSessionTitle = currentSession?.title?.takeIf { it.isNotBlank() } ?: "选择会话"
    // 仅用于主对话展示：隐藏工具调用/工具结果，避免界面被技术细节刷屏。
    val visibleMessages = remember(messages) {
        messages.filterNot { it.kind in HiddenInMainTimelineKinds }
    }
    // 将同轮 Agent 执行步骤聚合，减少刷屏并强化轮次关系。
    val renderItems = remember(visibleMessages) {
        buildConversationRenderItems(visibleMessages)
    }
    // 统一维护每个“执行轨迹卡片”的展开态，避免 LazyColumn 复用时局部 remember 状态错位。
    val traceCardExpandedStates = remember { mutableStateMapOf<String, Boolean>() }
    val listState = rememberLazyListState()

    // Auto-scroll to bottom
    var lastMessageCount by remember { mutableStateOf(0) }
    var lastSessionId by remember { mutableStateOf(currentSession?.id) }
    LaunchedEffect(visibleMessages.size, currentSession?.id) {
        if (visibleMessages.isNotEmpty()) {
            val sessionChanged = currentSession?.id != lastSessionId
            val isNewMessage = visibleMessages.size > lastMessageCount && !sessionChanged

            if (isNewMessage && lastMessageCount > 0) {
                listState.animateScrollToItem(visibleMessages.size - 1)
            } else {
                listState.scrollToItem(visibleMessages.size - 1)
            }

            lastMessageCount = visibleMessages.size
            lastSessionId = currentSession?.id
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(ChatSurface)
        ) {
            // 顶部：会话下拉 + 新对话/更新，替代左侧会话栏以释放主区空间。
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Chat,
                    contentDescription = null,
                    tint = ChatPurple,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))

                Box(modifier = Modifier.weight(1f)) {
                    OutlinedButton(
                        onClick = { sessionMenuExpanded = true },
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Text(
                            text = currentSessionTitle,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = TextStyle(fontSize = 14.sp)
                        )
                        Icon(
                            imageVector = Icons.Default.ExpandMore,
                            contentDescription = "选择会话",
                            tint = Color(0xFF6B7280)
                        )
                    }
                    DropdownMenu(
                        expanded = sessionMenuExpanded,
                        onDismissRequest = { sessionMenuExpanded = false }
                    ) {
                        sortedSessions.forEach { session ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(
                                            text = session.title,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = formatSessionTime(session.createdAt),
                                            style = TextStyle(
                                                fontSize = 11.sp,
                                                color = Color(0xFF9CA3AF)
                                            )
                                        )
                                    }
                                },
                                trailingIcon = {
                                    if (sessions.size > 1) {
                                        IconButton(
                                            onClick = {
                                                sessionMenuExpanded = false
                                                pendingDeleteSession = session
                                            }
                                        ) {
                                            Icon(
                                                imageVector = Icons.Filled.Delete,
                                                contentDescription = "删除会话",
                                                tint = Color(0xFFDC2626)
                                            )
                                        }
                                    }
                                },
                                onClick = {
                                    sessionMenuExpanded = false
                                    onSessionChange(session.id)
                                }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))
                FilledTonalButton(onClick = onNewSession) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("新对话", maxLines = 1)
                }
            }

            pendingDeleteSession?.let { session ->
                AlertDialog(
                    onDismissRequest = { pendingDeleteSession = null },
                    title = { Text("删除会话") },
                    text = { Text("确认删除会话「${session.title}」吗？删除后将不可恢复。") },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                onDeleteSession(session.id)
                                pendingDeleteSession = null
                            }
                        ) {
                            Text("删除", color = Color(0xFFDC2626))
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { pendingDeleteSession = null }) {
                            Text("取消")
                        }
                    }
                )
            }

            if (memoryWindowInfo.isNotBlank()) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xFFF5F3FF)
                ) {
                    Text(
                        text = memoryWindowInfo,
                        style = TextStyle(
                            fontSize = 11.sp,
                            color = Color(0xFF5B21B6),
                            fontWeight = FontWeight.Medium
                        ),
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            }

            if (runningTasks.isNotEmpty()) {
                // 全局执行中任务条：跨会话展示，并支持点击直接跳转到对应会话。
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 2.dp),
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xFFEFF6FF)
                ) {
                    Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                        Text(
                            text = "执行中任务（点击可跳转）",
                            style = TextStyle(
                                fontSize = 11.sp,
                                color = Color(0xFF1E40AF),
                                fontWeight = FontWeight.SemiBold
                            )
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        runningTasks.forEach { task ->
                            Text(
                                text = "• ${task.title}",
                                style = TextStyle(
                                    fontSize = 11.sp,
                                    color = Color(0xFF1D4ED8),
                                    fontWeight = FontWeight.Medium
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onRunningTaskClick(task.sessionId) }
                                    .padding(vertical = 2.dp)
                            )
                        }
                    }
                }
            }

            if (permissionStatusInfo.isNotBlank()) {
                // 主对话页实时权限状态条：放在会话记忆信息下方，方便随时观察。
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 2.dp),
                    shape = RoundedCornerShape(8.dp),
                    color = if (permissionStatusHealthy) Color(0xFFECFDF5) else Color(0xFFFEF2F2)
                ) {
                    Text(
                        text = permissionStatusInfo,
                        style = TextStyle(
                            fontSize = 11.sp,
                            color = if (permissionStatusHealthy) Color(0xFF047857) else Color(0xFFB91C1C),
                            fontWeight = FontWeight.Medium
                        ),
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            }

            if (currentModelInfo.isNotBlank()) {
                // 显示当前主模型，便于用户确认实际使用的 provider/model。
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 2.dp),
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xFFEFF6FF)
                ) {
                    Text(
                        text = currentModelInfo,
                        style = TextStyle(
                            fontSize = 11.sp,
                            color = Color(0xFF1E40AF),
                            fontWeight = FontWeight.Medium
                        ),
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            }

            // Message list
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                if (renderItems.isEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(text = "👋", style = TextStyle(fontSize = 48.sp))
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "开始聊天",
                            style = TextStyle(
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF111827)
                            )
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "向 AI 助手发送消息来控制手机",
                            style = TextStyle(fontSize = 14.sp, color = Color(0xFF6B7280))
                        )
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(renderItems, key = { it.key }) { item ->
                            when (item) {
                                is ConversationRenderItem.SingleMessage -> {
                                    MessageItem(message = item.message)
                                }
                                is ConversationRenderItem.AgentRoundGroup -> {
                                    val cardKey = item.key
                                    val expanded = traceCardExpandedStates[cardKey] ?: true
                                    AgentRoundTraceCard(
                                        roundIndex = item.roundIndex,
                                        steps = item.steps,
                                        expanded = expanded,
                                        onToggleExpanded = {
                                            traceCardExpandedStates[cardKey] = !expanded
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Divider(color = ChatDivider, thickness = 1.dp)

            // Message input box (语音/键盘 + 发送/停止按钮)
            MessageComposer(
                value = inputText,
                onValueChange = { inputText = it },
                onSend = {
                    if (inputText.isNotBlank()) {
                        onSendMessage(inputText)
                        inputText = ""
                    }
                },
                isAgentRunning = isLoading,
                onStop = onStopAgent,
                isVoiceListening = isVoiceListening,
                isVoiceProcessing = isVoiceProcessing,
                onVoicePressStart = onVoicePressStart,
                onVoicePressEnd = onVoicePressEnd,
                isVoiceMode = isVoiceMode,
                onVoiceModeChange = onVoiceModeChange,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

// ============================================================================
// 侧栏会话列表（CoPaw 风格）
// ============================================================================

// ============================================================================
// Message Item with Markdown + Collapse + Long-press Copy
// ============================================================================

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageItem(
    message: ChatMessage,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val parsedMedia = remember(message.content) { ChatMediaParser.extract(message.content) }
    val isLong = parsedMedia.cleanedText.length > COLLAPSE_THRESHOLD
    var expanded by remember { mutableStateOf(false) }
    var showCopyHint by remember { mutableStateOf(false) }

    val alignment = if (message.isUser) Alignment.End else Alignment.Start
    val (backgroundColor, textColor) = messageColors(message)
    val timelineLabel = messageTimelineLabel(message.kind)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = if (message.isUser) Arrangement.End else Arrangement.Start
    ) {
        // AI avatar (left)
        if (!message.isUser) {
            Avatar(
                text = "AI",
                backgroundColor = ChatPurple,
                modifier = Modifier.padding(end = 8.dp)
            )
        }

        // Message bubble
        Column(
            horizontalAlignment = alignment,
            modifier = Modifier.weight(1f, fill = false)
        ) {
            Surface(
                shape = RoundedCornerShape(
                    topStart = 18.dp,
                    topEnd = 18.dp,
                    bottomStart = if (message.isUser) 18.dp else 4.dp,
                    bottomEnd = if (message.isUser) 4.dp else 18.dp
                ),
                color = backgroundColor,
                shadowElevation = if (message.isUser) 0.dp else 1.dp,
                modifier = Modifier
                    .widthIn(max = 320.dp) // Wider bubbles
                    .animateContentSize()
            ) {
                Column(
                    modifier = Modifier
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                        .combinedClickable(
                            onClick = {},
                            onLongClick = {
                                copyToClipboard(context, message.content)
                                showCopyHint = true
                            }
                        )
                ) {
                    if (!message.isUser && timelineLabel != null) {
                        Text(
                            text = timelineLabel,
                            style = TextStyle(
                                color = Color(0xFF6B7280),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                    }

                    // Message content
                    val displayContent = if (isLong && !expanded) {
                        parsedMedia.cleanedText.take(COLLAPSE_THRESHOLD) + "..."
                    } else {
                        parsedMedia.cleanedText
                    }

                    if (displayContent.isNotBlank() || parsedMedia.mediaRefs.isEmpty()) {
                        if (message.isUser) {
                        // User messages: plain text
                            Text(
                                text = displayContent,
                                style = TextStyle(
                                    color = textColor,
                                    fontSize = 15.sp,
                                    lineHeight = 22.sp
                                )
                            )
                        } else {
                        // AI messages: render Markdown
                            Text(
                                text = parseMarkdown(displayContent, textColor),
                                style = TextStyle(
                                    color = textColor,
                                    fontSize = 15.sp,
                                    lineHeight = 22.sp
                                )
                            )
                        }
                    }

                    if (parsedMedia.mediaRefs.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        ChatMediaSection(
                            mediaRefs = parsedMedia.mediaRefs,
                            onMediaClick = { _ -> }
                        )
                    }

                    // Expand/Collapse toggle for long messages
                    if (isLong) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = if (expanded) "收起 ▲" else "展开 ▼",
                            style = TextStyle(
                                color = if (message.isUser) Color(0xFF6B21A8).copy(alpha = 0.85f)
                                else ChatPurple,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium
                            ),
                            modifier = Modifier.clickable { expanded = !expanded }
                        )
                    }

                    // Copy hint
                    if (showCopyHint) {
                        LaunchedEffect(showCopyHint) {
                            kotlinx.coroutines.delay(1500)
                            showCopyHint = false
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "✅ 已复制",
                            style = TextStyle(
                                color = textColor.copy(alpha = 0.6f),
                                fontSize = 11.sp
                            )
                        )
                    }

                    // Timestamp and status
                    Row(
                        modifier = Modifier
                            .align(Alignment.End)
                            .padding(top = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = formatTimestamp(message.timestamp),
                            style = TextStyle(
                                color = textColor.copy(alpha = 0.5f),
                                fontSize = 11.sp
                            )
                        )

                        if (message.isUser) {
                            Spacer(modifier = Modifier.width(4.dp))
                            StatusIndicator(status = message.status, color = textColor)
                        }
                    }
                }
            }
        }

        // User avatar (right)
        if (message.isUser) {
            Avatar(
                text = "You",
                backgroundColor = ChatPurple,
                modifier = Modifier.padding(start = 8.dp)
            )
        }
    }
}

private fun messageColors(message: ChatMessage): Pair<Color, Color> {
    if (message.isUser) {
        // 浅紫气泡 + 深色字，贴近参考图用户消息样式
        return Color(0xFFE9D5FF) to Color(0xFF1E1B4B)
    }
    return when (message.kind) {
        ChatMessageKind.TOOL_CALL -> Color(0xFFF3E8FF) to Color(0xFF4C1D95)
        ChatMessageKind.TOOL_RESULT -> Color(0xFFECFDF3) to Color(0xFF166534)
        ChatMessageKind.ERROR -> Color(0xFFFEE2E2) to Color(0xFF991B1B)
        ChatMessageKind.THINKING -> Color(0xFFF3F4F6) to Color(0xFF374151)
        ChatMessageKind.SYSTEM -> Color(0xFFEFF6FF) to Color(0xFF1D4ED8)
        else -> Color.White to Color(0xFF1A1A1A)
    }
}

private fun messageTimelineLabel(kind: ChatMessageKind): String? {
    return when (kind) {
        ChatMessageKind.USER -> null
        ChatMessageKind.ASSISTANT -> null
        ChatMessageKind.THINKING -> "思考过程"
        ChatMessageKind.BLOCK_REPLY -> "中间回复"
        ChatMessageKind.TOOL_CALL -> "工具调用"
        ChatMessageKind.TOOL_RESULT -> "工具结果"
        ChatMessageKind.ERROR -> "执行错误"
        ChatMessageKind.SYSTEM -> "系统消息"
    }
}

@Composable
private fun ChatMediaSection(
    mediaRefs: List<ChatMediaRef>,
    onMediaClick: (ChatMediaRef) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        mediaRefs.forEach { media ->
            when (media.type) {
                ChatMediaType.IMAGE -> ChatImageThumbnail(media = media, onClick = { onMediaClick(media) })
                ChatMediaType.VIDEO -> ChatVideoCard(media = media, onClick = { onMediaClick(media) })
            }
        }
    }
}

@Composable
private fun ChatImageThumbnail(
    media: ChatMediaRef,
    onClick: () -> Unit
) {
    val bitmap = remember(media.normalizedPath) {
        File(media.normalizedPath)
            .takeIf { it.exists() }
            ?.let { BitmapFactory.decodeFile(it.absolutePath) }
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = Color(0xFFF3F4F6)
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "聊天图片预览",
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 120.dp, max = 220.dp),
                contentScale = ContentScale.Crop
            )
        } else {
            Text(
                text = "图片不存在或无法读取\n${media.normalizedPath}",
                style = TextStyle(fontSize = 13.sp, color = Color(0xFF666666)),
                modifier = Modifier.padding(12.dp)
            )
        }
    }
}

@Composable
private fun ChatVideoCard(
    media: ChatMediaRef,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = Color(0xFFF3F4F6)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "视频预览",
                style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1A1A1A))
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = media.normalizedPath,
                style = TextStyle(fontSize = 12.sp, color = Color(0xFF666666))
            )
            Spacer(modifier = Modifier.height(6.dp))
                Text(
                text = "点击播放",
                style = TextStyle(fontSize = 13.sp, color = ChatPurple, fontWeight = FontWeight.Medium)
            )
        }
    }
}

// ============================================================================
// Markdown Parser (AnnotatedString based)
// ============================================================================

/**
 * Simple Markdown to AnnotatedString parser.
 *
 * Supports:
 * - ## Headings (H1-H3)
 * - **bold**
 * - *italic*
 * - `inline code`
 * - ```code blocks```
 * - - list items
 * - > blockquotes
 */
@Composable
fun parseMarkdown(text: String, textColor: Color): AnnotatedString {
    return buildAnnotatedString {
        val lines = text.split("\n")
        var inCodeBlock = false
        var codeBlockContent = StringBuilder()

        for ((idx, line) in lines.withIndex()) {
            if (idx > 0 && !inCodeBlock) append("\n")

            // Code block start/end
            if (line.trimStart().startsWith("```")) {
                if (inCodeBlock) {
                    // End code block
                    withStyle(SpanStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        color = textColor.copy(alpha = 0.9f),
                        background = textColor.copy(alpha = 0.08f)
                    )) {
                        append(codeBlockContent.toString())
                    }
                    codeBlockContent = StringBuilder()
                    inCodeBlock = false
                } else {
                    // Start code block
                    inCodeBlock = true
                }
                continue
            }

            if (inCodeBlock) {
                if (codeBlockContent.isNotEmpty()) codeBlockContent.append("\n")
                codeBlockContent.append(line)
                continue
            }

            // Headings
            when {
                line.startsWith("### ") -> {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = 16.sp)) {
                        appendInlineMarkdown(line.removePrefix("### "), textColor)
                    }
                }
                line.startsWith("## ") -> {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = 18.sp)) {
                        appendInlineMarkdown(line.removePrefix("## "), textColor)
                    }
                }
                line.startsWith("# ") -> {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = 20.sp)) {
                        appendInlineMarkdown(line.removePrefix("# "), textColor)
                    }
                }
                // Blockquote
                line.startsWith("> ") -> {
                    withStyle(SpanStyle(
                        color = textColor.copy(alpha = 0.7f),
                        fontStyle = FontStyle.Italic
                    )) {
                        append("│ ")
                        appendInlineMarkdown(line.removePrefix("> "), textColor.copy(alpha = 0.7f))
                    }
                }
                // List items
                line.trimStart().startsWith("- ") -> {
                    val indent = line.length - line.trimStart().length
                    append(" ".repeat(indent))
                    append("• ")
                    appendInlineMarkdown(line.trimStart().removePrefix("- "), textColor)
                }
                line.trimStart().matches(Regex("^\\d+\\.\\s.*")) -> {
                    appendInlineMarkdown(line, textColor)
                }
                // Regular paragraph
                else -> {
                    appendInlineMarkdown(line, textColor)
                }
            }
        }

        // Unclosed code block
        if (inCodeBlock && codeBlockContent.isNotEmpty()) {
            withStyle(SpanStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                color = textColor.copy(alpha = 0.9f),
                background = textColor.copy(alpha = 0.08f)
            )) {
                append(codeBlockContent.toString())
            }
        }
    }
}

/**
 * Parse inline markdown: **bold**, *italic*, `code`
 */
private fun AnnotatedString.Builder.appendInlineMarkdown(text: String, textColor: Color) {
    var i = 0
    while (i < text.length) {
        when {
            // Bold: **text**
            text.startsWith("**", i) -> {
                val end = text.indexOf("**", i + 2)
                if (end > 0) {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(text.substring(i + 2, end))
                    }
                    i = end + 2
                } else {
                    append(text[i])
                    i++
                }
            }
            // Inline code: `code`
            text[i] == '`' -> {
                val end = text.indexOf('`', i + 1)
                if (end > 0) {
                    withStyle(SpanStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        background = textColor.copy(alpha = 0.1f)
                    )) {
                        append(text.substring(i + 1, end))
                    }
                    i = end + 1
                } else {
                    append(text[i])
                    i++
                }
            }
            // Italic: *text* (but not **)
            text[i] == '*' && (i + 1 < text.length && text[i + 1] != '*') -> {
                val end = text.indexOf('*', i + 1)
                if (end > 0) {
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                        append(text.substring(i + 1, end))
                    }
                    i = end + 1
                } else {
                    append(text[i])
                    i++
                }
            }
            else -> {
                append(text[i])
                i++
            }
        }
    }
}

// ============================================================================
// Shared Components
// ============================================================================

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("message", text))
    Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
}

@Composable
fun Avatar(
    text: String,
    backgroundColor: Color,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(32.dp)
            .clip(CircleShape)
            .background(backgroundColor),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text.take(2).uppercase(),
            style = TextStyle(
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
        )
    }
}

@Composable
fun StatusIndicator(
    status: MessageStatus,
    color: Color
) {
    val iconText = when (status) {
        MessageStatus.SENDING -> "⏱"
        MessageStatus.SENT -> "✓"
        MessageStatus.ERROR -> "⚠"
    }
    Text(
        text = iconText,
        style = TextStyle(color = color.copy(alpha = 0.7f), fontSize = 10.sp)
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun MessageComposer(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
    isAgentRunning: Boolean = false,
    onStop: (() -> Unit)? = null,
    isVoiceListening: Boolean = false,
    isVoiceProcessing: Boolean = false,
    onVoicePressStart: (() -> Unit)? = null,
    onVoicePressEnd: (() -> Unit)? = null,
    isVoiceMode: Boolean = false,
    onVoiceModeChange: (Boolean) -> Unit = {}
) {
    val voicePressStart = onVoicePressStart
    val voicePressEnd = onVoicePressEnd
    val pressStartLatest = rememberUpdatedState(voicePressStart)
    val pressEndLatest = rememberUpdatedState(voicePressEnd)
    val canVoiceInput = voicePressStart != null && voicePressEnd != null
    val inputFocusRequester = remember { FocusRequester() }
    var pendingFocusRequest by remember { mutableStateOf(false) }

    LaunchedEffect(isVoiceMode, pendingFocusRequest) {
        if (!isVoiceMode && pendingFocusRequest) {
            inputFocusRequester.requestFocus()
            pendingFocusRequest = false
        }
    }

    Surface(
        modifier = modifier,
        color = Color.White,
        shadowElevation = 4.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            // 左侧语音/键盘切换按钮
            if (canVoiceInput) {
                IconButton(
                    modifier = Modifier.size(44.dp),
                    onClick = {
                        if (isVoiceMode) {
                            onVoiceModeChange(false)
                            pendingFocusRequest = true
                        } else {
                            onVoiceModeChange(true)
                        }
                    }
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(CircleShape)
                            .background(Color(0xFFE0E0E0)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (isVoiceMode) Icons.Default.Keyboard else Icons.Default.Mic,
                            contentDescription = if (isVoiceMode) "切换键盘输入" else "切换语音输入",
                            tint = Color(0xFF666666),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.width(6.dp))
            }

            // 中间区域：文本输入 / 按住说话
            Surface(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 44.dp, max = 120.dp),
                shape = RoundedCornerShape(24.dp),
                color = Color(0xFFF7F7F7),
                border = androidx.compose.foundation.BorderStroke(
                    width = 1.dp,
                    color = Color(0xFFE0E0E0)
                )
            ) {
                if (isVoiceMode && canVoiceInput) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                            .clip(RoundedCornerShape(20.dp))
                            .background(
                                when {
                                    isVoiceListening -> Color(0xFFFFEBEE)
                                    isVoiceProcessing -> Color(0xFFE8F0FE)
                                    else -> Color.White
                                }
                            )
                            .pointerInput(Unit) {
                                detectTapGestures(
                                    onPress = {
                                        if (isVoiceProcessing) return@detectTapGestures
                                        pressStartLatest.value?.invoke()
                                        try {
                                            tryAwaitRelease()
                                        } finally {
                                            pressEndLatest.value?.invoke()
                                        }
                                    }
                                )
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = when {
                                isVoiceListening -> "松开结束识别"
                                isVoiceProcessing -> "正在识别并等待回答..."
                                else -> "按住说话"
                            },
                            style = TextStyle(
                                fontSize = 15.sp,
                                color = when {
                                    isVoiceListening -> Color(0xFFFF3B30)
                                    isVoiceProcessing -> Color(0xFF1A73E8)
                                    else -> Color(0xFF666666)
                                },
                                fontWeight = FontWeight.Medium
                            )
                        )
                    }
                } else {
                    BasicTextField(
                        value = value,
                        onValueChange = onValueChange,
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(inputFocusRequester)
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                            .testTag("chat_input"),
                        textStyle = TextStyle(
                            fontSize = 15.sp,
                            color = Color.Black,
                            lineHeight = 20.sp
                        ),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(
                            onSend = {
                                if (value.isNotBlank()) onSend()
                            }
                        ),
                        decorationBox = { innerTextField ->
                            Box(
                                modifier = Modifier.fillMaxWidth(),
                                contentAlignment = Alignment.CenterStart
                            ) {
                                if (value.isEmpty()) {
                                    Text(
                                        text = "发送消息",
                                        style = TextStyle(fontSize = 15.sp, color = Color(0xFF999999))
                                    )
                                }
                                innerTextField()
                            }
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.width(6.dp))

            // 右侧：发送 / 插件 / 停止
            if (isAgentRunning && onStop != null) {
                Surface(
                    modifier = Modifier
                        .size(44.dp)
                        .testTag("stop_button"),
                    shape = CircleShape,
                    color = Color(0xFFFF3B30),
                    onClick = onStop
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Stop,
                            contentDescription = "停止",
                            tint = Color.White,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            } else if (!isVoiceMode && value.isNotBlank()) {
                Surface(
                    modifier = Modifier
                        .size(44.dp)
                        .testTag("send_button"),
                    shape = CircleShape,
                    color = ChatPurple,
                    onClick = onSend
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Send,
                            contentDescription = "发送",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }
}

private fun formatTimestamp(timestamp: Long): String {
    val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
    return sdf.format(Date(timestamp))
}

private fun formatSessionTime(timestamp: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - timestamp
    return when {
        diff < 60_000 -> "刚刚"
        diff < 3600_000 -> "${diff / 60_000} 分钟前"
        diff < 86400_000 -> "${diff / 3600_000} 小时前"
        else -> {
            val sdf = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
            sdf.format(Date(timestamp))
        }
    }
}

/**
 * 思考正文去掉模型写入的 [Step N] 前缀，避免与 UI 标题中的 Step 标签重复。
 */
private fun stripInlineStepPrefix(content: String): String {
    return content
        .replaceFirst(Regex("^\\s*\\[(?i:step)\\s*\\d+\\]\\s*"), "")
        .trimStart()
}

@Composable
private fun AgentRoundTraceCard(
    roundIndex: Int,
    steps: List<ChatMessage>,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    modifier: Modifier = Modifier
) {
    val latest = steps.lastOrNull()
    val summary = stripInlineStepPrefix(latest?.content.orEmpty())
        .lineSequence()
        .firstOrNull()
        ?.take(80)
        .orEmpty()

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 6.dp),
        shape = RoundedCornerShape(14.dp),
        color = Color(0xFFF8FAFC),
        border = BorderStroke(1.dp, Color(0xFFE2E8F0))
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = Color(0xFFEEF2FF)
                ) {
                    Text(
                        text = "第 ${roundIndex.coerceAtLeast(1)} 轮",
                        style = TextStyle(
                            fontSize = 11.sp,
                            color = Color(0xFF4338CA),
                            fontWeight = FontWeight.SemiBold
                        ),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Agent 执行轨迹 · ${steps.size} steps",
                    modifier = Modifier.weight(1f),
                    style = TextStyle(
                        fontSize = 13.sp,
                        color = Color(0xFF1F2937),
                        fontWeight = FontWeight.Bold
                    )
                )
                IconButton(onClick = onToggleExpanded, modifier = Modifier.size(24.dp)) {
                    Icon(
                        imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (expanded) "收起执行轨迹" else "展开执行轨迹",
                        tint = Color(0xFF94A3B8),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            if (!expanded) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "最近一步：${summary.ifBlank { "暂无摘要" }}",
                    style = TextStyle(
                        fontSize = 12.sp,
                        color = Color(0xFF64748B)
                    )
                )
            } else {
                Spacer(modifier = Modifier.height(10.dp))
                steps.forEachIndexed { index, step ->
                    AgentTraceStepRow(
                        stepIndex = index + 1,
                        message = step
                    )
                    if (index != steps.lastIndex) {
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun AgentTraceStepRow(
    stepIndex: Int,
    message: ChatMessage,
    modifier: Modifier = Modifier
) {
    val (_, textColor) = messageColors(message)
    val title = messageTimelineLabel(message.kind) ?: "步骤"
    val cleanedContent = remember(message.content) {
        stripInlineStepPrefix(message.content)
    }
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = Color.White,
        border = BorderStroke(1.dp, Color(0xFFE5E7EB))
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = Color(0xFFEEF2FF)
                ) {
                    Text(
                        text = "Step $stepIndex",
                        style = TextStyle(
                            fontSize = 10.sp,
                            color = Color(0xFF3730A3),
                            fontWeight = FontWeight.SemiBold
                        ),
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = title,
                    style = TextStyle(
                        fontSize = 12.sp,
                        color = Color(0xFF374151),
                        fontWeight = FontWeight.Medium
                    )
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = parseMarkdown(cleanedContent, textColor),
                style = TextStyle(
                    fontSize = 13.sp,
                    color = textColor,
                    lineHeight = 20.sp
                )
            )
        }
    }
}
