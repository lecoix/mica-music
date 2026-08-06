package com.mica.music.util

import com.mica.music.data.LyricLineNode
import com.mica.music.data.LyricTextRole
import com.mica.music.data.LyricToken
import com.mica.music.data.LyricsSync
import java.text.BreakIterator
import java.util.Locale

/**
 * Logs letter-theme glyph reveal schedules and first-draw timestamps into [DiagnosticLog]
 * (`MICA_DIAGNOSTICS` / `files/diagnostics/current-session.log`).
 */
object LetterRevealDiagnostics {
    private const val CATEGORY = "LetterReveal"

    @Volatile
    private var sessionKey: String? = null
    private val loggedLineSchedules = mutableSetOf<Int>()
    private val loggedGlyphs = mutableSetOf<String>()

    @Synchronized
    fun resetSession(key: String) {
        if (sessionKey == key) return
        sessionKey = key
        loggedLineSchedules.clear()
        loggedGlyphs.clear()
        DiagnosticLog.event(CATEGORY, "session-start key=$key")
    }

    @Synchronized
    fun logSongSchedules(
        sessionKey: String,
        lines: List<LyricLineNode>,
        primaryRevealSchedules: Map<Int, IntArray> = emptyMap(),
    ) {
        resetSession(sessionKey)
        lines.forEachIndexed { lineIndex, line ->
            logLineScheduleIfNeeded(
                lineIndex = lineIndex,
                line = line,
                displayText = line.originalDisplayText(),
                fallbackEndMs = lines.getOrNull(lineIndex + 1)?.startMs,
                primaryRevealMs = primaryRevealSchedules[lineIndex],
            )
        }
    }

    @Synchronized
    fun logLineScheduleIfNeeded(
        lineIndex: Int,
        line: LyricLineNode,
        displayText: String,
        fallbackEndMs: Int?,
        primaryRevealMs: IntArray? = null,
    ) {
        if (displayText.isBlank()) return
        if (!loggedLineSchedules.add(lineIndex)) return

        val graphemes = displayText.letterGraphemes()
        if (graphemes.isEmpty()) return

        val lineEndMs = line.endMs ?: fallbackEndMs ?: (line.startMs + 4_000)
        val uniformTimes = buildUniformRevealMs(
            lineStartMs = line.startMs,
            lineEndMs = lineEndMs,
            graphemeCount = graphemes.size,
        )
        val originalTokens = line.tokens.filter {
            it.partRole == LyricTextRole.ORIGINAL || it.partRole == LyricTextRole.EXTRA
        }
        val runtimeTimes = primaryRevealMs?.takeIf { it.size == graphemes.size } ?: uniformTimes
        val runtimeMode = when {
            runtimeTimes.contentEquals(uniformTimes) -> "uniform"
            LyricsSync.isWordTimedTokens(originalTokens) -> "wordTimed"
            else -> "mapped"
        }
        val runtimeSummary = formatCharTimes(graphemes, runtimeTimes)

        val lrcSummary = if (LyricsSync.isWordTimedTokens(originalTokens)) {
            " lrc=${formatTokenTimes(originalTokens)}"
        } else {
            ""
        }

        DiagnosticLog.event(
            CATEGORY,
            "schedule line=$lineIndex lineStart=${line.startMs} lineEnd=$lineEndMs " +
                "runtime=$runtimeMode $runtimeSummary$lrcSummary",
        )
    }

    @Synchronized
    fun onGlyphShown(
        lineIndex: Int,
        glyphIndex: Int,
        char: String,
        scheduledMs: Int,
        frameMs: Int,
        anchorMs: Int,
        inkProgress: Float,
        isTranslation: Boolean,
    ) {
        val session = sessionKey ?: return
        val dedupeKey = "$session:$lineIndex:$glyphIndex"
        if (!loggedGlyphs.add(dedupeKey)) return

        val lagFrameMs = frameMs - scheduledMs
        val lagAnchorMs = anchorMs - scheduledMs
        DiagnosticLog.event(
            CATEGORY,
            "show line=$lineIndex g=$glyphIndex char=${char.escapeForLog()} " +
                "sched=$scheduledMs frame=$frameMs anchor=$anchorMs " +
                "lagFrame=${lagFrameMs}ms lagAnchor=${lagAnchorMs}ms " +
                "ink=${"%.2f".format(Locale.US, inkProgress)} trans=$isTranslation",
        )
    }

    internal fun buildUniformRevealMs(
        lineStartMs: Int,
        lineEndMs: Int,
        graphemeCount: Int,
    ): IntArray {
        val lineDurationMs = (lineEndMs - lineStartMs).coerceAtLeast(1)
        return IntArray(graphemeCount) { index ->
            lineStartMs + (lineDurationMs.toLong() * index / graphemeCount.coerceAtLeast(1)).toInt()
        }
    }

    private fun LyricLineNode.originalDisplayText(): String =
        parts.filter { it.role == LyricTextRole.ORIGINAL }
            .joinToString("") { it.text }
            .trim()

    private fun formatCharTimes(graphemes: List<String>, timesMs: IntArray): String =
        graphemes.indices.joinToString(separator = ",") { index ->
            "${graphemes[index].escapeForLog()}@${timesMs[index]}"
        }

    private fun formatTokenTimes(tokens: List<LyricToken>): String =
        tokens.filter { it.text.isNotBlank() }
            .joinToString(separator = ",") { token ->
                "${token.text.trim().escapeForLog()}@${token.startMs}"
            }

    private fun String.escapeForLog(): String = replace("\n", "\\n").replace(" ", "·")

    private fun String.letterGraphemes(): List<String> {
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
}
