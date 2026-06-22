package com.mica.music.ui.screens.player.view

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import android.webkit.WebResourceResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import kotlin.math.min

internal data class ThreeParticleCoverTextureSource(
    val url: String,
    val bytes: Long,
    val cacheHit: Boolean,
)

internal class ThreeParticleCoverTextureStore(context: Context) {

    private val cacheDir = context.cacheDir.resolve(CacheDirName)

    suspend fun prepareTexture(
        cacheKey: String,
        bitmap: Bitmap,
    ): ThreeParticleCoverTextureSource = withContext(Dispatchers.IO) {
        cacheDir.mkdirs()
        val file = cacheDir.resolve("${hash("$TextureVersion:$cacheKey")}.jpg")
        val cacheHit = file.isFile && file.length() > 0L
        if (!cacheHit) {
            val tmp = cacheDir.resolve("${file.name}.tmp")
            writeJpeg(bitmap, tmp)
            if (file.exists()) file.delete()
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
            prune()
        }
        ThreeParticleCoverTextureSource(
            url = "$UrlPrefix/${file.name}",
            bytes = file.length(),
            cacheHit = cacheHit,
        )
    }

    fun intercept(uri: Uri): WebResourceResponse? {
        if (uri.scheme != "https" || uri.host != Host) return null
        val name = uri.lastPathSegment?.takeIf { it.matches(FileNameRegex) } ?: return null
        val file = cacheDir.resolve(name)
        val canonicalCacheDir = cacheDir.canonicalFile
        val canonicalFile = file.canonicalFile
        if (!canonicalFile.isFile || canonicalFile.parentFile != canonicalCacheDir) return null
        file.setLastModified(System.currentTimeMillis())
        return WebResourceResponse(
            "image/jpeg",
            null,
            200,
            "OK",
            mapOf(
                "Access-Control-Allow-Origin" to "*",
                "Cache-Control" to "max-age=86400",
            ),
            file.inputStream(),
        )
    }

    private fun writeJpeg(bitmap: Bitmap, file: File) {
        val uploadBitmap = bitmap.asSquareUploadBitmap()
        try {
            FileOutputStream(file).use { output ->
                uploadBitmap.compress(Bitmap.CompressFormat.JPEG, JpegQuality, output)
            }
        } finally {
            if (uploadBitmap !== bitmap) uploadBitmap.recycle()
        }
    }

    private fun Bitmap.asSquareUploadBitmap(): Bitmap {
        val source = if (config == Bitmap.Config.HARDWARE) {
            copy(Bitmap.Config.ARGB_8888, false)
        } else {
            this
        }
        if (source.width == source.height) return source
        val side = min(source.width, source.height).coerceAtLeast(1)
        val left = ((source.width - side) / 2).coerceAtLeast(0)
        val top = ((source.height - side) / 2).coerceAtLeast(0)
        val cropped = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        Canvas(cropped).drawBitmap(
            source,
            Rect(left, top, left + side, top + side),
            Rect(0, 0, side, side),
            BitmapUploadPaint,
        )
        if (source !== this) source.recycle()
        return cropped
    }

    private fun prune() {
        val files = cacheDir.listFiles { file -> file.isFile && file.name.matches(FileNameRegex) }
            ?.sortedByDescending { it.lastModified() }
            ?: return
        files.drop(MaxFiles).forEach { it.delete() }
    }

    private fun hash(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString(separator = "") { "%02x".format(it) }
    }

    private companion object {
        private const val CacheDirName = "particle_cover_web"
        private const val Host = "mica-particle-cover.local"
        private const val UrlPrefix = "https://$Host"
        private const val TextureVersion = "square-fill-v1"
        private const val JpegQuality = 96
        private const val MaxFiles = 48
        private val BitmapUploadPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
        private val FileNameRegex = Regex("[0-9a-f]{64}\\.jpg")
    }
}
