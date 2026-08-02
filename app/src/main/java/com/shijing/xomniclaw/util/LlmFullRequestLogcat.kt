package com.shijing.xomniclaw.util

import android.util.Log
import com.tencent.mmkv.MMKV
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * 在设置中开启后，将**实际上传**的 wire JSON 请求体：
 * 1）分段写入 logcat（标签 [TAG]）；
 * 2）**同时**异步落盘到 [DUMP_DIR]，与 [UnifiedLLMProvider.maybeDumpPrompt] 独立。
 *
 * 说明：部分机型的 logcat 会丢弃/截断超长行，仅 `adb logcat -s LLMFullRequest:I` 可能“看不见”分段正文，
 * 但会有一条短行包含落盘绝对路径，也可用 `adb pull` 取文件。
 */
object LlmFullRequestLogcat {

    private const val TAG = "LLMFullRequest"

    /** 与 prompt-dumps 同根目录，单独子目录存放「与 HTTP 字节一致」的 wire 正文。 */
    private const val DUMP_DIR = "/sdcard/.xomniclaw/workspace/logs/llm-full-request"

    /**
     * 单条 log 不宜过长。Log buffer 在部分设备上对「含超长 base64 的整行」不稳定，取保守值。
     * 与「----- 第 x 段」前缀合起来仍应明显低于 ~4K。
     */
    private const val CHUNK_SIZE = 2800

    /** 避免落盘把存储写爆（含多模态大 base64）。超出则截断并加尾部说明。 */
    private const val MAX_WIRE_BYTES = 2_000_000

    private val dumpSeq = AtomicInteger(0)

    /** 单线程串行写文件，避免并发交错。 */
    private val fileWriter = Executors.newSingleThreadExecutor { r ->
        Thread(r, "llm-full-req-dump").apply { isDaemon = true }
    }

    /**
     * [wireRequestBody] 与 HTTP POST 正文一致（含 [org.json.JSONObject.requestBodyForWire] 处理）。
     */
    fun logIfEnabled(
        wireRequestBody: String,
        providerName: String,
        modelId: String,
        apiUrl: String,
        requestFormat: String
    ) {
        val enabled = try {
            MMKV.defaultMMKV().decodeBool(MMKVKeys.LLM_FULL_REQUEST_LOGCAT.key, false)
        } catch (_: Exception) {
            false
        }
        if (!enabled) return

        // 先异步落盘，避免同步写大文件拖慢发 HTTP；失败不影响请求。
        enqueueFileDump(
            wireRequestBody = wireRequestBody,
            providerName = providerName,
            modelId = modelId,
            apiUrl = apiUrl,
            requestFormat = requestFormat
        )

        val n = wireRequestBody.length
        val totalParts = if (n == 0) 1 else (n + CHUNK_SIZE - 1) / CHUNK_SIZE

        // 必有一条极短行：方便过滤确认开关已生效（不依赖长正文是否进 buffer）。
        Log.i(
            TAG,
            "wire_dump chars=$n parts=$totalParts provider=$providerName fmt=$requestFormat (see file log line)"
        )
        Log.i(
            TAG,
            "========== 完整请求开始 format=$requestFormat provider=$providerName model=$modelId 共 $n 字符 url=$apiUrl 共 $totalParts 段 =========="
        )
        if (n == 0) {
            Log.i(TAG, "----- (empty body) -----")
        } else {
            var i = 0
            var part = 0
            while (i < n) {
                val end = (i + CHUNK_SIZE).coerceAtMost(n)
                part++
                Log.i(TAG, "p$part/$totalParts\n${wireRequestBody.substring(i, end)}")
                i = end
            }
        }
        Log.i(TAG, "========== 完整请求结束 ==========")
    }

    private fun enqueueFileDump(
        wireRequestBody: String,
        providerName: String,
        modelId: String,
        apiUrl: String,
        requestFormat: String
    ) {
        val truncated = wireRequestBody.length > MAX_WIRE_BYTES
        val bodyForFile = if (truncated) {
            wireRequestBody.substring(0, MAX_WIRE_BYTES)
        } else {
            wireRequestBody
        }
        fileWriter.execute {
            try {
                val dir = File(DUMP_DIR)
                if (!dir.exists() && !dir.mkdirs()) {
                    Log.w(TAG, "llm full-request dump: mkdir failed $DUMP_DIR")
                    return@execute
                }
                val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
                val safeModel = modelId.replace(Regex("""[\\/:*?"<>|]"""), "_").take(96)
                // 与 HTTP POST 正文**字节一致**的 .json，便于直接 pull 后重放/比对（元数据仅见 log 行与 .meta）
                val base = "wire_${stamp}_${dumpSeq.incrementAndGet()}_${requestFormat}_${providerName}_$safeModel"
                    .replace(Regex("""[\\/:*?"<>|]"""), "_")
                    .take(200)
                val jsonFile = File(dir, "$base.json")
                jsonFile.writeText(bodyForFile, Charsets.UTF_8)
                File(dir, "$base.meta.txt").writeText(
                    buildString {
                        appendLine("format=$requestFormat")
                        appendLine("provider=$providerName")
                        appendLine("model=$modelId")
                        appendLine("apiUrl=$apiUrl")
                        appendLine("wireCharCount=${wireRequestBody.length}")
                        appendLine("fileWireChars=${bodyForFile.length}")
                        if (truncated) {
                            appendLine("truncated=true (original ${wireRequestBody.length} > MAX_WIRE_BYTES=$MAX_WIRE_BYTES)")
                        }
                    },
                    Charsets.UTF_8
                )
                val n = jsonFile.length()
                Log.i(
                    TAG,
                    "llm_full_request_file path=${jsonFile.absolutePath} url=$apiUrl wireChars=${wireRequestBody.length} fileBytes=$n " +
                        if (truncated) "TRUNCATED" else "ok"
                )
            } catch (e: Exception) {
                Log.w(TAG, "llm full-request file dump failed: ${e.message}", e)
            }
        }
    }
}
