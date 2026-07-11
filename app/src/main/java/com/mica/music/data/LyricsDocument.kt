package com.mica.music.data

/** Versioned, renderer-neutral lyrics data. Legacy [LyricLine] remains the storage bridge for now. */
data class LyricsDocument(
    val version: Int = CURRENT_LYRICS_DOCUMENT_VERSION,
    val source: LyricsSource = LyricsSource.COMPATIBILITY,
    val lines: List<LyricLineNode> = emptyList(),
)

const val CURRENT_LYRICS_DOCUMENT_VERSION = 1

enum class LyricsSource {
    COMPATIBILITY,
    LRC,
    TTML,
    EMBEDDED,
    EXTERNAL,
}

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

/** Compatibility normalizer until parsers write [LyricsDocument] directly. */
fun List<LyricLine>.toLyricsDocumentCompat(
    source: LyricsSource = LyricsSource.COMPATIBILITY,
): LyricsDocument = LyricsDocument(
    source = source,
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
