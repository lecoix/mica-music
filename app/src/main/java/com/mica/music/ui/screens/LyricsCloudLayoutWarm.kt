package com.mica.music.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import com.mica.music.data.LyricDisplayRows
import com.mica.music.data.LyricLine
import com.mica.music.data.LyricTextRole
import com.mica.music.data.LyricsBilingualDisplayMode
import com.mica.music.data.LyricsDocument
import com.mica.music.ui.components.rememberLyricUniformStyle
import com.mica.music.ui.theme.LocalLyricReadingEnabled
import com.mica.music.ui.theme.LocalLyricSplitEnabled
import kotlin.random.Random
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive

internal data class LyricsCloudMeasuredRow(val width: Int, val height: Int)

internal data class LyricsCloudWarmKey(
    val documentSeed: Int,
    val bilingualMode: LyricsBilingualDisplayMode,
    val splitEnabled: Boolean,
    val readingEnabled: Boolean = true,
    val density: Float,
    val lineCount: Int,
)

internal data class LyricsCloudWarmEntry(
    val key: LyricsCloudWarmKey,
    val rowsPx: List<List<LyricsCloudMeasuredRow>>,
)

/**
 * Process-local precise cloud row metrics. Populated by low-priority prewarm when
 * lyrics page theme is CLOUD; consumed by [LyricsCloudPanel] to restore TextMeasurer packing.
 */
internal object LyricsCloudLayoutWarmCache {
    private val _entry = MutableStateFlow<LyricsCloudWarmEntry?>(null)
    val entry: StateFlow<LyricsCloudWarmEntry?> = _entry.asStateFlow()

    fun get(key: LyricsCloudWarmKey): LyricsCloudWarmEntry? =
        _entry.value?.takeIf { it.key == key }

    fun put(entry: LyricsCloudWarmEntry) {
        _entry.value = entry
    }

    fun clear() {
        _entry.value = null
    }
}

internal fun lyricsCloudDocumentSeed(document: LyricsDocument): Int =
    document.lines.fold(17) { value, line -> value * 31 + line.id.hashCode() }

internal fun lyricsCloudFontSizes(seed: Int, lineCount: Int): List<Int> {
    val random = Random(seed xor 0x51A7)
    return List(lineCount) { 16 + random.nextInt(15) }
}

internal fun lyricsCloudSizesFromMeasuredRows(
    rowsPx: List<List<LyricsCloudMeasuredRow>>,
    unit: Float,
): List<LyricsCloudSize> {
    val safeUnit = unit.coerceAtLeast(1f)
    return rowsPx.map { measured ->
        LyricsCloudSize(
            width = (measured.maxOfOrNull { it.width } ?: 1) / safeUnit,
            height = measured.sumOf { it.height }.coerceAtLeast(1) / safeUnit,
        )
    }
}

internal fun measureLyricsCloudLinePrecise(
    rows: List<LyricDisplayRows.DisplayRow>,
    lineStyle: TextStyle,
    translationStyle: TextStyle,
    lineNormalStyle: TextStyle,
    translationNormalStyle: TextStyle,
    textMeasurer: TextMeasurer,
): List<LyricsCloudMeasuredRow> = rows.map { row ->
    val useSecondary = row.role == LyricTextRole.READING ||
        row.role == LyricTextRole.TRANSLATION ||
        row.splitIndex > 0
    val style = if (useSecondary) translationStyle else lineStyle
    val normalStyle = if (useSecondary) translationNormalStyle else lineNormalStyle
    val whole = textMeasurer.measure(
        text = row.text,
        style = style,
        softWrap = false,
    ).size
    val characterWidth = row.text.sumOf { character ->
        textMeasurer.measure(
            text = character.toString(),
            style = normalStyle,
            softWrap = false,
        ).size.width
    }
    LyricsCloudMeasuredRow(
        width = maxOf(whole.width, characterWidth) + 4,
        height = whole.height,
    )
}

/**
 * Low-priority precise measure for the current song. Only meaningful when lyrics cloud
 * theme is active ([enabled]); yields between lines so cover/lyrics IO preloads stay responsive.
 */
@Composable
internal fun LyricsCloudLayoutPrewarm(
    enabled: Boolean,
    document: LyricsDocument,
    lyrics: List<LyricLine>,
    bilingualDisplayMode: LyricsBilingualDisplayMode,
) {
    LaunchedEffect(enabled) {
        if (!enabled) LyricsCloudLayoutWarmCache.clear()
    }
    if (!enabled || lyrics.isEmpty()) return
    val splitEnabled = LocalLyricSplitEnabled.current
    val readingEnabled = LocalLyricReadingEnabled.current
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    val uniformStyle = rememberLyricUniformStyle()
    val seed = remember(document) { lyricsCloudDocumentSeed(document) }
    val fontSizes = remember(seed, lyrics.size) { lyricsCloudFontSizes(seed, lyrics.size) }
    val lineStyles = remember(fontSizes, uniformStyle) {
        fontSizes.map { uniformStyle.withCloudFontSize(it) }
    }
    val translationStyles = remember(fontSizes, uniformStyle) {
        fontSizes.map { uniformStyle.withCloudFontSize((it - 3).coerceAtLeast(14)) }
    }
    val lineNormalStyles = remember(lineStyles) {
        lineStyles.map { it.copy(fontWeight = FontWeight.Normal) }
    }
    val translationNormalStyles = remember(translationStyles) {
        translationStyles.map { it.copy(fontWeight = FontWeight.Normal) }
    }
    val displayRows = remember(lyrics, document, splitEnabled, bilingualDisplayMode, readingEnabled) {
        lyrics.mapIndexed { index, line ->
            LyricDisplayRows.rowsFromParts(
                parts = document.lines.getOrNull(index)?.parts.orEmpty(),
                mode = bilingualDisplayMode,
                readingEnabled = readingEnabled,
            ) ?: LyricDisplayRows.rowsForBilingualDisplayMode(
                text = line.text,
                enabled = splitEnabled,
                mode = bilingualDisplayMode,
            )
        }
    }
    val warmKey = LyricsCloudWarmKey(
        documentSeed = seed,
        bilingualMode = bilingualDisplayMode,
        splitEnabled = splitEnabled,
        readingEnabled = readingEnabled,
        density = density.density,
        lineCount = lyrics.size,
    )
    LaunchedEffect(
        warmKey,
        displayRows,
        lineStyles,
        translationStyles,
        lineNormalStyles,
        translationNormalStyles,
    ) {
        if (LyricsCloudLayoutWarmCache.get(warmKey) != null) return@LaunchedEffect
        // Let cover / page settle; stay off the enter-animation critical path.
        delay(480)
        repeat(2) { withFrameNanos { } }
        if (!isActive) return@LaunchedEffect
        val rowsPx = ArrayList<List<LyricsCloudMeasuredRow>>(displayRows.size)
        for (index in displayRows.indices) {
            if (!isActive) return@LaunchedEffect
            rowsPx += measureLyricsCloudLinePrecise(
                rows = displayRows[index],
                lineStyle = lineStyles[index],
                translationStyle = translationStyles[index],
                lineNormalStyle = lineNormalStyles[index],
                translationNormalStyle = translationNormalStyles[index],
                textMeasurer = textMeasurer,
            )
            if (index % 2 == 1) {
                withFrameNanos { }
            }
        }
        if (!isActive) return@LaunchedEffect
        LyricsCloudLayoutWarmCache.put(
            LyricsCloudWarmEntry(key = warmKey, rowsPx = rowsPx),
        )
    }
}

internal fun TextStyle.withCloudFontSize(fontSizeSp: Int): TextStyle = copy(
    fontSize = fontSizeSp.sp,
    lineHeight = (fontSizeSp * 1.45f).sp,
)
