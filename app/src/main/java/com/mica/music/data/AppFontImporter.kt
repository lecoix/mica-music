package com.mica.music.data

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

object AppFontImporter {
    private const val LYRIC_FONTS_DIR = "lyric_fonts"
    private const val LYRIC_FONT_BASENAME = "lyric_font"
    private const val MAX_FONT_SIZE_BYTES = 100L * 1024L * 1024L
    private val SupportedExtensions = setOf("ttf", "otf")

    data class ImportResult(
        val selection: AppFontSelection?,
        val message: String,
    )

    fun importLyricFont(context: Context, uri: Uri): ImportResult {
        val appContext = context.applicationContext
        val displayName = displayNameForUri(appContext, uri)
            ?: uri.lastPathSegment?.substringAfterLast('/')
            ?: "lyric_font"
        val extension = displayName.substringAfterLast('.', missingDelimiterValue = "")
            .lowercase(Locale.ROOT)
        if (extension !in SupportedExtensions) {
            return ImportResult(null, "仅支持 TTF / OTF 字体")
        }
        val declaredSize = sizeForUri(appContext, uri)
        if (declaredSize != null && declaredSize > MAX_FONT_SIZE_BYTES) {
            return ImportResult(null, "字体文件不能超过 100MB")
        }

        val dir = File(appContext.filesDir, LYRIC_FONTS_DIR)
        if (!dir.exists() && !dir.mkdirs()) {
            return ImportResult(null, "无法创建字体目录")
        }

        val tempFile = File(dir, "$LYRIC_FONT_BASENAME.importing.$extension")
        val finalFile = File(dir, "$LYRIC_FONT_BASENAME.$extension")

        return try {
            appContext.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tempFile).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var copiedBytes = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        copiedBytes += read
                        if (copiedBytes > MAX_FONT_SIZE_BYTES) {
                            throw FontTooLargeException()
                        }
                        output.write(buffer, 0, read)
                    }
                }
            } ?: return ImportResult(null, "无法读取字体文件")

            Typeface.createFromFile(tempFile)

            dir.listFiles()?.forEach { file ->
                if (file != tempFile) file.delete()
            }
            if (finalFile.exists()) finalFile.delete()
            if (!tempFile.renameTo(finalFile)) {
                tempFile.copyTo(finalFile, overwrite = true)
                tempFile.delete()
            }

            ImportResult(
                selection = AppFontSelection(
                    source = AppFontSource.IMPORTED,
                    displayName = displayName.substringBeforeLast('.').ifBlank { displayName },
                    filePath = finalFile.absolutePath,
                ),
                message = "已导入歌词字体",
            )
        } catch (_: FontTooLargeException) {
            tempFile.delete()
            ImportResult(null, "字体文件不能超过 100MB")
        } catch (_: Exception) {
            tempFile.delete()
            ImportResult(null, "字体文件无法加载")
        }
    }

    fun clearLyricFont(context: Context) {
        File(context.applicationContext.filesDir, LYRIC_FONTS_DIR)
            .listFiles()
            ?.forEach { it.delete() }
    }

    private fun displayNameForUri(context: Context, uri: Uri): String? {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) return cursor.getString(index)
            }
        }
        return null
    }

    private fun sizeForUri(context: Context, uri: Uri): Long? {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (index >= 0 && !cursor.isNull(index)) return cursor.getLong(index)
            }
        }
        return null
    }

    private class FontTooLargeException : Exception()
}
