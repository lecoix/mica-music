package com.mica.music.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.constrainHeight
import com.mica.music.data.LyricRuby
import java.text.BreakIterator
import java.util.Locale

internal data class RubyTextUnit(val start: Int, val text: String, val reading: String = "")

/** Explicit annotation groups are indivisible. Platform line boundaries handle the unannotated gaps. */
internal fun rubyTextUnits(text: String, ruby: List<LyricRuby>): List<RubyTextUnit> = buildList {
    fun gap(start: Int, end: Int) {
        if (start >= end) return
        val value = text.substring(start, end)
        val breaks = BreakIterator.getLineInstance(Locale.ROOT).apply { setText(value) }
        var from = breaks.first()
        var to = breaks.next()
        while (to != BreakIterator.DONE) {
            add(RubyTextUnit(start + from, value.substring(from, to)))
            from = to
            to = breaks.next()
        }
    }
    var from = 0
    ruby.forEach {
        gap(from, it.start)
        add(RubyTextUnit(it.start, text.substring(it.start, it.endExclusive), it.reading))
        from = it.endExclusive
    }
    gap(from, text.length)
}

internal data class RubyTextPlacement(
    val unit: RubyTextUnit,
    val base: TextLayoutResult,
    val reading: TextLayoutResult?,
    val x: Int,
    val y: Int,
    val width: Int,
)

internal data class RubyTextLayout(val size: IntSize, val readingHeight: Int, val units: List<RubyTextPlacement>)

private class RubyMeasureCache(var width: Int = -1, var layout: RubyTextLayout? = null)

internal fun rubyReadingStyle(style: TextStyle): TextStyle = style.copy(
    fontSize = style.fontSize * 0.5f,
    lineHeight = style.lineHeight * 0.5f,
)

/** Shared by actual drawing and cloud packing; geometry never depends on the playback position. */
internal fun measureRubyText(
    text: String,
    ruby: List<LyricRuby>,
    style: TextStyle,
    measurer: TextMeasurer,
    maxWidth: Int = Constraints.Infinity,
    maxLines: Int = Int.MAX_VALUE,
    textAlign: TextAlign = TextAlign.Center,
    fillWidth: Boolean = false,
): RubyTextLayout {
    val readingStyle = rubyReadingStyle(style)
    val limit = maxWidth.coerceAtLeast(1)
    val textConstraints = Constraints(maxWidth = limit)
    val readingHeight = measurer.measure("あ", readingStyle, softWrap = false).size.height
    val measured = rubyTextUnits(text, ruby).map { unit ->
        val base = measurer.measure(unit.text, style, softWrap = false, maxLines = 1,
            overflow = TextOverflow.Ellipsis, constraints = textConstraints)
        val reading = unit.reading.takeIf { it.isNotEmpty() }?.let {
            measurer.measure(it, readingStyle, softWrap = false, maxLines = 1,
                overflow = TextOverflow.Ellipsis, constraints = textConstraints)
        }
        Triple(unit, base, reading)
    }
    val rowHeight = readingHeight + (measured.maxOfOrNull { it.second.size.height } ?: 0)
    val rows = mutableListOf<MutableList<Triple<RubyTextUnit, TextLayoutResult, TextLayoutResult?>>>()
    val widths = mutableListOf<Int>()
    var truncated = false
    for (unit in measured) {
        val width = maxOf(unit.second.size.width, unit.third?.size?.width ?: 0)
        if (rows.isEmpty() || (widths.last() > 0 && widths.last().toLong() + width > limit)) {
            if (rows.size >= maxLines) { truncated = true; break }
            rows.add(mutableListOf())
            widths.add(0)
        }
        rows.last().add(unit)
        widths[widths.lastIndex] += width
    }
    if (truncated && rows.isNotEmpty() && rows.last().isNotEmpty()) {
        val last = rows.last().last()
        val width = maxOf(last.second.size.width, last.third?.size?.width ?: 0)
        val ellipsized = measurer.measure(last.first.text + "…", style, softWrap = false, maxLines = 1,
            overflow = TextOverflow.Ellipsis, constraints = Constraints(maxWidth = width))
        rows.last()[rows.last().lastIndex] = Triple(last.first, ellipsized, last.third)
    }
    val width = if (fillWidth && limit != Constraints.Infinity) limit else widths.maxOrNull() ?: 0
    val placements = buildList {
        rows.forEachIndexed { index, row ->
            var x = when (textAlign) {
                TextAlign.Center -> (width - widths[index]) / 2
                TextAlign.End, TextAlign.Right -> width - widths[index]
                else -> 0
            }
            row.forEach { (unit, base, reading) ->
                val unitWidth = maxOf(base.size.width, reading?.size?.width ?: 0)
                add(RubyTextPlacement(unit, base, reading, x, index * rowHeight, unitWidth))
                x += unitWidth
            }
        }
    }
    return RubyTextLayout(IntSize(width, rows.size * rowHeight), readingHeight, placements)
}

@Composable
internal fun RubyLyricText(
    text: String,
    ruby: List<LyricRuby>,
    textStyle: TextStyle,
    color: Color,
    sungColor: Color = color,
    fillFraction: Float = 0f,
    textAlign: TextAlign = TextAlign.Center,
    maxLines: Int = Int.MAX_VALUE,
    fillWidth: Boolean = true,
    softWrap: Boolean = true,
    liftedRange: IntRange? = null,
    liftPx: Float = 0f,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer(cacheSize = 64)
    val cache = remember(text, ruby, textStyle, measurer, maxLines, textAlign, fillWidth, softWrap) { RubyMeasureCache() }
    // A single accessible text node; annotation/base groups have no duplicated overlay semantics.
    val description = remember(text, ruby) { "$text ${ruby.joinToString(" ") { it.reading }}" }
    SubcomposeLayout(modifier.semantics { contentDescription = description }) { constraints ->
        val availableWidth = if (softWrap) constraints.maxWidth else Constraints.Infinity
        val geometry = cache.layout?.takeIf { cache.width == availableWidth } ?: measureRubyText(
            text, ruby, textStyle, measurer, maxWidth = availableWidth,
            maxLines = maxLines, textAlign = textAlign, fillWidth = fillWidth,
        ).also { cache.width = availableWidth; cache.layout = it }
        val width = constraints.constrainWidth(geometry.size.width)
        val height = constraints.constrainHeight(geometry.size.height)
        val canvas = subcompose(Unit) {
            Canvas(Modifier) {
                val filled = text.length * fillFraction.coerceIn(0f, 1f)
                geometry.units.forEach { unit ->
                    val start = unit.unit.start
                    val lifts = liftedRange != null && start <= liftedRange.last &&
                        start + unit.unit.text.length > liftedRange.first
                    translate(top = if (lifts) -liftPx else 0f) {
                        val baseX = unit.x + (unit.width - unit.base.size.width) / 2f
                        val baseY = (unit.y + geometry.readingHeight).toFloat()
                        unit.reading?.let {
                            drawText(it, color, Offset(unit.x + (unit.width - it.size.width) / 2f, unit.y.toFloat()))
                        }
                        drawText(unit.base, color, Offset(baseX, baseY))
                        val fraction = ((filled - start) / unit.unit.text.length.coerceAtLeast(1)).coerceIn(0f, 1f)
                        if (fraction > 0f) {
                            clipRect(baseX, baseY, baseX + unit.base.size.width * fraction, baseY + unit.base.size.height) {
                                drawText(unit.base, sungColor, Offset(baseX, baseY))
                            }
                        }
                    }
                }
            }
        }.single().measure(Constraints.fixed(width, height))
        layout(width, height) { canvas.place(0, 0) }
    }
}
