package com.mica.music.data

/** Versioned, renderer-neutral lyrics data. */
data class LyricsDocument(
    val version: Int = CURRENT_LYRICS_DOCUMENT_VERSION,
    val format: LyricsFormat = LyricsFormat.UNKNOWN,
    val origin: LyricsOrigin = LyricsOrigin.UNKNOWN,
    val lines: List<LyricLineNode> = emptyList(),
)

const val CURRENT_LYRICS_DOCUMENT_VERSION = 2

enum class LyricsFormat {
    UNKNOWN,
    PLAIN,
    LRC,
    TTML,
    SYLT,
}

enum class LyricsOrigin {
    UNKNOWN,
    EMBEDDED,
    EXTERNAL,
}

/** The only lyric payloads retained for a song. */
enum class LyricsSlot {
    EMBEDDED,
    EXTERNAL_LRC,
    EXTERNAL_TTML,
}

data class LyricsSlots(
    val embedded: LyricsDocument? = null,
    val externalLrc: LyricsDocument? = null,
    val externalTtml: LyricsDocument? = null,
) {
    fun document(slot: LyricsSlot): LyricsDocument? = when (slot) {
        LyricsSlot.EMBEDDED -> embedded
        LyricsSlot.EXTERNAL_LRC -> externalLrc
        LyricsSlot.EXTERNAL_TTML -> externalTtml
    }?.takeIf { it.lines.isNotEmpty() }

    fun selected(priority: List<LyricsSlot> = DEFAULT_LYRICS_SLOT_PRIORITY): LyricsDocument =
        priority.firstNotNullOfOrNull(::document) ?: LyricsDocument()

    fun entries(): List<Pair<LyricsSlot, LyricsDocument>> = LyricsSlot.entries.mapNotNull { slot ->
        document(slot)?.let { slot to it }
    }
}

internal sealed interface LyricsProbeResult {
    data object NotProbed : LyricsProbeResult

    data object ReadFailed : LyricsProbeResult

    data class Complete(val slots: LyricsSlots) : LyricsProbeResult
}

val DEFAULT_LYRICS_SLOT_PRIORITY = listOf(
    LyricsSlot.EXTERNAL_TTML,
    LyricsSlot.EXTERNAL_LRC,
    LyricsSlot.EMBEDDED,
)

data class ScannedSongLyrics(
    val songId: String,
    val revision: String,
    val slots: LyricsSlots,
)

data class LyricsScanBatch(
    val completed: List<ScannedSongLyrics>,
    val readFailedCount: Int,
)

data class LyricLineNode(
    val id: String,
    val startMs: Int,
    val endMs: Int? = null,
    val parts: List<LyricTextPart>,
    val tokens: List<LyricToken> = emptyList(),
)

data class LyricTextPart(
    val role: LyricTextRole,
    val text: String,
)

enum class LyricTextRole {
    ORIGINAL,
    TRANSLATION,
    EXTRA,
}

data class LyricToken(
    val text: String,
    val startMs: Int,
    val endMs: Int? = null,
    val partRole: LyricTextRole = LyricTextRole.ORIGINAL,
)

/** Compatibility view for legacy producers that still emit flat lyric lines. */
fun List<LyricLine>.toLyricsDocumentCompat(
    format: LyricsFormat = LyricsFormat.UNKNOWN,
    origin: LyricsOrigin = LyricsOrigin.UNKNOWN,
): LyricsDocument = LyricsDocument(
    format = format,
    origin = origin,
    lines = mapIndexed { index, line ->
        val parts = LyricDisplayRows.splitForDisplay(line.text).mapIndexed { partIndex, text ->
            LyricTextPart(
                role = if (partIndex == 0) LyricTextRole.ORIGINAL else LyricTextRole.TRANSLATION,
                text = text,
            )
        }
        LyricLineNode(
            id = "$index-${line.timeMs}",
            startMs = line.timeMs,
            endMs = line.endTimeMs,
            parts = parts,
            tokens = line.cues.mapIndexed { cueIndex, cue ->
                LyricToken(
                    text = cue.text,
                    startMs = cue.timeMs,
                    endMs = line.cues.getOrNull(cueIndex + 1)?.timeMs ?: line.endTimeMs,
                )
            },
        )
    },
)

fun LyricsDocument.toLegacyLyricLines(): List<LyricLine> = lines.map { line ->
    LyricLine(
        timeMs = line.startMs,
        text = line.parts.joinToString(separator = "\n") { it.text },
        cues = line.tokens.map { token -> LyricCue(timeMs = token.startMs, text = token.text) },
        endTimeMs = line.endMs,
    )
}
