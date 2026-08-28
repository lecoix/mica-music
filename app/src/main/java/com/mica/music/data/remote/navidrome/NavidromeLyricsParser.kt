package com.mica.music.data.remote.navidrome

import com.mica.music.data.LyricLineNode
import com.mica.music.data.LyricTextPart
import com.mica.music.data.LyricTextRole
import com.mica.music.data.LyricsDocument
import com.mica.music.data.LyricsFormat
import com.mica.music.data.LyricsOrigin
import com.mica.music.data.scanner.LyricsSanitizer

internal object NavidromeLyricsParser {
    fun structuredLyrics(body: String): LyricsDocument? {
        val response = NavidromeJsonParser.validateResponse(body)
        val list = response.optJSONObject("lyricsList")
            ?.optJSONArray("structuredLyrics") ?: return null
        for (index in 0 until list.length()) {
            val lyrics = list.optJSONObject(index) ?: continue
            val lines = lyrics.optJSONArray("line") ?: continue
            val parsed = buildList {
                for (lineIndex in 0 until lines.length()) {
                    val line = lines.optJSONObject(lineIndex) ?: continue
                    val value = line.optString("value").trim()
                    if (value.isBlank() || LyricsSanitizer.isIgnorableLyricText(value)) continue
                    val startMs = line.optLong("start", 0L)
                        .coerceIn(0L, Int.MAX_VALUE.toLong())
                        .toInt()
                    add(
                        LyricLineNode(
                            id = "remote-$index-$lineIndex-$startMs",
                            startMs = startMs,
                            parts = listOf(LyricTextPart(LyricTextRole.ORIGINAL, value)),
                        ),
                    )
                }
            }
            if (parsed.isNotEmpty()) {
                val format = if (parsed.any { it.startMs > 0 }) LyricsFormat.LRC else LyricsFormat.PLAIN
                return LyricsSanitizer.finalizeDocument(
                    LyricsDocument(
                        format = format,
                        origin = LyricsOrigin.EXTERNAL,
                        lines = parsed,
                    ),
                ).takeIf { it.lines.isNotEmpty() }
            }
        }
        return null
    }

    fun legacyLyricsValue(body: String): String? =
        NavidromeJsonParser.validateResponse(body)
            .optJSONObject("lyrics")
            ?.optString("value")
            ?.trim()
            ?.takeIf(String::isNotBlank)
}
