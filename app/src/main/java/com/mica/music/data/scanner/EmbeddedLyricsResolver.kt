package com.mica.music.data.scanner

import com.mica.music.data.LyricsDocument
import com.mica.music.data.LyricsFormat

internal data class EmbeddedLyricsTextCandidate(
    val key: String,
    val text: String,
)

internal object EmbeddedLyricsResolver {
    internal const val MAX_CANDIDATE_CHARS = 1_000_000

    fun resolve(
        preparsedCandidates: List<LyricsDocument> = emptyList(),
        tagLibCandidates: List<EmbeddedLyricsTextCandidate>,
        parse: (String) -> LyricsDocument?,
        retrieverFallback: () -> LyricsDocument?,
        binaryFallback: () -> ProbeResult<LyricsDocument?>,
    ): ProbeResult<LyricsDocument?> {
        selectDocuments(preparsedCandidates)?.let { return ProbeResult.Ok(it) }
        selectTagLibCandidate(tagLibCandidates, parse)?.let { return ProbeResult.Ok(it) }
        retrieverFallback()?.takeIf { it.lines.isNotEmpty() }?.let { return ProbeResult.Ok(it) }
        return binaryFallback()
    }

    internal fun selectDocuments(candidates: Collection<LyricsDocument>): LyricsDocument? {
        var selected: LyricsDocument? = null
        var selectedRank = Int.MIN_VALUE
        for (document in candidates) {
            if (document.lines.isEmpty()) continue
            val rank = semanticRank(document)
            if (rank > selectedRank) {
                selected = document
                selectedRank = rank
            }
        }
        return selected
    }

    internal fun selectTagLibCandidate(
        candidates: List<EmbeddedLyricsTextCandidate>,
        parse: (String) -> LyricsDocument?,
    ): LyricsDocument? {
        var selected: LyricsDocument? = null
        var selectedRank = Int.MIN_VALUE
        for (candidate in candidates) {
            if (candidate.text.isBlank() || candidate.text.length > MAX_CANDIDATE_CHARS) continue
            val document = parse(MetadataTextFix.normalizeTrustedLyrics(candidate.text))
                ?.takeIf { it.lines.isNotEmpty() } ?: continue
            val rank = semanticRank(document) * 10 + keyRank(candidate.key)
            if (rank > selectedRank) {
                selected = document
                selectedRank = rank
            }
        }
        return selected
    }

    private fun semanticRank(document: LyricsDocument): Int = when {
        document.format == LyricsFormat.SYLT || document.lines.any { it.tokens.isNotEmpty() } -> 5
        document.format == LyricsFormat.TTML -> 4
        document.format == LyricsFormat.LRC && document.lines.any { it.startMs > 0 } -> 3
        document.lines.any { it.startMs > 0 } -> 2
        else -> 1
    }

    private fun keyRank(rawKey: String): Int {
        val key = rawKey.trim().uppercase()
        return when {
            key == "SYNCED LYRICS" || key.startsWith("SYNCEDLYRICS") -> 5
            key == "TTML" || key.startsWith("TTML:") -> 4
            key == "LYRICS" || key.startsWith("LYRICS:") -> 3
            key == "UNSYNCED LYRICS" || key.startsWith("UNSYNCEDLYRICS") -> 2
            else -> 1
        }
    }
}
