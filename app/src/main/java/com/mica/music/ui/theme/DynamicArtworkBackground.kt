package com.mica.music.ui.theme

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import coil.request.ImageRequest
import coil.request.SuccessResult
import coil.size.Scale
import com.mica.music.imaging.MicaImageLoaders
import com.mica.music.ui.motion.rememberMicaMotionEnabled
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val DynamicArtworkSourcePx = 384

@Composable
internal fun DynamicArtworkBackground(
    albumArtUri: String?,
    fallbackColor: Color,
    isDark: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val motionEnabled = rememberMicaMotionEnabled()
    val fallbackColorInt = fallbackColor.toArgb()
    val currentUri = albumArtUri?.takeIf { it.isNotBlank() }
    var loadedArtwork by remember { mutableStateOf<DynamicArtworkLoad?>(null) }

    LaunchedEffect(currentUri) {
        if (currentUri == null) {
            loadedArtwork = DynamicArtworkLoad(uri = null, bitmap = null)
            return@LaunchedEffect
        }
        val bitmap = loadDynamicArtworkBitmap(context, currentUri)
        loadedArtwork = DynamicArtworkLoad(uri = currentUri, bitmap = bitmap)
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx -> DynamicArtworkBackgroundView(ctx) },
        update = { view ->
            view.setFallbackColor(fallbackColorInt)
            view.setDarkScrim(isDark)
            view.setMotionEnabled(motionEnabled)
            view.setReducedEffects(!motionEnabled)

            val loaded = loadedArtwork
            when {
                currentUri == null -> view.clearArtwork()
                loaded?.uri == currentUri && loaded.bitmap != null ->
                    view.setArtwork(loaded.bitmap, stableArtworkSeed(currentUri))
                loaded?.uri == currentUri -> view.clearArtwork()
            }
        },
        onRelease = { view -> view.release() },
    )
}

private data class DynamicArtworkLoad(
    val uri: String?,
    val bitmap: Bitmap?,
)

private suspend fun loadDynamicArtworkBitmap(context: Context, albumArtUri: String): Bitmap? =
    withContext(Dispatchers.IO) {
        if (!MicaImageLoaders.isInitialized()) return@withContext null
        val result = MicaImageLoaders.background.execute(
            ImageRequest.Builder(context)
                .data(albumArtUri)
                .size(DynamicArtworkSourcePx, DynamicArtworkSourcePx)
                .scale(Scale.FILL)
                .allowHardware(false)
                .memoryCacheKey("dynamic-artwork:$albumArtUri")
                .build(),
        )
        (result as? SuccessResult)?.drawable?.toDynamicArtworkBitmap()
    }

private fun Drawable.toDynamicArtworkBitmap(): Bitmap? =
    runCatching {
        val output = Bitmap.createBitmap(
            DynamicArtworkSourcePx,
            DynamicArtworkSourcePx,
            Bitmap.Config.ARGB_8888,
        )
        val canvas = Canvas(output)
        val bitmap = (this as? BitmapDrawable)?.bitmap
        if (bitmap != null && !bitmap.isRecycled && bitmap.width > 0 && bitmap.height > 0) {
            val side = minOf(bitmap.width, bitmap.height)
            val left = (bitmap.width - side) / 2
            val top = (bitmap.height - side) / 2
            canvas.drawBitmap(
                bitmap,
                Rect(left, top, left + side, top + side),
                Rect(0, 0, DynamicArtworkSourcePx, DynamicArtworkSourcePx),
                ArtworkDecodePaint,
            )
        } else {
            val oldBounds = copyBounds()
            setBounds(0, 0, DynamicArtworkSourcePx, DynamicArtworkSourcePx)
            draw(canvas)
            bounds = oldBounds
        }
        output
    }.getOrNull()

private fun stableArtworkSeed(value: String): Long {
    var hash = -0x340d631b7bdddcdbL
    for (char in value) {
        hash = hash xor char.code.toLong()
        hash *= 0x100000001b3L
    }
    return hash
}

private val ArtworkDecodePaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
