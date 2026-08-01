package com.mica.music.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File
import java.io.FileOutputStream

object AppLetterSealImporter {
    private const val SEAL_DIR = "letter_seal"
    private const val MAX_OUTPUT_SIDE_PX = 512
    private const val MAX_DECODE_LONG_SIDE_PX = 2048
    private const val MAX_DECODE_SHORT_SIDE_PX = 1024

    data class ImportResult(
        val path: String?,
        val message: String,
    )

    fun importSeal(context: Context, uri: Uri): ImportResult {
        val appContext = context.applicationContext
        val dir = File(appContext.filesDir, SEAL_DIR)
        if (!dir.exists() && !dir.mkdirs()) {
            return ImportResult(null, "无法创建朱印图片目录")
        }

        val fileStamp = System.currentTimeMillis()
        val tempFile = File(dir, "seal.$fileStamp.importing.png")
        val finalFile = File(dir, "seal.$fileStamp.png")

        return try {
            val decoded = decodeScaledBitmap(appContext, uri)
                ?: return ImportResult(null, "无法读取朱印图片")
            val square = centerCropSquare(decoded)
            val output = if (square.width > MAX_OUTPUT_SIDE_PX) {
                Bitmap.createScaledBitmap(
                    square,
                    MAX_OUTPUT_SIDE_PX,
                    MAX_OUTPUT_SIDE_PX,
                    true,
                ).also {
                    if (it !== square) square.recycle()
                }
            } else {
                square
            }
            if (decoded !== square && decoded !== output) decoded.recycle()
            output.setHasAlpha(true)

            val saved = FileOutputStream(tempFile).use { stream ->
                output.compress(Bitmap.CompressFormat.PNG, 100, stream)
            }
            output.recycle()
            if (!saved) {
                tempFile.delete()
                return ImportResult(null, "朱印图片无法保存")
            }

            if (!tempFile.renameTo(finalFile)) {
                tempFile.copyTo(finalFile, overwrite = true)
                tempFile.delete()
            }
            dir.listFiles()?.forEach { file ->
                if (file != finalFile) file.delete()
            }

            ImportResult(finalFile.absolutePath, "已设置自定义信笺朱印")
        } catch (_: Exception) {
            tempFile.delete()
            ImportResult(null, "朱印图片无法加载")
        }
    }

    fun clearSeal(context: Context) {
        File(context.applicationContext.filesDir, SEAL_DIR)
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

    private fun centerCropSquare(bitmap: Bitmap): Bitmap {
        if (bitmap.width == bitmap.height) return bitmap
        val side = minOf(bitmap.width, bitmap.height)
        val left = (bitmap.width - side) / 2
        val top = (bitmap.height - side) / 2
        return Bitmap.createBitmap(bitmap, left, top, side, side)
    }

    private fun sampleSize(width: Int, height: Int): Int {
        var sample = 1
        var longSide = maxOf(width, height)
        var shortSide = minOf(width, height)
        while (
            longSide > MAX_DECODE_LONG_SIDE_PX ||
            shortSide > MAX_DECODE_SHORT_SIDE_PX
        ) {
            sample *= 2
            longSide /= 2
            shortSide /= 2
        }
        return sample
    }
}
