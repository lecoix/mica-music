package com.mica.music.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import com.mica.music.data.LyricLineNode
import com.mica.music.data.LyricTextRole
import com.mica.music.data.LyricToken
import com.mica.music.data.LyricsSync
import java.text.BreakIterator
import java.util.Locale

internal fun letterSyncTimeMs(framePositionMs: Int): Int = framePositionMs

/**
 * Frame clock for letter lyrics: advances every vsync without restarting when the player
 * anchor updates (~100 ms), avoiding backward jumps that make word-timed glyphs stutter.
 */
@Composable
internal fun rememberLetterFramePositionMs(
    anchorPositionMs: Int,
    isPlaying: Boolean,
): Int {
    var framePositionMs by remember { mutableIntStateOf(anchorPositionMs) }
    val anchor by rememberUpdatedState(anchorPositionMs)

    LaunchedEffect(isPlaying) {
        if (!isPlaying) {
            framePositionMs = anchor
            return@LaunchedEffect
        }
        var estimatedMs = anchor
        var lastFrameNanos = withFrameNanos { it }
        while (true) {
            val frameNanos = withFrameNanos { it }
            val deltaMs = ((frameNanos - lastFrameNanos) / 1_000_000L).toInt().coerceAtLeast(0)
            lastFrameNanos = frameNanos
            estimatedMs += deltaMs

            val latestAnchor = anchor
            val drift = latestAnchor - estimatedMs
            when {
                // Only catch up when behind the player; never jump backward in time.
                drift > 120 -> estimatedMs = latestAnchor
                drift in 1..120 -> estimatedMs += drift / 6
            }
            framePositionMs = estimatedMs
        }
    }

    LaunchedEffect(anchorPositionMs, isPlaying) {
        if (!isPlaying) {
            framePositionMs = anchorPositionMs
        }
    }

    return framePositionMs
}

internal const val LETTER_WORD_TIMING_MIN_COVERAGE = 0.8f

internal const val LETTER_INK_SETTLE_MS = 460

internal fun letterInkSettleProgress(
    syncTimeMs: Int,
    glyphRevealMs: Int,
    motionEnabled: Boolean,
): Float {
    if (!motionEnabled) return 1f
    return ((syncTimeMs - glyphRevealMs) / LETTER_INK_SETTLE_MS.toFloat())
        .coerceIn(0f, 1f)
}

/**
 * Per-glyph ink floor for the **current** lyric line: ink settle starts at the sync-time of the
 * first frame that actually draws the glyph. Past lines keep their scheduled times so they stay
 * fully inked without replaying animation.
 */
internal object LetterGlyphInkFloors {
    @Volatile
    private var sessionKey: String? = null
    private val floors = mutableMapOf<String, Int>()

    @Synchronized
    fun resetSession(key: String) {
        if (sessionKey == key) return
        sessionKey = key
        floors.clear()
    }

    @Synchronized
    fun inkRevealMs(
        sessionKey: String,
        lineIndex: Int,
        glyphIndex: Int,
        scheduledMs: Int,
        syncTimeMs: Int,
        isCurrentLine: Boolean,
    ): Int {
        if (sessionKey != this.sessionKey) return scheduledMs
        if (!isCurrentLine) return scheduledMs
        val key = "$lineIndex:$glyphIndex"
        val floor = floors.getOrPut(key) { syncTimeMs }
        return maxOf(scheduledMs, floor)
    }
}

/**
 * Builds per-grapheme reveal timestamps for the letter theme.
 * Uses word-level [tokens] when coverage is high enough; otherwise falls back to uniform
 * distribution across the line duration (legacy letter behavior).
 */
internal fun buildLetterGraphemeRevealMs(
    line: LyricLineNode,
    displayText: String,
    tokens: List<LyricToken>,
    fallbackEndMs: Int?,
): IntArray {
    val graphemes = displayText.letterGraphemes()
    if (graphemes.isEmpty()) return IntArray(0)
    val lineEndMs = line.endMs ?: fallbackEndMs ?: (line.startMs + 4_000)
    if (!LyricsSync.isWordTimedTokens(tokens)) {
        return buildUniformLetterGraphemeRevealMs(
            lineStartMs = line.startMs,
            lineEndMs = lineEndMs,
            graphemeCount = graphemes.size,
            revealStartIndex = 0,
            revealTotalCount = graphemes.size.coerceAtLeast(1),
        )
    }

    val joined = graphemes.joinToString("")
    val schedule = IntArray(graphemes.size) { -1 }
    val meaningfulTokens = tokens.filter { it.text.isNotBlank() }
    var searchFrom = 0

    data class MappedRange(
        val graphemeStart: Int,
        val graphemeEndExclusive: Int,
        val token: LyricToken,
        val tokenIndex: Int,
    )

    val mapped = buildList {
        meaningfulTokens.forEachIndexed { tokenIndex, token ->
            var visible = token.text
            var start = joined.indexOf(visible, searchFrom)
            if (start < 0) {
                visible = visible.trim()
                start = joined.indexOf(visible, searchFrom)
            }
            if (start < 0 || visible.isEmpty()) return@forEachIndexed
            val end = (start + visible.length).coerceAtMost(joined.length)
            val graphemeStart = joinedStringIndexToGraphemeIndex(graphemes, start)
            val graphemeEndExclusive =
                joinedStringIndexToGraphemeIndex(graphemes, (end - 1).coerceAtLeast(start)) + 1
            add(MappedRange(graphemeStart, graphemeEndExclusive, token, tokenIndex))
            searchFrom = end
        }
    }

    val mappedGraphemeCount = mapped.sumOf { it.graphemeEndExclusive - it.graphemeStart }
    if (mappedGraphemeCount.toFloat() / graphemes.size < LETTER_WORD_TIMING_MIN_COVERAGE) {
        return buildUniformLetterGraphemeRevealMs(
            lineStartMs = line.startMs,
            lineEndMs = lineEndMs,
            graphemeCount = graphemes.size,
            revealStartIndex = 0,
            revealTotalCount = graphemes.size.coerceAtLeast(1),
        )
    }

    mapped.forEach { range ->
        val nextTokenStartMs = meaningfulTokens.getOrNull(range.tokenIndex + 1)?.startMs
        val tokenEndMs = range.token.endMs ?: nextTokenStartMs ?: lineEndMs
        val span = range.graphemeEndExclusive - range.graphemeStart
        for (localIndex in 0 until span) {
            val graphemeIndex = range.graphemeStart + localIndex
            schedule[graphemeIndex] = if (span <= 1) {
                range.token.startMs
            } else {
                val durationMs = (tokenEndMs - range.token.startMs).coerceAtLeast(1)
                range.token.startMs +
                    (durationMs.toLong() * localIndex / span).toInt()
            }
        }
    }

    if (schedule.any { it < 0 }) {
        return buildUniformLetterGraphemeRevealMs(
            lineStartMs = line.startMs,
            lineEndMs = lineEndMs,
            graphemeCount = graphemes.size,
            revealStartIndex = 0,
            revealTotalCount = graphemes.size.coerceAtLeast(1),
        )
    }

    return schedule
}

internal fun buildUniformLetterGraphemeRevealMs(
    lineStartMs: Int,
    lineEndMs: Int,
    graphemeCount: Int,
    revealStartIndex: Int,
    revealTotalCount: Int,
): IntArray {
    val lineDurationMs = (lineEndMs - lineStartMs).coerceAtLeast(1)
    return IntArray(graphemeCount) { index ->
        val globalIndex = revealStartIndex + index
        lineStartMs +
            (lineDurationMs.toLong() * globalIndex / revealTotalCount.coerceAtLeast(1)).toInt()
    }
}

internal fun letterColumnVisibleCount(
    columnGraphemeCount: Int,
    graphemeRevealMs: IntArray,
    lineIndex: Int,
    activeLineIndex: Int,
    framePositionMs: Int,
): Int {
    if (lineIndex < activeLineIndex) return columnGraphemeCount
    if (lineIndex > activeLineIndex) return 0
    val syncTimeMs = letterSyncTimeMs(framePositionMs)
    var count = 0
    for (revealMs in graphemeRevealMs) {
        if (revealMs <= syncTimeMs) {
            count++
        } else {
            break
        }
    }
    return count.coerceAtMost(columnGraphemeCount)
}

internal fun letterLineRevealProgress(
    primarySchedule: IntArray?,
    framePositionMs: Int,
    lineStartMs: Int,
    lineEndMs: Int,
): Float {
    if (primarySchedule != null && primarySchedule.isNotEmpty()) {
        val syncTimeMs = letterSyncTimeMs(framePositionMs)
        var revealed = 0
        for (revealMs in primarySchedule) {
            if (revealMs <= syncTimeMs) revealed++ else break
        }
        return revealed.toFloat() / primarySchedule.size
    }
    if (framePositionMs <= lineStartMs) return 0f
    if (framePositionMs >= lineEndMs || lineEndMs <= lineStartMs) return 1f
    return ((framePositionMs - lineStartMs).toFloat() / (lineEndMs - lineStartMs))
        .coerceIn(0f, 1f)
}

internal fun letterOriginalWordTokens(line: LyricLineNode): List<LyricToken> =
    line.tokens.filter {
        it.partRole == LyricTextRole.ORIGINAL || it.partRole == LyricTextRole.EXTRA
    }

internal fun String.letterGraphemes(): List<String> {
    if (isEmpty()) return emptyList()
    val iterator = BreakIterator.getCharacterInstance(Locale.ROOT)
    iterator.setText(this)
    val result = ArrayList<String>(length)
    var start = iterator.first()
    var end = iterator.next()
    while (end != BreakIterator.DONE) {
        result += substring(start, end)
        start = end
        end = iterator.next()
    }
    return result
}

private fun joinedStringIndexToGraphemeIndex(graphemes: List<String>, stringIndex: Int): Int {
    var charIndex = 0
    for (graphemeIndex in graphemes.indices) {
        if (charIndex >= stringIndex) return graphemeIndex
        charIndex += graphemes[graphemeIndex].length
    }
    return graphemes.lastIndex.coerceAtLeast(0)
}
