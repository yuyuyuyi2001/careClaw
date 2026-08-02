package com.shijing.xomniclaw.agent.tools

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.shijing.xomniclaw.providers.FunctionDefinition
import com.shijing.xomniclaw.providers.ParametersSchema
import com.shijing.xomniclaw.providers.PropertySchema
import com.shijing.xomniclaw.providers.ToolDefinition
import java.io.File
import java.io.IOException

/**
 * Copies gallery images into a new folder under [Environment.DIRECTORY_PICTURES].
 * Most gallery apps show each such folder as an "album".
 *
 * Note: This is **copy**, not move — originals stay unless the user deletes them.
 * True in-place "move" across buckets is OEM/version dependent and not guaranteed here.
 */
class CopyImagesToAlbumSkill(private val context: Context) : Skill {

    companion object {
        private const val TAG = "CopyImagesToAlbumSkill"
        private const val MAX_ITEMS = 50
        private const val LLM_FUNCTION_DESCRIPTION = "Copy photos into a new album (subfolder under Pictures/). " +
            "album_name required. content_uris (from list_gallery_images) or media_ids. " +
            "clear_album_first: clear before copy; if true and no URIs/ids, clear only. Copy not move."
    }

    override val name = "copy_images_to_album"
    override val description = "Copy photos into Pictures/<album>/. See getToolDefinition LLM block."

    override fun getToolDefinition(): ToolDefinition {
        return ToolDefinition(
            type = "function",
            function = FunctionDefinition(
                name = name,
                description = LLM_FUNCTION_DESCRIPTION,
                parameters = ParametersSchema(
                    type = "object",
                    properties = mapOf(
                        "album_name" to PropertySchema(
                            type = "string",
                            description = "—"
                        ),
                        "content_uris" to PropertySchema(
                            type = "array",
                            description = "—",
                            items = PropertySchema(type = "string", description = "—")
                        ),
                        "media_ids" to PropertySchema(
                            type = "array",
                            description = "—",
                            items = PropertySchema(type = "integer", description = "—")
                        ),
                        "clear_album_first" to PropertySchema(
                            type = "boolean",
                            description = "—"
                        )
                    ),
                    required = listOf("album_name")
                )
            )
        )
    }

    override suspend fun execute(args: Map<String, Any?>): SkillResult {
        val rawName = (args["album_name"] as? String)?.trim().orEmpty()
        if (rawName.isEmpty()) return SkillResult.error("album_name is required")
        val folderSegment = sanitizeAlbumFolderName(rawName)
        if (folderSegment.isEmpty()) return SkillResult.error("album_name is invalid after sanitization")

        val uris = resolveSourceUris(args)
        val clearFirst = args["clear_album_first"] as? Boolean ?: false
        val resolver = context.contentResolver
        val relativePath = "${Environment.DIRECTORY_PICTURES}/$folderSegment/"
        val results = mutableListOf<String>()

        if (uris.isEmpty()) {
            if (!clearFirst) {
                return SkillResult.error(
                    "Provide content_uris and/or media_ids, or set clear_album_first=true to clear this album only (no copy)."
                )
            }
            val deleted = clearAlbumMediaInBucket(resolver, folderSegment, results)
            val summary = buildString {
                appendLine("📁 相册文件夹: Pictures/$folderSegment/ （系统相册中常显示为「$rawName」）")
                appendLine("仅清空：已删除 $deleted 条媒体记录（本相册内副本；原图仍在其它相册）。")
                results.forEach { appendLine(it) }
            }
            val meta = mapOf(
                "album_folder" to folderSegment,
                "relative_path" to relativePath,
                "copied" to 0,
                "failed" to 0,
                "deleted" to deleted
            )
            return SkillResult.success(summary, meta)
        }

        if (uris.size > MAX_ITEMS) {
            return SkillResult.error("At most $MAX_ITEMS images per call (got ${uris.size})")
        }

        var ok = 0
        var fail = 0
        var deleted = 0

        if (clearFirst) {
            deleted = clearAlbumMediaInBucket(resolver, folderSegment, results)
        }

        for ((index, src) in uris.withIndex()) {
            try {
                val mime = resolver.getType(src) ?: "image/jpeg"
                val baseName = guessDisplayName(resolver, src, index)
                val destName = ensureUniqueDisplayName(baseName, index)

                val insertedUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, destName)
                        put(MediaStore.MediaColumns.MIME_TYPE, mime)
                        put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                    }
                    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                        ?: throw IOException("insert returned null")
                    resolver.openOutputStream(uri).use { out ->
                        if (out == null) throw IOException("openOutputStream null")
                        resolver.openInputStream(src).use { inp ->
                            if (inp == null) throw IOException("openInputStream null")
                            inp.copyTo(out)
                        }
                    }
                    ContentValues().apply {
                        put(MediaStore.MediaColumns.IS_PENDING, 0)
                        resolver.update(uri, this, null, null)
                    }
                    uri
                } else {
                    @Suppress("DEPRECATION")
                    val base = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
                    val dir = File(base, folderSegment).apply { mkdirs() }
                    val destFile = File(dir, destName)
                    resolver.openInputStream(src).use { inp ->
                        if (inp == null) throw IOException("openInputStream null")
                        destFile.outputStream().use { out -> inp.copyTo(out) }
                    }
                    val values = ContentValues().apply {
                        put(MediaStore.MediaColumns.DATA, destFile.absolutePath)
                        put(MediaStore.MediaColumns.DISPLAY_NAME, destName)
                        put(MediaStore.MediaColumns.MIME_TYPE, mime)
                    }
                    resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                        ?: throw IOException("legacy insert returned null")
                }

                ok++
                results.add("OK: $src → $insertedUri ($destName)")
            } catch (e: Exception) {
                fail++
                Log.e(TAG, "copy failed for $src", e)
                results.add("FAIL: $src — ${e.message}")
            }
        }

        if (ok > 0) {
            notifyMediaStoreImagesChanged(resolver)
        }

        val summary = buildString {
            appendLine("📁 复制到相册文件夹: Pictures/$folderSegment/ （系统相册中常显示为「$rawName」）")
            appendLine("成功 $ok，失败 $fail（共 ${uris.size} 个源）")
            appendLine("说明：为**复制**非移动，原图仍在原相册。")
            results.forEach { appendLine(it) }
        }

        val meta = mapOf(
            "album_folder" to folderSegment,
            "relative_path" to relativePath,
            "copied" to ok,
            "failed" to fail,
            "deleted" to deleted
        )
        return if (ok == 0) SkillResult(false, summary, meta)
        else SkillResult.success(summary, meta)
    }

    /** Deletes all images whose bucket display name equals [folderSegment]. Returns count deleted. */
    private fun clearAlbumMediaInBucket(
        resolver: android.content.ContentResolver,
        folderSegment: String,
        results: MutableList<String>
    ): Int {
        var deleted = 0
        try {
            val selection = "${MediaStore.Images.Media.BUCKET_DISPLAY_NAME} = ?"
            val selArgs = arrayOf(folderSegment)
            val projection = arrayOf(MediaStore.Images.Media._ID)
            resolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                selArgs,
                null
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                    try {
                        val rows = resolver.delete(uri, null, null)
                        if (rows > 0) deleted++
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to delete old image $uri", e)
                    }
                }
            }
            results.add("Cleared $deleted old images from album '$folderSegment'.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear album", e)
            results.add("Warning: Failed to clear old images: ${e.message}")
        }
        // Nudge gallery / picker apps (e.g. CapCut) to drop stale thumbnails and album rows.
        notifyMediaStoreImagesChanged(resolver)
        return deleted
    }

    private fun notifyMediaStoreImagesChanged(resolver: android.content.ContentResolver) {
        try {
            resolver.notifyChange(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, null)
        } catch (e: Exception) {
            Log.w(TAG, "notifyChange(Images) failed", e)
        }
    }

    private fun resolveSourceUris(args: Map<String, Any?>): List<Uri> {
        val fromStrings = parseStringList(args["content_uris"])
        if (fromStrings.isNotEmpty()) {
            return fromStrings.mapNotNull { runCatching { Uri.parse(it) }.getOrNull() }
        }
        val ids = parseNumberList(args["media_ids"])
        return ids.map { id ->
            ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
        }
    }

    private fun parseStringList(v: Any?): List<String> {
        return when (v) {
            is List<*> -> v.mapNotNull { it?.toString()?.trim()?.takeIf { s -> s.isNotEmpty() } }
            is String -> listOf(v.trim()).filter { it.isNotEmpty() }
            else -> emptyList()
        }
    }

    private fun parseNumberList(v: Any?): List<Long> {
        return when (v) {
            is List<*> -> v.mapNotNull {
                when (it) {
                    is Number -> it.toLong()
                    is String -> it.trim().toLongOrNull()
                    else -> null
                }
            }
            is Number -> listOf(v.toLong())
            else -> emptyList()
        }
    }

    private fun sanitizeAlbumFolderName(name: String): String {
        val cleaned = name
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .replace(Regex("""\.\.+"""), "_")
            .trim()
            .trim('.')
        return if (cleaned.isEmpty()) "" else cleaned.take(80)
    }

    private fun guessDisplayName(resolver: android.content.ContentResolver, src: Uri, index: Int): String {
        val projection = arrayOf(MediaStore.MediaColumns.DISPLAY_NAME)
        resolver.query(src, projection, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val col = c.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                if (col >= 0) {
                    val n = c.getString(col)?.trim()
                    if (!n.isNullOrEmpty()) return n
                }
            }
        }
        return "image_$index.jpg"
    }

    private fun ensureUniqueDisplayName(base: String, index: Int): String {
        val dot = base.lastIndexOf('.')
        val stem = if (dot > 0) base.substring(0, dot) else base
        val ext = if (dot > 0) base.substring(dot) else ".jpg"
        return "${stem}_$index$ext"
    }
}
