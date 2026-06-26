package com.mica.music.ui.theme

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.palette.graphics.Palette
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val DynamicLightSourcePx = 64
private const val DynamicLightTexturePx = 8

@Composable
internal fun DynamicLightGlBackground(
    albumArtUri: String?,
    fallbackColor: Color,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val fallbackColorInt = fallbackColor.toArgb()
    var textureBitmap by remember(albumArtUri) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(albumArtUri) {
        textureBitmap = null
        val uri = albumArtUri?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        textureBitmap = loadDynamicLightTexture(context, uri)
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx -> DynamicLightGlBackgroundView(ctx) },
        update = { view ->
            view.setCover(textureBitmap, fallbackColorInt)
        },
        onRelease = { view -> view.release() },
    )
}

private suspend fun loadDynamicLightTexture(context: Context, albumArtUri: String): Bitmap? =
    withContext(Dispatchers.IO) {
        if (!MicaImageLoaders.isInitialized()) return@withContext null
        val result = MicaImageLoaders.background.execute(
            ImageRequest.Builder(context)
                .data(albumArtUri)
                .size(DynamicLightSourcePx, DynamicLightSourcePx)
                .scale(Scale.FILL)
                .allowHardware(false)
                .memoryCacheKey("dynamic-light-gl:$albumArtUri")
                .build(),
        )
        (result as? SuccessResult)?.drawable?.toDynamicLightTexture()
    }

private fun Drawable.toDynamicLightTexture(): Bitmap? =
    runCatching {
        val source = toSourceBitmap()
        val colors = source?.extractDynamicLightColors().orEmpty()
        if (source != null) {
            source.recycle()
        }
        createPaletteTexture(colors)
    }.getOrNull()

private fun Drawable.toSourceBitmap(): Bitmap? {
    val output = Bitmap.createBitmap(
        DynamicLightSourcePx,
        DynamicLightSourcePx,
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
            Rect(0, 0, DynamicLightSourcePx, DynamicLightSourcePx),
            TexturePaint,
        )
    } else {
        val oldBounds = copyBounds()
        setBounds(0, 0, DynamicLightSourcePx, DynamicLightSourcePx)
        draw(canvas)
        bounds = oldBounds
    }
    return output
}

private fun Bitmap.extractDynamicLightColors(): List<Int> {
    val palette = Palette.from(this)
        .maximumColorCount(12)
        .clearFilters()
        .generate()
    val usableSwatches = palette.swatches
        .filter { it.population > 0 }
    val primaryColors = usableSwatches
        .sortedByDescending { it.population }
        .map { softenColor(it.rgb) }
        .distinctBy { colorBucket(it) }
    val vividColors = listOfNotNull(
        palette.vibrantSwatch,
        palette.lightVibrantSwatch,
        palette.darkVibrantSwatch,
    )
        .plus(usableSwatches.sortedByDescending(::vividScore))
        .map { vividColor(it.rgb) }
        .distinctBy { colorBucket(it) }
    val primarySlots = fillColorSlots(primaryColors.ifEmpty { vividColors }, 4)
    val vividSlots = fillColorSlots(vividColors.ifEmpty { primarySlots }, 4)
    return limitRepeatedColors(primarySlots + vividSlots)
}

private fun createPaletteTexture(colors: List<Int>): Bitmap {
    val safeColors = colors.ifEmpty { listOf(0xff202020.toInt(), 0xff404040.toInt()) }
    val output = Bitmap.createBitmap(
        DynamicLightTexturePx,
        DynamicLightTexturePx,
        Bitmap.Config.ARGB_8888,
    )
    val swatches = List(DynamicLightTexturePx) { index ->
        safeColors.getOrElse(index) { safeColors[index % safeColors.size] }
    }
    for (y in 0 until DynamicLightTexturePx) {
        for (x in 0 until DynamicLightTexturePx) {
            output.setPixel(x, y, swatches[(x * swatches.size) / DynamicLightTexturePx])
        }
    }
    return output
}

private fun softenColor(color: Int): Int {
    val hsv = FloatArray(3)
    AndroidColor.colorToHSV(color, hsv)
    hsv[1] = (hsv[1] * 0.92f).coerceIn(0.16f, 0.82f)
    hsv[2] = (hsv[2] * 0.98f).coerceIn(0.20f, 0.92f)
    return AndroidColor.HSVToColor(0xff, hsv)
}

private fun vividColor(color: Int): Int {
    val hsv = FloatArray(3)
    AndroidColor.colorToHSV(color, hsv)
    hsv[1] = (hsv[1] * 1.08f).coerceIn(0.28f, 0.88f)
    hsv[2] = (hsv[2] * 1.00f).coerceIn(0.22f, 0.94f)
    return AndroidColor.HSVToColor(0xff, hsv)
}

private fun vividScore(swatch: Palette.Swatch): Float {
    val hsv = FloatArray(3)
    AndroidColor.colorToHSV(swatch.rgb, hsv)
    return hsv[1] * 1.8f + hsv[2] * 0.25f
}

private fun fillColorSlots(colors: List<Int>, count: Int): List<Int> {
    if (colors.isEmpty()) return emptyList()
    return List(count) { index -> colors.getOrElse(index) { colors[index % colors.size] } }
}

private fun limitRepeatedColors(colors: List<Int>): List<Int> {
    val source = colors.ifEmpty { listOf(0xff202020.toInt(), 0xff404040.toInt()) }
    val output = ArrayList<Int>(DynamicLightTexturePx)
    val bucketCounts = mutableMapOf<Int, Int>()
    var duplicateBucket: Int? = null
    var index = 0
    while (output.size < DynamicLightTexturePx) {
        val baseColor = source[index % source.size]
        var color = distinctColorForSlot(baseColor, output.size, bucketCounts, duplicateBucket)
        var bucket = colorBucket(color)
        if (!canAddBucket(bucket, bucketCounts, duplicateBucket)) {
            color = distinctColorVariant(baseColor, output.size + 17)
            bucket = colorBucket(color)
        }
        if ((bucketCounts[bucket] ?: 0) == 1 && duplicateBucket == null) {
            duplicateBucket = bucket
        }
        bucketCounts[bucket] = (bucketCounts[bucket] ?: 0) + 1
        output += color
        index++
    }
    return output
}

private fun distinctColorForSlot(
    color: Int,
    slot: Int,
    bucketCounts: Map<Int, Int>,
    duplicateBucket: Int?,
): Int {
    if (canAddBucket(colorBucket(color), bucketCounts, duplicateBucket)) return color
    repeat(48) { attempt ->
        val variant = distinctColorVariant(color, slot + attempt)
        if (canAddBucket(colorBucket(variant), bucketCounts, duplicateBucket)) return variant
    }
    repeat(48) { attempt ->
        val fallback = fallbackDistinctColor(slot + attempt)
        if (canAddBucket(colorBucket(fallback), bucketCounts, duplicateBucket)) return fallback
    }
    return color
}

private fun canAddBucket(
    bucket: Int,
    bucketCounts: Map<Int, Int>,
    duplicateBucket: Int?,
): Boolean {
    val count = bucketCounts[bucket] ?: 0
    return count == 0 || (count == 1 && duplicateBucket == null)
}

private fun distinctColorVariant(color: Int, index: Int): Int {
    val hsv = FloatArray(3)
    AndroidColor.colorToHSV(color, hsv)
    val hueShift = 9f + (index % 12) * 11f
    hsv[0] = (hsv[0] + hueShift) % 360f
    hsv[1] = (hsv[1] * (0.94f + (index % 3) * 0.04f)).coerceIn(0.18f, 0.90f)
    hsv[2] = (hsv[2] * (0.96f + (index % 2) * 0.08f)).coerceIn(0.20f, 0.94f)
    return AndroidColor.HSVToColor(0xff, hsv)
}

private fun fallbackDistinctColor(index: Int): Int {
    val hsv = floatArrayOf(
        ((index * 47) % 360).toFloat(),
        0.42f + (index % 4) * 0.10f,
        0.54f + (index % 3) * 0.11f,
    )
    return AndroidColor.HSVToColor(0xff, hsv)
}

private fun colorBucket(color: Int): Int {
    val r = AndroidColor.red(color) / 32
    val g = AndroidColor.green(color) / 32
    val b = AndroidColor.blue(color) / 32
    return r * 64 + g * 8 + b
}

private val TexturePaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
private val PalettePaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
