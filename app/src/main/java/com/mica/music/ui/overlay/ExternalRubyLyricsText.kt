package com.mica.music.ui.overlay

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.sp
import com.mica.music.data.ExternalLyricsStyle
import com.mica.music.media.ExternalLyricsLine
import com.mica.music.media.ExternalLyricsText
import com.mica.music.ui.components.RubyTextLayout
import com.mica.music.ui.components.marqueeHorizontalEdgeFade
import com.mica.music.ui.components.measureRubyText
import com.mica.music.ui.theme.MicaTheme
import kotlin.math.ceil
import kotlin.math.max

/** One measured canvas and one marquee for the complete base/reading pair, including all paint layers. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ExternalRubyLyricsText(
    text: ExternalLyricsText,
    line: ExternalLyricsLine,
    positionMs: Int,
    fontSizeSp: Int,
    style: ExternalLyricsStyle,
    marquee: Boolean,
    textAlign: TextAlign,
) {
    val measurer = rememberTextMeasurer(cacheSize = 64)
    val typography = MicaTheme.typography.lyricCurrent
    val baseStyle = remember(typography, fontSizeSp) {
        typography.copy(fontSize = fontSizeSp.sp, lineHeight = (fontSizeSp * 1.45f).sp,
            fontWeight = FontWeight.Normal, color = Color.Unspecified, shadow = null)
    }
    // Position and effect/color settings cannot invalidate text shaping or group geometry.
    val geometry = remember(text.text, text.ruby, baseStyle, measurer) {
        measureRubyText(text.text, text.ruby, baseStyle, measurer, textAlign = TextAlign.Start)
    }
    val effects = remember(style.shadowStrengthFraction, style.glowStrengthFraction) {
        externalLyricsEffectTuning(style.shadowStrengthFraction, style.glowStrengthFraction)
    }
    val shadow = remember(effects) { Shadow(Color.Black.copy(alpha = effects.shadowAlpha),
        Offset(effects.shadowOffsetX, effects.shadowOffsetY), effects.shadowBlurRadius) }
    val glow = remember(effects, style.normalizedColors.first()) {
        Shadow(Color(style.normalizedColors.first()).copy(alpha = effects.glowAlpha),
            Offset(effects.glowOffsetX, effects.glowOffsetY), effects.glowBlurRadius)
    }
    val glowVisible = style.glowStrengthFraction > 0f && style.opacityFraction > 0f
    // Keep the extra ascent and shadows inside the WRAP_CONTENT window, including y=0 status-bar overlays.
    val bleed = ceil(max(
        if (style.shadowStrengthFraction > 0f) effects.shadowBlurRadius + max(effects.shadowOffsetX, effects.shadowOffsetY) else 0f,
        if (glowVisible) effects.glowBlurRadius else 0f,
    )).toInt()
    val fraction = externalLyricsFillFraction(text, line, positionMs)
    val fillPath = remember(geometry) { Path() }
    val layerPaint = remember { Paint() }
    val description = remember(text.text, text.ruby) { "${text.text} ${text.ruby.joinToString(" ") { it.reading }}" }
    val scrolling = if (marquee) Modifier.fillMaxWidth().basicMarquee(
        iterations = Int.MAX_VALUE, initialDelayMillis = 1_200, repeatDelayMillis = 1_200,
    ) else Modifier.fillMaxWidth()
    Box(Modifier.fillMaxWidth().clipToBounds().marqueeHorizontalEdgeFade()) {
        Layout(
            modifier = scrolling.semantics { contentDescription = description },
            content = {
                Canvas(Modifier) {
                    val contentWidth = geometry.size.width + bleed * 2
                    val left = when (textAlign) {
                        TextAlign.Center -> (size.width - contentWidth) / 2f
                        TextAlign.End, TextAlign.Right -> size.width - contentWidth
                        else -> 0f
                    } + bleed
                    val top = bleed.toFloat()
                    if (glowVisible) {
                        drawRubyGlyphs(geometry, left, top,
                            Color(style.normalizedColors.first()).copy(alpha = effects.glowFillAlpha), glow)
                    }
                    drawRubyGlyphs(geometry, left, top,
                        Color(style.resolvedUnfilledColorArgb).copy(alpha = style.unfilledOpacityFraction), shadow)
                    fillPath.reset()
                    val filled = text.text.length * fraction.coerceIn(0f, 1f)
                    geometry.units.forEach { unit ->
                        val part = ((filled - unit.unit.start) / unit.unit.text.length.coerceAtLeast(1)).coerceIn(0f, 1f)
                        if (part <= 0f) return@forEach
                        val baseX = left + unit.x + (unit.width - unit.base.size.width) / 2f
                        val baseY = top + unit.y + geometry.readingHeight
                        fillPath.addRect(Rect(baseX, baseY, baseX + unit.base.size.width * part, baseY + unit.base.size.height))
                        unit.reading?.let { reading ->
                            val readingX = left + unit.x + (unit.width - reading.size.width) / 2f
                            val readingY = top + unit.y
                            fillPath.addRect(Rect(readingX, readingY, readingX + reading.size.width * part, readingY + reading.size.height))
                        }
                    }
                    if (fraction > 0f && style.opacityFraction > 0f) {
                        clipPath(fillPath) {
                            drawContext.canvas.saveLayer(Rect(Offset.Zero, size), layerPaint)
                            drawRubyGlyphs(geometry, left, top, Color.White, null)
                            drawRect(externalLyricsBrush(style, size, style.opacityFraction), blendMode = BlendMode.SrcIn)
                            drawContext.canvas.restore()
                        }
                    }
                }
            },
        ) { children, constraints ->
            val width = constraints.constrainWidth(geometry.size.width + bleed * 2)
            val height = constraints.constrainHeight(geometry.size.height + bleed * 2)
            val canvas = children.single().measure(Constraints.fixed(width, height))
            layout(width, height) { canvas.place(0, 0) }
        }
    }
}

private fun DrawScope.drawRubyGlyphs(geometry: RubyTextLayout, left: Float, top: Float, color: Color, shadow: Shadow?) {
    geometry.units.forEach { unit ->
        drawText(unit.base, color = color,
            topLeft = Offset(left + unit.x + (unit.width - unit.base.size.width) / 2f, top + unit.y + geometry.readingHeight),
            shadow = shadow)
        unit.reading?.let { reading ->
            drawText(reading, color = color,
                topLeft = Offset(left + unit.x + (unit.width - reading.size.width) / 2f, top + unit.y), shadow = shadow)
        }
    }
}
