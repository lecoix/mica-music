package com.mica.music.ui.theme

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import androidx.palette.graphics.Palette
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Generates the scene behind the wet-glass shader.
 *
 * The album artwork is used only as a palette source. No artwork pixels are copied into the
 * backdrop: the texture itself is a stable rainy-night environment made from low-frequency
 * gradients, haze, soft lights and vertical light streaks.
 */
internal object RainGlassBackdropFactory {

    fun create(
        sourceArtwork: Bitmap?,
        fallbackArgb: Int,
        seed: Long,
        size: Int,
    ): Bitmap {
        val palette = sourceArtwork
            ?.takeIf { !it.isRecycled && it.width > 0 && it.height > 0 }
            ?.let {
                Palette.from(it)
                    .clearFilters()
                    .maximumColorCount(12)
                    .generate()
            }

        val primaryRaw = palette?.getDarkMutedColor(0)
            ?.takeIf { it != 0 }
            ?: palette?.getMutedColor(0)?.takeIf { it != 0 }
            ?: palette?.getDominantColor(0)?.takeIf { it != 0 }
            ?: fallbackArgb
        val secondaryRaw = palette?.getMutedColor(0)
            ?.takeIf { it != 0 && it != primaryRaw }
            ?: palette?.getVibrantColor(0)?.takeIf { it != 0 }
            ?: fallbackArgb
        val accentRaw = palette?.getLightVibrantColor(0)
            ?.takeIf { it != 0 }
            ?: palette?.getVibrantColor(0)?.takeIf { it != 0 }
            ?: palette?.getLightMutedColor(0)?.takeIf { it != 0 }
            ?: fallbackArgb

        val primary = desaturate(primaryRaw, 0.48f)
        val secondary = desaturate(secondaryRaw, 0.58f)
        val accent = desaturate(accentRaw, 0.42f)

        val baseTop = mixColor(0xFF24384A.toInt(), darken(primary, 0.96f), 0.38f)
        val baseMid = mixColor(0xFF182635.toInt(), darken(secondary, 0.82f), 0.28f)
        val baseBottom = mixColor(0xFF0D131C.toInt(), darken(primary, 0.62f), 0.18f)

        val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)

        paint.shader = LinearGradient(
            0f,
            0f,
            0f,
            size.toFloat(),
            intArrayOf(baseTop, baseMid, baseBottom),
            floatArrayOf(0f, 0.48f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, size.toFloat(), size.toFloat(), paint)
        paint.shader = null

        // Fixed environment skeleton. Artwork colors tint it, but do not change its composition.
        // Broad backlights lift the glass mid-tones without flattening the whole scene.
        drawGlow(canvas, paint, size, 0.34f, 0.34f, 0.50f, 0xFFC5D7E8.toInt(), 52)
        drawGlow(canvas, paint, size, 0.76f, 0.54f, 0.36f, mixColor(0xFFFFD39A.toInt(), accent, 0.26f), 46)
        drawGlow(canvas, paint, size, 0.26f, 0.24f, 0.72f, primary, 108)
        drawGlow(canvas, paint, size, 0.79f, 0.42f, 0.60f, secondary, 92)
        drawGlow(canvas, paint, size, 0.56f, 0.76f, 0.52f, mixColor(0xFFFFC77A.toInt(), accent, 0.32f), 98)
        drawGlow(canvas, paint, size, 0.50f, 0.52f, 0.92f, 0xFFB8C9D9.toInt(), 36)

        val rng = Random((seed xor (seed ushr 32)).toInt())
        val warmLight = mixColor(0xFFFFC47A.toInt(), accent, 0.28f)
        val coolLight = mixColor(0xFF9FCBFF.toInt(), secondary, 0.34f)
        val neutralLight = mixColor(0xFFDCE5ED.toInt(), primary, 0.18f)

        // Portrait playback samples roughly the middle half of this square texture. Keep the
        // low-frequency scene structure inside that useful region so wet-glass refraction has
        // real luminance edges to bend instead of seeing only a near-flat gradient.
        drawSoftGeometry(
            canvas = canvas,
            paint = paint,
            size = size,
            seed = seed xor 0x31A7C9E4L,
            primary = primary,
            secondary = secondary,
        )

        drawLargeLightSources(
            canvas = canvas,
            paint = paint,
            size = size,
            warmLight = warmLight,
            coolLight = coolLight,
            neutralLight = neutralLight,
        )

        // Soft out-of-focus city lights. The seed only jitters placement/size so each album has
        // a stable atmosphere without exposing any recognisable artwork structure.
        repeat(10) { index ->
            val x = 0.08f + rng.nextFloat() * 0.84f
            val y = 0.12f + rng.nextFloat() * 0.76f
            val radius = 0.026f + rng.nextFloat() * 0.060f
            val color = when (index % 3) {
                0 -> warmLight
                1 -> coolLight
                else -> neutralLight
            }
            val alpha = 78 + rng.nextInt(58)
            drawGlow(canvas, paint, size, x, y, radius, color, alpha)
        }

        // Three stronger anchors survive the dry-glass blur and give droplet lenses something
        // concrete to bend without turning the background into a recognisable picture.
        drawGlow(canvas, paint, size, 0.22f, 0.40f, 0.050f, warmLight, 150)
        drawGlow(canvas, paint, size, 0.72f, 0.29f, 0.044f, coolLight, 142)
        drawGlow(canvas, paint, size, 0.64f, 0.68f, 0.058f, neutralLight, 118)

        // A few diffuse vertical light columns make refraction read like a rainy window instead
        // of a flat gradient. They are intentionally low contrast and sit behind the fog.
        val streakXs = floatArrayOf(0.16f, 0.58f, 0.82f)
        streakXs.forEachIndexed { index, x ->
            val jitter = (rng.nextFloat() - 0.5f) * 0.035f
            val halfWidth = size * (0.040f + rng.nextFloat() * 0.028f)
            val centerX = size * (x + jitter)
            val top = size * (0.08f + rng.nextFloat() * 0.20f)
            val bottom = size * (0.76f + rng.nextFloat() * 0.20f)
            val color = if (index == 1) warmLight else coolLight
            paint.shader = LinearGradient(
                centerX - halfWidth,
                0f,
                centerX + halfWidth,
                0f,
                intArrayOf(
                    AndroidColor.TRANSPARENT,
                    withAlpha(color, 30 + index * 5),
                    AndroidColor.TRANSPARENT,
                ),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP,
            )
            canvas.drawRect(centerX - halfWidth, top, centerX + halfWidth, bottom, paint)
            paint.shader = null
        }

        // Sharp enough to vanish under dry-glass blur and reappear inside drops / wet streaks.
        // Portrait sampling only sees the horizontal centre of this square texture.
        drawRecoverableStructure(
            canvas = canvas,
            paint = paint,
            size = size,
            seed = seed xor 0x6B1E4A0DL,
            warmLight = warmLight,
            coolLight = coolLight,
            neutralLight = neutralLight,
        )

        // Darken the outer pane. This is part of the fixed environment, not droplet shading.
        paint.shader = RadialGradient(
            size * 0.50f,
            size * 0.47f,
            size * 0.78f,
            intArrayOf(AndroidColor.TRANSPARENT, 0x50020509.toInt()),
            floatArrayOf(0.45f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, size.toFloat(), size.toFloat(), paint)
        paint.shader = null

        return output
    }

    /**
     * Very soft, deliberately non-descriptive geometry behind the glass. These shapes only provide
     * broad luminance boundaries (distant facades / horizon / road-like bands) for the droplet
     * lenses to distort. They must never resolve into a recognisable scene.
     */
    private fun drawSoftGeometry(
        canvas: Canvas,
        paint: Paint,
        size: Int,
        seed: Long,
        primary: Int,
        secondary: Int,
    ) {
        val rng = Random((seed xor (seed ushr 32)).toInt())
        val darkStructure = mixColor(0xFF0B1118.toInt(), darken(primary, 0.62f), 0.24f)
        val midStructure = mixColor(0xFF334454.toInt(), desaturate(secondary, 0.72f), 0.18f)
        val blur = BlurMaskFilter(size * 0.014f, BlurMaskFilter.Blur.NORMAL)

        paint.maskFilter = blur
        repeat(4) { index ->
            val center = 0.32f + index * 0.105f + (rng.nextFloat() - 0.5f) * 0.028f
            val halfWidth = 0.045f + rng.nextFloat() * 0.035f
            val top = 0.15f + rng.nextFloat() * 0.17f
            val bottom = 0.48f + rng.nextFloat() * 0.20f
            paint.color = withAlpha(
                if (index % 2 == 0) darkStructure else midStructure,
                68 + rng.nextInt(36),
            )
            canvas.drawRect(
                RectF(
                    size * (center - halfWidth),
                    size * top,
                    size * (center + halfWidth),
                    size * bottom,
                ),
                paint,
            )
        }

        // Two broad pane-like gradients make the structure immediately visible after the
        // shader's dry-glass blur, while remaining too soft to read as literal architecture.
        paint.maskFilter = BlurMaskFilter(size * 0.010f, BlurMaskFilter.Blur.NORMAL)
        paint.shader = LinearGradient(
            size * 0.34f,
            0f,
            size * 0.48f,
            0f,
            intArrayOf(
                withAlpha(darkStructure, 18),
                withAlpha(darkStructure, 112),
                AndroidColor.TRANSPARENT,
            ),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(size * 0.30f, size * 0.18f, size * 0.49f, size * 0.62f, paint)

        paint.shader = LinearGradient(
            size * 0.54f,
            0f,
            size * 0.70f,
            0f,
            intArrayOf(
                AndroidColor.TRANSPARENT,
                withAlpha(midStructure, 104),
                withAlpha(midStructure, 16),
            ),
            floatArrayOf(0f, 0.48f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(size * 0.52f, size * 0.24f, size * 0.72f, size * 0.67f, paint)

        // A hazy horizontal boundary and a wider lower band suggest depth without drawing a city.
        paint.maskFilter = BlurMaskFilter(size * 0.022f, BlurMaskFilter.Blur.NORMAL)
        paint.shader = LinearGradient(
            0f,
            size * 0.48f,
            0f,
            size * 0.66f,
            intArrayOf(
                AndroidColor.TRANSPARENT,
                withAlpha(midStructure, 76),
                withAlpha(darkStructure, 88),
                AndroidColor.TRANSPARENT,
            ),
            floatArrayOf(0f, 0.32f, 0.66f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(
            size * 0.26f,
            size * 0.45f,
            size * 0.74f,
            size * 0.70f,
            paint,
        )

        // A low-contrast diagonal band behaves like a distant road/reflection once refracted.
        paint.shader = null
        paint.color = withAlpha(mixColor(midStructure, 0xFFFFD7A3.toInt(), 0.30f), 74)
        canvas.save()
        canvas.rotate(-8f, size * 0.52f, size * 0.72f)
        canvas.drawRoundRect(
            RectF(size * 0.28f, size * 0.68f, size * 0.76f, size * 0.76f),
            size * 0.04f,
            size * 0.04f,
            paint,
        )
        canvas.restore()

        paint.maskFilter = null
        paint.shader = null
    }

    /**
     * Mid-frequency structure for wet trails to cut through. Drawn without blur so dry-glass
     * fog can hide it and a sharper drop/trail can reveal it.
     */
    private fun drawRecoverableStructure(
        canvas: Canvas,
        paint: Paint,
        size: Int,
        seed: Long,
        warmLight: Int,
        coolLight: Int,
        neutralLight: Int,
    ) {
        val rng = Random((seed xor (seed ushr 32)).toInt())
        paint.maskFilter = null
        paint.shader = null

        repeat(9) { index ->
            val x = 0.31f + index * 0.045f + (rng.nextFloat() - 0.5f) * 0.012f
            val halfWidth = size * (0.0035f + rng.nextFloat() * 0.0045f)
            val centerX = size * x
            val top = size * (0.16f + rng.nextFloat() * 0.10f)
            val bottom = size * (0.62f + rng.nextFloat() * 0.18f)
            val color = when (index % 3) {
                0 -> warmLight
                1 -> coolLight
                else -> neutralLight
            }
            paint.shader = LinearGradient(
                centerX - halfWidth,
                0f,
                centerX + halfWidth,
                0f,
                intArrayOf(
                    AndroidColor.TRANSPARENT,
                    withAlpha(color, 70 + rng.nextInt(50)),
                    AndroidColor.TRANSPARENT,
                ),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP,
            )
            canvas.drawRect(centerX - halfWidth, top, centerX + halfWidth, bottom, paint)
        }
        paint.shader = null

        repeat(7) { index ->
            val x = 0.33f + rng.nextFloat() * 0.34f
            val y = 0.22f + rng.nextFloat() * 0.50f
            val color = when (index % 3) {
                0 -> warmLight
                1 -> coolLight
                else -> neutralLight
            }
            drawGlow(canvas, paint, size, x, y, 0.010f + rng.nextFloat() * 0.012f, color, 160 + rng.nextInt(50))
        }
    }

    /** Large backlights that survive the dry-glass blur and become obvious inside droplet lenses. */
    private fun drawLargeLightSources(
        canvas: Canvas,
        paint: Paint,
        size: Int,
        warmLight: Int,
        coolLight: Int,
        neutralLight: Int,
    ) {
        // Keep these in the portrait-visible center of the source texture.
        drawGlow(canvas, paint, size, 0.37f, 0.30f, 0.18f, coolLight, 132)
        drawGlow(canvas, paint, size, 0.63f, 0.43f, 0.20f, warmLight, 144)
        drawGlow(canvas, paint, size, 0.51f, 0.72f, 0.17f, neutralLight, 106)

        // Compact hot cores inside the broad glows create gradients that a droplet can magnify.
        drawGlow(canvas, paint, size, 0.37f, 0.30f, 0.048f, coolLight, 176)
        drawGlow(canvas, paint, size, 0.63f, 0.43f, 0.055f, warmLight, 184)
        drawGlow(canvas, paint, size, 0.51f, 0.72f, 0.044f, neutralLight, 142)
    }

    private fun drawGlow(
        canvas: Canvas,
        paint: Paint,
        size: Int,
        x: Float,
        y: Float,
        radiusFraction: Float,
        color: Int,
        alpha: Int,
    ) {
        val radius = (size * radiusFraction).coerceAtLeast(1f)
        paint.shader = RadialGradient(
            size * x,
            size * y,
            radius,
            intArrayOf(withAlpha(color, alpha), withAlpha(color, 0)),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(size * x, size * y, radius, paint)
        paint.shader = null
    }

    private fun desaturate(argb: Int, amount: Float): Int {
        val t = amount.coerceIn(0f, 1f)
        val r = AndroidColor.red(argb)
        val g = AndroidColor.green(argb)
        val b = AndroidColor.blue(argb)
        val gray = (r * 0.299f + g * 0.587f + b * 0.114f).roundToInt().coerceIn(0, 255)
        return AndroidColor.rgb(
            mixChannel(r, gray, t),
            mixChannel(g, gray, t),
            mixChannel(b, gray, t),
        )
    }

    private fun darken(argb: Int, factor: Float): Int {
        val f = factor.coerceIn(0f, 1f)
        return AndroidColor.rgb(
            (AndroidColor.red(argb) * f).roundToInt().coerceIn(0, 255),
            (AndroidColor.green(argb) * f).roundToInt().coerceIn(0, 255),
            (AndroidColor.blue(argb) * f).roundToInt().coerceIn(0, 255),
        )
    }

    private fun mixColor(a: Int, b: Int, amount: Float): Int {
        val t = amount.coerceIn(0f, 1f)
        return AndroidColor.rgb(
            mixChannel(AndroidColor.red(a), AndroidColor.red(b), t),
            mixChannel(AndroidColor.green(a), AndroidColor.green(b), t),
            mixChannel(AndroidColor.blue(a), AndroidColor.blue(b), t),
        )
    }

    private fun mixChannel(a: Int, b: Int, t: Float): Int =
        (a + (b - a) * t).roundToInt().coerceIn(0, 255)

    private fun withAlpha(argb: Int, alpha: Int): Int =
        AndroidColor.argb(
            alpha.coerceIn(0, 255),
            AndroidColor.red(argb),
            AndroidColor.green(argb),
            AndroidColor.blue(argb),
        )
}
