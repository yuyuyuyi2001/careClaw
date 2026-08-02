package com.shijing.xomniclaw.agent.tools.device

import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import com.shijing.xomniclaw.DeviceController
import com.shijing.xomniclaw.accessibility.AccessibilityProxy
import com.shijing.xomniclaw.accessibility.service.ViewNode
import com.shijing.xomniclaw.config.ConfigLoader
import com.shijing.xomniclaw.config.ProviderConfig
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.LinkedHashMap
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

data class DualTrackTapDecision(
    val x: Int,
    val y: Int,
    val source: String,
    val confidence: Double,
    val reason: String,
    val fusedNodeText: String? = null,
    val fusedResourceId: String? = null
)

private data class StructuredGrounding(
    val x: Int,
    val y: Int,
    val confidence: Double,
    val reason: String,
    val matchedNode: ViewNode? = null
)

private data class VisualGrounding(
    val x: Int,
    val y: Int,
    val confidence: Double,
    val reason: String,
    val origin: String = "on_demand"
)

private data class SemanticMapping(
    val targetNorm: String,
    val packageName: String,
    val screenSignature: String,
    val resourceId: String?,
    val textHint: String?,
    val className: String?,
    var x: Int,
    var y: Int,
    var hitCount: Int,
    var updatedAt: Long
)

private data class PrewarmCandidate(
    val label: String,
    val x: Int,
    val y: Int,
    val confidence: Double
)

private data class PrewarmResult(
    val screenSignature: String,
    val packageName: String,
    val createdAt: Long,
    val candidates: List<PrewarmCandidate>
)

/**
 * Dual-track perception decision engine:
 * - StructuredProvider (UI tree): fast + precise
 * - VisualProvider (VLM): semantic fallback for non-standard UI
 */
class DualTrackDecisionEngine(private val context: Context) {
    companion object {
        private const val TAG = "DualTrackDecision"
        private const val UI_HIGH_CONFIDENCE = 0.78
        private const val FUSION_RADIUS_RATIO = 0.10
        private const val FUSION_MIN_RADIUS_PX = 88
        private const val FUSION_MAX_RADIUS_PX = 240
        private const val MEMORY_RADIUS_PX = 160
        private const val MEMORY_TTL_MS = 12 * 60 * 60 * 1000L
        private const val MAX_MEMORY_ENTRIES = 220
        private const val PREWARM_CACHE_SIZE = 8
        private const val PREWARM_TTL_MS = 15_000L
        private const val PREWARM_MIN_INTERVAL_MS = 1_200L
        private const val AVOID_RADIUS_PX = 76
        private const val SCREENSHOT_JPEG_QUALITY = 86
        /** 与 `xomniclaw.json.default.txt` 中 vlm 默认模型 id 对齐 */
        private const val DEFAULT_VLM_MODEL_ID = "MiniMax-M2.7-highspeed"
        private val JSON_BLOCK_RE = Regex("\\{[\\s\\S]*?\\}")
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .build()
    private val prewarmScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val semanticMemory = ArrayDeque<SemanticMapping>(MAX_MEMORY_ENTRIES + 1)
    private val semanticMemoryLock = Any()
    private val prewarmCache = LinkedHashMap<String, PrewarmResult>(PREWARM_CACHE_SIZE + 1, 0.75f, true)
    private val prewarmLock = Any()
    @Volatile private var lastPrewarmScheduleMs: Long = 0L
    @Volatile private var lastPrewarmSignature: String = ""

    /**
     * Resolve semantic tap target using:
     * 1) semantic memory alignment
     * 2) structured UI grounding
     * 3) VLM compensation (prewarm cache first, then on-demand)
     */
    suspend fun resolveTapTarget(
        target: String,
        preferVisual: Boolean = false,
        avoidCoordinates: List<Pair<Int, Int>> = emptyList()
    ): DualTrackTapDecision? {
        val semanticTarget = target.trim()
        if (semanticTarget.isBlank()) return null

        val viewNodes = try {
            AccessibilityProxy.dumpViewTree(useCache = false)
        } catch (e: Exception) {
            Log.w(TAG, "dumpViewTree failed: ${e.message}")
            emptyList()
        }
        if (viewNodes.isEmpty()) return null

        val dm = context.resources.displayMetrics
        val screenW = dm.widthPixels
        val screenH = dm.heightPixels
        val currentPackage = dominantPackage(viewNodes)
        val screenSignature = computeScreenSignature(viewNodes, currentPackage)

        val structured = structuredGrounding(
            target = semanticTarget,
            nodes = viewNodes,
            screenW = screenW,
            screenH = screenH,
            currentPackage = currentPackage,
            screenSignature = screenSignature
        )

        val screenshotJpeg = captureCurrentScreenJpeg()
        val vlmProvider = loadVlmProviderConfig()
        val shouldRunVlm = preferVisual ||
            structured.confidence < UI_HIGH_CONFIDENCE ||
            hasSurfaceView(viewNodes) ||
            structured.reason.contains("no strong", ignoreCase = true)

        val visual = if (shouldRunVlm && screenshotJpeg != null && vlmProvider != null && hasVlmCredentials(vlmProvider)) {
            runVisualGrounding(
                target = semanticTarget,
                screenshotJpeg = screenshotJpeg,
                nodes = viewNodes,
                screenW = screenW,
                screenH = screenH,
                vlmProvider = vlmProvider,
                screenSignature = screenSignature,
                currentPackage = currentPackage,
                avoidCoordinates = avoidCoordinates
            )
        } else {
            null
        }

        val fusedVisual = if (visual != null) fuseVisualToUi(visual, viewNodes, screenW, screenH) else null
        val finalDecision = chooseFinalDecision(structured, fusedVisual, preferVisual)
        rememberSemanticAlignment(semanticTarget, finalDecision, viewNodes, currentPackage, screenSignature)
        return finalDecision
    }

    /**
     * Snapshot-time async VLM prewarm:
     * - Runs in background
     * - Does not block snapshot response
     * - Produces candidate hotspots cache for fast fallback
     */
    fun warmupOnSnapshotAsync(snapshotId: String, packageName: String, viewNodes: List<ViewNode>) {
        val currentPackage = packageName.ifBlank { dominantPackage(viewNodes) }
        val signature = computeScreenSignature(viewNodes, currentPackage)
        val now = System.currentTimeMillis()
        if (signature == lastPrewarmSignature && now - lastPrewarmScheduleMs < PREWARM_MIN_INTERVAL_MS) {
            return
        }
        lastPrewarmSignature = signature
        lastPrewarmScheduleMs = now

        val vlmProvider = loadVlmProviderConfig()
        if (vlmProvider == null || !hasVlmCredentials(vlmProvider)) return

        prewarmScope.launch {
            try {
                val screenshot = captureCurrentScreenJpeg() ?: return@launch
                val dm = context.resources.displayMetrics
                val summary = buildUiSummary(viewNodes, dm.widthPixels, dm.heightPixels)
                val candidates = callVlmPrewarmCandidates(
                    screenshotJpeg = screenshot,
                    uiSummary = summary,
                    vlmProvider = vlmProvider
                )
                if (candidates.isEmpty()) return@launch

                val result = PrewarmResult(
                    screenSignature = signature,
                    packageName = currentPackage,
                    createdAt = System.currentTimeMillis(),
                    candidates = candidates
                )
                synchronized(prewarmLock) {
                    prewarmCache[signature] = result
                    while (prewarmCache.size > PREWARM_CACHE_SIZE) {
                        val oldestKey = prewarmCache.entries.firstOrNull()?.key ?: break
                        prewarmCache.remove(oldestKey)
                    }
                }
                Log.d(TAG, "Prewarm ready snapshot=$snapshotId pkg=$currentPackage candidates=${candidates.size}")
            } catch (e: Exception) {
                Log.w(TAG, "Prewarm failed: ${e.message}")
            }
        }
    }

    /**
     * Compare two UI snapshots to detect "page not changed" condition.
     */
    fun hasUiChanged(beforeNodes: List<ViewNode>, afterNodes: List<ViewNode>): Boolean {
        if (beforeNodes.isEmpty() || afterNodes.isEmpty()) return true
        val beforePkg = dominantPackage(beforeNodes)
        val afterPkg = dominantPackage(afterNodes)
        if (beforePkg != afterPkg) return true
        val beforeFp = computeUiFingerprint(beforeNodes)
        val afterFp = computeUiFingerprint(afterNodes)
        return beforeFp != afterFp
    }

    private fun loadVlmProviderConfig(): ProviderConfig? {
        return try {
            ConfigLoader(context).loadOmniClawConfig().resolveProviders()["vlm"]
        } catch (e: Exception) {
            Log.w(TAG, "load vlm provider failed: ${e.message}")
            null
        }
    }

    private fun hasVlmCredentials(vlmProvider: ProviderConfig): Boolean {
        val apiKey = vlmProvider.apiKey.orEmpty()
        return vlmProvider.baseUrl.isNotBlank() &&
            apiKey.contains(":") &&
            apiKey.substringBefore(":").isNotBlank() &&
            apiKey.substringAfter(":", "").isNotBlank()
    }

    private fun hasSurfaceView(nodes: List<ViewNode>): Boolean {
        return nodes.any { it.className?.contains("SurfaceView", ignoreCase = true) == true }
    }

    private fun dominantPackage(nodes: List<ViewNode>): String {
        return nodes.asSequence()
            .mapNotNull { it.packageName?.trim()?.takeIf(String::isNotBlank) }
            .groupingBy { it }
            .eachCount()
            .entries
            .maxByOrNull { it.value }
            ?.key
            .orEmpty()
    }

    private fun computeUiFingerprint(nodes: List<ViewNode>): String {
        val normalized = nodes.asSequence()
            .filter { isInteractive(it) || !it.text.isNullOrBlank() || !it.contentDesc.isNullOrBlank() }
            .sortedWith(compareBy<ViewNode> { it.top }.thenBy { it.left })
            .take(180)
            .map {
                val rid = it.resourceId?.substringAfterLast('/')?.trim().orEmpty()
                val label = (it.text ?: it.contentDesc ?: "").trim().take(42)
                val cls = it.className?.substringAfterLast('.')?.trim().orEmpty()
                "${cls}|${rid}|${label}|${it.left},${it.top},${it.right},${it.bottom}|${it.clickable}|${it.focusable}"
            }
            .joinToString("||")
        return normalized.hashCode().toString()
    }

    private fun computeScreenSignature(nodes: List<ViewNode>, currentPackage: String): String {
        return "$currentPackage#${computeUiFingerprint(nodes)}"
    }

    private fun structuredGrounding(
        target: String,
        nodes: List<ViewNode>,
        screenW: Int,
        screenH: Int,
        currentPackage: String,
        screenSignature: String
    ): StructuredGrounding {
        recallSemanticAlignment(target, nodes, screenW, screenH, currentPackage, screenSignature)?.let {
            return it
        }

        val interactive = nodes.filter { isInteractive(it) && isNodeVisible(it, screenW, screenH) }
        var bestNode: ViewNode? = null
        var bestScore = -1.0
        var bestReason = "no strong ui match"

        for (node in interactive) {
            val (score, reason) = scoreNodeAgainstTarget(target, node)
            if (score > bestScore) {
                bestScore = score
                bestReason = reason
                bestNode = node
            }
        }

        if (bestNode == null) {
            val reason = if (hasSurfaceView(nodes)) {
                "ui target missing and SurfaceView present"
            } else {
                "ui target missing"
            }
            return StructuredGrounding(
                x = screenW / 2,
                y = screenH / 2,
                confidence = if (hasSurfaceView(nodes)) 0.22 else 0.30,
                reason = reason,
                matchedNode = null
            )
        }

        var confidence = bestScore
        if (bestNode.className?.contains("SurfaceView", ignoreCase = true) == true) {
            confidence = confidence.coerceAtMost(0.35)
        }
        return StructuredGrounding(
            x = bestNode.point.x,
            y = bestNode.point.y,
            confidence = confidence.coerceIn(0.0, 0.99),
            reason = bestReason,
            matchedNode = bestNode
        )
    }

    private fun recallSemanticAlignment(
        target: String,
        nodes: List<ViewNode>,
        screenW: Int,
        screenH: Int,
        currentPackage: String,
        screenSignature: String
    ): StructuredGrounding? {
        val targetNorm = normalize(target)
        if (targetNorm.isBlank()) return null
        val now = System.currentTimeMillis()

        val candidates = synchronized(semanticMemoryLock) {
            semanticMemory
                .filter { it.targetNorm == targetNorm && now - it.updatedAt <= MEMORY_TTL_MS }
                .sortedByDescending {
                    scoreSemanticMemoryPriority(
                        entry = it,
                        currentPackage = currentPackage,
                        screenSignature = screenSignature
                    )
                }
        }
        if (candidates.isEmpty()) return null

        for (entry in candidates) {
            val matched = findNodeForSemanticMapping(entry, nodes, screenW, screenH) ?: continue
            synchronized(semanticMemoryLock) {
                entry.hitCount += 1
                entry.updatedAt = now
            }
            return StructuredGrounding(
                x = matched.point.x,
                y = matched.point.y,
                confidence = 0.94,
                reason = "semantic-memory alignment (${entry.resourceId ?: entry.textHint ?: "mapped-node"})",
                matchedNode = matched
            )
        }
        return null
    }

    private fun scoreSemanticMemoryPriority(
        entry: SemanticMapping,
        currentPackage: String,
        screenSignature: String
    ): Int {
        var score = 0
        if (entry.packageName == currentPackage) score += 6
        if (entry.screenSignature == screenSignature) score += 4
        score += entry.hitCount.coerceAtMost(6)
        return score
    }

    private fun findNodeForSemanticMapping(
        entry: SemanticMapping,
        nodes: List<ViewNode>,
        screenW: Int,
        screenH: Int
    ): ViewNode? {
        val visibleInteractive = nodes.filter { isInteractive(it) && isNodeVisible(it, screenW, screenH) }
        if (visibleInteractive.isEmpty()) return null

        val byRes = entry.resourceId?.takeIf(String::isNotBlank)?.let { rid ->
            visibleInteractive.firstOrNull { it.resourceId == rid }
        }
        if (byRes != null) return byRes

        val textNorm = normalize(entry.textHint ?: "")
        val byTextClass = visibleInteractive.firstOrNull {
            val t = normalize(it.text ?: it.contentDesc ?: "")
            val cls = it.className?.substringAfterLast('.')?.trim().orEmpty()
            val textOk = textNorm.isNotBlank() && (t == textNorm || t.contains(textNorm) || textNorm.contains(t))
            val classOk = entry.className.isNullOrBlank() || cls.equals(entry.className, ignoreCase = true)
            textOk && classOk
        }
        if (byTextClass != null) return byTextClass

        val nearest = visibleInteractive.minByOrNull { squaredDistance(it.point.x, it.point.y, entry.x, entry.y) }
            ?: return null
        val dist = sqrt(squaredDistance(nearest.point.x, nearest.point.y, entry.x, entry.y))
        return if (dist <= MEMORY_RADIUS_PX) nearest else null
    }

    private fun scoreNodeAgainstTarget(target: String, node: ViewNode): Pair<Double, String> {
        val targetNorm = normalize(target)
        if (targetNorm.isBlank()) return 0.0 to "empty target"

        val fields = listOfNotNull(
            node.text,
            node.contentDesc,
            node.resourceId,
            node.resourceId?.substringAfterLast('/'),
            node.className?.substringAfterLast('.')
        ).filter { it.isNotBlank() }

        var best = 0.0
        var reason = "weak semantic overlap"
        for (f in fields) {
            val fNorm = normalize(f)
            if (fNorm.isBlank()) continue
            when {
                fNorm == targetNorm -> {
                    best = maxOf(best, 0.95)
                    reason = "exact ui text/id match"
                }
                fNorm.contains(targetNorm) || targetNorm.contains(fNorm) -> {
                    best = maxOf(best, 0.84)
                    reason = "substring ui match"
                }
                charOverlap(targetNorm, fNorm) >= 0.6 -> {
                    best = maxOf(best, 0.70)
                    reason = "char-overlap ui match"
                }
            }
        }

        if (node.clickable) best += 0.04
        if (node.focusable) best += 0.02
        if (!node.clickable && !node.focusable) best -= 0.05
        return best.coerceIn(0.0, 0.99) to reason
    }

    private fun charOverlap(a: String, b: String): Double {
        if (a.isBlank() || b.isBlank()) return 0.0
        val sa = a.toSet()
        val sb = b.toSet()
        val inter = sa.intersect(sb).size.toDouble()
        val union = sa.union(sb).size.toDouble().coerceAtLeast(1.0)
        return inter / union
    }

    private fun normalize(s: String): String {
        return s.lowercase().replace(Regex("\\s+"), "")
    }

    private fun squaredDistance(x1: Int, y1: Int, x2: Int, y2: Int): Double {
        val dx = (x1 - x2).toDouble()
        val dy = (y1 - y2).toDouble()
        return dx * dx + dy * dy
    }

    private fun isNearAny(
        x: Int,
        y: Int,
        avoidCoordinates: List<Pair<Int, Int>>,
        radiusPx: Int = AVOID_RADIUS_PX
    ): Boolean {
        return avoidCoordinates.any { (ax, ay) ->
            sqrt(squaredDistance(x, y, ax, ay)) <= radiusPx
        }
    }

    private fun isInteractive(node: ViewNode): Boolean {
        return node.clickable || node.focusable || node.scrollable
    }

    private fun isNodeVisible(node: ViewNode, screenW: Int, screenH: Int): Boolean {
        val validRect = node.right > node.left && node.bottom > node.top
        if (!validRect) return false
        val cx = node.point.x
        val cy = node.point.y
        return cx in 0 until screenW && cy in 0 until screenH
    }

    private fun captureCurrentScreenJpeg(): ByteArray? {
        val pair = DeviceController.getScreenshot(context) ?: return null
        val bitmap = pair.first
        return try {
            bitmapToJpeg(bitmap, SCREENSHOT_JPEG_QUALITY)
        } catch (e: Exception) {
            Log.w(TAG, "bitmap->jpeg failed: ${e.message}")
            null
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    private fun bitmapToJpeg(bitmap: Bitmap, quality: Int): ByteArray {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
        return out.toByteArray()
    }

    private fun buildUiSummary(nodes: List<ViewNode>, screenW: Int, screenH: Int): String {
        val interactive = nodes.filter { isInteractive(it) }.take(120)
        val sb = StringBuilder()
        sb.append("screen=").append(screenW).append("x").append(screenH).append("\n")
        sb.append("interactive_nodes=").append(interactive.size).append("\n")
        interactive.forEachIndexed { index, node ->
            val label = (node.text ?: node.contentDesc ?: node.resourceId ?: "").replace('\n', ' ').take(80)
            val cls = node.className?.substringAfterLast('.') ?: "Unknown"
            sb.append(index)
                .append(": text=").append(if (label.isBlank()) "<none>" else label)
                .append(" id=").append(node.resourceId ?: "<none>")
                .append(" class=").append(cls)
                .append(" clickable=").append(node.clickable)
                .append(" bounds=").append(node.left).append(",").append(node.top)
                .append(",").append(node.right).append(",").append(node.bottom)
                .append("\n")
        }
        return sb.toString()
    }

    private fun runVisualGrounding(
        target: String,
        screenshotJpeg: ByteArray,
        nodes: List<ViewNode>,
        screenW: Int,
        screenH: Int,
        vlmProvider: ProviderConfig,
        screenSignature: String,
        currentPackage: String,
        avoidCoordinates: List<Pair<Int, Int>>
    ): VisualGrounding? {
        findBestPrewarmCandidate(
            target = target,
            screenSignature = screenSignature,
            currentPackage = currentPackage,
            avoidCoordinates = avoidCoordinates
        )?.let { return it }

        return try {
            val uiSummary = buildUiSummary(nodes, screenW, screenH)
            val content = callVlmGrounding(
                target = target,
                screenshotJpeg = screenshotJpeg,
                uiSummary = uiSummary,
                vlmProvider = vlmProvider,
                avoidCoordinates = avoidCoordinates
            )
            val parsed = parseVisualGrounding(content)
            if (parsed != null && isNearAny(parsed.x, parsed.y, avoidCoordinates)) {
                parsed.copy(
                    confidence = (parsed.confidence - 0.22).coerceAtLeast(0.10),
                    reason = "${parsed.reason}; near avoid-point",
                    origin = parsed.origin
                )
            } else {
                parsed
            }
        } catch (e: Exception) {
            Log.w(TAG, "visual grounding failed: ${e.message}")
            null
        }
    }

    private fun callVlmGrounding(
        target: String,
        screenshotJpeg: ByteArray,
        uiSummary: String,
        vlmProvider: ProviderConfig,
        avoidCoordinates: List<Pair<Int, Int>>
    ): String {
        // Log a compact view of the current UI summary for debugging.
        Log.d(TAG, "VLM grounding UI summary:\n${uiSummary.take(2000)}")
        val imageB64 = Base64.encodeToString(screenshotJpeg, Base64.NO_WRAP)
        val systemPrompt = """
            You are a dual-mode visual engine for Android UI automation. 
            Input: A screenshot and a query. 
            Output: JSON only (no markdown, no prose).

            Required schema: {"x":int, "y":int, "confidence":float, "reason":string, "target":string}

            OPERATING MODES:
            1. Grounding Mode (Locate/Find):
            - Goal: Find the center coordinates (x, y) of the target.
            - If not found: Set x=-1, y=-1, confidence<=0.3.
            - reason: Explain visual cues used for grounding.

            2. Analysis Mode (Questions/Status/Logic):
            - Goal: Answer semantic questions (e.g., "is it loaded?", "does it contain X?").
            - Truthy/Yes: Set x=1, y=1, confidence=1.0.
            - Falsy/No: Set x=0, y=0, confidence=1.0.
            - Unsure/Error: Set x=-1, y=-1, confidence<=0.3.
            - reason: Provide a structured conclusion (e.g., "Search results for 'Sun Yanzi' are visible").
            - target: Echo the summarized semantic result.

            STRICT RULES:
            - Never add new keys. 
            - Use 'reason' as the primary output for semantic analysis.
            - If a query implies both, prioritize finding a clickable element.
        """.trimIndent()
        val userText = buildString {
            append("Target to tap: ").append(target).append("\n")
            append("UI summary:\n").append(uiSummary.take(8000))
            if (avoidCoordinates.isNotEmpty()) {
                append("\nAvoid coordinates (likely wrong from previous attempt): ")
                append(avoidCoordinates.joinToString(";") { "(${it.first},${it.second})" })
                append("\nPrefer a different valid control for the same target intent.")
            }
        }

        val rawMessages = JSONArray()
        rawMessages.put(JSONObject().put("role", "system").put("content", systemPrompt))
        val userParts = JSONArray()
        userParts.put(JSONObject().put("type", "text").put("text", userText))
        userParts.put(
            JSONObject().put("type", "image_url")
                .put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$imageB64"))
        )
        rawMessages.put(JSONObject().put("role", "user").put("content", userParts))

        val modelId = vlmProvider.models.firstOrNull()?.id?.ifBlank { DEFAULT_VLM_MODEL_ID } ?: DEFAULT_VLM_MODEL_ID
        // OpenAI 兼容 Chat Completions（与 UnifiedLLMProvider / 默认 vlm 配置一致）
        val payload = JSONObject()
            .put("model", modelId)
            .put("messages", rawMessages)
            .put("max_tokens", 512)
        val body = payload.toString()
        val chatUrl = appendChatCompletionsPath(vlmProvider.baseUrl.trimEnd('/'))
        val apiKey = vlmProvider.apiKey?.trim().orEmpty()

        val req = Request.Builder()
            .url(chatUrl)
            .addHeader("Content-Type", "application/json")
            .apply {
                if (apiKey.isNotBlank()) addHeader("Authorization", "Bearer $apiKey")
            }
            .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        http.newCall(req).execute().use { resp ->
            val raw = resp.body?.string() ?: ""
            if (!resp.isSuccessful) {
                throw IllegalStateException("VLM HTTP ${resp.code}: ${raw.take(240)}")
            }
            val root = JSONObject(raw)
            if (root.has("code") && root.optInt("code", 0) != 0) {
                val err = root.optString("bizMsg", root.optString("msg", "unknown"))
                throw IllegalStateException("VLM code=${root.optInt("code")}, msg=$err")
            }
            val data = root.optJSONObject("data") ?: root
            val choices = data.optJSONArray("choices") ?: root.optJSONArray("choices")
            if (choices == null || choices.length() == 0) {
                throw IllegalStateException("VLM empty choices")
            }
            val msg = choices.optJSONObject(0)?.optJSONObject("message")
                ?: throw IllegalStateException("VLM missing message")
            val content = msg.optString("content", "")

            // Log a compact VLM grounding summary for debugging (similar spirit to prompt-dumps).
            Log.i(
                TAG,
                "VLM grounding result: target='$target', model=$modelId, " +
                    "content=${content.take(400).replace('\n', ' ')}"
            )

            // Persist a detailed grounding record to disk (similar to prompt-dumps).
            try {
                val dumpDir = File("/sdcard/.xomniclaw/workspace/logs/vlm-dumps")
                if (!dumpDir.exists()) {
                    dumpDir.mkdirs()
                }
                val ts = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss_SSS", Locale.US).format(Date())
                val model = modelId
                val filename = "vlm_grounding_${ts}_${model.replace('/', '_')}.json"
                val dumpFile = File(dumpDir, filename)

                val obj = JSONObject()
                    .put("timestamp", ts)
                    .put("model", model)
                    .put("apiUrl", chatUrl)
                    .put("target", target)
                    .put("uiSummary", uiSummary.take(8000))
                    .put("request", payload)
                    .put("rawResponse", raw)
                    .put("parsedContent", content)

                dumpFile.writeText(obj.toString(2))
                Log.i(TAG, "📝 VLM grounding dumped: ${dumpFile.absolutePath}")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to dump VLM grounding: ${e.message}")
            }

            return content
        }
    }

    private fun callVlmPrewarmCandidates(
        screenshotJpeg: ByteArray,
        uiSummary: String,
        vlmProvider: ProviderConfig
    ): List<PrewarmCandidate> {
        val imageB64 = Base64.encodeToString(screenshotJpeg, Base64.NO_WRAP)
        val systemPrompt = """
            You are a visual prewarm extractor for Android UI automation.
            Return JSON only with this schema:
            {"candidates":[{"label":"...","x":123,"y":456,"confidence":0.0}]}
            Include 8-14 high-value actionable targets (back, close, skip, search, send, confirm).
            Confidence range must be 0~1.
        """.trimIndent()
        val userText = buildString {
            append("Extract candidate actionable targets from this screen.\n")
            append("UI summary:\n").append(uiSummary.take(7000))
        }

        val rawMessages = JSONArray()
        rawMessages.put(JSONObject().put("role", "system").put("content", systemPrompt))
        val userParts = JSONArray()
        userParts.put(JSONObject().put("type", "text").put("text", userText))
        userParts.put(
            JSONObject().put("type", "image_url")
                .put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$imageB64"))
        )
        rawMessages.put(JSONObject().put("role", "user").put("content", userParts))

        val modelId = vlmProvider.models.firstOrNull()?.id?.ifBlank { DEFAULT_VLM_MODEL_ID } ?: DEFAULT_VLM_MODEL_ID
        val payload = JSONObject()
            .put("model", modelId)
            .put("messages", rawMessages)
            .put("max_tokens", 700)
        val body = payload.toString()
        val chatUrl = appendChatCompletionsPath(vlmProvider.baseUrl.trimEnd('/'))
        val apiKey = vlmProvider.apiKey?.trim().orEmpty()

        val req = Request.Builder()
            .url(chatUrl)
            .addHeader("Content-Type", "application/json")
            .apply {
                if (apiKey.isNotBlank()) addHeader("Authorization", "Bearer $apiKey")
            }
            .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        http.newCall(req).execute().use { resp ->
            val raw = resp.body?.string() ?: ""
            if (!resp.isSuccessful) return emptyList()
            val root = JSONObject(raw)
            if (root.has("code") && root.optInt("code", 0) != 0) return emptyList()
            val data = root.optJSONObject("data") ?: root
            val choices = data.optJSONArray("choices") ?: root.optJSONArray("choices") ?: return emptyList()
            val msg = choices.optJSONObject(0)?.optJSONObject("message") ?: return emptyList()
            val content = msg.optString("content", "")
            return parsePrewarmCandidates(content)
        }
    }

    private fun parsePrewarmCandidates(content: String): List<PrewarmCandidate> {
        val obj = parseFirstJsonObject(content) ?: return emptyList()
        val arr = obj.optJSONArray("candidates") ?: return emptyList()
        val out = mutableListOf<PrewarmCandidate>()
        for (i in 0 until arr.length()) {
            val c = arr.optJSONObject(i) ?: continue
            val label = c.optString("label", c.optString("text", "")).trim()
            val x = c.optInt("x", -1)
            val y = c.optInt("y", -1)
            if (label.isBlank() || x < 0 || y < 0) continue
            val conf = c.optDouble("confidence", 0.55).coerceIn(0.0, 1.0)
            out += PrewarmCandidate(label = label, x = x, y = y, confidence = conf)
        }
        return out
    }

    private fun parseFirstJsonObject(content: String): JSONObject? {
        val trimmed = content.trim()
        if (trimmed.startsWith("{")) {
            return try { JSONObject(trimmed) } catch (_: Exception) { null }
        }
        val jsonText = JSON_BLOCK_RE.find(trimmed)?.value ?: return null
        return try { JSONObject(jsonText) } catch (_: Exception) { null }
    }

    private fun findBestPrewarmCandidate(
        target: String,
        screenSignature: String,
        currentPackage: String,
        avoidCoordinates: List<Pair<Int, Int>>
    ): VisualGrounding? {
        val now = System.currentTimeMillis()
        val prewarm = synchronized(prewarmLock) {
            val exact = prewarmCache[screenSignature]
            if (exact != null && now - exact.createdAt <= PREWARM_TTL_MS) {
                exact
            } else {
                prewarmCache.values
                    .asSequence()
                    .filter { now - it.createdAt <= PREWARM_TTL_MS }
                    .filter { it.packageName == currentPackage }
                    .maxByOrNull { it.createdAt }
            }
        } ?: return null

        val targetNorm = normalize(target)
        if (targetNorm.isBlank()) return null
        var best: PrewarmCandidate? = null
        var bestScore = 0.0
        for (c in prewarm.candidates) {
            if (isNearAny(c.x, c.y, avoidCoordinates)) continue
            val labelNorm = normalize(c.label)
            val sim = when {
                labelNorm == targetNorm -> 1.0
                labelNorm.contains(targetNorm) || targetNorm.contains(labelNorm) -> 0.88
                else -> charOverlap(targetNorm, labelNorm)
            }
            val score = sim * 0.78 + c.confidence * 0.22
            if (score > bestScore) {
                best = c
                bestScore = score
            }
        }
        val chosen = best ?: return null
        if (bestScore < 0.58) return null
        return VisualGrounding(
            x = chosen.x,
            y = chosen.y,
            confidence = (chosen.confidence + 0.04).coerceAtMost(0.95),
            reason = "prewarm candidate match label='${chosen.label}' score=${"%.2f".format(bestScore)}",
            origin = "prewarm"
        )
    }

    /**
     * 将 baseUrl 规范到 `/chat/completions`（与 [UnifiedLLMProvider] 对 OpenAI 兼容线路的行为一致）。
     */
    private fun appendChatCompletionsPath(baseUrl: String): String {
        val suffix = "/chat/completions"
        return if (baseUrl.lowercase().endsWith(suffix)) baseUrl else baseUrl + suffix
    }

    private fun parseVisualGrounding(content: String): VisualGrounding? {
        val obj = parseFirstJsonObject(content) ?: return null
        return try {
            val x = obj.optInt("x", -1)
            val y = obj.optInt("y", -1)
            if (x < 0 || y < 0) return null
            val confidence = obj.optDouble("confidence", 0.55).coerceIn(0.0, 1.0)
            val reason = obj.optString("reason", "vlm semantic grounding")
            VisualGrounding(
                x = x,
                y = y,
                confidence = confidence,
                reason = reason,
                origin = "on_demand"
            )
        } catch (e: Exception) {
            Log.w(TAG, "parse visual grounding json failed: ${e.message}")
            null
        }
    }

    private fun fuseVisualToUi(
        visual: VisualGrounding,
        nodes: List<ViewNode>,
        screenW: Int,
        screenH: Int
    ): DualTrackTapDecision {
        val interactive = nodes.filter { isInteractive(it) && isNodeVisible(it, screenW, screenH) }
        if (interactive.isEmpty()) {
            return DualTrackTapDecision(
                x = visual.x,
                y = visual.y,
                source = if (visual.origin == "prewarm") "vlm_prewarm_raw" else "vlm_raw",
                confidence = visual.confidence,
                reason = "${visual.reason}; no interactive ui nodes",
                fusedNodeText = null,
                fusedResourceId = null
            )
        }

        val nearest = interactive.minByOrNull {
            squaredDistance(it.point.x, it.point.y, visual.x, visual.y)
        }!!
        val dx = nearest.point.x - visual.x
        val dy = nearest.point.y - visual.y
        val distance = sqrt((dx * dx + dy * dy).toDouble())
        val radius = (minOf(screenW, screenH) * FUSION_RADIUS_RATIO)
            .toInt()
            .coerceIn(FUSION_MIN_RADIUS_PX, FUSION_MAX_RADIUS_PX)

        val fusedLabel = nearest.text ?: nearest.contentDesc ?: nearest.resourceId
        val fusedResId = nearest.resourceId
        val sourcePrefix = if (visual.origin == "prewarm") "vlm_prewarm" else "vlm"
        return if (distance <= radius) {
            DualTrackTapDecision(
                x = nearest.point.x,
                y = nearest.point.y,
                source = "${sourcePrefix}_ui_fused",
                confidence = (visual.confidence + 0.10).coerceAtMost(0.96),
                reason = "${visual.reason}; ${sourcePrefix}->nearest ui fusion (distance=${distance.toInt()}px)",
                fusedNodeText = fusedLabel,
                fusedResourceId = fusedResId
            )
        } else {
            DualTrackTapDecision(
                x = visual.x,
                y = visual.y,
                source = "${sourcePrefix}_raw",
                confidence = visual.confidence,
                reason = "${visual.reason}; keep raw coordinate (nearest ui distance=${distance.toInt()}px)",
                fusedNodeText = null,
                fusedResourceId = null
            )
        }
    }

    private fun chooseFinalDecision(
        structured: StructuredGrounding,
        visualFused: DualTrackTapDecision?,
        preferVisual: Boolean
    ): DualTrackTapDecision {
        val structuredSource = if (structured.reason.startsWith("semantic-memory")) {
            "semantic_memory_ui"
        } else {
            "ui_structured"
        }
        val structuredDecision = DualTrackTapDecision(
            x = structured.x,
            y = structured.y,
            source = structuredSource,
            confidence = structured.confidence,
            reason = structured.reason,
            fusedNodeText = structured.matchedNode?.let { it.text ?: it.contentDesc ?: it.resourceId },
            fusedResourceId = structured.matchedNode?.resourceId
        )
        if (visualFused == null) return structuredDecision

        if (preferVisual) {
            if (visualFused.confidence >= structuredDecision.confidence - 0.08) {
                return visualFused
            }
        }
        val chooseVisual = visualFused.confidence >= structuredDecision.confidence + 0.05 ||
            structuredDecision.confidence < 0.55
        return if (chooseVisual) visualFused else structuredDecision
    }

    private fun rememberSemanticAlignment(
        target: String,
        decision: DualTrackTapDecision,
        nodes: List<ViewNode>,
        currentPackage: String,
        screenSignature: String
    ) {
        val targetNorm = normalize(target)
        if (targetNorm.isBlank()) return
        if (decision.confidence < 0.60) return

        val interactive = nodes.filter { isInteractive(it) }
        if (interactive.isEmpty()) return
        val nearest = interactive.minByOrNull {
            squaredDistance(it.point.x, it.point.y, decision.x, decision.y)
        } ?: return
        val dist = sqrt(squaredDistance(nearest.point.x, nearest.point.y, decision.x, decision.y))
        if (dist > MEMORY_RADIUS_PX) return

        val resourceId = nearest.resourceId?.trim().orEmpty()
        val textHint = (nearest.text ?: nearest.contentDesc ?: "").trim()
        val className = nearest.className?.substringAfterLast('.')?.trim().orEmpty()
        if (resourceId.isBlank() && textHint.isBlank() && className.isBlank()) return

        val now = System.currentTimeMillis()
        synchronized(semanticMemoryLock) {
            val existing = semanticMemory.firstOrNull {
                it.targetNorm == targetNorm &&
                    it.packageName == currentPackage &&
                    (
                        (resourceId.isNotBlank() && it.resourceId == resourceId) ||
                            (resourceId.isBlank() && it.textHint == textHint && it.className == className)
                        )
            }
            if (existing != null) {
                existing.x = nearest.point.x
                existing.y = nearest.point.y
                existing.hitCount += 1
                existing.updatedAt = now
                return
            }
            semanticMemory.addLast(
                SemanticMapping(
                    targetNorm = targetNorm,
                    packageName = currentPackage,
                    screenSignature = screenSignature,
                    resourceId = resourceId.ifBlank { null },
                    textHint = textHint.ifBlank { null },
                    className = className.ifBlank { null },
                    x = nearest.point.x,
                    y = nearest.point.y,
                    hitCount = 1,
                    updatedAt = now
                )
            )
            while (semanticMemory.size > MAX_MEMORY_ENTRIES) {
                semanticMemory.removeFirst()
            }
        }
    }
}
