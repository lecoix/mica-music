package com.mica.music.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File
import java.io.FileOutputStream

/** Copies user-selected playlist artwork into app-private storage. */
object PlaylistCoverImporter {
    private const val COVER_DIR = "playlist_covers"
    private const val MAX_DECODE_SIDE_PX = 1200

    data class ImportResult(
        val path: String?,
        val message: String,
    )

    fun importCover(context: Context, playlistId: String, uri: Uri): ImportResult {
        val appContext = context.applicationContext
        val directory = File(appContext.filesDir, COVER_DIR)
        if (!directory.exists() && !directory.mkdirs()) {
            return ImportResult(null, "无法创建歌单封面目录")
        }
        val finalFile = File(directory, "${playlistId}.jpg")
        val tempFile = File(directory, "${playlistId}.importing.jpg")
        return try {
            val bitmap = decodeScaledBitmap(appContext, uri)
                ?: return ImportResult(null, "无法读取图片文件")
            val saved = FileOutputStream(tempFile).use { output ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output)
            }
            bitmap.recycle()
            if (!saved) {
                tempFile.delete()
                return ImportResult(null, "歌单封面无法保存")
            }
            if (!tempFile.renameTo(finalFile)) {
                tempFile.copyTo(finalFile, overwrite = true)
                tempFile.delete()
            }
            ImportResult(finalFile.absolutePath, "已设置歌单封面")
        } catch (_: Exception) {
            tempFile.delete()
            ImportResult(null, "歌单封面无法加载")
        }
    }

    fun clearCover(context: Context, playlistId: String) {
        File(context.applicationContext.filesDir, COVER_DIR)
            .resolve("${playlistId}.jpg")
            .delete()
    }

    private fun decodeScaledBitmap(context: Context, uri: Uri): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight)
        }
        return context.contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, options)
        }
    }

    private fun sampleSize(width: Int, height: Int): Int {
        var sample = 1
        var currentWidth = width
        var currentHeight = height
        while (currentWidth > MAX_DECODE_SIDE_PX || currentHeight > MAX_DECODE_SIDE_PX) {
            sample *= 2
            currentWidth /= 2
            currentHeight /= 2
        }
        return sample
    }
}
