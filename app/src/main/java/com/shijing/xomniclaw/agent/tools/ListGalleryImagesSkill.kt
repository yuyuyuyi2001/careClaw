package com.shijing.xomniclaw.agent.tools

import android.content.ContentUris
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import java.util.Calendar
import com.shijing.xomniclaw.providers.FunctionDefinition
import com.shijing.xomniclaw.providers.ParametersSchema
import com.shijing.xomniclaw.providers.PropertySchema
import com.shijing.xomniclaw.providers.ToolDefinition

/**
 * Lists recent images from system MediaStore (gallery).
 *
 * Exposes album name, display name, size, dimensions, last modified — not semantic tags
 * (flowers, pets). For subject-based selection, use a dedicated album or vision on screenshots.
 */
class ListGalleryImagesSkill(private val context: Context) : Skill {
    companion object {
        private const val TAG = "ListGalleryImagesSkill"
        private const val DEFAULT_LIMIT = 30
        private const val MAX_LIMIT = 100
        private const val LLM_FUNCTION_DESCRIPTION = "List recent gallery images (MediaStore): URI, name, album, size, dates. " +
            "Optional limit (default 30, max 100), album_filter, name_filter, date_preset (today|yesterday|last_24h; today/yesterday prefer DATE_TAKEN), " +
            "modified_after_sec / modified_before_sec (Unix s on DATE_MODIFIED). No semantic tags—use album_filter. Pair with copy_images_to_album."
    }

    override val name = "list_gallery_images"
    override val description = "MediaStore recent images. See getToolDefinition LLM block for filters."

    override fun getToolDefinition(): ToolDefinition {
        return ToolDefinition(
            type = "function",
            function = FunctionDefinition(
                name = name,
                description = LLM_FUNCTION_DESCRIPTION,
                parameters = ParametersSchema(
                    type = "object",
                    properties = mapOf(
                        "limit" to PropertySchema(
                            type = "integer",
                            description = "—"
                        ),
                        "album_filter" to PropertySchema(
                            type = "string",
                            description = "—"
                        ),
                        "name_filter" to PropertySchema(
                            type = "string",
                            description = "—"
                        ),
                        "date_preset" to PropertySchema(
                            type = "string",
                            description = "—",
                            enum = listOf("today", "yesterday", "last_24h")
                        ),
                        "modified_after_sec" to PropertySchema(
                            type = "integer",
                            description = "—"
                        ),
                        "modified_before_sec" to PropertySchema(
                            type = "integer",
                            description = "—"
                        )
                    ),
                    required = emptyList()
                )
            )
        )
    }

    override suspend fun execute(args: Map<String, Any?>): SkillResult {
        val limitArg = (args["limit"] as? Number)?.toInt()
        val limit = (limitArg ?: DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)
        val albumFilter = (args["album_filter"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
        val nameFilter = (args["name_filter"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
        val datePreset = normalizeDatePreset(args["date_preset"] as? String)
        val modifiedAfterSec = (args["modified_after_sec"] as? Number)?.toLong()
        val modifiedBeforeSec = (args["modified_before_sec"] as? Number)?.toLong()

        return try {
            val projection = buildList {
                add(MediaStore.Images.Media._ID)
                add(MediaStore.Images.Media.DISPLAY_NAME)
                add(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
                add(MediaStore.Images.Media.DATE_MODIFIED)
                add(MediaStore.Images.Media.DATE_TAKEN)
                add(MediaStore.Images.Media.MIME_TYPE)
                add(MediaStore.Images.Media.SIZE)
                add(MediaStore.Images.Media.WIDTH)
                add(MediaStore.Images.Media.HEIGHT)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    add(MediaStore.Images.Media.RELATIVE_PATH)
                }
            }.toTypedArray()

            val selection = StringBuilder("${MediaStore.Images.Media.MIME_TYPE} LIKE ?")
            val selArgs = mutableListOf("image/%")
            albumFilter?.let {
                selection.append(" AND ${MediaStore.Images.Media.BUCKET_DISPLAY_NAME} LIKE ?")
                selArgs.add("%$it%")
            }
            nameFilter?.let {
                selection.append(" AND ${MediaStore.Images.Media.DISPLAY_NAME} LIKE ?")
                selArgs.add("%$it%")
            }

            when (datePreset) {
                "today" -> {
                    appendTodayOrYesterdayTakenSelection(
                        selection,
                        selArgs,
                        dayStartSec = startOfLocalDayEpochSec(0),
                        dayEndSec = endOfLocalDayEpochSec(0),
                        takenEndExclusiveMs = startOfNextLocalDayEpochMs()
                    )
                }
                "yesterday" -> {
                    appendTodayOrYesterdayTakenSelection(
                        selection,
                        selArgs,
                        dayStartSec = startOfLocalDayEpochSec(1),
                        dayEndSec = endOfLocalDayEpochSec(1),
                        takenEndExclusiveMs = startOfLocalDayEpochMs(0)
                    )
                }
                "last_24h" -> {
                    val since = System.currentTimeMillis() / 1000 - 86400L
                    selection.append(" AND ${MediaStore.Images.Media.DATE_MODIFIED} >= ?")
                    selArgs.add(since.toString())
                }
            }
            modifiedAfterSec?.let {
                selection.append(" AND ${MediaStore.Images.Media.DATE_MODIFIED} >= ?")
                selArgs.add(it.toString())
            }
            modifiedBeforeSec?.let {
                selection.append(" AND ${MediaStore.Images.Media.DATE_MODIFIED} <= ?")
                selArgs.add(it.toString())
            }

            val sort = "${MediaStore.Images.Media.DATE_MODIFIED} DESC"

            val items = mutableListOf<Map<String, Any?>>()
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection.toString(),
                selArgs.toTypedArray(),
                sort
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                val bucketCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
                val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)
                val takenCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
                val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
                val wCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH)
                val hCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT)
                val relPathCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    cursor.getColumnIndex(MediaStore.Images.Media.RELATIVE_PATH)
                } else -1

                var n = 0
                while (cursor.moveToNext() && n < limit) {
                    val id = cursor.getLong(idCol)
                    val uri = ContentUris.withAppendedId(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        id
                    ).toString()
                    val displayName = cursor.getString(nameCol) ?: ""
                    val album = cursor.getString(bucketCol) ?: ""
                    val modifiedSec = cursor.getLong(dateCol)
                    val takenMs = if (!cursor.isNull(takenCol)) cursor.getLong(takenCol) else 0L
                    val mime = cursor.getString(mimeCol) ?: ""
                    val size = if (!cursor.isNull(sizeCol)) cursor.getLong(sizeCol) else null
                    val width = if (!cursor.isNull(wCol)) cursor.getInt(wCol) else null
                    val height = if (!cursor.isNull(hCol)) cursor.getInt(hCol) else null
                    val relativePath = if (relPathCol >= 0 && !cursor.isNull(relPathCol)) {
                        cursor.getString(relPathCol)
                    } else null

                    items.add(
                        mapOf(
                            "id" to id,
                            "content_uri" to uri,
                            "display_name" to displayName,
                            "album" to album,
                            "modified_unix_sec" to modifiedSec,
                            "date_taken_ms" to takenMs,
                            "mime_type" to mime,
                            "size_bytes" to size,
                            "width" to width,
                            "height" to height,
                            "relative_path" to relativePath
                        )
                    )
                    n++
                }
            } ?: return SkillResult.error("MediaStore query returned null (permission denied?)")

            Log.d(
                TAG,
                "Listed ${items.size} images (limit=$limit, album=$albumFilter, name=$nameFilter, preset=$datePreset, after=$modifiedAfterSec, before=$modifiedBeforeSec)"
            )

            val text = buildString {
                appendLine("🖼 相册图片 (${items.size} 条，按修改时间倒序)")
                if (!datePreset.isNullOrBlank()) {
                    appendLine("筛选: date_preset=$datePreset（设备本地时区；today/yesterday 优先 DATE_TAKEN 落在该日，无拍摄时间再按 DATE_MODIFIED）")
                }
                modifiedAfterSec?.let { appendLine("筛选: modified_after_sec=$it") }
                modifiedBeforeSec?.let { appendLine("筛选: modified_before_sec=$it") }
                appendLine("说明：仅有文件名/相册/尺寸等元数据，无「花/人物」等语义标签；主题筛选请让用户用到命名相册或先截图走视觉。")
                appendLine("选剪映素材：若无用户另行指定张数，目标 **K=${items.size}**；须在 **`下一步 (N)` 的 N=K** 后再点下一步。界面「选3个以上更好」仅为最低建议，**禁止**在 N<K 时因已≥3 张就继续。")
                appendLine()
                items.forEachIndexed { i, row ->
                    appendLine("${i + 1}. ${row["display_name"]}  [相册: ${row["album"]}]")
                    appendLine("   URI: ${row["content_uri"]}")
                    appendLine(
                        "   ${row["width"]}x${row["height"]}  ${row["mime_type"]}  ${row["size_bytes"]} bytes  " +
                            "DATE_TAKEN(ms)=${row["date_taken_ms"]}  DATE_MODIFIED(sec)=${row["modified_unix_sec"]}"
                    )
                    row["relative_path"]?.let { appendLine("   path: $it") }
                }
            }

            SkillResult.success(
                text,
                mapOf("count" to items.size, "images" to items)
            )
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission denied for gallery read", e)
            SkillResult.error("Gallery read permission denied: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "list_gallery_images failed", e)
            SkillResult.error("list_gallery_images failed: ${e.message}")
        }
    }

    private fun normalizeDatePreset(raw: String?): String? {
        val s = raw?.trim()?.trim('"')?.trim()?.lowercase() ?: return null
        return s.takeIf { it.isNotEmpty() }
    }

    /**
     * Calendar day filter: **DATE_TAKEN** when present — some OEMs store **seconds** (below 1e12), AOSP uses **ms**.
     * Fallback: **DATE_MODIFIED** in [dayStartSec, dayEndSec] if taken is null or non-positive.
     */
    private fun appendTodayOrYesterdayTakenSelection(
        selection: StringBuilder,
        selArgs: MutableList<String>,
        dayStartSec: Long,
        dayEndSec: Long,
        takenEndExclusiveMs: Long
    ) {
        val takenStartMs = dayStartSec * 1000
        val takenEndExclusiveSec = takenEndExclusiveMs / 1000
        val dt = MediaStore.Images.Media.DATE_TAKEN
        val dm = MediaStore.Images.Media.DATE_MODIFIED
        // Below ≈ Sep 2001 in ms — treat as seconds-since-epoch (known OEM quirk); else milliseconds.
        val secMsThreshold = 1_000_000_000_000L
        selection.append(
            " AND (" +
                "($dt IS NOT NULL AND $dt > 0 AND $dt < $secMsThreshold AND $dt >= ? AND $dt < ?) OR " +
                "($dt IS NOT NULL AND $dt >= $secMsThreshold AND $dt >= ? AND $dt < ?) OR " +
                "(($dt IS NULL OR $dt <= 0) AND $dm >= ? AND $dm <= ?)" +
                ")"
        )
        selArgs.add(dayStartSec.toString())
        selArgs.add(takenEndExclusiveSec.toString())
        selArgs.add(takenStartMs.toString())
        selArgs.add(takenEndExclusiveMs.toString())
        selArgs.add(dayStartSec.toString())
        selArgs.add(dayEndSec.toString())
    }

    private fun startOfLocalDayEpochMs(daysAgo: Int): Long = startOfLocalDayEpochSec(daysAgo) * 1000

    /** Local tomorrow 00:00 — exclusive end for "today" DATE_TAKEN range. */
    private fun startOfNextLocalDayEpochMs(): Long {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        cal.add(Calendar.DAY_OF_YEAR, 1)
        return cal.timeInMillis
    }

    /** Start of calendar day in local timezone, `daysAgo`=0 → today 00:00:00. */
    private fun startOfLocalDayEpochSec(daysAgo: Int): Long {
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_YEAR, -daysAgo)
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis / 1000
    }

    /** End of calendar day in local timezone (23:59:59). */
    private fun endOfLocalDayEpochSec(daysAgo: Int): Long {
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_YEAR, -daysAgo)
        cal.set(Calendar.HOUR_OF_DAY, 23)
        cal.set(Calendar.MINUTE, 59)
        cal.set(Calendar.SECOND, 59)
        cal.set(Calendar.MILLISECOND, 999)
        return cal.timeInMillis / 1000
    }
}
