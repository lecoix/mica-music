package com.mica.music.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import java.io.File
import java.io.FileOutputStream

object AppWallpaperImporter {
    private const val WALLPAPER_DIR = "app_wallpaper"
    private const val MAX_DECODE_SIDE_PX = 2160

    data class ImportResult(
        val applied: Boolean,
        val message: String,
    )

    internal fun wallpaperDirectory(context: Context): File =
        File(context.applicationContext.filesDir, WALLPAPER_DIR)

    internal fun validStoredPath(path: String?): String? = path
        ?.takeIf { it.isNotBlank() }
        ?.let(::File)
        ?.takeIf { it.isFile && it.length() > 0L }
        ?.absolutePath

    /** Writes one fully encoded candidate. Publication and old-file cleanup belong to [AppWallpaperStore]. */
    internal fun writeCandidate(context: Context, uri: Uri, destination: File): Boolean {
        val appContext = context.applicationContext
        val decoded = decodeScaledBitmap(appContext, uri) ?: return false
        val oriented = applyExifRotation(appContext, uri, decoded)
        return try {
            FileOutputStream(destination).use { output ->
                oriented.compress(Bitmap.CompressFormat.JPEG, 90, output)
            }
        } finally {
            if (oriented !== decoded) oriented.recycle()
            decoded.recycle()
        }
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

    private fun applyExifRotation(context: Context, uri: Uri, bitmap: Bitmap): Bitmap {
        val orientation = runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                ExifInterface(input).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL,
                )
            }
        }.getOrNull() ?: ExifInterface.ORIENTATION_NORMAL
        val degrees = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (degrees == 0f) return bitmap
        return Bitmap.createBitmap(
            bitmap,
            0,
            0,
            bitmap.width,
            bitmap.height,
            Matrix().apply { postRotate(degrees) },
            true,
        )
    }
}
