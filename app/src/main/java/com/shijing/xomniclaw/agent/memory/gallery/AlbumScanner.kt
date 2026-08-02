package com.shijing.xomniclaw.agent.memory.gallery

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat

/**
 * 相册扫描器。
 *
 * 只负责“读取新增图片列表”，不掺杂摘要、画像或 memory 写入逻辑。
 * 查询结果按修改时间 **从新到旧**（DESC），便于优先处理最近图片；游标推进由 [GalleryMemoryWorkflow] 与本批最老/已处理边界配合完成。
 */
class AlbumScanner(private val context: Context) {
    companion object {
        private const val DEFAULT_SCAN_LIMIT = 20
    }

    fun hasRequiredPermission(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    fun scanNewImages(
        cursor: AlbumScanCursor,
        maxResults: Int = DEFAULT_SCAN_LIMIT
    ): List<AlbumImageRecord> {
        if (!hasRequiredPermission()) {
            return emptyList()
        }

        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Images.Media.MIME_TYPE,
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.DATE_MODIFIED
        )

        val selection = buildString {
            append("(")
            append("${MediaStore.Images.Media.DATE_MODIFIED} > ?")
            append(" OR (")
            append("${MediaStore.Images.Media.DATE_MODIFIED} = ?")
            append(" AND ${MediaStore.Images.Media._ID} > ?")
            append("))")
        }

        val selectionArgs = arrayOf(
            cursor.lastTimestampSec.toString(),
            cursor.lastTimestampSec.toString(),
            cursor.lastMediaId.toString()
        )

        // 同一游标之后未处理的图片中，优先返回「修改时间最新」的（再向更旧延伸）
        val sortOrder = "${MediaStore.Images.Media.DATE_MODIFIED} DESC, ${MediaStore.Images.Media._ID} DESC"
        val records = mutableListOf<AlbumImageRecord>()
        val contentUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI

        context.contentResolver.query(
            contentUri,
            projection,
            selection,
            selectionArgs,
            sortOrder
        )?.use { queryCursor ->
            val idIndex = queryCursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameIndex = queryCursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val bucketIndex = queryCursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
            val mimeIndex = queryCursor.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)
            val widthIndex = queryCursor.getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH)
            val heightIndex = queryCursor.getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT)
            val sizeIndex = queryCursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
            val takenIndex = queryCursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
            val addedIndex = queryCursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
            val modifiedIndex = queryCursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)

            while (queryCursor.moveToNext() && records.size < maxResults) {
                val mediaId = queryCursor.getLong(idIndex)
                records += AlbumImageRecord(
                    mediaId = mediaId,
                    contentUri = ContentUris.withAppendedId(contentUri, mediaId),
                    displayName = queryCursor.getString(nameIndex).orEmpty(),
                    bucketName = queryCursor.getString(bucketIndex),
                    mimeType = queryCursor.getString(mimeIndex),
                    width = queryCursor.takeIf { !it.isNull(widthIndex) }?.getInt(widthIndex),
                    height = queryCursor.takeIf { !it.isNull(heightIndex) }?.getInt(heightIndex),
                    sizeBytes = queryCursor.takeIf { !it.isNull(sizeIndex) }?.getLong(sizeIndex),
                    dateTakenMs = queryCursor.takeIf { !it.isNull(takenIndex) }?.getLong(takenIndex),
                    dateAddedSec = queryCursor.takeIf { !it.isNull(addedIndex) }?.getLong(addedIndex),
                    dateModifiedSec = queryCursor.takeIf { !it.isNull(modifiedIndex) }?.getLong(modifiedIndex)
                )
            }
        }

        return records
    }
}
