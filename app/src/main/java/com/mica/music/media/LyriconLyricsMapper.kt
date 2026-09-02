package com.mica.music.media

import com.mica.music.data.LyricDisplayRows
import com.mica.music.data.LyricLineNode
import com.mica.music.data.LyricTextRole
import com.mica.music.data.LyricToken
import com.mica.music.data.LyricsDocument
import com.mica.music.data.LyricsSync
import com.mica.music.data.Song
import io.github.proify.lyricon.lyric.model.LyricWord
import io.github.proify.lyricon.lyric.model.RichLyricLine
import io.github.proify.lyricon.lyric.model.Song as LyriconSong

internal object LyriconLyricsMapper {
    fun mapSong(
        song: Song,
        document: LyricsDocument,
        effectiveOffsetMs: Int,
    ): LyriconSong {
        val durationMs = song.durationSec.coerceAtLeast(0).toLong() * 1_000L
        val lyrics = document.lines.mapIndexedNotNull { index, line ->
            mapLine(
                line = line,
                nextLineStartMs = document.lines.getOrNull(index + 1)?.startMs,
                songDurationMs = durationMs,
                effectiveOffsetMs = effectiveOffsetMs,
            )
        }
        return LyriconSong(
            id = song.id,
            name = song.title,
            artist = song.artist,
            duration = durationMs,
            lyrics = lyrics,
        )
    }

    private fun mapLine(
        line: LyricLineNode,
        nextLineStartMs: Int?,
        songDurationMs: Long,
        effectiveOffsetMs: Int,
    ): RichLyricLine? {
        val semanticText = line.textFor(LyricTextRole.ORIGINAL)
        if (semanticText.isBlank()) return null

        val sourceEndMs = line.endMs?.toLong()
            ?: nextLineStartMs?.toLong()
            ?: songDurationMs.takeIf { it > 0L }
            ?: return null
        val begin = shiftAndClamp(line.startMs.toLong(), effectiveOffsetMs, songDurationMs)
        val end = shiftAndClamp(sourceEndMs, effectiveOffsetMs, songDurationMs)
        if (end <= begin) return null

        val originalTokens = line.tokens.filter { it.partRole == LyricTextRole.ORIGINAL }
        val translationTokens = line.tokens.filter { it.partRole == LyricTextRole.TRANSLATION }
        val words = mapTimedTokens(
            tokens = originalTokens,
            sourceLineEndMs = sourceEndMs,
            mappedLineBeginMs = begin,
            mappedLineEndMs = end,
            effectiveOffsetMs = effectiveOffsetMs,
            songDurationMs = songDurationMs,
        )
        val translationWords = mapTimedTokens(
            tokens = translationTokens,
            sourceLineEndMs = sourceEndMs,
            mappedLineBeginMs = begin,
            mappedLineEndMs = end,
            effectiveOffsetMs = effectiveOffsetMs,
            songDurationMs = songDurationMs,
        )
        val semanticTranslation = line.textFor(LyricTextRole.TRANSLATION).takeIf { it.isNotBlank() }
        val hasSemanticTranslation = semanticTranslation != null || translationWords.isNotEmpty()
        val displayRows = if (!hasSemanticTranslation && words.isEmpty()) {
            LyricDisplayRows.splitForDisplay(semanticText).takeIf { it.size == 2 }
        } else {
            null
        }
        val text = displayRows?.get(0) ?: semanticText
        val translation = semanticTranslation ?: displayRows?.get(1)

        return RichLyricLine(
            begin = begin,
            end = end,
            text = text,
            words = words.takeIf { it.isNotEmpty() },
            translation = translation,
            translationWords = translationWords.takeIf { it.isNotEmpty() },
            roma = line.textFor(LyricTextRole.READING).takeIf { it.isNotBlank() },
        )
    }

    private fun mapTimedTokens(
        tokens: List<LyricToken>,
        sourceLineEndMs: Long,
        mappedLineBeginMs: Long,
        mappedLineEndMs: Long,
        effectiveOffsetMs: Int,
        songDurationMs: Long,
    ): List<LyricWord> {
        if (!LyricsSync.isWordTimedTokens(tokens)) return emptyList()
        return tokens.mapIndexedNotNull { index, token ->
            if (token.text.isBlank()) return@mapIndexedNotNull null
            val sourceEnd = token.endMs?.toLong()
                ?: tokens.getOrNull(index + 1)?.startMs?.toLong()
                ?: sourceLineEndMs
            val begin = shiftAndClamp(token.startMs.toLong(), effectiveOffsetMs, songDurationMs)
                .coerceAtLeast(mappedLineBeginMs)
            val end = shiftAndClamp(sourceEnd, effectiveOffsetMs, songDurationMs)
                .coerceAtMost(mappedLineEndMs)
            if (end <= begin) return@mapIndexedNotNull null
            LyricWord(
                begin = begin,
                end = end,
                text = token.text,
            )
        }
    }

    private fun LyricLineNode.textFor(role: LyricTextRole): String = parts
        .asSequence()
        .filter { it.role == role }
        .map { it.text }
        .filter { it.isNotBlank() }
        .joinToString(separator = "")

    private fun shiftAndClamp(
        sourceMs: Long,
        effectiveOffsetMs: Int,
        songDurationMs: Long,
    ): Long {
        val shifted = sourceMs - effectiveOffsetMs.toLong()
        return if (songDurationMs > 0L) {
            shifted.coerceIn(0L, songDurationMs)
        } else {
            shifted.coerceAtLeast(0L)
        }
    }
}
