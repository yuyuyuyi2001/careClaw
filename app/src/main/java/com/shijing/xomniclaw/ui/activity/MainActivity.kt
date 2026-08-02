/**
 * OmniClaw Source Reference:
 * - ../omniclaw/src/gateway/(all)
 *
 * OmniClaw adaptation: Android UI layer.
 */
package com.shijing.xomniclaw.ui.activity

import android.content.Intent
import android.net.Uri
import android.content.ComponentName
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.shijing.xomniclaw.core.MyApplication
import com.shijing.xomniclaw.accessibility.AccessibilityProxy
import com.shijing.xomniclaw.util.MMKVKeys
import com.shijing.xomniclaw.R
import com.shijing.xomniclaw.databinding.ActivityMainBinding
import com.tencent.mmkv.MMKV
import kotlinx.coroutines.launch
import com.shijing.xomniclaw.agent.skills.SkillsLoader
import com.shijing.xomniclaw.ui.session.SessionManager
import java.io.File

/**
 * OmniClaw Main Activity
 *
 * Maps OmniClaw CLI commands to visual interface:
 * - omniclaw status → Status cards
 * - omniclaw config → Config page
 * - omniclaw skills → Skills management
 * - omniclaw gateway → Gateway control
 * - omniclaw sessions → Session list
 */
class MainActivity : AppCompatActivity() {

    private fun launchObserverPermissionActivity() {
        try {
            startActivity(Intent().apply {
                component = ComponentName(
                    "com.shijing.xomniclaw",
                    "com.shijing.xomniclaw.accessibility.PermissionActivity"
                )
            })
        } catch (e: Exception) {
            android.util.Log.w(TAG, "Observer PermissionActivity unavailable, fallback to local PermissionsActivity", e)
            startActivity(Intent(this, PermissionsActivity::class.java))
        }
    }

    private lateinit var binding: ActivityMainBinding
    private val mmkv by lazy { MMKV.defaultMMKV() }

    companion object {
        private const val TAG = "MainActivity"
        private const val REQUEST_ACCESSIBILITY = 1001
        private const val REQUEST_OVERLAY = 1002
        private const val REQUEST_SCREEN_CAPTURE = 1003
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupViews()
        updateStatusCards()
    }

    override fun onResume() {
        super.onResume()
        updateStatusCards()
    }
    private fun setupViews() {
        // Status card click events
        binding.apply {
            // Gateway card
            cardGateway.setOnClickListener {
                if (isGatewayRunning()) {
                    showGatewayInfo()
                } else {
                    Toast.makeText(this@MainActivity, "Gateway 未运行", Toast.LENGTH_SHORT).show()
                }
            }

            // Permissions card
            cardPermissions.setOnClickListener {
                launchObserverPermissionActivity()
            }

            // Skills card
            cardSkills.setOnClickListener {
                showSkillsDialog()
            }

            // Sessions card
            cardSessions.setOnClickListener {
                showSessionsDialog()
            }

            // Bottom navigation buttons
            btnConfig.setOnClickListener {
                startActivity(Intent(this@MainActivity, ConfigActivity::class.java))
            }


            btnLogs.setOnClickListener {
                showLogsDialog()
            }
        }
    }

    /**
     * Update status cards
     * Maps to OmniClaw CLI: omniclaw status
     */
    private fun updateStatusCards() {
        lifecycleScope.launch {
            updateGatewayCard()
            updatePermissionsCard()
            updateSkillsCard()
            updateSessionsCard()
        }
    }

    /**
     * Update Gateway status card
     */
    private fun updateGatewayCard() {
        val isRunning = isGatewayRunning()
        binding.apply {
            tvGatewayStatus.text = if (isRunning) "运行中" else "未运行"
            tvGatewayStatus.setTextColor(
                if (isRunning) getColor(R.color.status_ok)
                else getColor(R.color.status_error)
            )

            if (isRunning) {
                tvGatewayDetails.text = "WebSocket: ws://0.0.0.0:8765\n" +
                        "Sessions: ${getSessionCount()}"
            } else {
                tvGatewayDetails.text = "Gateway 服务未启动"
            }
        }
    }

    /**
     * Update permissions status card
     */
    private fun updatePermissionsCard() {
        val accessibility = AccessibilityProxy.isConnected.value == true && AccessibilityProxy.isServiceReady()
        val overlay = Settings.canDrawOverlays(this)
        val screenCapture = AccessibilityProxy.isMediaProjectionGranted()

        val allGranted = accessibility && overlay && screenCapture

        binding.apply {
            tvPermissionsStatus.text = if (allGranted) "已授权" else "需要授权"
            tvPermissionsStatus.setTextColor(
                if (allGranted) getColor(R.color.status_ok)
                else getColor(R.color.status_warning)
            )

            tvPermissionsDetails.text = buildString {
                append("无障碍: ${if (accessibility) "✓" else "✗"}\n")
                append("悬浮窗: ${if (overlay) "✓" else "✗"}\n")
                append("录屏: ${if (screenCapture) "✓" else "✗"} (${AccessibilityProxy.getMediaProjectionStatus()})")
            }
        }
    }

    /**
     * Update Skills status card
     */
    private fun updateSkillsCard() {
        try {
            val skillsLoader = SkillsLoader(this)
            val allSkills = skillsLoader.getAllSkills()
            val alwaysSkills = skillsLoader.getAlwaysSkills()
            val totalSkills = allSkills.size

            binding.apply {
                tvSkillsStatus.text = "$totalSkills 个 Skills"
                tvSkillsStatus.setTextColor(getColor(R.color.status_ok))

                tvSkillsDetails.text = buildString {
                    append("Always: ${alwaysSkills.size}\n")
                    append("On-Demand: ${totalSkills - alwaysSkills.size}\n")
                    append("Total: $totalSkills")
                }
            }
        } catch (e: Exception) {
            binding.tvSkillsStatus.text = "加载失败"
            binding.tvSkillsDetails.text = e.message ?: "未知错误"
        }
    }

    /**
     * Update Sessions status card
     */
    private fun updateSessionsCard() {
        val sessionCount = getSessionCount()

        binding.apply {
            tvSessionsStatus.text = if (sessionCount > 0) {
                "$sessionCount 个活跃会话"
            } else {
                "无活跃会话"
            }
            tvSessionsStatus.setTextColor(
                if (sessionCount > 0) getColor(R.color.status_ok)
                else getColor(R.color.text_secondary)
            )

            tvSessionsDetails.text = if (sessionCount > 0) {
                "点击查看详情"
            } else {
                "暂无活跃的 Agent 会话"
            }
        }
    }

    /**
     * Show Gateway detailed information
     * Maps to OmniClaw CLI: omniclaw gateway status
     */
    private fun showGatewayInfo() {
        val info = buildString {
            append("Gateway 状态\n\n")
            append("WebSocket 端口: 8765\n")
            append("连接地址: ws://0.0.0.0:8765\n")
            append("活跃 Sessions: ${getSessionCount()}\n\n")
            append("RPC 方法:\n")
            append("  • agent - 执行 Agent 任务\n")
            append("  • agent.wait - 等待任务完成\n")
            append("  • health - 健康检查\n")
            append("  • session.list - 列出会话\n")
            append("  • session.reset - 重置会话\n")
        }

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Gateway 信息")
            .setMessage(info)
            .setPositiveButton("关闭", null)
            .setNeutralButton("测试连接") { _, _ ->
                Toast.makeText(this, if (isGatewayRunning()) "Gateway 运行正常 ✅" else "Gateway 未运行 ❌", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    /**
     * Show permissions dialog
     */
    private fun showPermissionsDialog() {
        val accessibility = AccessibilityProxy.isConnected.value == true && AccessibilityProxy.isServiceReady()
        val overlay = Settings.canDrawOverlays(this)
        val screenCapture = AccessibilityProxy.isMediaProjectionGranted()

        val message = buildString {
            append("权限状态:\n\n")
            append("${if (accessibility) "✓" else "✗"} 无障碍服务\n")
            if (!accessibility) {
                append("  用于: 点击、滑动、输入\n\n")
            }
            append("${if (overlay) "✓" else "✗"} 悬浮窗权限\n")
            if (!overlay) {
                append("  用于: 显示 Agent 状态\n\n")
            }
            append("${if (screenCapture) "✓" else "✗"} 录屏权限\n")
            if (!screenCapture) {
                append("  用于: 截图观察界面\n")
                append("  状态: ${AccessibilityProxy.getMediaProjectionStatus()}\n")
            }
        }

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("权限管理")
            .setMessage(message)
            .setPositiveButton("前往设置") { _, _ ->
                requestPermissions()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * Request permissions
     */
    private fun requestPermissions() {
        val accessibility = AccessibilityProxy.isConnected.value == true && AccessibilityProxy.isServiceReady()
        val overlay = Settings.canDrawOverlays(this)
        val screenCapture = AccessibilityProxy.isMediaProjectionGranted()

        when {
            !accessibility -> {
                // Open accessibility settings
                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                startActivityForResult(intent, REQUEST_ACCESSIBILITY)
            }
            !overlay -> {
                // Request overlay permission
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    android.net.Uri.parse("package:$packageName")
                )
                startActivityForResult(intent, REQUEST_OVERLAY)
            }
            !screenCapture -> {
                // Screen recording permission managed by accessibility service APK
                Toast.makeText(
                    this,
                    "录屏权限由无障碍服务 APK 管理\n请在系统设置中授予",
                    Toast.LENGTH_LONG
                ).show()
            }
            else -> {
                Toast.makeText(this, "所有权限已授予", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * Check if Gateway is running
     */
    private fun isGatewayRunning(): Boolean {
        return try {
            val app = application as? MyApplication
            java.net.Socket().use { s -> s.connect(java.net.InetSocketAddress("127.0.0.1", 8765), 500); true }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Get active Session count
     */
    private fun getSessionCount(): Int {
        return try {
            val app = application as? MyApplication
            SessionManager().getSessionCount()
        } catch (e: Exception) {
            0
        }
    }


    /**
     * Show Skills management dialog
     * Maps to: omniclaw skills
     */
    private fun showSkillsDialog() {
        try {
            val skillsLoader = SkillsLoader(this)
            val allSkills = skillsLoader.getAllSkills()

            val message = buildString {
                if (allSkills.isEmpty()) {
                    append("暂无已安装的 Skills")
                } else {
                    allSkills.forEachIndexed { index, skill ->
                        val emoji = skill.metadata.emoji ?: "📋"
                        val always = if (skill.metadata.always) " [Always]" else ""
                        append("${index + 1}. $emoji ${skill.name}$always\n")
                        append("   ${skill.description.lines().first().take(50)}\n\n")
                    }
                }
            }

            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Skills 管理 (${allSkills.size} 个)")
                .setMessage(message)
                .setPositiveButton("关闭", null)
                .show()
        } catch (e: Exception) {
            Toast.makeText(this, "加载 Skills 失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Show Sessions list dialog
     * Maps to: omniclaw sessions
     */
    private fun showSessionsDialog() {
        try {
            val sessionManager = SessionManager()
            val sessions = sessionManager.getAllSessions()

            val message = buildString {
                if (sessions.isEmpty()) {
                    append("暂无活跃会话")
                } else {
                    sessions.forEachIndexed { index, session ->
                        append("${index + 1}. ${session.title}\n")
                        append("   消息数: ${session.messages.size}\n\n")
                    }
                }
            }

            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("会话列表 (${sessions.size} 个)")
                .setMessage(message)
                .setPositiveButton("关闭", null)
                .show()
        } catch (e: Exception) {
            Toast.makeText(this, "加载会话失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Show Logs viewer dialog
     * Maps to: viewing AgentLoop session logs
     */
    private fun showLogsDialog() {
        val logDir = File("/sdcard/.xomniclaw/workspace/logs")
        if (!logDir.exists() || !logDir.isDirectory) {
            Toast.makeText(this, "暂无日志文件", Toast.LENGTH_SHORT).show()
            return
        }

        val logFiles = logDir.listFiles()
            ?.filter { it.name.endsWith(".log") }
            ?.sortedByDescending { it.lastModified() }
            ?.take(20)
            ?: emptyList()

        if (logFiles.isEmpty()) {
            Toast.makeText(this, "暂无日志文件", Toast.LENGTH_SHORT).show()
            return
        }

        val fileNames = logFiles.map { file ->
            val sizeKb = file.length() / 1024
            "${file.name} (${sizeKb}KB)"
        }.toTypedArray()

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("AgentLoop 日志 (${logFiles.size} 个)")
            .setItems(fileNames) { _, which ->
                showLogContent(logFiles[which])
            }
            .setPositiveButton("关闭", null)
            .show()
    }

    /**
     * Show specific log file content
     */
    private fun showLogContent(file: File) {
        try {
            val content = file.readText()
            val truncated = if (content.length > 5000) {
                content.take(5000) + "\n\n... (${content.length - 5000} chars truncated)"
            } else content

            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(file.name)
                .setMessage(truncated)
                .setPositiveButton("关闭", null)
                .show()
        } catch (e: Exception) {
            Toast.makeText(this, "读取日志失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
    /**
     * Open URL in browser
     */
    private fun openUrl(url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "无法打开浏览器: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        when (requestCode) {
            REQUEST_ACCESSIBILITY, REQUEST_OVERLAY -> {
                // Returned from permission settings, refresh status
                updateStatusCards()
            }
        }
    }
}
