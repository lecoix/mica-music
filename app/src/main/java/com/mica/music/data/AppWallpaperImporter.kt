package com.mica.music.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File
import java.io.FileOutputStream

object AppWallpaperImporter {
    private const val WALLPAPER_DIR = "app_wallpaper"
    private const val MAX_DECODE_SIDE_PX = 2160

    data class ImportResult(
        val path: String?,
        val message: String,
    )

    fun importWallpaper(context: Context, uri: Uri): ImportResult {
        val appContext = context.applicationContext
        val dir = File(appContext.filesDir, WALLPAPER_DIR)
        if (!dir.exists() && !dir.mkdirs()) {
            return ImportResult(null, "无法创建壁纸目录")
        }

        val fileStamp = System.currentTimeMillis()
        val tempFile = File(dir, "wallpaper.$fileStamp.importing.jpg")
        val finalFile = File(dir, "wallpaper.$fileStamp.jpg")

        return try {
            val bitmap = decodeScaledBitmap(appContext, uri)
                ?: return ImportResult(null, "无法读取图片文件")
            val saved = FileOutputStream(tempFile).use { output ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output)
            }
            bitmap.recycle()
            if (!saved) {
                tempFile.delete()
                return ImportResult(null, "壁纸图片无法保存")
            }

            if (!tempFile.renameTo(finalFile)) {
                tempFile.copyTo(finalFile, overwrite = true)
                tempFile.delete()
            }
            dir.listFiles()?.forEach { file ->
                if (file != finalFile) file.delete()
            }

            ImportResult(finalFile.absolutePath, "已设置自定义壁纸")
        } catch (_: Exception) {
            tempFile.delete()
            ImportResult(null, "壁纸图片无法加载")
        }
    }

    fun clearWallpaper(context: Context) {
        File(context.applicationContext.filesDir, WALLPAPER_DIR)
            .listFiles()
            ?.forEach { it.delete() }
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
