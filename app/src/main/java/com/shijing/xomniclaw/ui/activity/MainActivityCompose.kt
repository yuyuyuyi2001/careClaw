/**
 * X-OmniClaw Source Reference:
 * - ../xomniclaw/src/gateway/(all)
 *
 * X-OmniClaw adaptation: Android UI layer.
 */
package com.shijing.xomniclaw.ui.activity

import android.Manifest
import android.content.Intent
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.shijing.xomniclaw.ui.compose.CameraPreviewOverlay
import com.shijing.xomniclaw.ui.compose.ChatScreen
import com.shijing.xomniclaw.ui.compose.VisionFrameSource
import com.shijing.xomniclaw.ui.viewmodel.ChatViewModel
import com.shijing.xomniclaw.voice.VoiceRecorderManager
import com.shijing.xomniclaw.voice.VoiceRecorderManager.VoiceProcessingStage
import com.shijing.xomniclaw.voice.VoiceRoundDisclosureHint
import com.shijing.xomniclaw.voice.applyStableSpeechParams
import com.shijing.xomniclaw.voice.sanitizeForTts
import com.shijing.xomniclaw.util.AccessibilityAutoEnableHelper
import com.shijing.xomniclaw.util.ChatBroadcastReceiver
import com.shijing.xomniclaw.util.CrashBreadcrumbs
import com.shijing.xomniclaw.util.MediaProjectionHelper
import com.shijing.xomniclaw.ui.floatwindow.SessionFloatWindow
import com.tencent.mmkv.MMKV
import com.shijing.xomniclaw.util.MMKVKeys
import com.shijing.xomniclaw.R
import com.shijing.xomniclaw.BuildConfig
import com.shijing.xomniclaw.deeplink.RootShellExecutor
import com.shijing.xomniclaw.deeplink.ui.DeeplinkBookmarksTab
import androidx.lifecycle.lifecycleScope
import com.shijing.xomniclaw.agent.memory.MemoryManager
import com.shijing.xomniclaw.agent.memory.evolution.MemoryEvolutionAutomationManager
import com.shijing.xomniclaw.agent.memory.evolution.MemoryEvolutionStatus
import com.shijing.xomniclaw.agent.memory.evolution.MemoryEvolutionStatusStore
import com.shijing.xomniclaw.agent.memory.gallery.GalleryMemoryAutomationManager
import com.shijing.xomniclaw.agent.memory.gallery.GalleryMemorySettings
import com.shijing.xomniclaw.agent.memory.gallery.GalleryMemorySettingsStore
import com.shijing.xomniclaw.agent.memory.gallery.GalleryMemorySyncForegroundService
import com.shijing.xomniclaw.agent.memory.gallery.GalleryMemorySyncStatus
import com.shijing.xomniclaw.agent.memory.gallery.GalleryMemorySyncStatusStore
import com.shijing.xomniclaw.agent.memory.gallery.GalleryMemoryWorkflow
import com.shijing.xomniclaw.agent.tools.device.DeviceToolSettings
import com.shijing.xomniclaw.agent.tools.device.DeviceToolSettingsStore
import com.shijing.xomniclaw.agent.tools.device.DeviceYoloThresholdConfig
import com.shijing.xomniclaw.agent.tools.device.yolo.UiDetectionOcrEngine
import com.shijing.xomniclaw.config.ConfigLoader
import com.shijing.xomniclaw.core.MainEntryNew
import com.shijing.xomniclaw.scheduler.ScheduledTask
import com.shijing.xomniclaw.scheduler.ScheduledTaskEditDraft
import com.shijing.xomniclaw.scheduler.ScheduledTaskManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Check if S4Claw (observer extension) accessibility service is enabled
 *
 * Note: This method only checks system settings without blocking the thread
 */
suspend fun isS4ClawAccessibilityEnabled(context: Context): Boolean {
    return withContext(Dispatchers.IO) {
        try {
            // Check system settings
            val accessibilityEnabled = Settings.Secure.getInt(
                context.contentResolver,
                Settings.Secure.ACCESSIBILITY_ENABLED,
                0
            ) == 1

            if (!accessibilityEnabled) {
                Log.d("MainActivityCompose", "System accessibility not enabled")
                return@withContext false
            }

            val enabledServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return@withContext false

            // S4Claw accessibility service package name
            val s4clawServiceName = "com.shijing.xomniclaw/com.shijing.xomniclaw.accessibility.service.PhoneAccessibilityService"

            val isEnabled = enabledServices.contains(s4clawServiceName)
            Log.d("MainActivityCompose", "S4Claw accessibility service system status: $isEnabled")

            // If system shows enabled, verify service is actually available
            if (isEnabled) {
                try {
                    val ready = com.shijing.xomniclaw.accessibility.AccessibilityProxy.isServiceReadyAsync()
                    Log.d("MainActivityCompose", "S4Claw accessibility service availability: $ready")
                    return@withContext ready
                } catch (e: Exception) {
                    Log.w("MainActivityCompose", "Service check failed, using system settings result", e)
                    return@withContext isEnabled
                }
            }

            isEnabled
        } catch (e: Exception) {
            Log.e("MainActivityCompose", "Failed to check S4Claw accessibility service", e)
            false
        }
    }
}

/**
 * 获取当前系统版本下需要申请的相册读取权限。
 */
private fun requiredAlbumPermissionsForCurrentSdk(): List<String> {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        listOf(Manifest.permission.READ_MEDIA_IMAGES)
    } else {
        listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }
}

/**
 * 检查当前是否已经具备相册读取权限。
 */
private fun hasAlbumPermission(context: Context): Boolean {
    val requiredPermissions = requiredAlbumPermissionsForCurrentSdk()
    return requiredPermissions.all { permission ->
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }
}

/**
 * 统一读取关键权限快照，供状态页与主对话页复用。
 */
private suspend fun queryPermissionSnapshot(context: Context): PermissionSnapshot {
    return withContext(Dispatchers.IO) {
        val overlayVal = Settings.canDrawOverlays(context)

        val systemEnabled = try {
            val accessibilityOn = Settings.Secure.getInt(
                context.contentResolver,
                Settings.Secure.ACCESSIBILITY_ENABLED,
                0
            ) == 1
            if (!accessibilityOn) {
                false
            } else {
                val services = Settings.Secure.getString(
                    context.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                ) ?: ""
                services.contains("com.shijing.xomniclaw")
            }
        } catch (_: Exception) {
            false
        }

        val proxy = com.shijing.xomniclaw.accessibility.AccessibilityProxy
        val serviceReady = proxy.isServiceReadyAsync()
        val serviceAvailable =
            com.shijing.xomniclaw.accessibility.service.AccessibilityBinderService.serviceInstance != null
        val accessibilityVal = systemEnabled || serviceAvailable || serviceReady
        val screenCaptureVal = if (serviceAvailable) proxy.isMediaProjectionGranted() else false
        val albumVal = hasAlbumPermission(context)
        val allFilesVal = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            true
        }
        val cameraVal = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        val microphoneVal = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

        PermissionSnapshot(
            overlay = overlayVal,
            accessibility = accessibilityVal,
            screenCapture = screenCaptureVal,
            album = albumVal,
            allFilesAccess = allFilesVal,
            camera = cameraVal,
            microphone = microphoneVal,
            systemEnabled = systemEnabled,
            serviceAvailable = serviceAvailable,
            serviceReady = serviceReady
        )
    }
}

/**
 * MainActivity - Compose version
 *
 * Contains three tabs:
 * 1. Chat - AI assistant chat interface
 * 2. Status - System status cards
 * 3. Settings - 配置与系统相关项
 */
class MainActivityCompose : ComponentActivity() {

    /**
     * Workaround for Compose 1.4.x hover event crash on some MIUI devices.
     * See: https://issuetracker.google.com/issues/286991266
     */
    override fun dispatchGenericMotionEvent(ev: android.view.MotionEvent?): Boolean {
        return try {
            super.dispatchGenericMotionEvent(ev)
        } catch (e: IllegalStateException) {
            if (e.message?.contains("HOVER_EXIT") == true) {
                Log.w("MainActivityCompose", "Suppressed Compose hover crash: ${e.message}")
                true
            } else throw e
        }
    }

    private fun launchObserverPermissionActivity() {
        try {
            startActivity(Intent().apply {
                component = android.content.ComponentName(
                    "com.shijing.xomniclaw",
                    "com.shijing.xomniclaw.accessibility.PermissionActivity"
                )
            })
        } catch (e: Exception) {
            Log.w(TAG, "Observer PermissionActivity unavailable, fallback to local PermissionsActivity", e)
            startActivity(Intent(this, PermissionsActivity::class.java))
        }
    }

    companion object {
        private const val TAG = "MainActivityCompose"
        private const val REQUEST_MANAGE_EXTERNAL_STORAGE = 1001
    }

    private var chatBroadcastReceiver: ChatBroadcastReceiver? = null
    private var chatViewModel: ChatViewModel? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Check and request file management permission
        checkAndRequestStoragePermission()

        // Best-effort auto-enable accessibility on privileged/root devices.
        lifecycleScope.launch(Dispatchers.IO) {
            val result = AccessibilityAutoEnableHelper.tryEnable(this@MainActivityCompose)
            Log.i(TAG, "Auto-enable accessibility: success=${result.success}, method=${result.method}, msg=${result.message}")
        }

        // Check if model setup is needed (first run, no API key configured)
        if (ModelSetupActivity.isNeeded(this)) {
            Log.i(TAG, "🔧 首次启动，打开模型配置引导...")
            startActivity(Intent(this, ModelSetupActivity::class.java))
        }

        setContent {
            // Save ViewModel reference for BroadcastReceiver use
            val viewModel: ChatViewModel = viewModel()
            chatViewModel = viewModel

            MaterialTheme {
                MainScreen(
                    chatViewModel = viewModel,
                    onNavigateToPermissions = {
                        startActivity(Intent(this, PermissionsActivity::class.java))
                    },
                    onNavigateToSkills = {
                        startActivity(Intent(this, SkillsActivity::class.java))
                    },
                    onNavigateToConfig = {
                        Log.d("MainActivityCompose", "Clicked model configuration")
                        try {
                            startActivity(Intent(this, ModelConfigActivity::class.java))
                            Log.d("MainActivityCompose", "Successfully started ModelConfigActivity")
                        } catch (e: Exception) {
                            Log.e("MainActivityCompose", "Failed to start ConfigActivity", e)
                        }
                    }
                )
            }
        }

        // Register ADB test interface
        registerChatBroadcastReceiver()
    }

    override fun onResume() {
        super.onResume()
        // Notify float window manager when main activity is visible
        SessionFloatWindow.setMainActivityVisible(true, this)
        // 自动更新检查已关闭（对话页仍可通过菜单手动检查更新）
        // silentUpdateCheck()
    }

    /**
     * Check GitHub Releases for updates in background.
     * Only shows dialog if a new version is available.
     */
    fun silentUpdateCheck() {
        lifecycleScope.launch {
            try {
                val updater = com.shijing.xomniclaw.updater.AppUpdater(this@MainActivityCompose)
                val info = updater.checkForUpdate()
                if (info.hasUpdate && info.downloadUrl != null) {
                    // Show update dialog on main thread
                    val sizeStr = if (info.fileSize > 0) "%.1f MB".format(info.fileSize / 1024.0 / 1024.0) else ""
                    val message = buildString {
                        append("发现新版本 v${info.latestVersion}\n")
                        append("当前版本 v${info.currentVersion}\n")
                        if (sizeStr.isNotEmpty()) append("大小: $sizeStr\n")
                        if (!info.releaseNotes.isNullOrEmpty()) {
                            append("\n${info.releaseNotes.take(200)}")
                        }
                    }

                    androidx.appcompat.app.AlertDialog.Builder(this@MainActivityCompose)
                        .setTitle("发现新版本")
                        .setMessage(message)
                        .setPositiveButton("立即更新") { _, _ ->
                            lifecycleScope.launch {
                                val success = updater.downloadAndInstall(info.downloadUrl, info.latestVersion)
                                if (!success) {
                                    try {
                                        startActivity(android.content.Intent(
                                            android.content.Intent.ACTION_VIEW,
                                            android.net.Uri.parse(info.releaseUrl)
                                        ))
                                    } catch (_: Exception) {}
                                }
                            }
                        }
                        .setNegativeButton("稍后再说", null)
                        .show()
                }
            } catch (_: Exception) {
                // Silent — don't interrupt user
            }
        }
    }

    override fun onPause() {
        super.onPause()
        // Notify float window manager when main activity is not visible
        SessionFloatWindow.setMainActivityVisible(false, this)
    }

    override fun onDestroy() {
        UiDetectionOcrEngine.close()
        super.onDestroy()
        unregisterChatBroadcastReceiver()
    }

    /**
     * Register Chat Broadcast Receiver
     *
     * Note: Uses RECEIVER_EXPORTED to support ADB testing
     */
    private fun registerChatBroadcastReceiver() {
        chatBroadcastReceiver = ChatBroadcastReceiver { message ->
            Log.d(TAG, "📨 [BroadcastReceiver] Received message: $message")
            chatViewModel?.sendMessage(message)
        }

        val filter = ChatBroadcastReceiver.createIntentFilter()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Log.i(TAG, "✅ Register ChatBroadcastReceiver (EXPORTED, SDK >= 33)")
            registerReceiver(chatBroadcastReceiver, filter, RECEIVER_EXPORTED)
        } else {
            Log.i(TAG, "✅ Register ChatBroadcastReceiver (SDK < 33)")
            registerReceiver(chatBroadcastReceiver, filter)
        }
    }

    /**
     * Unregister Chat Broadcast Receiver
     */
    private fun unregisterChatBroadcastReceiver() {
        chatBroadcastReceiver?.let {
            try {
                unregisterReceiver(it)
            } catch (e: Exception) {
                // Ignore
            }
        }
    }


    /**
     * Check and request file management permission
     */
    private fun checkAndRequestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11+ requires MANAGE_EXTERNAL_STORAGE permission
            if (!Environment.isExternalStorageManager()) {
                // Debug version skips permission request page to avoid jumping to Settings causing Activity to go background affecting tests
                if (com.shijing.xomniclaw.BuildConfig.SKIP_PERMISSION_REQUEST) {
                    Log.w(TAG, "⚠️ DEBUG mode: File management permission not granted, but skipping request page")
                    Log.w(TAG, "   Config file read/write may fail, please grant permission manually")
                    return
                }

                Log.i(TAG, "File management permission not granted, requesting permission...")
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                    intent.data = Uri.parse("package:$packageName")
                    startActivityForResult(intent, REQUEST_MANAGE_EXTERNAL_STORAGE)
                } catch (e: Exception) {
                    Log.e(TAG, "Cannot open file management permission settings page", e)
                    // Fallback to general settings page
                    try {
                        val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                        startActivityForResult(intent, REQUEST_MANAGE_EXTERNAL_STORAGE)
                    } catch (e2: Exception) {
                        Log.e(TAG, "Cannot open file management permission settings", e2)
                    }
                }
            } else {
                Log.i(TAG, "✅ File management permission granted")
            }
        } else {
            // Android 10 and below use traditional permissions
            Log.i(TAG, "Android 10 and below, using traditional storage permissions")
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == REQUEST_MANAGE_EXTERNAL_STORAGE) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                if (Environment.isExternalStorageManager()) {
                    Log.i(TAG, "✅ File management permission granted")
                } else {
                    Log.w(TAG, "⚠️ File management permission not granted, config file reading may fail")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    chatViewModel: ChatViewModel,
    onNavigateToPermissions: () -> Unit,
    onNavigateToSkills: () -> Unit,
    onNavigateToConfig: () -> Unit
) {
    val context = LocalContext.current
    val mmkv = remember { MMKV.defaultMMKV() }
    var showFirstLaunchPermissionAlert by remember { mutableStateOf(false) }
    val albumPermissionGranted by remember {
        derivedStateOf { hasAlbumPermission(context) }
    }
    val albumPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { grantResult ->
        val granted = grantResult.values.all { it }
        if (granted) {
            Toast.makeText(context, "相册权限已授权", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, "相册权限未完全授权，请在权限页手动开启", Toast.LENGTH_LONG).show()
        }
    }

    LaunchedEffect(Unit) {
        val hasShown = mmkv.decodeBool(MMKVKeys.FIRST_LAUNCH_PERMISSION_ALERT_SHOWN.key, false)
        if (!hasShown) {
            // 首次启动强提醒：权限入口 + 相册权限显眼提示
            showFirstLaunchPermissionAlert = true
            mmkv.encode(MMKVKeys.FIRST_LAUNCH_PERMISSION_ALERT_SHOWN.key, true)
        }
    }

    var selectedTab by remember { mutableStateOf(MainTab.CHAT) }
    val currentSession by chatViewModel.currentSession.collectAsState()

    // 主对话输入区「语音 / 键盘」与底部 Tab 解耦：切走时 ChatScreen 会离组，态须挂在 MainScreen 才不失忆。
    var isChatVoiceInputMode by rememberSaveable { mutableStateOf(false) }

    // 语音与 TTS 与底部 Tab 解耦：切到「状态/设置」时勿销毁 ChatTab 内的唯一 VoiceRecorderManager，
    // 否则 in-flight 的 STT/VLM 会被 recordingScope.cancel，且收不到 COMPLETED 事件，对话会表现为「自动结束」。
    val voiceManager = remember { VoiceRecorderManager(context) }
    val tts = remember {
        lateinit var engine: android.speech.tts.TextToSpeech
        engine = android.speech.tts.TextToSpeech(context) { status ->
            if (status == android.speech.tts.TextToSpeech.SUCCESS) {
                engine.applyStableSpeechParams(java.util.Locale.CHINESE)
            } else {
                Log.w("MainScreen", "TTS init failed: $status")
            }
        }
        engine
    }
    LaunchedEffect(Unit) {
        try {
            val configLoader = com.shijing.xomniclaw.config.ConfigLoader(context)
            val config = configLoader.loadOmniClawConfig()
            val vision = config.vision ?: com.shijing.xomniclaw.config.VisionConfig()
            voiceManager.visionConfig = vision
        } catch (_: Exception) {
        }
    }
    var lastVoiceRecognitionText by remember { mutableStateOf("") }
    var lastVoiceRecognitionPressStartTs by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(voiceManager, chatViewModel, tts) {
        voiceManager.voiceResultEvents.collect { result ->
            val recognizedText = result.recognizedText.trim()
            val aiReply = result.aiReply.trim()
            val directAction = result.directAction
            val alreadyDisplayedRecognition =
                recognizedText.isNotBlank() &&
                    lastVoiceRecognitionText == recognizedText &&
                    lastVoiceRecognitionPressStartTs == result.pressStartTimestampMs
            when (result.stage) {
                VoiceProcessingStage.TRANSCRIPTION_READY -> {
                    if (!alreadyDisplayedRecognition) {
                        chatViewModel.addVoiceUserMessage(recognizedText)
                        lastVoiceRecognitionText = recognizedText
                        lastVoiceRecognitionPressStartTs = result.pressStartTimestampMs
                    }
                }
                VoiceProcessingStage.COMPLETED -> {
                    if (recognizedText.isNotBlank() && !alreadyDisplayedRecognition) {
                        chatViewModel.addVoiceUserMessage(recognizedText)
                        lastVoiceRecognitionText = recognizedText
                        lastVoiceRecognitionPressStartTs = result.pressStartTimestampMs
                    }
                    result.alignedFramePath
                        ?.trim()
                        ?.takeIf { it.isNotBlank() }
                        ?.let { alignedPath ->
                            chatViewModel.addVoiceAlignedFrameMessage(alignedPath)
                        }
                    if (aiReply.isNotBlank()) {
                        chatViewModel.addVoiceAssistantMessage(aiReply)
                        val spoken = sanitizeForTts(aiReply)
                        if (spoken.isNotBlank()) {
                            tts.speak(spoken, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "voice_reply")
                        }
                    }
                    val action = directAction
                    if (action != null) {
                        val actionType = action.optString("action", "")
                        Log.i("ChatTab", "Executing direct_action: $action")
                        try {
                            if (actionType != "agent_task" && recognizedText.isNotBlank()) {
                                Log.i("ChatTab", "Voice path unified to AgentLoop by STT text: ${recognizedText.take(100)}")
                                chatViewModel.sendMessage(
                                    content = recognizedText,
                                    returnToMainOnFinish = true,
                                    userBubbleAlreadyFromVoice = true
                                )
                            } else {
                                when (actionType) {
                                    "agent_task" -> {
                                        val task = action.optString("task", "")
                                        if (task.isNotBlank()) {
                                            Log.i("ChatTab", "Delegating to AgentLoop: ${task.take(100)}")
                                            chatViewModel.sendMessage(
                                                content = task,
                                                returnToMainOnFinish = true,
                                                userBubbleAlreadyFromVoice = true
                                            )
                                        } else if (recognizedText.isNotBlank()) {
                                            Log.w("ChatTab", "agent_task empty, fallback to recognizedText")
                                            chatViewModel.sendMessage(
                                                content = recognizedText,
                                                returnToMainOnFinish = true,
                                                userBubbleAlreadyFromVoice = true
                                            )
                                        }
                                    }
                                    else -> {
                                        if (recognizedText.isNotBlank()) {
                                            Log.w("ChatTab", "Unknown direct_action, fallback to AgentLoop by STT text")
                                            chatViewModel.sendMessage(
                                                content = recognizedText,
                                                returnToMainOnFinish = true,
                                                userBubbleAlreadyFromVoice = true
                                            )
                                        } else {
                                            Log.w("ChatTab", "Unknown direct_action type with empty STT: $actionType")
                                        }
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            Log.e("ChatTab", "Failed to execute direct_action", e)
                        }
                    }
                    if (aiReply.isNotBlank() || directAction != null) {
                        voiceManager.consumeReply()
                    }
                }
                VoiceProcessingStage.FAILED -> {
                }
            }
        }
    }
    val voiceError by voiceManager.error.collectAsState()
    LaunchedEffect(voiceError) {
        voiceError?.let {
            android.widget.Toast.makeText(context, "语音识别: $it", android.widget.Toast.LENGTH_SHORT).show()
        }
    }
    DisposableEffect(voiceManager, tts) {
        onDispose {
            try {
                tts.shutdown()
            } catch (_: Exception) {
            }
            voiceManager.destroy()
        }
    }
    val rootAvailable by produceState(initialValue = false) {
        value = withContext(Dispatchers.IO) {
            RootShellExecutor.hasRootAccess(forceRefresh = true)
        }
    }
    val availableTabs = remember(rootAvailable) {
        MainTab.values().filter { tab ->
            tab != MainTab.DEEPLINK || rootAvailable
        }
    }

    LaunchedEffect(availableTabs) {
        if (selectedTab !in availableTabs) {
            selectedTab = MainTab.CHAT
        }
    }

    if (showFirstLaunchPermissionAlert) {
        AlertDialog(
            onDismissRequest = {
                // 首次提醒保持显眼，不允许点遮罩直接关闭。
            },
            title = { Text("首次使用请先开启权限") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("为了保证功能正常，请先完成以下授权：")
                    Text("1) 无障碍与关键系统权限（进入权限页开启）")
                    Text(
                        text = "2) 相册读取权限（用于图片/相册相关能力）${if (albumPermissionGranted) "：已授权" else "：未授权"}"
                    )
                    Text("该提示仅首次展示一次。")
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val permissions = requiredAlbumPermissionsForCurrentSdk()
                        if (permissions.isNotEmpty()) {
                            albumPermissionLauncher.launch(permissions.toTypedArray())
                        }
                        onNavigateToPermissions()
                        showFirstLaunchPermissionAlert = false
                    }
                ) {
                    Text("去开启权限")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showFirstLaunchPermissionAlert = false
                    }
                ) {
                    Text("稍后处理")
                }
            }
        )
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                availableTabs.forEach { tab ->
                    NavigationBarItem(
                        icon = { Icon(tab.icon, contentDescription = tab.title) },
                        label = { Text(tab.title) },
                        selected = selectedTab == tab,
                        onClick = { selectedTab = tab }
                    )
                }
            }
        }
    ) { paddingValues ->
        Box(modifier = Modifier.padding(paddingValues)) {
            when (selectedTab) {
                MainTab.CHAT -> ChatTab(
                    chatViewModel = chatViewModel,
                    voiceManager = voiceManager,
                    tts = tts,
                    isVoiceInputMode = isChatVoiceInputMode,
                    onVoiceInputModeChange = { isChatVoiceInputMode = it }
                )
                MainTab.STATUS -> StatusTab(
                    onNavigateToPermissions = onNavigateToPermissions,
                    onNavigateToSkills = onNavigateToSkills,
                    currentSessionId = currentSession.id
                )
                MainTab.SETTINGS -> SettingsTab(onNavigateToConfig)
                MainTab.DEEPLINK -> DeeplinkBookmarksTab()
            }
        }
    }
}

enum class MainTab(val title: String, val icon: ImageVector) {
    CHAT("对话", Icons.Default.Chat),
    STATUS("状态", Icons.Default.Dashboard),
    SETTINGS("设置", Icons.Default.Settings),
    DEEPLINK("收藏", Icons.Default.Bookmarks)
}

@Composable
fun ChatTab(
    chatViewModel: ChatViewModel,
    voiceManager: VoiceRecorderManager,
    tts: android.speech.tts.TextToSpeech,
    isVoiceInputMode: Boolean,
    onVoiceInputModeChange: (Boolean) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current

    val messages by chatViewModel.messages.collectAsState()
    val isLoading by chatViewModel.isLoading.collectAsState()
    val memoryWindowInfo by chatViewModel.memoryWindowInfo.collectAsState()
    val runningTasks by chatViewModel.runningTasks.collectAsState()
    var permissionStatusInfo by remember { mutableStateOf("权限状态：检查中...") }
    var permissionStatusHealthy by remember { mutableStateOf(false) }
    var currentModelInfo by remember { mutableStateOf("当前模型：读取中...") }
    val sessions by chatViewModel.sessions.collectAsState()
    val currentSession by chatViewModel.currentSession.collectAsState()
    val companionUiState by com.shijing.xomniclaw.voice.ScreenCompanionController.uiState.collectAsState()
    val behaviorRecordingState by com.shijing.xomniclaw.behavior.BehaviorRecordingController.uiState.collectAsState()
    val application = context.applicationContext as? android.app.Application

    suspend fun refreshChatPermissionStatus() {
        try {
            val snapshot = queryPermissionSnapshot(context)
            val statusList = listOf(
                "无障碍" to snapshot.accessibility,
                "悬浮窗" to snapshot.overlay,
                "录屏" to snapshot.screenCapture,
                "相册" to snapshot.album,
                "全部文件" to snapshot.allFilesAccess,
                "摄像头" to snapshot.camera,
                "麦克风" to snapshot.microphone
            )
            val grantedCount = statusList.count { it.second }
            val missing = statusList.filterNot { it.second }.map { it.first }
            val allGranted = missing.isEmpty()
            permissionStatusHealthy = allGranted
            permissionStatusInfo = if (allGranted) {
                "权限状态：全部已授权（$grantedCount/7）"
            } else {
                "权限状态：未全部授权（$grantedCount/7）｜缺失：${missing.joinToString("、")}"
            }
        } catch (e: Exception) {
            permissionStatusHealthy = false
            permissionStatusInfo = "权限状态：检查失败"
            Log.e("ChatTab", "refreshChatPermissionStatus failed", e)
        }
    }

    suspend fun refreshCurrentModelInfo() {
        try {
            val config = ConfigLoader(context).loadOmniClawConfig()
            val providers = config.resolveProviders()
            val defaultRef = config.resolveDefaultModel().trim()
            if (defaultRef.isBlank()) {
                currentModelInfo = "当前模型：未配置"
                return
            }

            val providerName = defaultRef.substringBefore("/", missingDelimiterValue = "")
            val modelId = defaultRef.substringAfter("/", missingDelimiterValue = defaultRef)
            val modelName = providers[providerName]
                ?.models
                ?.firstOrNull { it.id == modelId }
                ?.name
                ?.trim()
                .orEmpty()
            val agentDisplay = if (modelName.isBlank() || modelName == modelId) modelId else modelName
            val sttModelId = providers["stt"]?.models?.firstOrNull()?.id.orEmpty()
            val vlmModelId = providers["vlm"]?.models?.firstOrNull()?.id.orEmpty()
            val sttDisplay = if (sttModelId.isBlank()) "未配置" else "stt/$sttModelId"
            val vlmDisplay = if (vlmModelId.isBlank()) "跟随 Agent" else "vlm/$vlmModelId"
            currentModelInfo = "当前模型：Agent $agentDisplay｜STT $sttDisplay｜VLM $vlmDisplay"
        } catch (e: Exception) {
            currentModelInfo = "当前模型：读取失败"
            Log.e("ChatTab", "refreshCurrentModelInfo failed", e)
        }
    }

    // 主对话页定时刷新权限状态，保证显示是实时的。
    LaunchedEffect(Unit) {
        refreshChatPermissionStatus()
        refreshCurrentModelInfo()
        while (isActive) {
            delay(2000)
            refreshChatPermissionStatus()
        }
    }

    // 从系统权限页返回主对话时立即刷新，避免等待下一次轮询。
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                    // 前台恢复时先校准一次 Agent 运行态，防止状态栏误判“无任务”。
                    chatViewModel.refreshRunningTaskStatusOnResume()
                    refreshChatPermissionStatus()
                    // 模型配置通常在设置页修改，回到对话页时立即刷新展示。
                    refreshCurrentModelInfo()
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // ===== 摄像头 / 屏幕 推流 =====
    var showCameraPreview by remember { mutableStateOf(false) }
    var visionFrameSource by remember { mutableStateOf(VisionFrameSource.CAMERA_BACK) }
    var showVisionSourcePicker by remember { mutableStateOf(false) }
    var currentVisionConfig by remember {
        mutableStateOf(com.shijing.xomniclaw.config.VisionConfig())
    }

    val framePusher = remember { com.shijing.xomniclaw.vision.CameraFramePusher(context) }
    val screenSampler = remember { com.shijing.xomniclaw.vision.ScreenFrameSampler() }

    // 从配置读取 vision（帧率画质等；STT/VLM 由 MainScreen 注入的 voiceManager 在顶层加载）
    LaunchedEffect(Unit) {
        try {
            val configLoader = com.shijing.xomniclaw.config.ConfigLoader(context)
            val config = configLoader.loadOmniClawConfig()
            val vision = config.vision ?: com.shijing.xomniclaw.config.VisionConfig()
            currentVisionConfig = vision
            framePusher.fps = vision.fps
            framePusher.jpegQuality = vision.quality
            screenSampler.fps = vision.fps
            screenSampler.jpegQuality = vision.quality
        } catch (_: Exception) {}
    }

    val projectionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        CrashBreadcrumbs.mark(
            stage = "ui.projection.result_callback",
            detail = "resultCode=${result.resultCode}, hasData=${result.data != null}"
        )
        MediaProjectionHelper.applyScreenCaptureResult(context, result.resultCode, result.data)
        if (MediaProjectionHelper.isMediaProjectionGranted()) {
            val app = application
            if (app != null) {
                CrashBreadcrumbs.mark(
                    stage = "ui.projection.result_callback",
                    detail = "projection_granted_enter_companion, sessionId=${currentSession.id}"
                )
                val entered = com.shijing.xomniclaw.voice.ScreenCompanionController.enterCompanionMode(
                    application = app,
                    sessionId = currentSession.id,
                    visionConfig = currentVisionConfig
                )
                val message = if (entered) "已进入屏内替身模式" else "进入替身模式失败"
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, "应用上下文异常，无法进入替身模式", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(context, "需要录屏权限以采集屏幕画面", Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(currentSession.id, companionUiState.isActive) {
        if (companionUiState.isActive) {
            com.shijing.xomniclaw.voice.ScreenCompanionController.updateSessionId(currentSession.id)
        }
    }

    // 摄像头权限请求
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            showCameraPreview = true
        } else {
            Toast.makeText(context, "需要摄像头权限", Toast.LENGTH_SHORT).show()
        }
    }
    // 统一摄像头入口，具体前后切换交给预览层中的翻转按钮。
    val openCameraPreview = {
        showVisionSourcePicker = false
        if (
            visionFrameSource != VisionFrameSource.CAMERA_BACK &&
            visionFrameSource != VisionFrameSource.CAMERA_FRONT
        ) {
            visionFrameSource = VisionFrameSource.CAMERA_BACK
        }
        val hasCameraPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        if (hasCameraPermission) {
            showCameraPreview = true
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }
    val isVoiceListening by voiceManager.isListening.collectAsState()
    val isVoiceProcessing by voiceManager.isProcessing.collectAsState()
    // 录音开始时读取「是否在全屏视觉叠加层」，避免重组导致按键瞬间状态错位。
    val updatedShowCameraPreview = rememberUpdatedState(showCameraPreview)

    // 录音权限请求
    val audioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            try {
                tts.stop()
            } catch (_: Exception) {}
            // 首次授权后多从主界面入口开始录音，按非叠加层处理；叠加层用户会先关授权弹窗再按键。
            VoiceRoundDisclosureHint.visionOverlayActiveForNextRecording = updatedShowCameraPreview.value
            voiceManager.startListening()
        } else {
            android.widget.Toast.makeText(context, "需要录音权限", android.widget.Toast.LENGTH_SHORT).show()
        }
    }
    // 必须稳定引用：否则 pointerInput 会因 key 变化在重组时取消，导致「松手不 stop」、录音一直开着。
    val onVoicePressStart = remember(voiceManager, context, audioPermissionLauncher, tts, updatedShowCameraPreview) {
        {
            try {
                tts.stop()
            } catch (_: Exception) {}
            VoiceRoundDisclosureHint.visionOverlayActiveForNextRecording = updatedShowCameraPreview.value
            val hasPerm = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
            if (hasPerm) {
                voiceManager.startListening()
            } else {
                audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }
    val onVoicePressEnd = remember(voiceManager) {
        { voiceManager.stopListening() }
    }

    // 仅释放摄像头/截屏流；语音由 MainScreen 在离开主界面时统一 destroy
    DisposableEffect(Unit) {
        onDispose {
            framePusher.stop()
            screenSampler.stop()
        }
    }

    if (showVisionSourcePicker) {
        AlertDialog(
            onDismissRequest = { showVisionSourcePicker = false },
            title = { Text("选择视觉输入") },
            text = {
                Column {
                    TextButton(onClick = {
                        openCameraPreview()
                    }) { Text("摄像头") }
                    TextButton(onClick = {
                        showVisionSourcePicker = false
                        if (MediaProjectionHelper.isMediaProjectionGranted()) {
                            val app = application
                            if (app != null) {
                                CrashBreadcrumbs.mark(
                                    stage = "ui.companion.enter_direct",
                                    detail = "projection_already_granted, sessionId=${currentSession.id}"
                                )
                                val entered = com.shijing.xomniclaw.voice.ScreenCompanionController.enterCompanionMode(
                                    application = app,
                                    sessionId = currentSession.id,
                                    visionConfig = currentVisionConfig
                                )
                                val message = if (entered) "已进入屏内替身模式" else "进入替身模式失败"
                                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, "应用上下文异常，无法进入替身模式", Toast.LENGTH_SHORT).show()
                            }
                        } else {
                            CrashBreadcrumbs.mark(
                                stage = "ui.companion.request_projection",
                                detail = "sessionId=${currentSession.id}, visionFps=${currentVisionConfig.fps}"
                            )
                            MediaProjectionHelper.startForegroundForMediaProjection(context)
                            projectionLauncher.launch(MediaProjectionHelper.createScreenCaptureIntent(context))
                        }
                    }) { Text("屏幕画面（截屏流）") }
                    TextButton(onClick = {
                        showVisionSourcePicker = false
                        val app = application
                        val serviceReady = com.shijing.xomniclaw.accessibility.service.AccessibilityBinderService.serviceInstance != null
                        if (app == null) {
                            Toast.makeText(context, "应用上下文异常，无法开始轨迹录制", Toast.LENGTH_SHORT).show()
                        } else if (!serviceReady) {
                            Toast.makeText(context, "需要先启用无障碍服务", Toast.LENGTH_SHORT).show()
                        } else {
                            val started = com.shijing.xomniclaw.behavior.BehaviorRecordingController.start(app)
                            val message = if (started) "已开始轨迹录制" else "开始轨迹录制失败"
                            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                        }
                    }) { Text("轨迹录制（支持 Deeplink 收藏）") }
                }
            },
            confirmButton = {
                TextButton(onClick = { showVisionSourcePicker = false }) { Text("取消") }
            }
        )
    }

    ChatScreen(
        messages = messages,
        onSendMessage = { message ->
            chatViewModel.sendMessage(message)
        },
        isLoading = isLoading,
        memoryWindowInfo = memoryWindowInfo,
        runningTasks = runningTasks,
        permissionStatusInfo = permissionStatusInfo,
        permissionStatusHealthy = permissionStatusHealthy,
        currentModelInfo = currentModelInfo,
        onStopAgent = { chatViewModel.stopAgent() },
        sessions = sessions,
        currentSession = currentSession,
        onSessionChange = { sessionId ->
            chatViewModel.switchSession(sessionId)
        },
        onRunningTaskClick = { sessionId ->
            chatViewModel.switchSession(sessionId)
        },
        isVoiceMode = isVoiceInputMode,
        onVoiceModeChange = onVoiceInputModeChange,
        onNewSession = {
            chatViewModel.createNewSession()
        },
        onDeleteSession = { sessionId ->
            chatViewModel.deleteSession(sessionId)
        },
        onCheckUpdate = {
            val activity = context as? MainActivityCompose
            activity?.let {
                android.widget.Toast.makeText(it, "正在检查更新...", android.widget.Toast.LENGTH_SHORT).show()
                it.lifecycleScope.launch {
                    try {
                        val updater = com.shijing.xomniclaw.updater.AppUpdater(it)
                        val info = updater.checkForUpdate()
                        if (info.hasUpdate) {
                            it.silentUpdateCheck()
                        } else {
                            android.widget.Toast.makeText(it, "已是最新版本 v${info.currentVersion}", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    } catch (e: Exception) {
                        android.widget.Toast.makeText(it, "检查更新失败", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            }
        },
        onCameraToggle = {
            if (showCameraPreview) {
                showCameraPreview = false
            } else if (companionUiState.isActive) {
                com.shijing.xomniclaw.voice.ScreenCompanionController.exitCompanionMode(stopAgent = true)
            } else if (behaviorRecordingState.isRecording) {
                com.shijing.xomniclaw.behavior.BehaviorRecordingController.stop()
            } else {
                showVisionSourcePicker = true
            }
        },
        isVoiceListening = isVoiceListening,
        isVoiceProcessing = isVoiceProcessing,
        onVoicePressStart = onVoicePressStart,
        onVoicePressEnd = onVoicePressEnd,
        showCameraPreview = showCameraPreview,
        cameraPreviewContent = {
            CameraPreviewOverlay(
                framePusher = framePusher,
                screenSampler = screenSampler,
                source = visionFrameSource,
                onClose = { showCameraPreview = false },
                onSwitchCamera = {
                    visionFrameSource = when (visionFrameSource) {
                        VisionFrameSource.CAMERA_BACK -> VisionFrameSource.CAMERA_FRONT
                        VisionFrameSource.CAMERA_FRONT -> VisionFrameSource.CAMERA_BACK
                        VisionFrameSource.SCREEN_CAPTURE -> VisionFrameSource.CAMERA_BACK
                    }
                },
                isVoiceListening = isVoiceListening,
                onVoicePressStart = onVoicePressStart,
                onVoicePressEnd = onVoicePressEnd
            )
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatusTab(
    onNavigateToPermissions: () -> Unit,
    onNavigateToSkills: () -> Unit = {},
    currentSessionId: String? = null
) {
    val context = LocalContext.current
    val tokenUsageStatus by MainEntryNew.tokenUsageStatus.collectAsState()
    val gatewayRunning = remember { mutableStateOf(false) }
    val skillsCount = remember { mutableStateOf(0) }
    val scheduledTasks = remember { mutableStateOf<List<ScheduledTask>>(emptyList()) }
    val galleryMemorySettings = remember {
        mutableStateOf(
            GalleryMemorySettingsState(
                featureEnabled = false,
                profileLoadingEnabled = true,
                scanIntervalMinutes = 24 * 60,
                manualSyncMaxImages = 100,
                automationTaskSummary = "未启用"
            )
        )
    }
    val memorySnapshot = remember {
        mutableStateOf(
            MemoryStatusSnapshot(
                longTermMemoryExists = false,
                longTermMemoryLength = 0,
                imageMemoriesExists = false,
                imageMemoriesLength = 0,
                userProfileExists = false,
                userProfileLength = 0,
                knowledgeFiles = emptyList(),
                dailyLogs = emptyList(),
                evolutionStatus = MemoryEvolutionStatus(
                    lastRunAtMs = 0L,
                    processedEvents = 0,
                    acceptedCandidates = 0,
                    globalMemoryChars = 0,
                    userProfileChars = 0,
                    pendingEvents = 0,
                    lastMessage = "尚未运行"
                )
            )
        )
    }
    val loadErrorMessage = remember { mutableStateOf<String?>(null) }
    val editingTask = remember { mutableStateOf<ScheduledTaskEditorState?>(null) }
    val editErrorMessage = remember { mutableStateOf<String?>(null) }
    val saveInProgress = remember { mutableStateOf(false) }
    val taskActionInProgressId = remember { mutableStateOf<String?>(null) }
    val pendingDeleteTask = remember { mutableStateOf<ScheduledTask?>(null) }
    val memoryDetailState = remember { mutableStateOf<MemoryDetailState?>(null) }
    val memoryDetailLoading = remember { mutableStateOf(false) }
    val manualGallerySyncInProgress = remember { mutableStateOf(false) }
    val manualGallerySyncMessage = remember { mutableStateOf<String?>(null) }
    val memoryMaintenanceMessage = remember { mutableStateOf<String?>(null) }
    val galleryMemorySyncStatus = remember { mutableStateOf(GalleryMemorySyncStatus()) }
    // 开启「相册记忆与画像」总开关时给出一次明确授权说明。
    val showGalleryMemoryEnableHintDialog = remember { mutableStateOf(false) }
    val taskSearchQuery = remember { mutableStateOf("") }
    val taskSortOption = remember { mutableStateOf(ScheduledTaskSortOption.NEXT_TRIGGER_ASC) }
    val coroutineScope = rememberCoroutineScope()

    suspend fun refreshStatus() {
        loadErrorMessage.value = null

        try {
            val gatewayStatus = withContext(Dispatchers.IO) {
                try {
                java.net.Socket().use { socket ->
                    socket.connect(java.net.InetSocketAddress("127.0.0.1", 8765), 100)
                    true
                }
                } catch (_: Exception) {
                    false
            }
            }
            gatewayRunning.value = gatewayStatus
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            gatewayRunning.value = false
            Log.e("StatusTab", "Failed to refresh gateway status", e)
    }

        try {
            val loader = com.shijing.xomniclaw.agent.skills.SkillsLoader(context)
            val stats = loader.getStatistics()
            skillsCount.value = stats.totalSkills
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e("StatusTab", "Failed to get Skills count", e)
            skillsCount.value = 0
        }

        try {
            val taskManager = ScheduledTaskManager(context)
            scheduledTasks.value = withContext(Dispatchers.IO) {
                // 状态页刷新时自修复托管任务，避免升级或任务文件被清理后列表里看不到“全局记忆进化”。
                MemoryEvolutionAutomationManager(context).ensureDefaultTask()
                taskManager.listTasks()
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e("StatusTab", "Failed to load scheduled tasks", e)
            loadErrorMessage.value = "读取定时任务失败：${e.message}"
        }

        try {
            val settingsStore = GalleryMemorySettingsStore()
            val settings = settingsStore.load()
            val automationTask = GalleryMemoryAutomationManager(context, settingsStore)
                .resolveManagedTask(settings.automationTaskId)
            galleryMemorySettings.value = GalleryMemorySettingsState(
                featureEnabled = settings.featureEnabled,
                profileLoadingEnabled = settings.profileLoadingEnabled,
                scanIntervalMinutes = settings.scanIntervalMinutes,
                manualSyncMaxImages = settings.manualSyncMaxImages,
                automationTaskSummary = if (settings.featureEnabled) {
                    automationTask?.let {
                        "已启用，每隔 ${it.intervalMinutes ?: settings.scanIntervalMinutes} 分钟扫描一次"
                    } ?: "已启用，等待后台自动任务创建"
                } else {
                    "未启用"
                }
            )
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e("StatusTab", "Failed to load gallery memory settings", e)
            if (loadErrorMessage.value == null) {
                loadErrorMessage.value = "读取相册记忆设置失败：${e.message}"
            }
        }

        try {
            galleryMemorySyncStatus.value = GalleryMemorySyncStatusStore().load()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e("StatusTab", "Failed to load gallery memory sync status", e)
        }

        try {
            val manager = MemoryManager("/sdcard/.xomniclaw/workspace", context)
            val longTermMemory = manager.readMemory()
            val imageMemories = manager.readNamedMemoryFile("IMAGE-MEMORY.md")
            val userProfile = manager.readNamedMemoryFile("USER-PROFILE.md")
            val memoryFiles = manager.listMemoryFiles()
            val logFiles = manager.listLogs()
            val evolutionStatus = MemoryEvolutionStatusStore().load()
            memorySnapshot.value = MemoryStatusSnapshot(
                longTermMemoryExists = longTermMemory.isNotBlank(),
                longTermMemoryLength = longTermMemory.length,
                imageMemoriesExists = imageMemories.isNotBlank(),
                imageMemoriesLength = imageMemories.length,
                userProfileExists = userProfile.isNotBlank(),
                userProfileLength = userProfile.length,
                knowledgeFiles = memoryFiles.map { File(it).name }
                    .filter { it !in setOf("MEMORY.md", "IMAGE-MEMORY.md", "USER-PROFILE.md") },
                dailyLogs = logFiles,
                evolutionStatus = evolutionStatus
            )
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e("StatusTab", "Failed to load memory status", e)
            if (loadErrorMessage.value == null) {
                loadErrorMessage.value = "读取 Memory 失败：${e.message}"
            }
        }
    }

    suspend fun openMemoryDetail(title: String, loader: suspend (MemoryManager) -> String) {
        memoryDetailLoading.value = true
        try {
            val manager = MemoryManager("/sdcard/.xomniclaw/workspace", context)
            val content = withContext(Dispatchers.IO) { loader(manager) }
            memoryDetailState.value = MemoryDetailState(
                title = title,
                content = if (content.isNotBlank()) content else "暂无内容"
            )
        } catch (e: Exception) {
            Toast.makeText(context, "读取 Memory 内容失败：${e.message}", Toast.LENGTH_SHORT).show()
        } finally {
            memoryDetailLoading.value = false
        }
    }

    suspend fun saveGalleryMemorySettings(
        featureEnabled: Boolean = galleryMemorySettings.value.featureEnabled,
        profileLoadingEnabled: Boolean = galleryMemorySettings.value.profileLoadingEnabled,
        scanIntervalMinutes: Int = galleryMemorySettings.value.scanIntervalMinutes,
        manualSyncMaxImages: Int = galleryMemorySettings.value.manualSyncMaxImages
    ) {
        try {
            val settingsStore = GalleryMemorySettingsStore()
            val current = settingsStore.load()
            val updated = GalleryMemoryAutomationManager(context, settingsStore).applySettings(
                GalleryMemorySettings(
                    featureEnabled = featureEnabled,
                    profileLoadingEnabled = profileLoadingEnabled,
                    scanIntervalMinutes = scanIntervalMinutes,
                    manualSyncMaxImages = manualSyncMaxImages,
                    automationTaskId = current.automationTaskId
                )
            )
            galleryMemorySettings.value = GalleryMemorySettingsState(
                featureEnabled = updated.featureEnabled,
                profileLoadingEnabled = updated.profileLoadingEnabled,
                scanIntervalMinutes = updated.scanIntervalMinutes,
                manualSyncMaxImages = updated.manualSyncMaxImages,
                automationTaskSummary = if (updated.featureEnabled) {
                    "已启用，每隔 ${updated.scanIntervalMinutes} 分钟扫描一次"
                } else {
                    "未启用"
                }
            )
            refreshStatus()
        } catch (e: Exception) {
            Toast.makeText(context, "保存相册记忆设置失败：${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    suspend fun runGalleryMemorySyncNow() {
        manualGallerySyncMessage.value = null
        try {
            val settings = GalleryMemorySettingsStore().load()
            GalleryMemorySyncForegroundService.enqueue(
                context = context,
                maxImages = settings.manualSyncMaxImages,
                forceRescan = false,
                updateProfile = true
            )
            manualGallerySyncMessage.value =
                "后台扫描已启动：本次最多补扫 ${settings.manualSyncMaxImages} 张未写入图片，切到后台后也会继续执行。"
            refreshStatus()
        } catch (e: Exception) {
            manualGallerySyncMessage.value = "启动后台扫描失败：${e.message}"
        }
    }

    suspend fun resetGalleryMemoryCursor() {
        manualGallerySyncInProgress.value = true
        manualGallerySyncMessage.value = null
        try {
            withContext(Dispatchers.IO) {
                createGalleryMemoryWorkflow(context).resetCursor()
            }
            manualGallerySyncMessage.value = "已重置相册扫描游标，下次同步会从更早的位置重新补扫未写入图片。"
            refreshStatus()
        } catch (e: Exception) {
            manualGallerySyncMessage.value = "重置扫描游标失败：${e.message}"
        } finally {
            manualGallerySyncInProgress.value = false
        }
    }

    suspend fun restoreMemoryFileNow(fileName: String) {
        manualGallerySyncInProgress.value = true
        memoryMaintenanceMessage.value = null
        try {
            withContext(Dispatchers.IO) {
                com.shijing.xomniclaw.workspace.WorkspaceInitializer(context)
                    .restoreBootstrapMemoryFile(fileName)
            }
            memoryMaintenanceMessage.value = "已初始化 $fileName：当前内容已恢复为初始模板。"
            refreshStatus()
        } catch (e: Exception) {
            memoryMaintenanceMessage.value = "初始化 $fileName 失败：${e.message}"
        } finally {
            manualGallerySyncInProgress.value = false
        }
    }

    LaunchedEffect(Unit) {
        refreshStatus()
    }

    // 跟随当前会话切换状态页“本会话累计”展示对象。
    LaunchedEffect(currentSessionId) {
        if (!currentSessionId.isNullOrBlank()) {
            MainEntryNew.syncTokenUsageForSession(currentSessionId)
        }
    }

    LaunchedEffect(galleryMemorySyncStatus.value.isRunning) {
        if (!galleryMemorySyncStatus.value.isRunning) {
            return@LaunchedEffect
        }
        while (galleryMemorySyncStatus.value.isRunning) {
            delay(1000)
            refreshStatus()
        }
    }

    val visibleScheduledTasks by remember(
        scheduledTasks.value,
        taskSearchQuery.value,
        taskSortOption.value
    ) {
        derivedStateOf {
            filterAndSortScheduledTasks(
                tasks = scheduledTasks.value,
                searchQuery = taskSearchQuery.value,
                sortOption = taskSortOption.value
            )
        }
    }

    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                coroutineScope.launch {
                    refreshStatus()
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    if (editingTask.value != null) {
        ScheduledTaskEditDialog(
            state = editingTask.value!!,
            isSaving = saveInProgress.value,
            errorMessage = editErrorMessage.value,
            onDismiss = {
                if (!saveInProgress.value) {
                    editingTask.value = null
                    editErrorMessage.value = null
                }
            },
            onStateChange = {
                editingTask.value = it
                editErrorMessage.value = null
            },
            onSave = { updatedState ->
                coroutineScope.launch {
                    saveInProgress.value = true
                    val taskManager = ScheduledTaskManager(context)
                    val result = withContext(Dispatchers.IO) {
                        taskManager.updateTask(
                            ScheduledTaskEditDraft(
                                taskId = updatedState.taskId,
                                name = updatedState.name,
                                instruction = updatedState.instruction,
                                repeat = updatedState.repeat,
                                runAtText = updatedState.runAtText.ifBlank { null },
                                dailyTime = updatedState.dailyTime.ifBlank { null },
                                daysOfWeekText = updatedState.daysOfWeekText.ifBlank { null },
                                intervalMinutesText = updatedState.intervalMinutesText.ifBlank { null },
                                timezone = updatedState.timezone.ifBlank { null },
                                enabled = updatedState.enabled,
                                exact = updatedState.exact,
                                allowWhileIdle = updatedState.allowWhileIdle
                            )
                        )
                    }
                    saveInProgress.value = false

                    if (result.success) {
                        Toast.makeText(context, "定时任务已更新", Toast.LENGTH_SHORT).show()
                        editingTask.value = null
                        editErrorMessage.value = null
                        refreshStatus()
                    } else {
                        editErrorMessage.value = result.errorMessage ?: "保存失败"
                    }
                }
            }
        )
    }

    pendingDeleteTask.value?.let { task ->
        AlertDialog(
            onDismissRequest = {
                if (taskActionInProgressId.value == null) {
                    pendingDeleteTask.value = null
                }
            },
            title = { Text("删除定时任务") },
            text = { Text("确认删除任务“${task.name}”？删除后不会自动恢复。") },
            confirmButton = {
                TextButton(
                    enabled = taskActionInProgressId.value == null,
                    onClick = {
                        coroutineScope.launch {
                            taskActionInProgressId.value = task.id
                            val deleted = withContext(Dispatchers.IO) {
                                ScheduledTaskManager(context).cancelTask(task.id)
                            }
                            taskActionInProgressId.value = null
                            pendingDeleteTask.value = null
                            if (deleted) {
                                Toast.makeText(context, "定时任务已删除", Toast.LENGTH_SHORT).show()
                                refreshStatus()
                            } else {
                                Toast.makeText(context, "删除失败，任务可能已不存在", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                ) {
                    Text(if (taskActionInProgressId.value == task.id) "删除中..." else "删除")
                }
            },
            dismissButton = {
                TextButton(
                    enabled = taskActionInProgressId.value == null,
                    onClick = { pendingDeleteTask.value = null }
                ) {
                    Text("取消")
                }
            }
        )
    }

    memoryDetailState.value?.let { detail ->
        AlertDialog(
            onDismissRequest = {
                if (!memoryDetailLoading.value) {
                    memoryDetailState.value = null
                }
            },
            title = { Text(detail.title) },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        text = detail.content,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { memoryDetailState.value = null }) {
                    Text("关闭")
                }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineMedium
        )

        Text(
            text = "AI 移动自动化平台",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        loadErrorMessage.value?.let { message ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
            ) {
                Text(
                    text = message,
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        PermissionsCard(onClick = onNavigateToPermissions)

        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Text(
                    text = "Gateway",
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = if (gatewayRunning.value) "运行中 (ws://0.0.0.0:8765)" else "未运行",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (gatewayRunning.value) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                )
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            onClick = onNavigateToSkills
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Text(
                    text = "Skills",
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = if (skillsCount.value > 0) "${skillsCount.value} 个 Skills" else "加载中...",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        TokenUsageCard(status = tokenUsageStatus)

        ScheduledTasksCard(
            allTasksCount = scheduledTasks.value.size,
            tasks = visibleScheduledTasks,
            searchQuery = taskSearchQuery.value,
            sortOption = taskSortOption.value,
            actionInProgressTaskId = taskActionInProgressId.value,
            onSearchQueryChange = { taskSearchQuery.value = it },
            onSortOptionChange = { taskSortOption.value = it },
            onRefresh = {
                coroutineScope.launch {
                    refreshStatus()
                }
            },
            onEditTask = { task ->
                editingTask.value = ScheduledTaskEditorState.fromTask(task)
                editErrorMessage.value = null
            },
            onToggleTaskEnabled = { task, enabled ->
                coroutineScope.launch {
                    taskActionInProgressId.value = task.id
                    val result = withContext(Dispatchers.IO) {
                        ScheduledTaskManager(context).setTaskEnabled(task.id, enabled)
                    }
                    taskActionInProgressId.value = null
                    if (result.success) {
                        Toast.makeText(
                            context,
                            if (enabled) "定时任务已启用" else "定时任务已停用",
                            Toast.LENGTH_SHORT
                        ).show()
                        refreshStatus()
                    } else {
                        Toast.makeText(
                            context,
                            result.errorMessage ?: "更新任务状态失败",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            },
            onDeleteTask = { task ->
                pendingDeleteTask.value = task
            }
        )

        GalleryMemorySettingsCard(
            state = galleryMemorySettings.value,
            manualSyncInProgress = manualGallerySyncInProgress.value || galleryMemorySyncStatus.value.isRunning,
            manualSyncMessage = manualGallerySyncMessage.value,
            syncStatus = galleryMemorySyncStatus.value,
            onFeatureEnabledChange = { enabled ->
                if (enabled && !galleryMemorySettings.value.featureEnabled) {
                    showGalleryMemoryEnableHintDialog.value = true
                }
                coroutineScope.launch {
                    saveGalleryMemorySettings(featureEnabled = enabled)
                }
            },
            onProfileLoadingEnabledChange = { enabled ->
                coroutineScope.launch {
                    saveGalleryMemorySettings(profileLoadingEnabled = enabled)
                }
            },
            onScanIntervalMinutesChange = { minutes ->
                coroutineScope.launch {
                    saveGalleryMemorySettings(scanIntervalMinutes = minutes)
                }
            },
            onManualSyncMaxImagesChange = { maxImages ->
                coroutineScope.launch {
                    saveGalleryMemorySettings(manualSyncMaxImages = maxImages)
                }
            },
            onRunManualSync = {
                coroutineScope.launch {
                    runGalleryMemorySyncNow()
                }
            },
            onResetCursor = {
                coroutineScope.launch {
                    resetGalleryMemoryCursor()
                }
            }
        )

        if (showGalleryMemoryEnableHintDialog.value) {
            AlertDialog(
                onDismissRequest = { showGalleryMemoryEnableHintDialog.value = false },
                title = { Text("开启相册记忆与画像") },
                text = {
                    Text(
                        "已开始准备扫描相册内容，用于构建图片记忆与用户画像。\n" +
                            "请在系统里授权照片/媒体权限。\n" +
                            "可随时关闭总开关暂停。"
                    )
                },
                confirmButton = {
                    TextButton(onClick = { showGalleryMemoryEnableHintDialog.value = false }) {
                        Text("我知道了")
                    }
                }
            )
        }

        MemoryStatusCard(
            snapshot = memorySnapshot.value,
            memoryDetailLoading = memoryDetailLoading.value,
            maintenanceInProgress = manualGallerySyncInProgress.value || galleryMemorySyncStatus.value.isRunning,
            maintenanceMessage = memoryMaintenanceMessage.value,
            onRefresh = {
                coroutineScope.launch {
                    refreshStatus()
                }
            },
            onViewLongTermMemory = {
                coroutineScope.launch {
                    openMemoryDetail("MEMORY.md") { manager -> manager.readMemory() }
                }
            },
            onViewImageMemories = {
                coroutineScope.launch {
                    openMemoryDetail("IMAGE-MEMORY.md") { manager -> manager.readNamedMemoryFile("IMAGE-MEMORY.md") }
                }
            },
            onViewUserProfile = {
                coroutineScope.launch {
                    openMemoryDetail("USER-PROFILE.md") { manager -> manager.readNamedMemoryFile("USER-PROFILE.md") }
                }
            },
            onInitializeLongTermMemory = {
                coroutineScope.launch {
                    restoreMemoryFileNow("MEMORY.md")
                }
            },
            onInitializeImageMemories = {
                coroutineScope.launch {
                    restoreMemoryFileNow("IMAGE-MEMORY.md")
                }
            },
            onInitializeUserProfile = {
                coroutineScope.launch {
                    restoreMemoryFileNow("USER-PROFILE.md")
                }
            },
            onViewKnowledgeFile = { fileName ->
                coroutineScope.launch {
                    openMemoryDetail(fileName) { manager -> manager.readNamedMemoryFile(fileName) }
                }
            },
            onViewDailyLog = { date ->
                coroutineScope.launch {
                    openMemoryDetail("$date.md") { manager -> manager.getLogByDate(date) }
                }
            }
        )
    }
}

private data class PermissionSnapshot(
    val overlay: Boolean,
    val accessibility: Boolean,
    val screenCapture: Boolean,
    val album: Boolean,
    val allFilesAccess: Boolean,
    val camera: Boolean,
    val microphone: Boolean,
    val systemEnabled: Boolean,
    val serviceAvailable: Boolean,
    val serviceReady: Boolean,
)

@Composable
private fun TokenUsageCard(status: MainEntryNew.TokenUsageStatus) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = "Token 使用量（实时）",
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = "本会话累计：${formatTokenUsageLine(status.sessionCounter)}",
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = "全局累计：${formatTokenUsageLine(status.globalCounter)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * 统一 token 展示文案，避免状态页出现不同格式。
 */
private fun formatTokenUsageLine(counter: MainEntryNew.TokenUsageCounter): String {
    return "输入 ${counter.promptTokens} / 输出 ${counter.completionTokens} / 合计 ${counter.totalTokens}"
}

@Composable
private fun ScheduledTasksCard(
    allTasksCount: Int,
    tasks: List<ScheduledTask>,
    searchQuery: String,
    sortOption: ScheduledTaskSortOption,
    actionInProgressTaskId: String?,
    onSearchQueryChange: (String) -> Unit,
    onSortOptionChange: (ScheduledTaskSortOption) -> Unit,
    onRefresh: () -> Unit,
    onEditTask: (ScheduledTask) -> Unit,
    onToggleTaskEnabled: (ScheduledTask, Boolean) -> Unit,
    onDeleteTask: (ScheduledTask) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "定时任务",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = "当前共 ${allTasksCount} 个任务，当前显示 ${tasks.size} 个",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                TextButton(onClick = onRefresh) {
                    Text("刷新")
                }
            }

            OutlinedTextField(
                value = searchQuery,
                onValueChange = onSearchQueryChange,
                label = { Text("搜索任务") },
                supportingText = { Text("支持按任务名、执行指令、重复类型搜索") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            ExposedDropdownField(
                label = "排序方式",
                value = formatTaskSortOptionLabel(sortOption),
                options = ScheduledTaskSortOption.values().map { it.name },
                enabled = true,
                onValueSelected = { onSortOptionChange(ScheduledTaskSortOption.valueOf(it)) },
                labelFormatter = { formatTaskSortOptionLabel(ScheduledTaskSortOption.valueOf(it)) }
            )

            if (allTasksCount == 0) {
                Text(
                    text = "暂无定时任务",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else if (tasks.isEmpty()) {
                Text(
                    text = "没有匹配当前搜索条件的定时任务",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                tasks.forEachIndexed { index, task ->
                    ScheduledTaskListItem(
                        task = task,
                        actionsEnabled = actionInProgressTaskId == null || actionInProgressTaskId == task.id,
                        onEdit = { onEditTask(task) },
                        onToggleEnabled = { onToggleTaskEnabled(task, !task.enabled) },
                        onDelete = { onDeleteTask(task) }
                    )
                    if (index != tasks.lastIndex) {
                        Divider()
                    }
                }
            }
        }
    }
}

@Composable
private fun ScheduledTaskListItem(
    task: ScheduledTask,
    actionsEnabled: Boolean,
    onEdit: () -> Unit,
    onToggleEnabled: () -> Unit,
    onDelete: () -> Unit
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = task.name,
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    text = if (task.enabled) "已启用" else "已停用",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (task.enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                )
            }
            TextButton(onClick = onEdit) {
                Text("编辑")
            }
        }

        Text(
            text = "类型：${formatRepeatLabel(task.repeat)}",
            style = MaterialTheme.typography.bodySmall
        )
        Text(
            text = "下一次触发：${formatTaskScheduleSummary(task)}",
            style = MaterialTheme.typography.bodySmall
        )
        Text(
            text = "执行指令：${task.instruction}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        TaskDiagnosticsBlock(task = task)

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            TextButton(
                onClick = onToggleEnabled,
                enabled = actionsEnabled
            ) {
                Text(if (task.enabled) "停用" else "启用")
            }
            TextButton(
                onClick = onDelete,
                enabled = actionsEnabled
            ) {
                Text("删除")
            }
        }
    }
}

@Composable
private fun TaskDiagnosticsBlock(task: ScheduledTask) {
    Column(
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = "最近触发：${task.lastTriggeredAtMs?.let { formatTimestamp(it) } ?: "暂无"}",
            style = MaterialTheme.typography.bodySmall
        )
        Text(
            text = "触发延迟：${task.lastTriggerDelayMs?.let { "${it}ms" } ?: "暂无"}",
            style = MaterialTheme.typography.bodySmall
        )
        Text(
            text = "派发耗时：${task.lastDispatchLatencyMs?.let { "${it}ms" } ?: "暂无"}",
            style = MaterialTheme.typography.bodySmall
        )
        Text(
            text = "触发来源：${task.lastTriggerSource ?: "暂无"}",
            style = MaterialTheme.typography.bodySmall
        )
        task.lastWakeSummary?.takeIf { it.isNotBlank() }?.let { wakeSummary ->
            Text(
                text = "亮屏结果：$wakeSummary",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun GalleryMemorySettingsCard(
    state: GalleryMemorySettingsState,
    manualSyncInProgress: Boolean,
    manualSyncMessage: String?,
    syncStatus: GalleryMemorySyncStatus,
    onFeatureEnabledChange: (Boolean) -> Unit,
    onProfileLoadingEnabledChange: (Boolean) -> Unit,
    onScanIntervalMinutesChange: (Int) -> Unit,
    onManualSyncMaxImagesChange: (Int) -> Unit,
    onRunManualSync: () -> Unit,
    onResetCursor: () -> Unit
) {
    val intervalOptions = listOf(30, 60, 180, 360, 720, 1440)
    val manualSyncOptions = listOf(10, 20, 50, 100, 200, 500)

    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "相册记忆与画像",
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = "管理后台增量扫描、画像默认加载和自动同步频率",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (syncStatus.isRunning || syncStatus.stage == "completed" || syncStatus.stage == "failed") {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "后台扫描进度",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    when {
                        !syncStatus.isRunning -> LinearProgressIndicator(
                            progress = if (syncStatus.stage == "failed") 0f else 1f,
                            modifier = Modifier.fillMaxWidth()
                        )
                        syncStatus.progressFraction() == null ->
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        else -> LinearProgressIndicator(
                            progress = syncStatus.progressFraction() ?: 0f,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    Text(
                        text = "${formatGalleryMemorySyncStage(syncStatus.stage)}：${syncStatus.progressText()}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = syncStatus.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("总开关")
                    Text(
                        text = "启用后默认允许后台定时增量扫描相册",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = state.featureEnabled,
                    onCheckedChange = onFeatureEnabledChange
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("画像加载")
                    Text(
                        text = "执行任务时默认加载 USER-PROFILE.md",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = state.profileLoadingEnabled,
                    onCheckedChange = onProfileLoadingEnabledChange
                )
            }

            Text(
                text = "扫描频率",
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = "选择后台增量扫描相册的频率，开启总开关后生效",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            ExposedDropdownField(
                label = "扫描频率",
                value = formatGalleryMemoryIntervalLabel(state.scanIntervalMinutes),
                options = intervalOptions.map { it.toString() },
                enabled = !manualSyncInProgress,
                onValueSelected = { onScanIntervalMinutesChange(it.toInt()) },
                labelFormatter = { formatGalleryMemoryIntervalLabel(it.toInt()) }
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("快速测试")
                    Text(
                        text = "立即执行一次：后台最多补扫 ${state.manualSyncMaxImages} 张未写入图片，并更新画像",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                TextButton(
                    onClick = onRunManualSync,
                    enabled = !manualSyncInProgress
                ) {
                    Text(if (manualSyncInProgress) "扫描中..." else "立即扫描一次")
                }
            }
            ExposedDropdownField(
                label = "快速测试最多扫描",
                value = formatGalleryMemoryManualSyncLabel(state.manualSyncMaxImages),
                options = manualSyncOptions.map { it.toString() },
                enabled = !manualSyncInProgress,
                onValueSelected = { onManualSyncMaxImagesChange(it.toInt()) },
                labelFormatter = { formatGalleryMemoryManualSyncLabel(it.toInt()) }
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("游标维护")
                    Text(
                        text = "如果怀疑有遗漏，可重置扫描位置后重新补扫；已写入的图片会被 stableKey 过滤，不会重复写入。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                TextButton(
                    onClick = onResetCursor,
                    enabled = !manualSyncInProgress
                ) {
                    Text("重置游标")
                }
            }

            manualSyncMessage?.takeIf { it.isNotBlank() }?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Text(
                text = "当前状态：${state.automationTaskSummary}。仅扫描新增图片；若没有新增图片，则不会生成新的记忆内容。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun MemoryStatusCard(
    snapshot: MemoryStatusSnapshot,
    memoryDetailLoading: Boolean,
    maintenanceInProgress: Boolean,
    maintenanceMessage: String?,
    onRefresh: () -> Unit,
    onViewLongTermMemory: () -> Unit,
    onViewImageMemories: () -> Unit,
    onViewUserProfile: () -> Unit,
    onInitializeLongTermMemory: () -> Unit,
    onInitializeImageMemories: () -> Unit,
    onInitializeUserProfile: () -> Unit,
    onViewKnowledgeFile: (String) -> Unit,
    onViewDailyLog: (String) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Memory",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = "可查看 MEMORY.md、IMAGE-MEMORY.md、USER-PROFILE.md 与按日沉淀日志",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                TextButton(onClick = onRefresh) {
                    Text("刷新")
                }
            }

            MemoryFileActionRow(
                statusText = if (snapshot.longTermMemoryExists) {
                    "MEMORY.md 已存在，长度 ${snapshot.longTermMemoryLength} 字符"
                } else {
                    "MEMORY.md 暂无内容"
                },
                viewButtonText = "查看 MEMORY.md",
                memoryDetailLoading = memoryDetailLoading,
                maintenanceInProgress = maintenanceInProgress,
                onView = onViewLongTermMemory,
                onInitialize = onInitializeLongTermMemory
            )

            Text(
                text = "全局记忆进化：待处理 ${snapshot.evolutionStatus.pendingEvents} 条，最近处理 ${snapshot.evolutionStatus.processedEvents} 条，采纳 ${snapshot.evolutionStatus.acceptedCandidates} 条",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "最近结果：${snapshot.evolutionStatus.lastMessage}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            MemoryFileActionRow(
                statusText = if (snapshot.imageMemoriesExists) {
                    "IMAGE-MEMORY.md 已存在，长度 ${snapshot.imageMemoriesLength} 字符"
                } else {
                    "IMAGE-MEMORY.md 暂无内容"
                },
                viewButtonText = "查看 IMAGE-MEMORY.md",
                memoryDetailLoading = memoryDetailLoading,
                maintenanceInProgress = maintenanceInProgress,
                onView = onViewImageMemories,
                onInitialize = onInitializeImageMemories
            )

            MemoryFileActionRow(
                statusText = if (snapshot.userProfileExists) {
                    "USER-PROFILE.md 已存在，长度 ${snapshot.userProfileLength} 字符"
                } else {
                    "USER-PROFILE.md 暂无内容"
                },
                viewButtonText = "查看 USER-PROFILE.md",
                memoryDetailLoading = memoryDetailLoading,
                maintenanceInProgress = maintenanceInProgress,
                onView = onViewUserProfile,
                onInitialize = onInitializeUserProfile
            )

            maintenanceMessage?.takeIf { it.isNotBlank() }?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Text(
                text = "知识类文件：${snapshot.knowledgeFiles.size} 个",
                style = MaterialTheme.typography.bodySmall
            )
            if (snapshot.knowledgeFiles.isEmpty()) {
                Text(
                    text = "暂无额外 memory 文件",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                snapshot.knowledgeFiles.take(6).forEach { fileName ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                    ) {
                        Text(
                            text = fileName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(
                            onClick = { onViewKnowledgeFile(fileName) },
                            enabled = !memoryDetailLoading
                        ) {
                            Text("查看")
                        }
                    }
                }
            }

            Divider()

            Text(
                text = "每日日志：${snapshot.dailyLogs.size} 个",
                style = MaterialTheme.typography.bodySmall
            )
            if (snapshot.dailyLogs.isEmpty()) {
                Text(
                    text = "暂无 daily log",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                snapshot.dailyLogs.take(6).forEach { date ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                    ) {
                        Text(
                            text = "$date.md",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(
                            onClick = { onViewDailyLog(date) },
                            enabled = !memoryDetailLoading
                        ) {
                            Text("查看")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MemoryFileActionRow(
    statusText: String,
    viewButtonText: String,
    memoryDetailLoading: Boolean,
    maintenanceInProgress: Boolean,
    onView: () -> Unit,
    onInitialize: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = statusText,
            style = MaterialTheme.typography.bodyMedium
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
        ) {
            TextButton(
                onClick = onView,
                enabled = !memoryDetailLoading
            ) {
                Text(if (memoryDetailLoading) "读取中..." else viewButtonText)
            }
            TextButton(
                onClick = onInitialize,
                enabled = !maintenanceInProgress
            ) {
                Text(if (maintenanceInProgress) "处理中..." else "初始化")
            }
        }
    }
}

@Composable
private fun ScheduledTaskEditDialog(
    state: ScheduledTaskEditorState,
    isSaving: Boolean,
    errorMessage: String?,
    onDismiss: () -> Unit,
    onStateChange: (ScheduledTaskEditorState) -> Unit,
    onSave: (ScheduledTaskEditorState) -> Unit
) {
    val repeatOptions = listOf(
        ScheduledTask.REPEAT_ONCE,
        ScheduledTask.REPEAT_DAILY,
        ScheduledTask.REPEAT_WEEKLY,
        ScheduledTask.REPEAT_WORKDAY,
        ScheduledTask.REPEAT_INTERVAL
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("编辑定时任务")
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = state.name,
                    onValueChange = { onStateChange(state.copy(name = it)) },
                    label = { Text("任务名称") },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isSaving
                )
                OutlinedTextField(
                    value = state.instruction,
                    onValueChange = { onStateChange(state.copy(instruction = it)) },
                    label = { Text("执行指令") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    enabled = !isSaving
                )

                ExposedDropdownField(
                    label = "重复类型",
                    value = state.repeat,
                    options = repeatOptions,
                    enabled = !isSaving,
                    onValueSelected = { onStateChange(state.copy(repeat = it)) },
                    labelFormatter = { "${formatRepeatLabel(it)} ($it)" }
                )

                when (state.repeat) {
                    ScheduledTask.REPEAT_ONCE -> {
                        OutlinedTextField(
                            value = state.runAtText,
                            onValueChange = { onStateChange(state.copy(runAtText = it)) },
                            label = { Text("执行时间") },
                            supportingText = { Text("格式：yyyy-MM-dd HH:mm 或 yyyy-MM-dd HH:mm:ss") },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !isSaving
                        )
                    }

                    ScheduledTask.REPEAT_DAILY,
                    ScheduledTask.REPEAT_WORKDAY -> {
                        OutlinedTextField(
                            value = state.dailyTime,
                            onValueChange = { onStateChange(state.copy(dailyTime = it)) },
                            label = { Text("每日时间") },
                            supportingText = { Text("格式：HH:mm，例如 21:30") },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !isSaving
                        )
                    }

                    ScheduledTask.REPEAT_WEEKLY -> {
                        OutlinedTextField(
                            value = state.dailyTime,
                            onValueChange = { onStateChange(state.copy(dailyTime = it)) },
                            label = { Text("每周时间") },
                            supportingText = { Text("格式：HH:mm，例如 09:00") },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !isSaving
                        )
                        OutlinedTextField(
                            value = state.daysOfWeekText,
                            onValueChange = { onStateChange(state.copy(daysOfWeekText = it)) },
                            label = { Text("周几") },
                            supportingText = { Text("支持：周三 / 1,3,5 / mon,wed,fri") },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !isSaving
                        )
                    }

                    ScheduledTask.REPEAT_INTERVAL -> {
                        OutlinedTextField(
                            value = state.intervalMinutesText,
                            onValueChange = { onStateChange(state.copy(intervalMinutesText = it)) },
                            label = { Text("间隔分钟数") },
                            supportingText = { Text("例如：30 表示每隔 30 分钟") },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !isSaving
                        )
                    }
                }

                OutlinedTextField(
                    value = state.timezone,
                    onValueChange = { onStateChange(state.copy(timezone = it)) },
                    label = { Text("时区（可选）") },
                    supportingText = { Text("留空则使用系统时区，例如 Asia/Shanghai") },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isSaving
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                ) {
                    Text("启用任务")
                    Switch(
                        checked = state.enabled,
                        onCheckedChange = { onStateChange(state.copy(enabled = it)) },
                        enabled = !isSaving
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                ) {
                    Text("精确闹钟")
                    Switch(
                        checked = state.exact,
                        onCheckedChange = { onStateChange(state.copy(exact = it)) },
                        enabled = !isSaving
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                ) {
                    Text("允许息屏空闲时触发")
                    Switch(
                        checked = state.allowWhileIdle,
                        onCheckedChange = { onStateChange(state.copy(allowWhileIdle = it)) },
                        enabled = !isSaving
                    )
                }

                errorMessage?.let { message ->
                    Text(
                        text = message,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(state) },
                enabled = !isSaving
            ) {
                Text(if (isSaving) "保存中..." else "保存")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isSaving
            ) {
                Text("取消")
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExposedDropdownField(
    label: String,
    value: String,
    options: List<String>,
    enabled: Boolean,
    onValueSelected: (String) -> Unit,
    labelFormatter: (String) -> String = { it }
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = {
            if (enabled) {
                expanded = !expanded
            }
        }
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = {
                ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
            },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(),
            enabled = enabled
        )

        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(labelFormatter(option)) },
                    onClick = {
                        expanded = false
                        onValueSelected(option)
                    }
                )
            }
        }
    }
}

private fun formatTaskScheduleSummary(task: ScheduledTask): String {
    if (!task.enabled) {
        return "已停用"
    }

    return when (task.repeat) {
        ScheduledTask.REPEAT_ONCE -> task.runAtMs?.let { formatTimestamp(it) } ?: "未配置"
        ScheduledTask.REPEAT_DAILY -> "${task.dailyTime ?: "--:--"}，下次 ${task.nextTriggerAtMs?.let { formatTimestamp(it) } ?: "未知"}"
        ScheduledTask.REPEAT_WEEKLY -> {
            val days = task.daysOfWeek?.joinToString(",") ?: "-"
            "周[$days] ${task.dailyTime ?: "--:--"}，下次 ${task.nextTriggerAtMs?.let { formatTimestamp(it) } ?: "未知"}"
        }
        ScheduledTask.REPEAT_WORKDAY -> "${task.dailyTime ?: "--:--"}，下次 ${task.nextTriggerAtMs?.let { formatTimestamp(it) } ?: "未知"}"
        ScheduledTask.REPEAT_INTERVAL -> "每隔 ${task.intervalMinutes ?: 0} 分钟，下次 ${task.nextTriggerAtMs?.let { formatTimestamp(it) } ?: "未知"}"
        else -> task.nextTriggerAtMs?.let { formatTimestamp(it) } ?: "未知"
    }
}

fun formatRepeatLabel(repeat: String): String {
    return when (repeat) {
        ScheduledTask.REPEAT_ONCE -> "一次性"
        ScheduledTask.REPEAT_DAILY -> "每天"
        ScheduledTask.REPEAT_WEEKLY -> "每周"
        ScheduledTask.REPEAT_WORKDAY -> "工作日"
        ScheduledTask.REPEAT_INTERVAL -> "固定间隔"
        else -> repeat
    }
}

fun formatTimestamp(timestampMs: Long): String {
    return try {
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(timestampMs))
    } catch (_: Exception) {
        timestampMs.toString()
    }
}

private fun formatGalleryMemoryIntervalLabel(minutes: Int): String {
    return when {
        minutes % (24 * 60) == 0 -> "每 ${minutes / (24 * 60)} 天"
        minutes % 60 == 0 -> "每 ${minutes / 60} 小时"
        else -> "每 $minutes 分钟"
    }
}

private fun formatGalleryMemoryManualSyncLabel(maxImages: Int): String {
    return "最多 $maxImages 张"
}

private fun formatGalleryMemorySyncStage(stage: String): String {
    return when (stage) {
        "preparing" -> "准备中"
        "scanning" -> "扫描中"
        "summarizing" -> "生成记忆中"
        "writing" -> "写入中"
        "completed" -> "已完成"
        "failed" -> "失败"
        else -> "待命中"
    }
}

private fun createGalleryMemoryWorkflow(context: Context): GalleryMemoryWorkflow {
    val configLoader = ConfigLoader(context)
    val openClawCfg = configLoader.loadOmniClawConfig()
    val embeddingProviders = openClawCfg.resolveProviders()
    val embeddingBaseUrl = embeddingProviders.values.firstOrNull()?.baseUrl ?: ""
    val embeddingApiKey = embeddingProviders.values.firstOrNull()?.apiKey ?: ""
    val memoryManager = MemoryManager(
        workspacePath = "/sdcard/.xomniclaw/workspace",
        context = context,
        embeddingBaseUrl = embeddingBaseUrl,
        embeddingApiKey = embeddingApiKey
    )
    return GalleryMemoryWorkflow(
        context = context,
        memoryManager = memoryManager
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionsCard(onClick: () -> Unit) {
    val context = LocalContext.current

    var accessibility by remember { mutableStateOf(false) }
    var overlay by remember { mutableStateOf(false) }
    var screenCapture by remember { mutableStateOf(false) }
    var albumPermission by remember { mutableStateOf(false) }
    var allFilesAccess by remember { mutableStateOf(false) }
    var cameraPermission by remember { mutableStateOf(false) }
    var microphonePermission by remember { mutableStateOf(false) }
    var isConnecting by remember { mutableStateOf(false) }

    suspend fun refreshPermissionState() {
            try {
            // Never mutate Compose mutableState from Dispatchers.IO — causes snapshot / crash on tab switch.
            val snapshot = withContext(Dispatchers.IO) {
                val overlayVal = Settings.canDrawOverlays(context)

                val systemEnabled = try {
                    val accessibilityOn = Settings.Secure.getInt(
                        context.contentResolver,
                        Settings.Secure.ACCESSIBILITY_ENABLED, 0
                    ) == 1
                    if (!accessibilityOn) false
                    else {
                        val services = Settings.Secure.getString(
                            context.contentResolver,
                            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                        ) ?: ""
                        services.contains("com.shijing.xomniclaw")
                    }
                } catch (e: Exception) {
                    false
                }

                val proxy = com.shijing.xomniclaw.accessibility.AccessibilityProxy
                val serviceReady = proxy.isServiceReadyAsync()
                val serviceAvailable =
                    com.shijing.xomniclaw.accessibility.service.AccessibilityBinderService.serviceInstance != null

                val accessibilityVal = systemEnabled || serviceAvailable || serviceReady
                val screenCaptureVal =
                    if (serviceAvailable) proxy.isMediaProjectionGranted() else false
                val albumVal = hasAlbumPermission(context)
                val allFilesVal = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    Environment.isExternalStorageManager()
                } else {
                    true
                }
                val cameraVal = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                    PackageManager.PERMISSION_GRANTED
                val microphoneVal = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED

                PermissionSnapshot(
                    overlayVal,
                    accessibilityVal,
                    screenCaptureVal,
                    albumVal,
                    allFilesVal,
                    cameraVal,
                    microphoneVal,
                    systemEnabled,
                    serviceAvailable,
                    serviceReady
                )
            }
            overlay = snapshot.overlay
            accessibility = snapshot.accessibility
            screenCapture = snapshot.screenCapture
            albumPermission = snapshot.album
            allFilesAccess = snapshot.allFilesAccess
            cameraPermission = snapshot.camera
            microphonePermission = snapshot.microphone
            Log.d(
                "PermissionsCard",
                "Permission status: accessibility=${snapshot.accessibility} (system=${snapshot.systemEnabled}, serviceAvailable=${snapshot.serviceAvailable}, serviceReady=${snapshot.serviceReady}), overlay=${snapshot.overlay}, screenCapture=${snapshot.screenCapture}, album=${snapshot.album}, allFiles=${snapshot.allFilesAccess}, camera=${snapshot.camera}, microphone=${snapshot.microphone}"
            )
            } catch (e: Exception) {
                Log.e("PermissionsCard", "Error checking permissions", e)
        }
    }

    // Refresh on initial composition
    LaunchedEffect(Unit) {
        refreshPermissionState()
    }

    // Refresh every time the activity resumes (e.g. returning from system settings)
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                    Log.d("PermissionsCard", "ON_RESUME: refreshing permission state")
                    refreshPermissionState()
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    DisposableEffect(context) {
        val accessibilityObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                super.onChange(selfChange)
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                    refreshPermissionState()
                }
            }
        }

        val overlayObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                super.onChange(selfChange)
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                    refreshPermissionState()
                }
            }
        }

        context.contentResolver.registerContentObserver(
            Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES),
            false,
            accessibilityObserver
        )
        context.contentResolver.registerContentObserver(
            Settings.Secure.getUriFor(Settings.Secure.ACCESSIBILITY_ENABLED),
            false,
            accessibilityObserver
        )
        context.contentResolver.registerContentObserver(
            Settings.Secure.getUriFor("enabled_accessibility_services"),
            false,
            accessibilityObserver
        )
        context.contentResolver.registerContentObserver(
            Settings.canDrawOverlays(context).let { Settings.System.CONTENT_URI },
            true,
            overlayObserver
        )

        onDispose {
            context.contentResolver.unregisterContentObserver(accessibilityObserver)
            context.contentResolver.unregisterContentObserver(overlayObserver)
        }
    }

    val allGranted = accessibility &&
        overlay &&
        screenCapture &&
        albumPermission &&
        allFilesAccess &&
        cameraPermission &&
        microphonePermission
    val grantedCount = listOf(
        accessibility,
        overlay,
        screenCapture,
        albumPermission,
        allFilesAccess,
        cameraPermission,
        microphonePermission
    ).count { it }

    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "权限状态（实时）",
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = if (allGranted) {
                    "全部已授权（$grantedCount/7）"
                } else {
                    "未全部授权（$grantedCount/7）"
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (allGranted) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                }
            )

            @Composable
            fun statusLine(label: String, granted: Boolean) {
                Text(
                    text = "$label：${if (granted) "已授权" else "未授权"}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (granted) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    }
                )
            }

            // 显眼展示关键权限，便于用户快速判断是否可用。
            statusLine("无障碍服务", accessibility)
            statusLine("悬浮窗", overlay)
            statusLine("录屏权限", screenCapture)
            statusLine("相册读取", albumPermission)
            statusLine("文件管理(全部文件)", allFilesAccess)
            statusLine("摄像头", cameraPermission)
            statusLine("麦克风", microphonePermission)

            Text(
                text = "点击此卡片可进入权限页面",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsTab(
    onNavigateToConfig: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()

    // 与「状态」页 Memory 卡片一致：先展示文件概要，再点「查看 xomniclaw.json」用应用内对话框阅读全文。
    val xomniclawJsonPath = "/sdcard/.xomniclaw/xomniclaw.json"
    val xomniclawJsonSnapshot = remember { mutableStateOf<Pair<Boolean, Int>?>(null) }
    val xomniclawJsonDetail = remember { mutableStateOf<MemoryDetailState?>(null) }
    val xomniclawJsonLoading = remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        xomniclawJsonSnapshot.value = withContext(Dispatchers.IO) {
            val f = File(xomniclawJsonPath)
            if (f.exists()) true to f.readText().length else false to 0
        }
    }

    suspend fun refreshXomniclawJsonSnapshot() {
        xomniclawJsonSnapshot.value = withContext(Dispatchers.IO) {
            val f = File(xomniclawJsonPath)
            if (f.exists()) true to f.readText().length else false to 0
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
        Text(
            text = "设置",
            style = MaterialTheme.typography.headlineMedium
        )

        Spacer(modifier = Modifier.height(8.dp))

        VersionInfoCard()

        // 配置按钮
        Card(
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                android.util.Log.d("SettingsTab", "卡片被点击了")
                onNavigateToConfig()
            }
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = "配置"
                )
                Spacer(modifier = Modifier.width(16.dp))
                Column {
                    Text(
                        text = "模型配置",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = "配置 API Key 和模型参数",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // Channels 按钮
        Card(
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                val intent = Intent(context, ChannelListActivity::class.java)
                context.startActivity(intent)
            }
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "Channels"
                )
                Spacer(modifier = Modifier.width(16.dp))
                Column {
                    Text(
                        text = "Channels",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = "配置多渠道接入（飞书等）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // 查看 xomniclaw.json（交互对齐「状态」页 MEMORY.md：概要文案 +「查看 xomniclaw.json」按钮 + 应用内全文对话框）
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = when {
                        xomniclawJsonSnapshot.value == null -> "xomniclaw.json 读取中…"
                        xomniclawJsonSnapshot.value!!.first ->
                            "xomniclaw.json 已存在，长度 ${xomniclawJsonSnapshot.value!!.second} 字符"
                        else -> "xomniclaw.json 暂无内容（文件尚未生成）"
                    },
                    style = MaterialTheme.typography.bodyMedium
                )
                TextButton(
                    onClick = {
                        scope.launch {
                            xomniclawJsonLoading.value = true
                            try {
                                val (exists, raw) = withContext(Dispatchers.IO) {
                                    val f = File(xomniclawJsonPath)
                                    if (!f.exists()) return@withContext false to ""
                                    true to f.readText()
                                }
                                xomniclawJsonDetail.value = MemoryDetailState(
                                    title = "xomniclaw.json",
                                    content = when {
                                        exists && raw.isNotBlank() -> raw
                                        exists -> "暂无内容"
                                        else ->
                                            "文件不存在：$xomniclawJsonPath\n\n可在「模型配置」保存后自动生成。"
                                    }
                                )
                            } catch (e: Exception) {
                                Toast.makeText(context, "读取失败：${e.message}", Toast.LENGTH_SHORT).show()
                            } finally {
                                xomniclawJsonLoading.value = false
                            }
                        }
                    },
                    enabled = !xomniclawJsonLoading.value
                ) {
                    Text(if (xomniclawJsonLoading.value) "读取中..." else "查看 xomniclaw.json")
                }
                Text(
                    text = xomniclawJsonPath,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    fontSize = 10.sp
                )
            }
        }

        // device(snapshot) 的 YOLO 附加树默认开关
        DeviceYoloFusedTreeSwitch()

        // Prompt dumps 开关（默认关闭）
        PromptDumpsSwitch()
        LlmFullRequestLogcatSwitch()
    }

    xomniclawJsonDetail.value?.let { detail ->
        AlertDialog(
            onDismissRequest = {
                if (!xomniclawJsonLoading.value) {
                    xomniclawJsonDetail.value = null
                    scope.launch { refreshXomniclawJsonSnapshot() }
                }
            },
            title = { Text(detail.title) },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        text = detail.content,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        xomniclawJsonDetail.value = null
                        scope.launch { refreshXomniclawJsonSnapshot() }
                    }
                ) {
                    Text("关闭")
                }
            }
        )
    }
    }
}

@Composable
private fun VersionInfoCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = "版本信息"
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "版本信息",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = "显示当前应用版本与构建类型；可从 GitHub Releases 检查新版本。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Text(
                text = "应用版本：${BuildConfig.VERSION_NAME}（versionCode ${BuildConfig.VERSION_CODE}）",
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = "构建类型：${BuildConfig.BUILD_VARIANT}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // 紧凑入口：与原「检查更新」卡片逻辑一致（AppUpdater + silentUpdateCheck）
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(
                    onClick = {
                        val activity = context as? MainActivityCompose
                        if (activity == null) {
                            Toast.makeText(context, "当前页面无法执行检查更新", Toast.LENGTH_SHORT).show()
                            return@TextButton
                        }
                        Toast.makeText(activity, "正在检查更新...", Toast.LENGTH_SHORT).show()
                        scope.launch {
                            try {
                                val updater = com.shijing.xomniclaw.updater.AppUpdater(activity)
                                val info = updater.checkForUpdate()
                                if (info.hasUpdate) {
                                    activity.silentUpdateCheck()
                                } else {
                                    Toast.makeText(
                                        activity,
                                        "已是最新版本 v${info.currentVersion}",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            } catch (_: Exception) {
                                Toast.makeText(activity, "检查更新失败", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.SystemUpdate,
                        contentDescription = "检查更新",
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "检查更新",
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }
        }
    }
}

@Composable
fun DeviceYoloFusedTreeSwitch() {
    val settingsStore = remember { DeviceToolSettingsStore() }
    var settings by remember {
        mutableStateOf(settingsStore.load())
    }

    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Snapshot 附加 YOLO 结果",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = "关闭时仅返回无障碍 snapshot；开启后会并行执行 YOLO，并追加原始检测结果给大模型参考。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Switch(
                    checked = settings.includeYoloFusedTreeByDefault,
                    onCheckedChange = { enabled ->
                        val updated = settings.copy(includeYoloFusedTreeByDefault = enabled)
                        settings = updated
                        settingsStore.save(updated)
                    }
                )
            }

            Divider()

            DeviceYoloThresholdSlider(
                title = "YOLO 置信度阈值",
                description = "分数低于该值的检测框会被过滤。",
                value = settings.yoloConfidenceThreshold,
                valueRange = DeviceYoloThresholdConfig.MIN_CONFIDENCE..DeviceYoloThresholdConfig.MAX_CONFIDENCE,
                onValueChange = { value ->
                    val updated = settings.copy(yoloConfidenceThreshold = value)
                    settings = updated
                    settingsStore.save(updated)
                }
            )

            DeviceYoloThresholdSlider(
                title = "YOLO IoU 阈值",
                description = "NMS 去重时使用，越大越容易保留重叠检测框。",
                value = settings.yoloIouThreshold,
                valueRange = DeviceYoloThresholdConfig.MIN_IOU..DeviceYoloThresholdConfig.MAX_IOU,
                onValueChange = { value ->
                    val updated = settings.copy(yoloIouThreshold = value)
                    settings = updated
                    settingsStore.save(updated)
                }
            )
        }
    }
}

@Composable
private fun DeviceYoloThresholdSlider(
    title: String,
    description: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = String.format(Locale.US, "%.3f", value),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange
        )
    }
}

@Composable
fun PromptDumpsSwitch() {
    val mmkv = remember { MMKV.defaultMMKV() }
    var isEnabled by remember {
        // 默认关闭：避免误落盘完整 prompt 造成噪音与隐私风险。
        mutableStateOf(mmkv.decodeBool(MMKVKeys.PROMPT_DUMPS_ENABLED.key, false))
    }

    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Prompt Dumps",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = "将发送给大模型的完整请求落盘到 /sdcard/.xomniclaw/workspace/logs/prompt-dumps/（默认关闭）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Switch(
                checked = isEnabled,
                onCheckedChange = { enabled ->
                    isEnabled = enabled
                    mmkv.encode(MMKVKeys.PROMPT_DUMPS_ENABLED.key, enabled)
                }
            )
        }
    }
}

@Composable
fun LlmFullRequestLogcatSwitch() {
    val mmkv = remember { MMKV.defaultMMKV() }
    var isEnabled by remember {
        mutableStateOf(mmkv.decodeBool(MMKVKeys.LLM_FULL_REQUEST_LOGCAT.key, false))
    }

    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Logcat 完整 LLM 请求",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = "将实际上传 wire JSON 分段打 logcat（标签 LLMFullRequest），并写入 " +
                        "/sdcard/.xomniclaw/workspace/logs/llm-full-request/*.json（与 HTTP 正文一致，另附 .meta.txt）。\n" +
                        "长 base64 时 logcat 可能丢行，请用 adb pull 或看带 path= 的短行。默认关闭。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Switch(
                checked = isEnabled,
                onCheckedChange = { enabled ->
                    isEnabled = enabled
                    mmkv.encode(MMKVKeys.LLM_FULL_REQUEST_LOGCAT.key, enabled)
                }
            )
        }
    }
}
