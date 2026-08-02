package com.shijing.xomniclaw.agent.tools

/**
 * System Settings Tool - Control system-level settings like Bluetooth, WiFi, Airplane mode
 *
 * Android security note:
 * - Direct Bluetooth/WiFi control requires BLUETOOTH_ADMIN/WIFI_STATE_CHANGE permissions
 * - For robust control, we use a hybrid approach:
 *   1. Try Settings.Global/Secure (requires WRITE_SECURE_SETTINGS or root)
 *   2. Fallback to shell commands via exec
 *   3. Last resort: open system settings UI via Intent
 */

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.shijing.xomniclaw.providers.FunctionDefinition
import com.shijing.xomniclaw.providers.ParametersSchema
import com.shijing.xomniclaw.providers.PropertySchema
import com.shijing.xomniclaw.providers.ToolDefinition

class SystemSettingsTool(private val context: Context) : Tool {
    companion object {
        private const val TAG = "SystemSettingsTool"
        private const val LLM_FUNCTION_DESCRIPTION = "Control system toggles: Bluetooth, WiFi, airplane, mobile data, volume, brightness, or get_status. " +
            "action is required. toggle_* use optional enabled. set_volume: stream + volume. set_brightness: brightness and optional auto_brightness. " +
            "May fall back to settings UI on permission limits."
    }

    override val name = "system_settings"
    override val description = "System-level toggles. See getToolDefinition LLM block."

    override fun getToolDefinition(): ToolDefinition {
        return ToolDefinition(
            type = "function",
            function = FunctionDefinition(
                name = name,
                description = LLM_FUNCTION_DESCRIPTION,
                parameters = ParametersSchema(
                    type = "object",
                    properties = mapOf(
                        "action" to PropertySchema(
                            type = "string",
                            description = "—",
                            enum = listOf(
                                "toggle_bluetooth", "toggle_wifi", "toggle_airplane_mode",
                                "toggle_mobile_data", "set_volume", "set_brightness", "get_status"
                            )
                        ),
                        "enabled" to PropertySchema(
                            type = "boolean",
                            description = "—"
                        ),
                        "stream" to PropertySchema(
                            type = "string",
                            description = "—",
                            enum = listOf("music", "ring", "alarm", "notification", "call", "system")
                        ),
                        "volume" to PropertySchema(
                            type = "integer",
                            description = "—"
                        ),
                        "brightness" to PropertySchema(
                            type = "integer",
                            description = "—"
                        ),
                        "auto_brightness" to PropertySchema(
                            type = "boolean",
                            description = "—"
                        )
                    ),
                    required = listOf("action")
                )
            )
        )
    }

    override suspend fun execute(args: Map<String, Any?>): ToolResult {
        val action = args["action"] as? String ?: return ToolResult.error("Missing 'action' parameter")

        return when (action) {
            "toggle_bluetooth" -> toggleBluetooth(args["enabled"] as? Boolean)
            "toggle_wifi" -> toggleWifi(args["enabled"] as? Boolean)
            "toggle_airplane_mode" -> toggleAirplaneMode(args["enabled"] as? Boolean)
            "toggle_mobile_data" -> toggleMobileData(args["enabled"] as? Boolean)
            "set_volume" -> setVolume(
                args["stream"] as? String ?: "music",
                (args["volume"] as? Number)?.toInt() ?: return ToolResult.error("Missing 'volume' for set_volume")
            )
            "set_brightness" -> setBrightness(
                (args["brightness"] as? Number)?.toInt(),
                args["auto_brightness"] as? Boolean
            )
            "get_status" -> getStatus()
            else -> ToolResult.error("Unknown action: $action")
        }
    }

    // ==================== Bluetooth ====================

    private fun toggleBluetooth(enabled: Boolean?): ToolResult {
        return try {
            val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
                ?: return ToolResult.error("BluetoothManager not available")
            val adapter = bluetoothManager.adapter
                ?: return ToolResult.error("Bluetooth adapter not available")

            val currentState = adapter.isEnabled
            val targetState = enabled ?: !currentState

            when {
                targetState == currentState -> {
                    ToolResult.success("Bluetooth already ${if (currentState) "enabled" else "disabled"}")
                }
                targetState -> {
                    // Try to enable
                    if (adapter.enable()) {
                        ToolResult.success("Bluetooth enabled", mapOf("state" to true))
                    } else {
                        // Fallback: open Bluetooth settings
                        openSystemSettings(Settings.ACTION_BLUETOOTH_SETTINGS)
                        ToolResult.success("Requested Bluetooth enable. Please confirm in settings UI.", mapOf("opened_ui" to true))
                    }
                }
                else -> {
                    // Try to disable
                    if (adapter.disable()) {
                        ToolResult.success("Bluetooth disabled", mapOf("state" to false))
                    } else {
                        openSystemSettings(Settings.ACTION_BLUETOOTH_SETTINGS)
                        ToolResult.success("Requested Bluetooth disable. Please confirm in settings UI.", mapOf("opened_ui" to true))
                    }
                }
            }
        } catch (e: SecurityException) {
            // Missing BLUETOOTH_ADMIN permission, fallback to shell
            Log.w(TAG, "BLUETOOTH_ADMIN permission missing, trying shell fallback", e)
            val targetState = enabled ?: false
            val cmd = if (targetState) "svc bluetooth enable" else "svc bluetooth disable"
            return executeShellCommand(cmd, "Bluetooth")
        } catch (e: Exception) {
            ToolResult.error("Bluetooth toggle failed: ${e.message}")
        }
    }

    // ==================== WiFi ====================

    private fun toggleWifi(enabled: Boolean?): ToolResult {
        return try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                ?: return ToolResult.error("WiFi manager not available")

            val currentState = wifiManager.isWifiEnabled
            val targetState = enabled ?: !currentState

            when {
                targetState == currentState -> {
                    ToolResult.success("WiFi already ${if (currentState) "enabled" else "disabled"}")
                }
                targetState -> {
                    if (wifiManager.setWifiEnabled(true)) {
                        ToolResult.success("WiFi enabled", mapOf("state" to true))
                    } else {
                        // API 29+ requires settings panel
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            openSystemSettings(Settings.ACTION_WIFI_SETTINGS)
                            ToolResult.success("Requested WiFi enable. Please confirm in settings UI (Android 10+ requires this).", mapOf("opened_ui" to true))
                        } else {
                            ToolResult.error("Failed to enable WiFi")
                        }
                    }
                }
                else -> {
                    if (wifiManager.setWifiEnabled(false)) {
                        ToolResult.success("WiFi disabled", mapOf("state" to false))
                    } else {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            openSystemSettings(Settings.ACTION_WIFI_SETTINGS)
                            ToolResult.success("Requested WiFi disable. Please confirm in settings UI (Android 10+ requires this).", mapOf("opened_ui" to true))
                        } else {
                            ToolResult.error("Failed to disable WiFi")
                        }
                    }
                }
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "WiFi permission missing, trying shell fallback", e)
            val targetState = enabled ?: false
            val cmd = if (targetState) "svc wifi enable" else "svc wifi disable"
            return executeShellCommand(cmd, "WiFi")
        } catch (e: Exception) {
            ToolResult.error("WiFi toggle failed: ${e.message}")
        }
    }

    // ==================== Airplane Mode ====================

    private fun toggleAirplaneMode(enabled: Boolean?): ToolResult {
        return try {
            val currentState = Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1
            val targetState = enabled ?: !currentState

            if (targetState == currentState) {
                return ToolResult.success("Airplane mode already ${if (currentState) "enabled" else "disabled"}")
            }

            // Try shell command first (requires root or system permission)
            val cmd = if (targetState) "settings put global airplane_mode_on 1" else "settings put global airplane_mode_on 0"
            val result = executeShellCommandInternal(cmd)
            if (result.success) {
                // Broadcast the change
                context.sendBroadcast(Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED).apply {
                    putExtra("state", targetState)
                })
                ToolResult.success("Airplane mode ${if (targetState) "enabled" else "disabled"}", mapOf("state" to targetState))
            } else {
                openSystemSettings(Settings.ACTION_AIRPLANE_MODE_SETTINGS)
                ToolResult.success("Requested airplane mode change. Please confirm in settings UI.", mapOf("opened_ui" to true))
            }
        } catch (e: Exception) {
            openSystemSettings(Settings.ACTION_AIRPLANE_MODE_SETTINGS)
            ToolResult.success("Requested airplane mode change. Please confirm in settings UI.", mapOf("opened_ui" to true))
        }
    }

    // ==================== Mobile Data ====================

    private fun toggleMobileData(enabled: Boolean?): ToolResult {
        val targetState = enabled ?: return ToolResult.error("Mobile data toggle requires explicit enabled parameter")
        val cmd = if (targetState) "svc data enable" else "svc data disable"
        return executeShellCommand(cmd, "Mobile data")
    }

    // ==================== Volume ====================

    private fun setVolume(stream: String, volumePercent: Int): ToolResult {
        return try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
                ?: return ToolResult.error("AudioManager not available")

            val streamType = when (stream.lowercase()) {
                "music", "media" -> android.media.AudioManager.STREAM_MUSIC
                "ring", "ringtone" -> android.media.AudioManager.STREAM_RING
                "alarm" -> android.media.AudioManager.STREAM_ALARM
                "notification" -> android.media.AudioManager.STREAM_NOTIFICATION
                "call", "voice_call" -> android.media.AudioManager.STREAM_VOICE_CALL
                "system" -> android.media.AudioManager.STREAM_SYSTEM
                else -> android.media.AudioManager.STREAM_MUSIC
            }

            val maxVolume = audioManager.getStreamMaxVolume(streamType)
            val targetVolume = (volumePercent / 100.0 * maxVolume).toInt().coerceIn(0, maxVolume)

            audioManager.setStreamVolume(streamType, targetVolume, 0)

            ToolResult.success("Volume set to $volumePercent% ($targetVolume/$maxVolume) for $stream", mapOf(
                "stream" to stream,
                "volume_percent" to volumePercent,
                "volume_level" to targetVolume,
                "max_level" to maxVolume
            ))
        } catch (e: Exception) {
            // Fallback to shell
            val cmd = "media volume --set $volumePercent --stream $stream"
            executeShellCommand(cmd, "Volume")
        }
    }

    // ==================== Brightness ====================

    private fun setBrightness(brightnessPercent: Int?, autoBrightness: Boolean?): ToolResult {
        return try {
            when {
                autoBrightness != null -> {
                    Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE,
                        if (autoBrightness) Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
                        else Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
                    if (autoBrightness) {
                        ToolResult.success("Auto brightness enabled", mapOf("auto_brightness" to true))
                    } else {
                        ToolResult.success("Auto brightness disabled", mapOf("auto_brightness" to false))
                    }
                }
                brightnessPercent != null -> {
                    // First disable auto brightness
                    Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE,
                        Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
                    // Set brightness (0-255)
                    val brightnessValue = (brightnessPercent / 100.0 * 255).toInt().coerceIn(0, 255)
                    Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, brightnessValue)
                    ToolResult.success("Brightness set to $brightnessPercent% ($brightnessValue/255)", mapOf(
                        "brightness_percent" to brightnessPercent,
                        "brightness_value" to brightnessValue
                    ))
                }
                else -> ToolResult.error("Either brightness or auto_brightness must be specified")
            }
        } catch (e: SecurityException) {
            // Fallback to shell
            val cmd = when {
                autoBrightness == true -> "settings put system screen_brightness_mode 1"
                autoBrightness == false -> "settings put system screen_brightness_mode 0"
                brightnessPercent != null -> "settings put system screen_brightness ${(brightnessPercent / 100.0 * 255).toInt()}"
                else -> return ToolResult.error("Failed to set brightness: ${e.message}")
            }
            executeShellCommand(cmd, "Brightness")
        } catch (e: Exception) {
            ToolResult.error("Failed to set brightness: ${e.message}")
        }
    }

    // ==================== Get Status ====================

    private fun getStatus(): ToolResult {
        return try {
            val status = buildString {
                // Bluetooth status
                try {
                    val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
                    val btAdapter = btManager?.adapter
                    appendLine("Bluetooth: ${if (btAdapter?.isEnabled == true) "✅ enabled" else "❌ disabled"}")
                } catch (e: Exception) {
                    appendLine("Bluetooth: unknown")
                }

                // WiFi status
                try {
                    val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                    appendLine("WiFi: ${if (wifiManager?.isWifiEnabled == true) "✅ enabled" else "❌ disabled"}")
                } catch (e: Exception) {
                    appendLine("WiFi: unknown")
                }

                // Airplane mode
                try {
                    val airplaneMode = Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1
                    appendLine("Airplane mode: ${if (airplaneMode) "✅ enabled" else "❌ disabled"}")
                } catch (e: Exception) {
                    appendLine("Airplane mode: unknown")
                }

                // Brightness
                try {
                    val autoMode = Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, 0)
                    val brightness = Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 0)
                    val brightnessPercent = (brightness / 255.0 * 100).toInt()
                    if (autoMode == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC) {
                        appendLine("Brightness: auto (current ~$brightnessPercent%)")
                    } else {
                        appendLine("Brightness: $brightnessPercent% ($brightness/255)")
                    }
                } catch (e: Exception) {
                    appendLine("Brightness: unknown")
                }
            }

            ToolResult.success(status.trim(), mapOf(
                "bluetooth" to isBluetoothEnabled(),
                "wifi" to isWifiEnabled(),
                "airplane_mode" to isAirplaneModeEnabled()
            ))
        } catch (e: Exception) {
            ToolResult.error("Failed to get status: ${e.message}")
        }
    }

    // ==================== Helpers ====================

    private fun isBluetoothEnabled(): Boolean {
        return try {
            val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            btManager?.adapter?.isEnabled == true
        } catch (e: Exception) { false }
    }

    private fun isWifiEnabled(): Boolean {
        return try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            wifiManager?.isWifiEnabled == true
        } catch (e: Exception) { false }
    }

    private fun isAirplaneModeEnabled(): Boolean {
        return try {
            Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1
        } catch (e: Exception) { false }
    }

    private fun openSystemSettings(action: String) {
        try {
            val intent = Intent(action).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to open settings: $action", e)
        }
    }

    private fun executeShellCommand(cmd: String, label: String): ToolResult {
        val result = executeShellCommandInternal(cmd)
        return if (result.success) {
            ToolResult.success("$label ${if (cmd.contains("enable")) "enabled" else "disabled"} via shell", mapOf("shell" to true))
        } else {
            ToolResult.error("Failed to control $label: ${result.error}")
        }
    }

    private fun executeShellCommandInternal(cmd: String): ShellResult {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
            val exitCode = process.waitFor()
            val output = process.inputStream.bufferedReader().readText()
            val error = process.errorStream.bufferedReader().readText()
            ShellResult(exitCode == 0, output, error, exitCode)
        } catch (e: Exception) {
            ShellResult(false, "", e.message ?: "Unknown error", -1)
        }
    }

    private data class ShellResult(
        val success: Boolean,
        val output: String,
        val error: String,
        val exitCode: Int
    )
}
