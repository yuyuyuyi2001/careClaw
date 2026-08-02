package com.shijing.xomniclaw.agent.tools.device

/**
 * OmniClaw Source Reference:
 * - ../xomniclaw/src/agents/tools/(device / playwright-aligned patterns)
 *
 * OmniClaw adaptation: unified device control tool aligned with
 * Playwright-style snapshot + ref 交互模式。
 *
 * Usage pattern (same as Playwright):
 *   1. device(action="snapshot") → get UI tree with refs
 *   2. device(action="act", kind="tap", ref="e5") → 基于最近一次 snapshot 的 ref 操作
 *   3. device(action="act", kind="multi_tap", refs=[...]) → 多 ref 连点（refs 须来自最近一次 snapshot）
 *   4. device(action="snapshot") → verify result
 */

import android.content.ActivityNotFoundException
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Looper
import android.util.Log
import com.shijing.xomniclaw.accessibility.AccessibilityProxy
import com.shijing.xomniclaw.accessibility.service.ViewNode
import com.shijing.xomniclaw.agent.tools.Tool
import com.shijing.xomniclaw.agent.tools.ToolResult
import com.shijing.xomniclaw.agent.tools.device.yolo.UiDetectionFormatter
import com.shijing.xomniclaw.agent.tools.device.yolo.UiYoloSnapshotEngine
import com.shijing.xomniclaw.providers.FunctionDefinition
import com.shijing.xomniclaw.providers.ParametersSchema
import com.shijing.xomniclaw.providers.PropertySchema
import com.shijing.xomniclaw.providers.ToolDefinition
import com.shijing.xomniclaw.util.PromptArtifactNaming
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay

class DeviceTool(private val context: Context) : Tool {

    private data class MultiTapCoord(
        val ref: String,
        val x: Int,
        val y: Int,
        val label: String?,
    )

    companion object {
        private const val TAG = "DeviceTool"
        // Aligned with Playwright Computer Use: wait after actions for UI to settle
        private const val POST_ACTION_DELAY_MS = 800L  // after tap/type/press
        private const val POST_OPEN_DELAY_MS = 1500L   // after opening apps
        private const val POST_SCROLL_DELAY_MS = 500L   // after scroll
        private const val REF_MAX_AGE_MS = 20_000L      // refs older than this are considered stale
        /** Max refs per multi_tap — 与剪映单屏可见格数上限对齐（约 15 格）。 */
        private const val MULTI_TAP_MAX = 15
        /** Delay between taps in multi_tap so grid/checkbox UI can update. */
        private const val MULTI_TAP_DELAY_MS = 450L
        /** Hard cap for kind=wait (milliseconds). */
        private const val WAIT_MAX_MS = 120_000L
        /** Scroll `amount`: with a ref on a wheel column, ~1.0 ≈ one notch (see executeScroll). */
        private const val SCROLL_AMOUNT_MAX = 12.0
        private const val SCROLL_AMOUNT_MIN = 0.25
        /** Absolute floor for half-span (px) so tiny amounts still send a gesture. */
        private const val SCROLL_HALF_SPAN_MIN_PX = 10
        /** 剪映「一键成片」相关入口（智能推荐/编辑 Tab）；版本不符时可能无效，可回退 UI 点「一键成片」。 */
        const val CAPCUT_DEEPLINK_ONE_TAP_SMART =
            "videocut://template/smart_recommend?enter_from=intelligent_edit&tab_name=edit"

        private const val DUAL_TRACK_MIN_CONFIDENCE = 0.45
        private const val RETRY_SECOND_TAP_MIN_DISTANCE_PX = 24.0
        private const val AUTO_DISMISS_MIN_CONFIDENCE = 0.50
        private val AUTO_DISMISS_TARGETS = listOf(
            "关闭广告", "跳过广告", "关闭", "跳过", "不感兴趣", "稍后", "×", "✕", "x"
        )
        private val AD_SCREEN_TEXT_HINTS = listOf(
            "广告", "赞助", "推广", "sponsored", "advertisement",
            "立即下载", "点击下载", "免费下载", "下载app", "激励视频", "观看完整", "领取奖励"
        )
        private val AD_SCREEN_CLASS_OR_ID_HINTS = listOf(
            "adview", "bannerad", "nativead", "feedad", "ttad", "gdt", "pangle",
            "mads", "windmill", "csjad", "unionad", "ksad", "splash", "interstitial",
            "reward", "incentive", "advert", "promo"
        )
        /** 常见「提示/帮助」按钮关键词；默认防止误点。 */
        private val HINT_BUTTON_KEYWORDS = listOf(
            "查看提示", "提示", "help", "hint", "题解", "解析"
        )
        private val FEISHU_PACKAGE_ALIASES = mapOf(
            "com.bytedance.feishu" to "com.ss.android.lark"
        )
        private val FEISHU_PACKAGES = setOf("com.ss.android.lark", "com.bytedance.feishu")
        private val SEND_TARGET_HINTS = listOf("发送", "发 送", "send")

        /** Total swipe length (|end-start|) that approximates one vertical wheel tick. */
        private fun estimateVerticalWheelNotchTotalPx(regionH: Int, refNode: RefNode?, density: Float): Int {
            val refH = refNode?.bounds?.height() ?: 0
            val byRef = if (refH > 120) (refH / 6.2f).toInt() else 0
            val byRegion = (regionH / 7.5f).toInt()
            val byDp = (density * 10f).toInt().coerceAtLeast(22)
            return max(max(byRef, byRegion), byDp).coerceIn(34, 68)
        }

        /** Total swipe length for one horizontal wheel tick. */
        private fun estimateHorizontalWheelNotchTotalPx(regionW: Int, refNode: RefNode?, density: Float): Int {
            val refW = refNode?.bounds?.width() ?: 0
            val byRef = if (refW > 120) (refW / 6.2f).toInt() else 0
            val byRegion = (regionW / 7.5f).toInt()
            val byDp = (density * 10f).toInt().coerceAtLeast(22)
            return max(max(byRef, byRegion), byDp).coerceIn(34, 68)
        }

        /**
         * Packages that commonly indicate top overlays instead of the underlying business page.
         * Examples: keyboards (IME) and System UI panels.
         */
        private fun isOverlayPackage(packageName: String?): Boolean {
            val pkg = packageName?.trim()?.lowercase().orEmpty()
            if (pkg.isBlank()) return false
            return pkg.contains("systemui") ||
                pkg.contains("inputmethod") ||
                pkg.contains("latinime") ||
                pkg.contains(".ime") ||
                pkg.contains("ime.") ||
                pkg.contains("keyboard")
        }

        /**
         * 附在「会改变界面」的操作成功结果末尾，强调模型侧流程约束（不再附带 snapshot_id / expected 等监控文案）。
         */
        private const val SNAPSHOT_BEFORE_NEXT_ACT_HINT =
            "\n[建议] 若页面已变化、ref 可能失效或将执行高风险点击，请先调用 device(action=\"snapshot\") 刷新界面再继续。"
    }

    override val name = "device"
    override val description =
        "Control the Android device screen with snapshot, screenshot, act, open, and status operations. " +
            "Use snapshot when state changed or refs may be stale."

    private val refManager = RefManager()
    private val dualTrackEngine by lazy { DualTrackDecisionEngine(context) }
    private val yoloSnapshotEngine by lazy { UiYoloSnapshotEngine(context) }

    override fun getToolDefinition(): ToolDefinition {
        return ToolDefinition(
            type = "function",
            function = FunctionDefinition(
                name = name,
                description = description,
                parameters = ParametersSchema(
                    type = "object",
                    properties = mapOf(
                        "action" to PropertySchema(
                            type = "string",
                            description = "Action: snapshot | screenshot | act | open | status | clipboard",
                            enum = listOf("snapshot", "screenshot", "act", "open", "status", "clipboard")
                        ),
                        "kind" to PropertySchema(
                            type = "string",
                            description = "For action=act: tap | multi_tap | type | press | long_press | scroll | swipe | wait | home | back",
                            enum = listOf("tap", "multi_tap", "type", "press", "long_press", "scroll", "swipe", "wait", "home", "back")
                        ),
                        "refs" to PropertySchema(
                            type = "array",
                            description = "For kind=multi_tap: refs from the **current** snapshot only (e.g. 剪映从左到右各格勾选圈 ref 列表)；ref 编号无奇偶规律，勿套用旧 snapshot。**重复 ref 会自动去重（保序）**，避免连点同一格导致取消选中。Max $MULTI_TAP_MAX per call.",
                            items = PropertySchema("string", "ref id")
                        ),
                        "ref" to PropertySchema(
                            type = "string",
                            description = "Element ref from snapshot (e.g. 'e5')"
                        ),
                        "snapshot_id" to PropertySchema(
                            type = "string",
                            description = "Deprecated/ignored. Snapshot header may still show an id for logging only; do not use for validation."
                        ),
                        "strict" to PropertySchema(
                            type = "boolean",
                            description = "Deprecated/ignored. Ref validation no longer uses snapshot_id matching."
                        ),
                        "ignore_ad_guard" to PropertySchema(
                            type = "boolean",
                            description = "If true, allow taps/scroll on nodes that look like ads (default false)."
                        ),
                        "ignore_hint_guard" to PropertySchema(
                            type = "boolean",
                            description = "If true, allow tapping refs that look like hint/help buttons (default false)."
                        ),
                        "text" to PropertySchema(
                            type = "string",
                            description = "Text to type (for kind=type)"
                        ),
                        "target" to PropertySchema(
                            type = "string",
                            description = "Semantic target label for tap grounding."
                        ),
                        "use_dual_track" to PropertySchema(
                            type = "boolean",
                            description = "Allow visual grounding fallback for act=tap."
                        ),
                        "key" to PropertySchema(
                            type = "string",
                            description = "Key to press: BACK, HOME, ENTER, TAB, VOLUME_UP, etc."
                        ),
                        "coordinate" to PropertySchema(
                            type = "array",
                            description = "Fallback [x, y] coordinate when ref not available",
                            items = PropertySchema(type = "integer", description = "coordinate value")
                        ),
                        "direction" to PropertySchema(
                            type = "string",
                            description = "Scroll direction",
                            enum = listOf("up", "down", "left", "right")
                        ),
                        "amount" to PropertySchema(
                            type = "number",
                            description = "Scroll strength ${SCROLL_AMOUNT_MIN}-${SCROLL_AMOUNT_MAX}. Around 1.0 is one notch on many wheel pickers."
                        ),
                        "timeMs" to PropertySchema(
                            type = "number",
                            description = "Wait time in milliseconds (capped at ${WAIT_MAX_MS}ms; actual waited time is returned)"
                        ),
                        "package_name" to PropertySchema(
                            type = "string",
                            description = "App package for action=open. With uri: sets Intent target package (recommended com.lemon.lv for videocut://). Without uri: launch app default activity. With class_name: launch specific activity."
                        ),
                        "class_name" to PropertySchema(
                            type = "string",
                            description = "Activity class name for action=open. Used with package_name to launch a specific Activity (e.g. class_name=\"com.example.SettingsActivity\"). Requires use_root=true for unexported activities."
                        ),
                        "use_root" to PropertySchema(
                            type = "boolean",
                            description = "For action=open with package_name+class_name: if true, use root shell `am start -n` to launch the Activity. Required for unexported/system activities that cannot be started via normal Intent."
                        ),
                        "uri" to PropertySchema(
                            type = "string",
                            description = "Deep link for action=open: uses ACTION_VIEW. " +
                                "剪映一键成片入口（示例）: uri=\"$CAPCUT_DEEPLINK_ONE_TAP_SMART\" + package_name=\"com.lemon.lv\" " +
                                "可直达相关页，无需再点首页「一键成片」。scheme=videocut 且未写 package_name 时默认 com.lemon.lv。"
                        ),
                        "url" to PropertySchema(
                            type = "string",
                            description = "Alias of uri for action=open (same behavior)."
                        ),
                        "query" to PropertySchema(
                            type = "string",
                            description = "For action=screenshot: what to verify or which UI region matters (VLM/grounding hint). " +
                                "Be specific (e.g. 'confirm 消息已发出', '右上角搜索'). If omitted, a generic screen analysis is used."
                        ),
                        "format" to PropertySchema(
                            type = "string",
                            description = "Snapshot format: compact (default) | tree | interactive",
                            enum = listOf("compact", "tree", "interactive")
                        ),
                        "include_yolo_fused_tree" to PropertySchema(
                            type = "boolean",
                            description = "For action=snapshot: whether to append raw YOLO detections for the model. Default false; when true, YOLO runs in parallel with snapshot formatting."
                        )
                    ),
                    required = listOf("action")
                )
            )
        )
    }

    override suspend fun execute(args: Map<String, Any?>): ToolResult {
        val rawAction = args["action"] as? String ?: return ToolResult.error("Missing action")
        val action = normalizeDeviceStringParam(rawAction)

        return when (action) {
            "snapshot" -> executeSnapshot(args)
            "screenshot" -> executeScreenshot(args)
            "act" -> executeAct(args)
            "open" -> appendUiVerifyHintAfterOpen(executeOpen(args))
            "status" -> executeStatus()
            "clipboard" -> executeClipboard()
            else -> ToolResult.error("Unknown action: $action")
        }
    }

    /**
     * LLM 偶发输出带换行/嵌套引号的枚举参数（如 `"\\n  \\"snapshot\\"\\n"`），
     * 仅 strip 首尾引号不够；去掉全部空白与引号类字符后再匹配。
     */
    private fun normalizeDeviceStringParam(raw: String): String =
        raw.lowercase()
            .replace(Regex("[\"'`«»＂]+"), "")
            .replace(Regex("\\s+"), "")

    /** ref：去掉空白与引号，保留 e12 等 token */
    private fun sanitizeRefOrSnapshotToken(raw: String?): String =
        raw?.trim()
            ?.replace(Regex("[\"'`«»＂]+"), "")
            ?.replace(Regex("\\s+"), "")
            .orEmpty()

    /** 语义目标是否在表达“发送/提交消息”。 */
    private fun isSendLikeTarget(target: String): Boolean {
        val t = target.trim().lowercase()
        if (t.isBlank()) return false
        return SEND_TARGET_HINTS.any { hint -> t.contains(hint.lowercase()) }
    }

    /** 归一化常见包名别名，避免历史错误包名导致开错应用。 */
    private fun normalizeKnownPackageAlias(rawPackageName: String?): Pair<String?, String?> {
        val input = rawPackageName?.trim()?.takeIf { it.isNotEmpty() } ?: return null to null
        val normalized = FEISHU_PACKAGE_ALIASES[input] ?: input
        val note = if (normalized != input) "package alias: $input -> $normalized" else null
        return normalized to note
    }

    private fun appendUiVerifyHintAfterMutation(result: ToolResult, kind: String?): ToolResult {
        if (!result.success) return result
        if (normalizeDeviceStringParam(kind.orEmpty()) == "wait") return result
        // 仍使当前 ref 集合视为「过期」，促使下一轮先 snapshot；不再用 snapshot_id 与模型参数比对。
        refManager.invalidateSnapshotAfterMutation()
        return ToolResult.success(result.content + SNAPSHOT_BEFORE_NEXT_ACT_HINT, result.metadata)
    }

    private fun appendUiVerifyHintAfterOpen(result: ToolResult): ToolResult {
        if (!result.success) return result
        refManager.invalidateSnapshotAfterMutation()
        return ToolResult.success(result.content + SNAPSHOT_BEFORE_NEXT_ACT_HINT, result.metadata)
    }

    private fun collectTopPackages(nodes: List<RefNode>, limit: Int = 3): List<Pair<String, Int>> {
        return nodes.asSequence()
            .mapNotNull { it.packageName?.trim()?.takeIf(String::isNotBlank) }
            .groupingBy { it }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            .take(limit)
            .map { it.key to it.value }
    }

    private fun formatTopPackages(topPackages: List<Pair<String, Int>>): String {
        return topPackages.joinToString(",") { (pkg, count) -> "$pkg:$count" }
    }

    private fun buildOverlaySnapshotInfo(
        currentPackage: String,
        topPackages: List<Pair<String, Int>>
    ): String? {
        val dominantPackage = topPackages.firstOrNull()?.first ?: currentPackage
        val overlayOnTop = isOverlayPackage(dominantPackage) || isOverlayPackage(currentPackage)
        if (!overlayOnTop) return null

        val underlyingApp = topPackages.firstOrNull { (pkg, _) -> !isOverlayPackage(pkg) }?.first
        return if (underlyingApp != null) {
            "[overlay_top=$dominantPackage underlying_app=$underlyingApp]"
        } else {
            "[overlay_top=$dominantPackage]"
        }
    }

    /** OEM 权限/安全弹窗（如 ColorOS com.oplus.securitypermission）常与底层 App 合并到同一棵无障碍树。 */
    private fun isPermissionDialogPackage(packageName: String?): Boolean {
        val p = packageName?.trim()?.lowercase().orEmpty()
        if (p.isBlank()) return false
        return p.contains("securitypermission") ||
            p.contains("packageinstaller") ||
            p.contains("permissioncontroller")
    }

    /**
     * 顶层为权限/安全页且树内仍含剪映等大量节点时，避免模型误点底层 ref。
     */
    private fun buildPermissionOverlaySnapshotHint(
        packageHeader: String,
        topPackages: List<Pair<String, Int>>
    ): String? {
        if (!isPermissionDialogPackage(packageHeader)) return null
        val lemonCount = topPackages.find { it.first == "com.lemon.lv" }?.second ?: 0
        if (lemonCount < 5) return null
        return "[权限/安全顶层] 当前 **package=$packageHeader**（系统权限或安全页在上层）。树中虽混有 **剪映 com.lemon.lv** 节点，" +
            "在弹窗未处理前 **勿点** 剪映区域的 ref（易点穿、坐标错位或与包名校验冲突）。请先点本页 **允许 / 拒绝 / 仅在使用中允许** 等带文案的按钮；" +
            "待 snapshot 首行 **package=com.lemon.lv** 后再点「一键成片」。"
    }

    // ==================== snapshot ====================

    private suspend fun executeSnapshot(args: Map<String, Any?>): ToolResult {
        val format = (args["format"] as? String) ?: "compact"
        val defaultSettings = DeviceToolSettingsStore().load()
        val includeYoloFusedTree = DeviceSnapshotOptions.shouldIncludeYoloFusedTree(
            args = args,
            defaultEnabled = defaultSettings.includeYoloFusedTreeByDefault
        )

        val proxy = AccessibilityProxy

        val viewNodes = try {
            proxy.dumpViewTree(useCache = false)
        } catch (e: IllegalStateException) {
            Log.e(TAG, "Accessibility service not available", e)
            return ToolResult.error("无障碍服务未开启。请到 设置 → 无障碍 → OmniClaw 开启无障碍权限，才能获取屏幕元素。")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to dump view tree", e)
            return ToolResult.error("获取 UI 树失败: ${e.message}。请检查无障碍服务是否正常运行。")
        }

        if (viewNodes.isEmpty()) {
            val accessibilityOn = try { proxy.isConnected.value == true && proxy.isServiceReady() } catch (_: Exception) { false }
            val status = if (accessibilityOn) "无障碍服务: ✅ 已开启（但当前页面无可识别元素，可能页面正在加载，建议等 1-2 秒重试）" 
                         else "无障碍服务: ❌ 未开启。请到 设置 → 无障碍 → OmniClaw 开启无障碍权限。"
            return ToolResult.error(status)
        }

        val nodes = SnapshotBuilder.buildFromViewNodes(viewNodes)
        val topPackages = collectTopPackages(nodes)
        val currentPackage = try { proxy.getCurrentPackageName() } catch (_: Exception) { "" }
        val packageForHeader = currentPackage.ifBlank { topPackages.firstOrNull()?.first ?: "unknown" }
        val snapshotId = refManager.updateRefs(
            nodes,
            packageForHeader.takeIf { it.isNotBlank() && it != "unknown" }
        )

        // Get screen info
        val dm = context.resources.displayMetrics
        val width = dm.widthPixels
        val height = dm.heightPixels
        val appName = try {
            packageForHeader.let { pkg ->
                if (pkg.isNotBlank()) {
                    context.packageManager.getApplicationLabel(
                        context.packageManager.getApplicationInfo(pkg, 0)
                    ).toString()
                } else null
            }
        } catch (e: Exception) { null }

        val (body, yoloSnapshotResult) = if (includeYoloFusedTree) {
            coroutineScope {
                // 仅在显式开启时，并行执行 YOLO；默认 snapshot 仍只返回 UI tree。
                val yoloDeferred = async(Dispatchers.Default) {
                    yoloSnapshotEngine.buildSnapshotResult(viewNodes, nodes)
                }
                val formattedBody = when (format) {
                    "tree" -> SnapshotFormatter.tree(nodes, width, height, appName)
                    "interactive" -> SnapshotFormatter.interactive(nodes, appName, height)
                    else -> SnapshotFormatter.compact(nodes, width, height, appName)
                }
                val yoloResult = yoloDeferred.await()
                (formattedBody + "\n\n" + UiDetectionFormatter.toSnapshotAppendix(yoloResult)) to yoloResult
            }
        } else {
            val formattedBody = when (format) {
                "tree" -> SnapshotFormatter.tree(nodes, width, height, appName)
                "interactive" -> SnapshotFormatter.interactive(nodes, appName, height)
                else -> SnapshotFormatter.compact(nodes, width, height, appName)
            }
            formattedBody to null
        }

        val output = buildString {
            append("[seq=$snapshotId package=$packageForHeader refs=${nodes.size}")
            if (topPackages.isNotEmpty()) {
                append(" top_packages=${formatTopPackages(topPackages)}")
            }
            appendLine("]")
            appendLine("提示：[约束] 每次执行 act/open 等会改变界面的操作前，必须先 snapshot；首行 seq 仅便于日志对照，勿作为工具入参或校验依据。")
            buildOverlaySnapshotInfo(packageForHeader, topPackages)?.let { appendLine(it) }
            buildPermissionOverlaySnapshotHint(packageForHeader, topPackages)?.let { appendLine(it) }

            if (packageForHeader == "com.lemon.lv") {
                SnapshotFormatter.capCutAlbumSourceDropdownHint(nodes)?.let { appendLine(it) }
                SnapshotFormatter.capCutA_latestAlbumListRowHint(nodes)?.let { appendLine(it) }
            }
            SnapshotFormatter.mediaPickerBannerIfNeeded(nodes, height)?.let { appendLine(it) }
            if (packageForHeader == "com.lemon.lv") {
                SnapshotFormatter.capCutVideoTabSelectedPhotoReminder(nodes, height)?.let { appendLine(it) }
                SnapshotFormatter.capCutThreePlusSuggestionReminder(nodes)?.let { appendLine(it) }
                SnapshotFormatter.capCutGridPairingHint(nodes)?.let { appendLine(it) }
                SnapshotFormatter.capCutGridOrderedLinkRefsLine(nodes)?.let { appendLine(it) }
            }
            if (packageForHeader == "com.lemon.lv" && body.contains("一键成片")) {
                refManager.findRefForLabelTextContaining("一键成片", "com.lemon.lv")?.let { r ->
                    appendLine(
                        "[剪映首页·一键成片] **请对该入口使用 ref=$r**（与树内 **`text '一键成片' [ref=$r]`** 一致）。**禁止**凭感觉改用列表中下区其它功能的 ref（例如 **e49 可能是「AI 图片编辑」**，与一键成片无关）。"
                    )
                }
                appendLine(
                    "提示（剪映）：进入「一键成片」请优先使用带「一键成片」字样的 ref；若点到旁边大块空白 button，引擎会改点到文案命中区。" +
                        "相册多选：大块无文案 button 多为缩略图（易进预览）；勾选圈多为 link（可 selected）。误点大图时引擎会尽量改点到同格右上角圈。"
                )
            }
            append(body)
        }

        // Snapshot 不再默认执行 YOLO；仅在 include_yolo_fused_tree=true 时并行补充原始检测结果，
        // 仍然不会触发异步 VLM 预热，避免隐藏耗时与网络开销。

        return ToolResult.success(
            output,
            metadata = buildMap {
                put("include_yolo_fused_tree", includeYoloFusedTree)
                yoloSnapshotResult?.let {
                    put("yolo_status", it.status)
                    put("yolo_raw_count", it.rawDetections.size)
                    put("yolo_fused_count", it.fusedDetections.size)
                    put("yolo_ocr_count", it.rawDetections.count { detection -> detection.recognizedText != null })
                    put("yolo_ocr_non_blank_count", it.rawDetections.count { detection -> !detection.recognizedText.isNullOrBlank() })
                    put("yolo_raw_debug_image_path", it.rawDebugImagePath)
                    put("yolo_fused_debug_image_path", it.fusedDebugImagePath)
                }
            }
        )
    }

    // ==================== screenshot ====================

    private suspend fun executeScreenshot(): ToolResult {
        // Delegate to existing ScreenshotSkill logic
        val screenshotResult = try {
            val controller = com.shijing.xomniclaw.DeviceController
            controller.getScreenshot(context)
        } catch (e: Exception) {
            null
        }

        if (screenshotResult == null) {
            // Fallback: try shell screencap
            try {
                val screenshotFile = PromptArtifactNaming.buildScreenshotFile(
                    dir = java.io.File("/sdcard/.xomniclaw/workspace/screenshots"),
                    // device 工具自己的 shell 兜底截图单独标识来源。
                    suffix = "device_shell_screenshot"
                )
                val path = screenshotFile.absolutePath
                val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", "screencap -p $path"))
                process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
                if (screenshotFile.exists() && screenshotFile.length() > 0) {
                    return ToolResult.success("Screenshot saved: $path (${screenshotFile.length()} bytes)")
                }
            } catch (_: Exception) {}
            return ToolResult.error("Screenshot failed. Please grant screen capture permission.")
        }

        val (bitmap, path) = screenshotResult
        return ToolResult.success("Screenshot: ${bitmap.width}x${bitmap.height}, path: $path")
    }

    // Enhanced screenshot with query — keeps old behavior when query is blank.
    private suspend fun executeScreenshot(args: Map<String, Any?>): ToolResult {
        var query = (args["query"] as? String)?.trim().orEmpty()

        if (query.isBlank()) {
            query = "分析当前界面并返回最显著交互目标。"
        }

        // 截一张图，获取当前画面和分辨率
        val screenshotResult = try {
            val controller = com.shijing.xomniclaw.DeviceController
            controller.getScreenshot(context)
        } catch (e: Exception) {
            null
        }

        if (screenshotResult == null) {
            return ToolResult.error("Screenshot failed. Please grant screen capture permission.")
        }

        val (bitmap, path) = screenshotResult

        val decision = dualTrackEngine.resolveTapTarget(
            target = query,
            preferVisual = true
        ) ?: return ToolResult.error(
            "未能根据 query='$query' 定位到可点击目标。请改写更具体 query（如“右上角搜索按钮”）后重试。"
        )

        // Heuristic: Analysis Mode (see DualTrackDecisionEngine.callVlmGrounding systemPrompt).
        // 当 query 更像“问题/状态判断”时，VLM 可以返回:
        //   - Yes: x=1, y=1, confidence=1.0
        //   - No:  x=0, y=0, confidence=1.0
        //   - Unsure/Error: x=-1, y=-1, confidence<=0.3
        val isAnalysisMode =
            (decision.x in 0..1) && (decision.y in 0..1) && decision.confidence >= 0.99

        if (isAnalysisMode) {
            val yes = (decision.x == 1 && decision.y == 1)
            val no = (decision.x == 0 && decision.y == 0)
            val status = when {
                yes -> "yes"
                no -> "no"
                else -> "unknown"
            }
            val resultText = buildString {
                appendLine("Screenshot: ${bitmap.width}x${bitmap.height}, path: $path")
                appendLine("query: $query")
                appendLine("analysis_mode: true")
                appendLine("analysis_result: $status")
                appendLine("analysis_reason: ${decision.reason}")
            }
            return ToolResult.success(
                resultText,
                metadata = mapOf(
                    "analysis_mode" to true,
                    "analysis_result" to status,
                    "grounding_source" to decision.source,
                    "grounding_confidence" to decision.confidence,
                    "query" to query,
                    "screenshot_path" to path
                )
            )
        }

        val confidenceText = "%.2f".format(decision.confidence)

        val resultText = buildString {
            appendLine("Screenshot: ${bitmap.width}x${bitmap.height}, path: $path")
            appendLine("query: $query")
            appendLine(
                "grounding: source=${decision.source}, confidence=$confidenceText, reason=${decision.reason}, " +
                    "x=${decision.x}, y=${decision.y}"
            )
        }

        return ToolResult.success(
            resultText,
            metadata = mapOf(
                "grounding_source" to decision.source,
                "grounding_confidence" to decision.confidence,
                "x" to decision.x,
                "y" to decision.y,
                "query" to query,
                "screenshot_path" to path
            )
        )
    }

    // ==================== act ====================

    private suspend fun executeAct(args: Map<String, Any?>): ToolResult {
        val rawKind = args["kind"] as? String ?: return ToolResult.error("Missing 'kind' for action=act")
        val kind = normalizeDeviceStringParam(rawKind)

        val result = when (kind) {
            "tap" -> executeTap(args)
            "multi_tap" -> executeMultiTap(args)
            "type" -> executeType(args)
            "press" -> executePress(args)
            "long_press" -> executeLongPress(args)
            "scroll" -> executeScroll(args)
            "swipe" -> executeSwipe(args)
            "wait" -> executeWait(args)
            "home" -> executeKey("HOME")
            "back" -> executeKey("BACK")
            else -> ToolResult.error("Unknown kind: $kind")
        }
        return appendUiVerifyHintAfterMutation(result, kind)
    }

    /**
     * 剪映首页：VLM 双轨点「一键成片」常命中状态栏/营销区（如 y≈300），误进 AI 创作。
     * 若当前 snapshot 里已有「一键成片」文案节点，则注入其 ref，强制走无障碍 ref 路径（跳过双轨优先）。
     */
    private fun injectCapCutYijianchengpianRefIfNeeded(
        args: Map<String, Any?>,
        semanticTarget: String
    ): Pair<Map<String, Any?>, Boolean> {
        val refExisting = sanitizeRefOrSnapshotToken(args["ref"] as? String)
        if (refExisting.isNotEmpty()) return args to false
        if (refManager.getSnapshotPackage() != "com.lemon.lv") return args to false
        if (!semanticTarget.contains("一键成片")) return args to false
        val r = refManager.findRefForLabelTextContaining("一键成片", "com.lemon.lv") ?: return args to false
        val m = LinkedHashMap<String, Any?>().apply {
            putAll(args)
            put("ref", r)
        }
        return m to true
    }

    /**
     * 剪映相册选图页：双轨点「照片」常看似成功但 **Tab 仍为视频**，随后会误选带「分:秒」的视频格。
     * 当树里已有顶部 Tab（照片+视频）且 target 为精确「照片/视频/实况」时，注入对应可点击 ref。
     */
    private fun injectCapCutMediaPickerTabRefIfNeeded(
        args: Map<String, Any?>,
        semanticTarget: String
    ): Pair<Map<String, Any?>, Boolean> {
        val refExisting = sanitizeRefOrSnapshotToken(args["ref"] as? String)
        if (refExisting.isNotEmpty()) return args to false
        if (refManager.getSnapshotPackage() != "com.lemon.lv") return args to false
        if (!refManager.hasCapCutMediaPickerTabBar()) return args to false
        val tabLabel = when (semanticTarget.trim()) {
            "照片" -> "照片"
            "视频" -> "视频"
            "实况" -> "实况"
            else -> return args to false
        }
        val r = refManager.findRefForLabelTextContaining(tabLabel, "com.lemon.lv") ?: return args to false
        val m = LinkedHashMap<String, Any?>().apply {
            putAll(args)
            put("ref", r)
        }
        return m to true
    }

    /**
     * 飞书聊天页常见问题：模型能看到输入框但容易点错右下角图标（如加号/语音）导致内容丢失。
     * 当目标语义是“发送”且当前为飞书包时，强制注入最可信发送按钮 ref，优先树内点击而非盲点坐标。
     */
    private fun injectFeishuSendRefIfNeeded(
        args: Map<String, Any?>,
        semanticTarget: String
    ): Pair<Map<String, Any?>, Boolean> {
        val refExisting = sanitizeRefOrSnapshotToken(args["ref"] as? String)
        if (refExisting.isNotEmpty()) return args to false
        if (!isSendLikeTarget(semanticTarget)) return args to false

        val pkg = refManager.getSnapshotPackage() ?: return args to false
        if (!FEISHU_PACKAGES.contains(pkg)) return args to false

        val sendRef = refManager.findLikelyChatSendButtonRef(pkg) ?: return args to false
        val mapped = LinkedHashMap<String, Any?>().apply {
            putAll(args)
            put("ref", sendRef)
        }
        return mapped to true
    }

    private suspend fun executeTap(args: Map<String, Any?>): ToolResult {
        val useDualTrack = (args["use_dual_track"] as? Boolean) == true
        val semanticTarget = deriveTapSemanticTarget(args)
        val (tapAfterFeishuSend, feishuSendRefForced) = injectFeishuSendRefIfNeeded(args, semanticTarget)
        val (tapAfterYijian, capcutYijianRefForced) = injectCapCutYijianchengpianRefIfNeeded(tapAfterFeishuSend, semanticTarget)
        val (tapArgs, capcutPickerTabRefForced) = injectCapCutMediaPickerTabRefIfNeeded(tapAfterYijian, semanticTarget)
        val capcutTreeRefForced = capcutYijianRefForced || capcutPickerTabRefForced
        val hasExplicitPointer = hasExplicitTapPointer(tapArgs)
        val beforeTapNodes = safeDumpViewTreeForRetry()
        val adLikelyOnScreen = looksLikeAdvertisementScreen(beforeTapNodes)
        val retryEnabled = semanticTarget.isNotBlank() || useDualTrack || adLikelyOnScreen
        var dualDecision: DualTrackTapDecision? = null
        var base: ResolvedCoordinate? = null

        // Dual-track first only when semantic tap is requested and no explicit pointer is provided.
        val shouldTryDualFirst = !hasExplicitPointer && (useDualTrack || semanticTarget.isNotBlank())
        if (shouldTryDualFirst) {
            dualDecision = dualTrackEngine.resolveTapTarget(
                target = semanticTarget,
                preferVisual = false
            )
            if (dualDecision != null) {
                base = ResolvedCoordinate(
                    dualDecision.x,
                    dualDecision.y,
                    dualDecision.fusedNodeText ?: semanticTarget.ifBlank { null }
                )
            }
        }

        // Structured fallback (existing path). If it fails and target exists, try dual-track recovery once.
        if (base == null) {
            val resolved = resolveCoordinate(tapArgs)
            if (resolved.error != null) {
                if (isAdGuardBlockedError(resolved.error) && semanticTarget.isBlank()) {
                    val dismiss = tryAutoDismissObstruction(avoidCoordinates = emptyList())
                    if (dismiss != null) {
                        return ToolResult.success(
                            "Blocked ad tap rerouted to '${dismiss.target}' at " +
                                "(${dismiss.decision.x}, ${dismiss.decision.y}) " +
                                "[dual-track ${dismiss.decision.source} conf=${"%.2f".format(dismiss.decision.confidence)}]",
                            mapOf(
                                "ad_auto_dismiss" to true,
                                "ad_auto_target" to dismiss.target,
                                "ad_auto_source" to dismiss.decision.source,
                                "ad_auto_confidence" to dismiss.decision.confidence,
                                "ad_auto_reason" to dismiss.decision.reason
                            )
                        )
                    }
                }
                // 已为剪映注入树内 ref（一键成片 / 相册 Tab）时，禁止再双轨兜底
                if (semanticTarget.isNotBlank() && dualDecision == null && !capcutTreeRefForced) {
                    dualDecision = dualTrackEngine.resolveTapTarget(
                        target = semanticTarget,
                        preferVisual = true
                    )
                    if (dualDecision != null) {
                        base = ResolvedCoordinate(
                            dualDecision.x,
                            dualDecision.y,
                            dualDecision.fusedNodeText ?: semanticTarget
                        )
                    } else {
                        return ToolResult.error("${resolved.error}；双轨兜底未命中，请补充 target 或重新 snapshot。")
                    }
                } else {
                    return ToolResult.error(
                        if (capcutTreeRefForced) {
                            "${resolved.error}（已使用剪映树内 ref 优先路径，请勿依赖双轨；请先重新 snapshot 再 act。）"
                        } else {
                            resolved.error
                        }
                    )
                }
            } else {
                base = resolved.coordinate
            }
        }

        val baseCoord = base ?: return ToolResult.error("Cannot resolve target. Provide ref/coordinate or semantic target.")
        val lowConfidenceDualTrack = dualDecision?.takeIf { it.confidence < DUAL_TRACK_MIN_CONFIDENCE }
        val ref = tapArgs["ref"] as? String
        val dm = context.resources.displayMetrics
        val screenWidth = dm.widthPixels
        val screenHeight = dm.heightPixels
        val tapPoint = if (!ref.isNullOrBlank()) {
            refManager.resolveRefForTap(ref, screenWidth, screenHeight)?.let { (ax, ay) ->
                ResolvedCoordinate(ax, ay, baseCoord.label)
            } ?: baseCoord
        } else {
            baseCoord
        }
        val (x, y, label) = tapPoint
        val adaptiveRedirected = (x != baseCoord.x || y != baseCoord.y)
        val tapAdjustTag = refManager.takeTapAdjustTag()

        return try {
            val ok = AccessibilityProxy.tap(x, y)
            if (!ok) {
                ToolResult.error("Tap failed via accessibility service at ($x, $y)")
            } else {
                delay(POST_ACTION_DELAY_MS)
                val dualTrackHint = dualDecision?.let {
                    " [dual-track ${it.source} conf=${"%.2f".format(it.confidence)}]"
                }.orEmpty()
                val lowConfidenceHint = lowConfidenceDualTrack?.let {
                    " [warn low-confidence-allowed threshold=${"%.2f".format(DUAL_TRACK_MIN_CONFIDENCE)}]"
                }.orEmpty()
                val metadata = mutableMapOf<String, Any?>()
                if (capcutYijianRefForced) {
                    metadata["capcut_yijianchengpian_ref_forced"] = true
                }
                if (capcutPickerTabRefForced) {
                    metadata["capcut_picker_tab_ref_forced"] = true
                }
                if (feishuSendRefForced) {
                    metadata["feishu_send_ref_forced"] = true
                    // 供上层循环做“发送后验收”约束，避免只点了发送就当完成。
                    metadata["requires_post_send_snapshot_verify"] = true
                }
                if (dualDecision != null) {
                    metadata["dual_track_source"] = dualDecision.source
                    metadata["dual_track_confidence"] = dualDecision.confidence
                    metadata["dual_track_reason"] = dualDecision.reason
                }
                if (lowConfidenceDualTrack != null) {
                    metadata["dual_track_low_confidence_allowed"] = true
                    metadata["dual_track_min_confidence_threshold"] = DUAL_TRACK_MIN_CONFIDENCE
                }

                var retryHint = ""
                if (retryEnabled) {
                    val afterTapNodes = safeDumpViewTreeForRetry()
                    val uiUnchanged = beforeTapNodes.isNotEmpty() &&
                        afterTapNodes.isNotEmpty() &&
                        !dualTrackEngine.hasUiChanged(beforeTapNodes, afterTapNodes)
                    metadata["retry_ui_unchanged"] = uiUnchanged

                    if (uiUnchanged) {
                        val retryTarget = semanticTarget.ifBlank { label?.trim().orEmpty() }
                        if (retryTarget.isNotBlank()) {
                            val retryDecision = dualTrackEngine.resolveTapTarget(
                                target = retryTarget,
                                preferVisual = true,
                                avoidCoordinates = listOf(Pair(x, y))
                            )
                            if (retryDecision != null) {
                                val delta = euclideanDistance(x, y, retryDecision.x, retryDecision.y)
                                metadata["retry_dual_source"] = retryDecision.source
                                metadata["retry_dual_confidence"] = retryDecision.confidence
                                metadata["retry_dual_reason"] = retryDecision.reason
                                metadata["retry_dual_delta_px"] = delta

                                if (retryDecision.confidence >= DUAL_TRACK_MIN_CONFIDENCE &&
                                    delta >= RETRY_SECOND_TAP_MIN_DISTANCE_PX) {
                                    val retryOk = AccessibilityProxy.tap(retryDecision.x, retryDecision.y)
                                    if (retryOk) {
                                        delay(POST_ACTION_DELAY_MS)
                                        retryHint =
                                            " [retry-vlm source=${retryDecision.source} conf=${"%.2f".format(retryDecision.confidence)}]"
                                        metadata["retry_executed"] = true
                                        metadata["retry_success"] = true
                                    } else {
                                        metadata["retry_executed"] = true
                                        metadata["retry_success"] = false
                                    }
                                } else {
                                    metadata["retry_executed"] = false
                                    metadata["retry_skipped_reason"] =
                                        "low_conf_or_same_point(conf=${"%.2f".format(retryDecision.confidence)}, delta=${"%.1f".format(delta)})"
                                }
                            } else {
                                metadata["retry_executed"] = false
                                metadata["retry_skipped_reason"] = "retry_dual_not_found"
                            }
                        } else {
                            val dismiss = tryAutoDismissObstruction(
                                avoidCoordinates = listOf(Pair(x, y))
                            )
                            if (dismiss != null) {
                                retryHint =
                                    " [retry-auto-dismiss target=${dismiss.target} source=${dismiss.decision.source} conf=${"%.2f".format(dismiss.decision.confidence)}]"
                                metadata["retry_executed"] = true
                                metadata["retry_success"] = true
                                metadata["retry_auto_dismiss"] = true
                                metadata["retry_auto_target"] = dismiss.target
                                metadata["retry_auto_source"] = dismiss.decision.source
                                metadata["retry_auto_confidence"] = dismiss.decision.confidence
                                metadata["retry_auto_reason"] = dismiss.decision.reason
                            } else {
                                metadata["retry_executed"] = false
                                metadata["retry_skipped_reason"] = "empty_retry_target_and_auto_dismiss_failed"
                            }
                        }
                    }
                }

                val resultText = buildString {
                    append("Tapped")
                    label?.let { append(" '").append(it).append("'") }
                    append(" at (").append(x).append(", ").append(y).append(")")
                    if (adaptiveRedirected) {
                        when (tapAdjustTag) {
                            "capcut_pair" -> append(" [capcut→同格link勾选]")
                            "capcut_corner" -> append(" [capcut→圈坐标校正]")
                            "settings_switch" -> append(" [adaptive-switch-target]")
                            else -> append(" [tap坐标已校正]")
                        }
                    }
                    append(dualTrackHint)
                    append(lowConfidenceHint)
                    append(retryHint)
                    when {
                        feishuSendRefForced ->
                            append(" [飞书：已强制发送按钮 ref，避免误点右下角非发送图标；请立即观察确认消息已发出]")
                        capcutYijianRefForced ->
                            append(" [剪映：树内「一键成片」ref 优先，未用双轨盲点]")
                        capcutPickerTabRefForced ->
                            append(" [剪映：相册顶部「${semanticTarget.trim()}」Tab 已用语义树 ref，未用双轨]")
                    }
                }
                ToolResult.success(resultText, metadata)
            }
        } catch (e: Exception) {
            ToolResult.error("Tap failed: ${e.message}")
        }
    }

    private suspend fun executeType(args: Map<String, Any?>): ToolResult {
        val text = args["text"] as? String ?: return ToolResult.error("Missing 'text' for kind=type")
        val hasNonAscii = text.any { it.code > 127 }
        // If ref provided, focus target through accessibility before typing.
        val resolved = resolveCoordinate(args)
        if (resolved.error != null) return ToolResult.error(resolved.error)
        if (resolved.coordinate != null) {
            val (x, y, _) = resolved.coordinate
            // 仅走无障碍聚焦路径，避免依赖特定输入法状态。
            val focused = AccessibilityProxy.tap(x, y)
            if (!focused) {
                return ToolResult.error("输入失败：无障碍服务未开启或目标输入框无法聚焦。")
            }
            delay(POST_ACTION_DELAY_MS)
        }

        // Type text: 先尝试 ACTION_SET_TEXT，失败后对 ASCII 使用 shell 兜底。
        try {
            var typed = false

            // 1) 主路径：AccessibilityProxy.inputText（通过 ACTION_SET_TEXT，支持中文）
            typed = AccessibilityProxy.inputText(text)
            if (typed) Log.d(TAG, "Typed via AccessibilityProxy (ACTION_SET_TEXT)")

            // 2) 兜底：shell input text（仅支持 ASCII，中文会丢失）
            if (!typed) {
                if (hasNonAscii) {
                    // 中文/emoji 等非 ASCII 文本禁止使用 shell 兜底，避免“执行成功但内容错误”
                    return ToolResult.error(
                        "中文输入失败：当前输入通道不支持非 ASCII 文本。请确认输入框已正确聚焦，并确保无障碍输入可用后重试。"
                    )
                }
                Log.w(TAG, "Falling back to shell 'input text' (ASCII only)")
                val escaped = text.replace("'", "'\\''")
                Runtime.getRuntime().exec(arrayOf("sh", "-c", "input text '$escaped'")).waitFor()
                typed = true
            }

            val refLabel = sanitizeRefOrSnapshotToken(args["ref"] as? String)
                .takeIf { it.isNotEmpty() }
                ?.let { refManager.getRefNode(it)?.text }
            return ToolResult.success("Typed '${text.take(100)}'${refLabel?.let { " into '$it'" } ?: ""}")
        } catch (e: Exception) {
            return ToolResult.error("Type failed: ${e.message}")
        }
    }

    private suspend fun executePress(args: Map<String, Any?>): ToolResult {
        val key = (args["key"] as? String) ?: (args["text"] as? String)
            ?: return ToolResult.error("Missing 'key' for kind=press")
        return executeKey(key)
    }

    private fun executeKey(key: String): ToolResult {
        return try {
            val ok = when (key.uppercase()) {
                "BACK" -> AccessibilityProxy.pressBack()
                "HOME" -> AccessibilityProxy.pressHome()
                else -> {
                    val keycode = mapKeyToKeycode(key)
                    Runtime.getRuntime().exec(arrayOf("sh", "-c", "input keyevent $keycode")).waitFor()
                    true
                }
            }
            if (ok) ToolResult.success("Pressed $key")
            else ToolResult.error("Key press failed for $key")
        } catch (e: Exception) {
            ToolResult.error("Key press failed: ${e.message}")
        }
    }

    private suspend fun executeLongPress(args: Map<String, Any?>): ToolResult {
        val resolved = resolveCoordinate(args)
        if (resolved.error != null) return ToolResult.error(resolved.error)
        val (x, y, label) = resolved.coordinate ?: return ToolResult.error("Cannot resolve target.")

        return try {
            val ok = AccessibilityProxy.longPress(x, y)
            if (!ok) {
                ToolResult.error("Long press failed via accessibility service at ($x, $y)")
            } else {
                delay(POST_ACTION_DELAY_MS)
                ToolResult.success("Long pressed${label?.let { " '$it'" } ?: ""} at ($x, $y)")
            }
        } catch (e: Exception) {
            ToolResult.error("Long press failed: ${e.message}")
        }
    }

    private suspend fun executeScroll(args: Map<String, Any?>): ToolResult {
        val direction = (args["direction"] as? String) ?: "down"
        val amountRequested = (args["amount"] as? Number)?.toDouble() ?: 1.0
        val amountEff = amountRequested.coerceIn(SCROLL_AMOUNT_MIN, SCROLL_AMOUNT_MAX)
        val dm = context.resources.displayMetrics
        val screenW = dm.widthPixels
        val screenH = dm.heightPixels
        val density = dm.density

        val resolved = resolveCoordinate(args)
        if (resolved.error != null) return ToolResult.error(resolved.error)
        val ref = (args["ref"] as? String)?.let { sanitizeRefOrSnapshotToken(it) }?.takeIf { it.isNotBlank() }
        val refNode = ref?.let { refManager.getRefNode(it) }

        // Avoid status-bar / notification pull and navigation edge: keep gestures inside a safe band,
        // and inside ref bounds when possible (time pickers often have tall nodes).
        val edgePad = max(8, (density * 6f).toInt())
        val bandTop = max(
            max((screenH * 0.12f).toInt(), (density * 56f).toInt()),
            140
        ).coerceAtMost(screenH - 120)
        val bandBottom = min(
            screenH - max(max((screenH * 0.08f).toInt(), (density * 52f).toInt()), 88),
            screenH - edgePad
        )
        var bandTopUse = bandTop
        var bandBottomUse = bandBottom
        if (bandBottomUse <= bandTopUse + 80) {
            bandTopUse = (screenH * 0.14f).toInt().coerceAtLeast(edgePad)
            bandBottomUse = (screenH * 0.86f).toInt().coerceAtMost(screenH - edgePad)
        }

        val (anchorX, anchorY, halfSpan) = when (direction) {
            "up", "down" -> {
                var top = bandTopUse
                var bottom = bandBottomUse
                var ax = resolved.coordinate?.x ?: (screenW / 2)
                var ay = resolved.coordinate?.y ?: ((top + bottom) / 2)
                if (refNode != null) {
                    val r = refNode.bounds
                    ax = r.centerX().coerceIn(edgePad, screenW - edgePad)
                    val intersectTop = max(r.top + edgePad, bandTopUse)
                    val intersectBottom = min(r.bottom - edgePad, bandBottomUse)
                    if (intersectBottom - intersectTop > 100) {
                        top = intersectTop
                        bottom = intersectBottom
                    }
                    ay = ((top + bottom) / 2)
                } else {
                    ax = ax.coerceIn(edgePad, screenW - edgePad)
                    ay = ay.coerceIn(top + 40, bottom - 40)
                }
                val maxHalf = min(ay - top - edgePad, bottom - ay - edgePad).coerceAtLeast(24)
                val regionH = (bottom - top).coerceAtLeast(1)
                val maxTotalSwipe = (2 * maxHalf).coerceAtLeast(1)
                val notchTotal = estimateVerticalWheelNotchTotalPx(regionH, refNode, density)
                val desiredTotalSwipe = (notchTotal * amountEff).coerceAtMost(maxTotalSwipe.toDouble())
                val span = (desiredTotalSwipe / 2.0).toInt()
                    .coerceIn(SCROLL_HALF_SPAN_MIN_PX, maxHalf)
                Triple(ax, ay, span)
            }
            "left", "right" -> {
                var left = max(max((screenW * 0.06f).toInt(), (density * 24f).toInt()), 24)
                var right = min(
                    screenW - max(max((screenW * 0.06f).toInt(), (density * 24f).toInt()), 24),
                    screenW - edgePad
                )
                var ax = resolved.coordinate?.x ?: (screenW / 2)
                var ay = resolved.coordinate?.y ?: (screenH / 2)
                if (refNode != null) {
                    val r = refNode.bounds
                    ay = r.centerY().coerceIn(edgePad, screenH - edgePad)
                    val intersectLeft = max(r.left + edgePad, left)
                    val intersectRight = min(r.right - edgePad, right)
                    if (intersectRight - intersectLeft > 80) {
                        left = intersectLeft
                        right = intersectRight
                    }
                    ax = ((left + right) / 2)
                } else {
                    ax = ax.coerceIn(left + 40, right - 40)
                    ay = ay.coerceIn(edgePad, screenH - edgePad)
                }
                val maxHalf = min(ax - left - edgePad, right - ax - edgePad).coerceAtLeast(24)
                val regionW = (right - left).coerceAtLeast(1)
                val maxTotalSwipe = (2 * maxHalf).coerceAtLeast(1)
                val notchTotal = estimateHorizontalWheelNotchTotalPx(regionW, refNode, density)
                val desiredTotalSwipe = (notchTotal * amountEff).coerceAtMost(maxTotalSwipe.toDouble())
                val span = (desiredTotalSwipe / 2.0).toInt()
                    .coerceIn(SCROLL_HALF_SPAN_MIN_PX, maxHalf)
                Triple(ax, ay, span)
            }
            else -> return ToolResult.error("Invalid direction: $direction")
        }

        val raw = when (direction) {
            "down" -> listOf(anchorX, anchorY + halfSpan, anchorX, anchorY - halfSpan)
            "up" -> listOf(anchorX, anchorY - halfSpan, anchorX, anchorY + halfSpan)
            "left" -> listOf(anchorX - halfSpan, anchorY, anchorX + halfSpan, anchorY)
            "right" -> listOf(anchorX + halfSpan, anchorY, anchorX - halfSpan, anchorY)
            else -> return ToolResult.error("Invalid direction: $direction")
        }
        val sx = raw[0].coerceIn(edgePad, screenW - edgePad)
        val sy = raw[1].coerceIn(bandTopUse, bandBottomUse)
        val ex = raw[2].coerceIn(edgePad, screenW - edgePad)
        val ey = raw[3].coerceIn(bandTopUse, bandBottomUse)

        if (sx == ex && sy == ey) {
            return ToolResult.error("Scroll distance too small after bounds clamp. Try a smaller/larger amount or refresh snapshot.")
        }

        val pxPrimary = when (direction) {
            "up", "down" -> abs(ey - sy)
            else -> abs(ex - sx)
        }
        val amountNote = if (abs(amountRequested - amountEff) > 1e-6) {
            " requested=${"%.2f".format(amountRequested)}→${"%.2f".format(amountEff)}"
        } else {
            ""
        }
        val durationMs = (340L + pxPrimary / 4).coerceIn(320L, 680L)

        return try {
            val ok = AccessibilityProxy.swipe(sx, sy, ex, ey, durationMs)
            if (!ok) {
                ToolResult.error("Scroll failed via accessibility service")
            } else {
                delay(POST_SCROLL_DELAY_MS)
                ToolResult.success(
                    "Scrolled $direction (amount=${"%.2f".format(amountEff)}$amountNote px=$pxPrimary dur=${durationMs}ms) from ($sx,$sy) to ($ex,$ey)"
                )
            }
        } catch (e: Exception) {
            ToolResult.error("Scroll failed: ${e.message}")
        }
    }

    private suspend fun executeSwipe(args: Map<String, Any?>): ToolResult {
        @Suppress("UNCHECKED_CAST")
        val startCoord = args["start_coordinate"] as? List<Number>
        @Suppress("UNCHECKED_CAST")
        val endCoord = args["coordinate"] as? List<Number>

        if (startCoord == null || endCoord == null || startCoord.size < 2 || endCoord.size < 2) {
            return ToolResult.error("Swipe requires start_coordinate and coordinate (both [x, y])")
        }

        return try {
            val ok = AccessibilityProxy.swipe(
                startCoord[0].toInt(),
                startCoord[1].toInt(),
                endCoord[0].toInt(),
                endCoord[1].toInt(),
                300
            )
            if (ok) {
                ToolResult.success("Swiped from (${startCoord[0]}, ${startCoord[1]}) to (${endCoord[0]}, ${endCoord[1]})")
            } else {
                ToolResult.error("Swipe failed via accessibility service")
            }
        } catch (e: Exception) {
            ToolResult.error("Swipe failed: ${e.message}")
        }
    }

    private suspend fun executeWait(args: Map<String, Any?>): ToolResult {
        val requested = ((args["timeMs"] as? Number)?.toLong()) ?: 1000L
        val actual = requested.coerceIn(100L, WAIT_MAX_MS)
        delay(actual)
        val note = if (actual != requested) " (requested ${requested}ms, applied cap ${actual}ms)" else ""
        return ToolResult.success("Waited ${actual}ms$note")
    }

    private fun parseRefsList(raw: Any?): List<String> {
        val rawList = when (raw) {
            is List<*> -> raw.mapNotNull { it?.toString()?.trim()?.takeIf(String::isNotEmpty) }
            is String -> raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            else -> emptyList()
        }
        return rawList.map { sanitizeRefOrSnapshotToken(it) }.filter { it.isNotEmpty() }
    }

    /**
     * Fire several taps from the **same** snapshot in one tool call to save LLM iterations.
     * Coordinates are resolved upfront; UI must not invalidate ref positions mid-batch (avoid scrolling between taps).
     */
    private suspend fun executeMultiTap(args: Map<String, Any?>): ToolResult {
        // 保序去重：重复 ref 连点会取消勾选，distinct() 保留首次出现顺序
        val refs = parseRefsList(args["refs"]).distinct()
        if (refs.isEmpty()) {
            return ToolResult.error(
                "multi_tap 需要 refs 数组（须与**当前** snapshot 一致，从左到右勾选圈 ref；编号无奇偶规律）。请先 snapshot 再 multi_tap。"
            )
        }
        if (refs.size > MULTI_TAP_MAX) {
            return ToolResult.error(
                "multi_tap 单次最多 $MULTI_TAP_MAX 个 ref；请先选一批 → snapshot → 再 multi_tap 下一批。"
            )
        }

        if (refManager.isStale(REF_MAX_AGE_MS)) {
            return ToolResult.error(
                "当前 snapshot 已过期（>${REF_MAX_AGE_MS}ms）。请先 device(action='snapshot') 再 multi_tap。"
            )
        }

        val dmGrid = context.resources.displayMetrics
        val ignoreAd = (args["ignore_ad_guard"] as? Boolean) == true
        val coords = mutableListOf<MultiTapCoord>()

        for (ref in refs) {
            val node = refManager.getRefNode(ref)
                ?: return ToolResult.error("Ref '$ref' 不在当前 snapshot。请重新 snapshot。")
            if (!ignoreAd && AdUiGuard.isLikelyAdvertisement(node)) {
                return ToolResult.error(
                    "Ref '$ref' 疑似广告区域，已中止 multi_tap。请换 ref 或 ignore_ad_guard=true（慎用）。"
                )
            }
            refForegroundPackageMismatchError(ref, node)?.let { return ToolResult.error(it) }
            val pt = refManager.resolveRefForTap(ref, dmGrid.widthPixels, dmGrid.heightPixels)
                ?: refManager.resolveRef(ref)
                ?: return ToolResult.error("无法解析 ref '$ref' 的坐标。")
            coords.add(MultiTapCoord(ref, pt.first, pt.second, node.text))
        }

        val summaries = mutableListOf<String>()
        for (i in coords.indices) {
            val c = coords[i]
            val ok = AccessibilityProxy.tap(c.x, c.y)
            if (!ok) {
                return ToolResult.error("multi_tap 第 ${i + 1}/${coords.size} 次失败: ref=${c.ref} at (${c.x},${c.y})")
            }
            summaries.add(
                buildString {
                    append(c.ref).append("@(").append(c.x).append(",").append(c.y).append(")")
                    val lbl = c.label?.trim()?.takeIf { t -> t.isNotEmpty() }
                    if (lbl != null) {
                        append(" '").append(lbl.take(40)).append("'")
                    }
                }
            )
            if (i < coords.lastIndex) delay(MULTI_TAP_DELAY_MS)
        }
        delay(POST_ACTION_DELAY_MS)

        return ToolResult.success(
            "multi_tap ${coords.size} taps: ${summaries.joinToString("; ")}",
            mapOf("tap_count" to coords.size, "refs" to refs)
        )
    }

    // ==================== open ====================

    private suspend fun executeOpen(args: Map<String, Any?>): ToolResult {
        val uriRaw = (args["uri"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
            ?: (args["url"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
        val rawPackageName = (args["package_name"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
        val className = (args["class_name"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
        val useRoot = args["use_root"] as? Boolean ?: false
        val (normalizedPackageName, aliasNote) = normalizeKnownPackageAlias(rawPackageName)

        return try {
            // 显式启动指定 Activity：同时提供 package_name 和 class_name
            if (!normalizedPackageName.isNullOrBlank() && !className.isNullOrBlank()) {
                return if (useRoot) {
                    executeOpenActivityViaRoot(normalizedPackageName, className, aliasNote)
                } else {
                    executeOpenExplicitActivity(normalizedPackageName, className, aliasNote)
                }
            }

            if (!uriRaw.isNullOrBlank()) {
                val uri = Uri.parse(uriRaw)
                val explicitPackage = when {
                    !normalizedPackageName.isNullOrBlank() -> normalizedPackageName
                    uri.scheme.equals("videocut", ignoreCase = true) -> "com.lemon.lv"
                    else -> null
                }
                val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    if (explicitPackage != null) setPackage(explicitPackage)
                }
                try {
                    context.startActivity(intent)
                } catch (_: ActivityNotFoundException) {
                    val isHttpLike = uri.scheme.equals("http", true) || uri.scheme.equals("https", true)
                    if (explicitPackage != null && isHttpLike) {
                        Log.w(TAG, "open: $explicitPackage cannot handle $uriRaw, retrying without package constraint (browser redirect)")
                        val fallbackIntent = Intent(Intent.ACTION_VIEW, uri).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(fallbackIntent)
                        kotlinx.coroutines.delay(POST_OPEN_DELAY_MS)
                        return appendUiVerifyHintAfterOpen(
                            ToolResult.success(
                                "Opened via browser redirect: $uriRaw (package=$explicitPackage 无法直接处理，已通过浏览器重定向打开)" +
                                    (aliasNote?.let { " [$it]" } ?: ""),
                                mapOf("uri" to uriRaw, "target_package" to "browser_redirect", "original_package" to explicitPackage)
                            )
                        )
                    }
                    throw ActivityNotFoundException()
                }
                kotlinx.coroutines.delay(POST_OPEN_DELAY_MS)
                val target = intent.`package` ?: "implicit"
                return appendUiVerifyHintAfterOpen(
                    ToolResult.success(
                        "Opened deep link: $uriRaw (targetPackage=$target)" + (aliasNote?.let { " [$it]" } ?: ""),
                        mapOf("uri" to uriRaw, "target_package" to target)
                    )
                )
            }

            if (normalizedPackageName.isNullOrBlank()) {
                return ToolResult.error("Provide package_name to launch an app, or uri/url for a deep link (e.g. 剪映 videocut://… + package_name com.lemon.lv).")
            }

            val launchCandidates = buildList {
                add(normalizedPackageName)
                if (!rawPackageName.isNullOrBlank() && rawPackageName != normalizedPackageName) {
                    // 别名失败时回退原始包名，兼容同类国际版安装场景。
                    add(rawPackageName)
                }
            }
            val resolvedPackage = launchCandidates.firstOrNull { pkg ->
                context.packageManager.getLaunchIntentForPackage(pkg) != null
            }
            if (resolvedPackage != null) {
                val launchIntent = context.packageManager.getLaunchIntentForPackage(resolvedPackage)
                    ?: return ToolResult.error("App not found: $resolvedPackage")
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launchIntent)
                val appName = try {
                    context.packageManager.getApplicationLabel(
                        context.packageManager.getApplicationInfo(resolvedPackage, 0)
                    ).toString()
                } catch (_: Exception) { resolvedPackage }
                kotlinx.coroutines.delay(POST_OPEN_DELAY_MS)
                val fallbackNote = if (!rawPackageName.isNullOrBlank() && resolvedPackage != normalizedPackageName) {
                    " [fallback to raw package=$resolvedPackage]"
                } else {
                    ""
                }
                ToolResult.success(
                    "Opened $appName ($resolvedPackage)" +
                        (aliasNote?.let { " [$it]" } ?: "") +
                        fallbackNote
                )
            } else {
                ToolResult.error("App not found: $normalizedPackageName" + (rawPackageName?.let { " (raw=$it)" } ?: ""))
            }
        } catch (_: ActivityNotFoundException) {
            val pkg = normalizedPackageName ?: uriRaw?.let { Uri.parse(it).scheme?.takeIf { s -> s.equals("videocut", true) }?.let { "com.lemon.lv" } }
            val hint = if (pkg != null) "package=$pkg 无法处理 uri=$uriRaw" else "无 Activity 可处理 uri=$uriRaw"
            ToolResult.error("ActivityNotFound: $hint。请检查 URI 是否为可直接解析的 deep link（HTTP 短链需浏览器重定向，不能直接指定 package_name）。")
        } catch (e: Exception) {
            ToolResult.error("Failed to open: ${e.message}")
        }
    }

    /**
     * 通过 root shell 执行 am start 启动指定 Activity。
     * 用于未导出的 Activity 或系统 App 内部页面，普通 Intent 无法启动的场景。
     */
    private suspend fun executeOpenActivityViaRoot(
        packageName: String,
        className: String,
        aliasNote: String?
    ): ToolResult {
        if (!com.shijing.xomniclaw.deeplink.RootShellExecutor.hasRootAccess()) {
            return ToolResult.error("Root 不可用，无法通过 root shell 启动 Activity。请先获取 root 权限。")
        }

        val amCommand = "am start -n $packageName/$className"
        val result = com.shijing.xomniclaw.deeplink.RootShellExecutor.executeAmCommand(amCommand)

        return if (result.success) {
            kotlinx.coroutines.delay(POST_OPEN_DELAY_MS)
            val appName = try {
                context.packageManager.getApplicationLabel(
                    context.packageManager.getApplicationInfo(packageName, 0)
                ).toString()
            } catch (_: Exception) { packageName }

            val shortClassName = className.substringAfterLast(".")
            appendUiVerifyHintAfterOpen(
                ToolResult.success(
                    "Opened $appName / $shortClassName via root shell" +
                        (aliasNote?.let { " [$it]" } ?: ""),
                    mapOf(
                        "package_name" to packageName,
                        "class_name" to className,
                        "app_name" to appName,
                        "method" to "root_shell"
                    )
                )
            )
        } else {
            ToolResult.error("Root shell 启动失败: ${result.error.ifBlank { result.output }}")
        }
    }

    /**
     * 通过显式 Intent 启动指定 Activity。
     * 用于没有 deeplink 但需要直达特定页面的场景（如系统设置页、第三方 App 内部页面）。
     * 注意：未导出的 Activity 会因权限问题启动失败，此时需使用 use_root=true。
     */
    private suspend fun executeOpenExplicitActivity(
        packageName: String,
        className: String,
        aliasNote: String?
    ): ToolResult {
        return try {
            val componentName = android.content.ComponentName(packageName, className)
            val intent = Intent().apply {
                component = componentName
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            kotlinx.coroutines.delay(POST_OPEN_DELAY_MS)

            val appName = try {
                context.packageManager.getApplicationLabel(
                    context.packageManager.getApplicationInfo(packageName, 0)
                ).toString()
            } catch (_: Exception) { packageName }

            val shortClassName = className.substringAfterLast(".")
            appendUiVerifyHintAfterOpen(
                ToolResult.success(
                    "Opened $appName / $shortClassName via explicit Intent" +
                        (aliasNote?.let { " [$it]" } ?: ""),
                    mapOf(
                        "package_name" to packageName,
                        "class_name" to className,
                        "app_name" to appName
                    )
                )
            )
        } catch (_: ActivityNotFoundException) {
            ToolResult.error("ActivityNotFound: $packageName/$className 不存在或未导出。请检查 Activity 是否正确。")
        } catch (e: SecurityException) {
            ToolResult.error("SecurityException: $packageName/$className 无法启动（可能未导出或需要权限）: ${e.message}")
        } catch (e: Exception) {
            ToolResult.error("Failed to open $packageName/$className: ${e.message}")
        }
    }

    // ==================== clipboard ====================

    private suspend fun executeClipboard(): ToolResult {
        return try {
            val text = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.primaryClip?.getItemAt(0)?.let { item ->
                    // coerceToText handles HTML clips, URI clips, etc.
                    item.coerceToText(context)?.toString()
                }
            }
            if (text.isNullOrBlank()) {
                ToolResult.success(
                    "[clipboard] empty — no text on clipboard.",
                    mapOf("clipboard_text" to "")
                )
            } else {
                ToolResult.success(text, mapOf("clipboard_text" to text))
            }
        } catch (e: Exception) {
            Log.w(TAG, "clipboard read failed", e)
            ToolResult.error("Failed to read clipboard: ${e.message}")
        }
    }

    // ==================== status ====================

    private fun executeStatus(): ToolResult {
        val proxy = AccessibilityProxy
        val connected = proxy.isConnected.value == true
        val refCount = refManager.getRefCount()
        val stale = refManager.isStale()

        return ToolResult.success(buildString {
            appendLine("Device status:")
            appendLine("  Accessibility: ${if (connected) "✅ connected" else "❌ not connected"}")
            appendLine("  Cached refs: $refCount${if (stale) " (stale)" else ""}")
            appendLine("  Last capture age: ${refManager.getSnapshotAgeMs()}ms${if (stale) " — need snapshot before next act" else ""}")
            appendLine("  Screen: ${context.resources.displayMetrics.widthPixels}x${context.resources.displayMetrics.heightPixels}")
        })
    }

    // ==================== helpers ====================

    private data class ResolvedCoordinate(val x: Int, val y: Int, val label: String?)
    private data class CoordinateResolveResult(val coordinate: ResolvedCoordinate?, val error: String? = null)
    private data class AutoDismissAttempt(val target: String, val decision: DualTrackTapDecision)
    private data class RefMatch(val ref: String, val text: String?, val role: String, val distancePx: Int)
    private data class SnapshotRefreshResult(
        val success: Boolean,
        val snapshotId: String? = null,
        val packageName: String? = null,
        val refCount: Int = 0,
        val error: String? = null
    )

    private suspend fun safeDumpViewTreeForRetry(): List<ViewNode> {
        return try {
            AccessibilityProxy.dumpViewTree(useCache = false)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun isAdGuardBlockedError(error: String?): Boolean {
        val msg = error.orEmpty()
        return msg.contains("疑似广告") || msg.contains("广告/推广区域")
    }

    private fun looksLikeAdvertisementScreen(nodes: List<ViewNode>): Boolean {
        if (nodes.isEmpty()) return false
        var score = 0
        for (node in nodes.take(260)) {
            val textBlob = "${node.text.orEmpty()} ${node.contentDesc.orEmpty()}".lowercase()
            val classOrIdBlob = "${node.className.orEmpty()} ${node.resourceId.orEmpty()}".lowercase()

            if (AD_SCREEN_TEXT_HINTS.any { textBlob.contains(it.lowercase()) }) {
                score += 1
            }
            if (AD_SCREEN_CLASS_OR_ID_HINTS.any { hint -> classOrIdBlob.contains(hint) }) {
                score += 1
            }
            if (score >= 2) return true
        }
        return false
    }

    /**
     * 防误触：当 ref 是无文案可点击容器，且其内部文案命中「查看提示/提示/help」时默认拦截。
     * 避免题目场景把「查看提示」误当「确定」点击。
     */
    private fun hintButtonGuardError(
        args: Map<String, Any?>,
        ref: String,
        node: RefNode
    ): String? {
        val ignoreHintGuard = (args["ignore_hint_guard"] as? Boolean) == true
        if (ignoreHintGuard) return null
        if (!node.clickable) return null

        val labels = buildList {
            node.text?.trim()?.takeIf(String::isNotEmpty)?.let { add(it) }
            addAll(refManager.collectTextsInsideRefBounds(ref, maxCount = 4))
        }
        if (labels.isEmpty()) return null
        val hit = labels.firstOrNull { label ->
            val lower = label.lowercase()
            HINT_BUTTON_KEYWORDS.any { kw -> lower.contains(kw.lowercase()) }
        } ?: return null

        return "Ref '$ref' 疑似「提示/帮助」按钮（命中文案：'$hit'），已拦截以避免误触。请先重新 snapshot 并改选「确定/提交/下一题」对应 ref；确需点提示可传 ignore_hint_guard=true。"
    }

    private suspend fun tryAutoDismissObstruction(
        avoidCoordinates: List<Pair<Int, Int>>
    ): AutoDismissAttempt? {
        for (target in AUTO_DISMISS_TARGETS) {
            val decision = dualTrackEngine.resolveTapTarget(
                target = target,
                preferVisual = true,
                avoidCoordinates = avoidCoordinates
            ) ?: continue

            if (decision.confidence < AUTO_DISMISS_MIN_CONFIDENCE) continue
            val tooCloseToAvoid = avoidCoordinates.any { (ax, ay) ->
                euclideanDistance(ax, ay, decision.x, decision.y) < RETRY_SECOND_TAP_MIN_DISTANCE_PX
            }
            if (tooCloseToAvoid) continue

            val ok = AccessibilityProxy.tap(decision.x, decision.y)
            if (ok) {
                delay(POST_ACTION_DELAY_MS)
                return AutoDismissAttempt(target = target, decision = decision)
            }
        }
        return null
    }

    private fun euclideanDistance(x1: Int, y1: Int, x2: Int, y2: Int): Double {
        val dx = (x1 - x2).toDouble()
        val dy = (y1 - y2).toDouble()
        return sqrt(dx * dx + dy * dy)
    }

    private fun findNearestActionableRef(nodes: List<RefNode>, x: Int, y: Int): RefMatch? {
        val candidates = nodes.filter { it.clickable || it.focusable || it.editable }
        if (candidates.isEmpty()) return null

        val nearest = candidates.minByOrNull { node ->
            val dx = (node.bounds.centerX() - x).toDouble()
            val dy = (node.bounds.centerY() - y).toDouble()
            dx * dx + dy * dy
        } ?: return null

        val distance = euclideanDistance(
            nearest.bounds.centerX(),
            nearest.bounds.centerY(),
            x,
            y
        ).toInt()

        return RefMatch(
            ref = nearest.ref,
            text = nearest.text,
            role = nearest.role,
            distancePx = distance
        )
    }

    private fun hasExplicitTapPointer(args: Map<String, Any?>): Boolean {
        val hasRef = sanitizeRefOrSnapshotToken(args["ref"] as? String).isNotEmpty()
        val coord = args["coordinate"] as? List<*>
        val hasCoord = coord != null && coord.size >= 2
        val hasXY = args["x"] is Number || args["y"] is Number
        return hasRef || hasCoord || hasXY
    }

    private fun deriveTapSemanticTarget(args: Map<String, Any?>): String {
        val target = (args["target"] as? String)?.trim().orEmpty()
        if (target.isNotBlank()) return target

        val ref = sanitizeRefOrSnapshotToken(args["ref"] as? String)
        if (ref.isNotBlank()) {
            val refLabel = refManager.getRefNode(ref)?.text?.trim().orEmpty()
            if (refLabel.isNotBlank()) return refLabel
        }
        return ""
    }

    /**
     * 包名硬校验已禁用。
     *
     * 背景：
     * - 在应用启动/切换动画期间，系统可能短暂上报 launcher 作为前台包名；
     * - 这会导致 ref 与 current package 误判不一致，从而阻断本应可执行的点击/输入。
     *
     * 现策略：
     * - 不再基于 package mismatch 直接拒绝；
     * - 仍保留 ref 过期、广告防护、hint 防误触等其他安全校验。
     * @return 错误文案，null 表示通过
     */
    private suspend fun refForegroundPackageMismatchError(ref: String, node: RefNode): String? {
        // 保留参数名是为了兼容现有调用点；这里显式标记未使用，避免误解。
        @Suppress("UNUSED_PARAMETER")
        val _keepSignature = ref to node
        return null
    }

    /**
     * 页面包名变化时做一次轻量自愈刷新，避免用户必须手动先 snapshot。
     * 仅更新 ref 缓存，不拼装完整 snapshot 文本。
     */
    private suspend fun refreshRefCacheFromCurrentUi(): SnapshotRefreshResult {
        val viewNodes = try {
            AccessibilityProxy.dumpViewTree(useCache = false)
        } catch (e: Exception) {
            Log.w(TAG, "Auto refresh refs failed: dump view tree error", e)
            return SnapshotRefreshResult(success = false, error = "dump_view_tree_failed:${e.message}")
        }
        if (viewNodes.isEmpty()) {
            return SnapshotRefreshResult(success = false, error = "view_tree_empty")
        }

        return try {
            val nodes = SnapshotBuilder.buildFromViewNodes(viewNodes)
            val topPackages = collectTopPackages(nodes)
            val currentPackage = try { AccessibilityProxy.getCurrentPackageName() } catch (_: Exception) { "" }
            val packageForHeader = currentPackage.ifBlank { topPackages.firstOrNull()?.first ?: "unknown" }
            val snapshotId = refManager.updateRefs(
                nodes,
                packageForHeader.takeIf { it.isNotBlank() && it != "unknown" }
            )
            SnapshotRefreshResult(
                success = true,
                snapshotId = snapshotId,
                packageName = packageForHeader,
                refCount = nodes.size
            )
        } catch (e: Exception) {
            Log.w(TAG, "Auto refresh refs failed: build/update refs error", e)
            SnapshotRefreshResult(success = false, error = "build_or_update_refs_failed:${e.message}")
        }
    }

    /**
     * 同 ref 自动复用的安全条件：角色一致 + 文案一致（若原文案存在）+ 几何位置接近。
     * 防止页面已切换时把旧 ref 误映射到不相干节点。
     */
    private fun isSafeToReuseSameRef(oldNode: RefNode, newNode: RefNode): Boolean {
        if (!oldNode.role.equals(newNode.role, ignoreCase = true)) return false

        val oldText = oldNode.text?.trim().orEmpty()
        val newText = newNode.text?.trim().orEmpty()
        if (oldText.isNotBlank() && oldText != newText) return false

        val oldCx = oldNode.bounds.centerX()
        val oldCy = oldNode.bounds.centerY()
        val newCx = newNode.bounds.centerX()
        val newCy = newNode.bounds.centerY()
        val dx = abs(oldCx - newCx)
        val dy = abs(oldCy - newCy)
        val maxGapX = max(oldNode.bounds.width(), newNode.bounds.width()) + 40
        val maxGapY = max(oldNode.bounds.height(), newNode.bounds.height()) + 40
        return dx <= maxGapX && dy <= maxGapY
    }

    /**
     * 若同 ref 无法安全复用，则尝试按“唯一文本”重绑定到新页面上的 ref。
     * 只在文案精确匹配且语义角色一致时放行，避免误点。
     */
    private fun tryRebindRefByExactText(oldNode: RefNode, currentPkg: String): Pair<String, RefNode>? {
        val oldText = oldNode.text?.trim().orEmpty()
        if (oldText.isBlank()) return null

        val reboundRef = refManager.findRefForLabelTextContaining(oldText, currentPkg) ?: return null
        val reboundNode = refManager.getRefNode(reboundRef) ?: return null
        val reboundText = reboundNode.text?.trim().orEmpty()
        if (reboundText != oldText) return null
        if (!oldNode.role.equals(reboundNode.role, ignoreCase = true)) return null
        return reboundRef to reboundNode
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun resolveCoordinate(args: Map<String, Any?>): CoordinateResolveResult {
        // Priority 1: ref
        val ref = (args["ref"] as? String)?.let { sanitizeRefOrSnapshotToken(it) }?.takeIf { it.isNotBlank() }
        if (ref != null) {
            // 已不再根据 snapshot_id / strict 与模型传参做比对；ref 是否可用仅依赖最近一次 snapshot 与下方过期检测。

            val ageMs = refManager.getSnapshotAgeMs()
            if (refManager.isStale(REF_MAX_AGE_MS)) {
                return CoordinateResolveResult(
                    coordinate = null,
                    error = "Ref '$ref' 已过期（${ageMs}ms，阈值=${REF_MAX_AGE_MS}ms）。请先调用 device(action='snapshot') 获取最新页面元素后再操作。"
                )
            }

            val originalNode = refManager.getRefNode(ref)
                ?: return CoordinateResolveResult(
                    coordinate = null,
                    error = "Ref '$ref' 不存在于最近一次 snapshot。请先 device(action='snapshot') 再操作。"
                )

            var effectiveRef = ref
            var effectiveNode = originalNode

            // 先做包名一致性校验；不通过时尝试自动刷新一次并进行“安全重绑定”。
            val mismatchError = refForegroundPackageMismatchError(ref, originalNode)
            if (mismatchError != null) {
                val refresh = refreshRefCacheFromCurrentUi()
                if (!refresh.success) {
                    return CoordinateResolveResult(
                        coordinate = null,
                        error = "$mismatchError（自动刷新 snapshot 失败：${refresh.error}）"
                    )
                }

                val sameRefAfterRefresh = refManager.getRefNode(ref)
                val reusedSameRef =
                    sameRefAfterRefresh != null &&
                        refForegroundPackageMismatchError(ref, sameRefAfterRefresh) == null &&
                        isSafeToReuseSameRef(originalNode, sameRefAfterRefresh)
                if (reusedSameRef) {
                    effectiveRef = ref
                    effectiveNode = sameRefAfterRefresh!!
                } else {
                    val rebound = tryRebindRefByExactText(originalNode, refresh.packageName.orEmpty())
                    if (rebound != null && refForegroundPackageMismatchError(rebound.first, rebound.second) == null) {
                        effectiveRef = rebound.first
                        effectiveNode = rebound.second
                    } else {
                        return CoordinateResolveResult(
                            coordinate = null,
                            error = "$mismatchError（已自动刷新 snapshot=${refresh.snapshotId} package=${refresh.packageName} refs=${refresh.refCount}，但未找到安全可重用 ref，请基于最新 snapshot 重试。）"
                        )
                    }
                }
            }

            val ignoreAdGuard = (args["ignore_ad_guard"] as? Boolean) == true
            if (!ignoreAdGuard && AdUiGuard.isLikelyAdvertisement(effectiveNode)) {
                val hint = effectiveNode.text?.take(80)?.replace('\'', ' ') ?: ""
                return CoordinateResolveResult(
                    coordinate = null,
                    error = "Ref '$effectiveRef' 疑似广告/推广区域（text='$hint'），已拒绝操作。请优先选择「跳过/关闭/不感兴趣」等 ref，或关闭广告后重新 snapshot。确需操作可传 ignore_ad_guard=true（慎用）。"
                )
            }

            hintButtonGuardError(args, effectiveRef, effectiveNode)?.let { msg ->
                return CoordinateResolveResult(coordinate = null, error = msg)
            }

            val coord = refManager.resolveRef(effectiveRef)
            if (coord != null) {
                val label = effectiveNode.text
                return CoordinateResolveResult(ResolvedCoordinate(coord.first, coord.second, label))
            }
            Log.w(TAG, "Ref '$effectiveRef' not found in cache, trying coordinate fallback")
        }

        // Priority 2: coordinate
        val coordList = args["coordinate"]
        if (coordList is List<*> && coordList.size >= 2) {
            val x = (coordList[0] as? Number)?.toInt()
            val y = (coordList[1] as? Number)?.toInt()
            if (x != null && y != null) {
                return CoordinateResolveResult(ResolvedCoordinate(x, y, null))
            }
        }

        // Priority 3: x, y params
        val x = (args["x"] as? Number)?.toInt()
        val y = (args["y"] as? Number)?.toInt()
        if (x != null && y != null) {
            return CoordinateResolveResult(ResolvedCoordinate(x, y, null))
        }

        return CoordinateResolveResult(null, null)
    }

    private fun mapKeyToKeycode(key: String): String {
        return when (key.uppercase()) {
            "BACK" -> "KEYCODE_BACK"
            "HOME" -> "KEYCODE_HOME"
            "ENTER", "RETURN" -> "KEYCODE_ENTER"
            "TAB" -> "KEYCODE_TAB"
            "ESCAPE", "ESC" -> "KEYCODE_ESCAPE"
            "DELETE", "DEL" -> "KEYCODE_DEL"
            "VOLUME_UP" -> "KEYCODE_VOLUME_UP"
            "VOLUME_DOWN" -> "KEYCODE_VOLUME_DOWN"
            "POWER" -> "KEYCODE_POWER"
            "SPACE" -> "KEYCODE_SPACE"
            "MENU" -> "KEYCODE_MENU"
            "RECENT", "APP_SWITCH" -> "KEYCODE_APP_SWITCH"
            else -> "KEYCODE_$key"
        }
    }
}
