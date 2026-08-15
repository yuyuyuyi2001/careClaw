package com.shijing.xomniclaw.agent.tools

/**
 * OmniClaw Source Reference:
 * - ../xomniclaw/src/agents/tools/(all)
 *
 * OmniClaw adaptation: agent tool implementation.
 */


import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import com.shijing.xomniclaw.providers.FunctionDefinition
import com.shijing.xomniclaw.providers.ParametersSchema
import com.shijing.xomniclaw.providers.PropertySchema
import com.shijing.xomniclaw.providers.ToolDefinition
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import kotlin.coroutines.resume

/**
 * Install App Skill
 *
 * Installs an APK file using PackageInstaller (session-based).
 * Supports:
 * - Local file path (/sdcard/..., /data/...)
 * - content:// URI
 * - Automatic version comparison (upgrade vs fresh install)
 * - Silent install when possible (system-level INSTALL_PACKAGES)
 * - Fallback to user-confirmation install (REQUEST_INSTALL_PACKAGES)
 */
class InstallAppSkill(private val context: Context) : Skill {
    companion object {
        private const val TAG = "InstallAppSkill"
        private const val ACTION_INSTALL_RESULT = "com.shijing.xomniclaw.INSTALL_RESULT"
        private const val INSTALL_TIMEOUT_MS = 60_000L
        private const val LLM_FUNCTION_DESCRIPTION = "Install or upgrade an APK from a local path or content:// URI. " +
            "apk_path is required. allow_downgrade (default false) allows version downgrade. Uses PackageInstaller with fallbacks."
    }

    override val name = "install_app"
    override val description = "Install/upgrade APK from local path or content://. See getToolDefinition LLM block."

    override fun getToolDefinition(): ToolDefinition {
        return ToolDefinition(
            type = "function",
            function = FunctionDefinition(
                name = name,
                description = LLM_FUNCTION_DESCRIPTION,
                parameters = ParametersSchema(
                    type = "object",
                    properties = mapOf(
                        "apk_path" to PropertySchema(
                            type = "string",
                            description = "—"
                        ),
                        "allow_downgrade" to PropertySchema(
                            type = "boolean",
                            description = "—"
                        )
                    ),
                    required = listOf("apk_path")
                )
            )
        )
    }

    override suspend fun execute(args: Map<String, Any?>): SkillResult {
        // ===== 1. 参数解析 =====
        // LLM 通过 function calling 传入的参数都在 args 里；
        // apk_path 是必填项，若缺失/类型不对（as? String 转型失败）直接返回错误。
        val apkPath = args["apk_path"] as? String
            ?: return SkillResult.error("Missing required parameter: apk_path")
        // allow_downgrade 可选，缺省为 false（默认禁止降级安装）。
        val allowDowngrade = args["allow_downgrade"] as? Boolean ?: false

        Log.d(TAG, "Installing APK: $apkPath (allowDowngrade=$allowDowngrade)")

        // ===== 2. 解析 APK 文件 =====
        // resolveApkFile 处理 content:// URI（拷贝到 cacheDir）与本地路径（含常见前缀兜底）；
        // 解析失败则文件不存在，直接返回错误。
        val apkFile = resolveApkFile(apkPath)
            ?: return SkillResult.error("APK file not found: $apkPath")

        // 校验读取权限，避免后续 PackageInstaller 写入会话时因无权限失败。
        if (!apkFile.canRead()) {
            return SkillResult.error("Cannot read APK file: ${apkFile.absolutePath}. Check file permissions.")
        }

        // ===== 3. 从 APK 解析包信息 =====
        // getPackageArchiveInfo 读取 APK 内的 manifest 元数据（无需安装即可读）。
        val pm = context.packageManager
        val apkInfo = pm.getPackageArchiveInfo(apkFile.absolutePath, PackageManager.GET_META_DATA)
        val apkPackageName = apkInfo?.packageName
        val apkVersionName = apkInfo?.versionName ?: "unknown"
        // 版本号取法分版本：API 28(P) 起用 longVersionCode，否则用废弃的 versionCode。
        val apkVersionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            apkInfo?.longVersionCode ?: -1
        } else {
            @Suppress("DEPRECATION")
            apkInfo?.versionCode?.toLong() ?: -1
        }

        // 无法解析出包名说明 APK 损坏或非有效安装包，终止安装。
        if (apkPackageName == null) {
            return SkillResult.error("Invalid APK file: cannot parse package info from $apkPath")
        }

        Log.d(TAG, "APK: $apkPackageName v$apkVersionName ($apkVersionCode)")

        // ===== 4. 检查是否已安装 =====
        // 用包名查系统已安装信息；未安装则抛 NameNotFoundException，catch 后置为 null。
        val existingInfo = try {
            pm.getPackageInfo(apkPackageName, 0)
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }

        // ===== 5. 判定安装类型（升/降/重装/全新） =====
        val installType = if (existingInfo != null) {
            // 已安装：取已装版本号（同样按 API 版本分支）。
            val existingVersionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                existingInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                existingInfo.versionCode.toLong()
            }
            val existingVersionName = existingInfo.versionName ?: "unknown"

            // 用 APK 版本号与已装版本号比较：
            when {
                apkVersionCode > existingVersionCode -> "upgrade (${existingVersionName} → ${apkVersionName})"
                apkVersionCode == existingVersionCode -> "reinstall (same version ${apkVersionName})"
                else -> {
                    // 降级情况：默认禁止，需 allow_downgrade=true 才放行。
                    if (!allowDowngrade) {
                        return SkillResult.error(
                            "Downgrade not allowed: installed=$existingVersionName ($existingVersionCode), " +
                                    "apk=$apkVersionName ($apkVersionCode). Set allow_downgrade=true to force."
                        )
                    }
                    "downgrade (${existingVersionName} → ${apkVersionName})"
                }
            }
        } else {
            "fresh install"
        }

        Log.d(TAG, "Install type: $installType")

        // ===== 6. 执行安装并返回结果 =====
        // 调 performInstall 走 PackageInstaller 会话式安装（见下方实现）。
        return try {
            val result = performInstall(apkFile, apkPackageName, allowDowngrade)
            if (result.success) {
                // 安装成功：返回给 LLM 的成功文案 + 元数据（含 APK 大小，供展示）。
                SkillResult.success(
                    "Successfully installed $apkPackageName v$apkVersionName ($installType)",
                    mapOf(
                        "package_name" to apkPackageName,
                        "version_name" to apkVersionName,
                        "version_code" to apkVersionCode,
                        "install_type" to installType,
                        "apk_size_mb" to String.format("%.1f", apkFile.length() / 1048576.0)
                    )
                )
            } else {
                // 安装失败：把 performInstall 里的错误详情透传出去。
                SkillResult.error("Install failed for $apkPackageName: ${result.content}")
            }
        } catch (e: Exception) {
            // 兜底捕获任何未处理异常（超时、IO 等），转成错误结果而非崩溃。
            Log.e(TAG, "Install failed", e)
            SkillResult.error("Install failed: ${e.message}")
        }
    }

    private fun resolveApkFile(path: String): File? {
        // Handle content:// URI
        if (path.startsWith("content://")) {
            return try {
                val uri = Uri.parse(path)
                val tempFile = File(context.cacheDir, "install_temp_${System.currentTimeMillis()}.apk")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    tempFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                if (tempFile.exists() && tempFile.length() > 0) tempFile else null
            } catch (e: Exception) {
                Log.e(TAG, "Failed to resolve content URI: $path", e)
                null
            }
        }

        // Handle regular file path
        val file = File(path)
        if (file.exists()) return file

        // Try common prefixes
        val candidates = listOf(
            File("/sdcard/$path"),
            File("/sdcard/Download/$path"),
            File("/sdcard/.xomniclaw/$path"),
            File("/sdcard/.xomniclaw/workspace/skills/$path")
        )
        return candidates.firstOrNull { it.exists() }
    }

    private suspend fun performInstall(
        apkFile: File,
        packageName: String,
        allowDowngrade: Boolean
    ): SkillResult {
        val pm = context.packageManager
        val installer = pm.packageInstaller

        // Create session
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(packageName)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
            // Note: setRequestDowngrade() requires API 34+
            // For lower APIs, downgrade is handled by pm install -d via adb or system permissions
        }

        val sessionId = installer.createSession(params)
        val session = installer.openSession(sessionId)

        try {
            // Write APK to session
            session.openWrite("install.apk", 0, apkFile.length()).use { out ->
                FileInputStream(apkFile).use { input ->
                    input.copyTo(out)
                }
                session.fsync(out)
            }

            // Commit with result callback
            return withTimeout(INSTALL_TIMEOUT_MS) {
                suspendCancellableCoroutine { cont ->
                    val intentFilter = IntentFilter(ACTION_INSTALL_RESULT)
                    val receiver = object : BroadcastReceiver() {
                        override fun onReceive(ctx: Context, intent: Intent) {
                            val status = intent.getIntExtra(
                                PackageInstaller.EXTRA_STATUS,
                                PackageInstaller.STATUS_FAILURE
                            )
                            val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ""

                            try {
                                context.unregisterReceiver(this)
                            } catch (_: Exception) {}

                            when (status) {
                                PackageInstaller.STATUS_SUCCESS -> {
                                    Log.d(TAG, "Install success: $packageName")
                                    cont.resume(SkillResult.success("OK"))
                                }
                                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                                    // Need user confirmation — launch the confirmation intent
                                    val confirmIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                        intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                                    } else {
                                        @Suppress("DEPRECATION")
                                        intent.getParcelableExtra(Intent.EXTRA_INTENT)
                                    }
                                    if (confirmIntent != null) {
                                        confirmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        context.startActivity(confirmIntent)
                                        Log.d(TAG, "User confirmation required, launched install dialog")
                                        cont.resume(
                                            SkillResult.success(
                                                "Install requires user confirmation. The install dialog has been shown on screen. " +
                                                        "Use 'screenshot' and 'tap' to interact with the confirmation dialog if needed."
                                            )
                                        )
                                    } else {
                                        cont.resume(SkillResult.error("User confirmation required but no confirmation intent available"))
                                    }
                                }
                                else -> {
                                    val statusName = when (status) {
                                        PackageInstaller.STATUS_FAILURE -> "FAILURE"
                                        PackageInstaller.STATUS_FAILURE_BLOCKED -> "BLOCKED"
                                        PackageInstaller.STATUS_FAILURE_ABORTED -> "ABORTED"
                                        PackageInstaller.STATUS_FAILURE_INVALID -> "INVALID_APK"
                                        PackageInstaller.STATUS_FAILURE_CONFLICT -> "CONFLICT"
                                        PackageInstaller.STATUS_FAILURE_STORAGE -> "STORAGE"
                                        PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "INCOMPATIBLE"
                                        else -> "UNKNOWN($status)"
                                    }
                                    Log.e(TAG, "Install failed: $statusName - $message")
                                    cont.resume(SkillResult.error("$statusName: $message"))
                                }
                            }
                        }
                    }

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        context.registerReceiver(receiver, intentFilter, Context.RECEIVER_NOT_EXPORTED)
                    } else {
                        context.registerReceiver(receiver, intentFilter)
                    }

                    cont.invokeOnCancellation {
                        try {
                            context.unregisterReceiver(receiver)
                        } catch (_: Exception) {}
                        try {
                            session.abandon()
                        } catch (_: Exception) {}
                    }

                    val pendingIntent = PendingIntent.getBroadcast(
                        context,
                        sessionId,
                        Intent(ACTION_INSTALL_RESULT).setPackage(context.packageName),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                    )

                    session.commit(pendingIntent.intentSender)
                    Log.d(TAG, "Session committed, waiting for result...")
                }
            }
        } catch (e: IOException) {
            session.abandon()
            throw e
        }
    }
}
