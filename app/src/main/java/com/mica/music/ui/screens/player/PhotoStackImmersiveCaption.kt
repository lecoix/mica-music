package com.mica.music.ui.screens.player

import com.mica.music.data.ArtistNames
import com.mica.music.data.LyricDisplayRows
import com.mica.music.data.LyricLine
import com.mica.music.data.LyricTextRole
import com.mica.music.data.LyricsBilingualDisplayMode
import com.mica.music.data.LyricsRenderState
import com.mica.music.data.Song
import com.mica.music.lyrics.LyricsDisplayOptions
import com.mica.music.lyrics.LyricsDisplayProjection

internal const val PhotoStackCaptionMarqueeInitialDelayMs = 1_200L
internal const val PhotoStackCaptionMarqueeVelocityDpPerSec = 30f

internal data class PhotoStackImmersiveCaption(
    val title: String,
    val subtitle: String,
    val karaokeLine: LyricLine? = null,
    val nextLineTimeMs: Int? = null,
    val positionMs: Int = 0,
)

internal fun photoStackSongCaption(song: Song): PhotoStackImmersiveCaption =
    PhotoStackImmersiveCaption(
        title = song.title,
        subtitle = song.artist.ifBlank { song.album },
    )

internal fun photoStackImmersiveCaption(
    song: Song,
    isPlaying: Boolean,
    lyricsInTitleEnabled: Boolean,
    renderState: LyricsRenderState,
    splitEnabled: Boolean,
): PhotoStackImmersiveCaption {
    val fallback = photoStackSongCaption(song)
    if (!lyricsInTitleEnabled || !isPlaying) return fallback
    val index = renderState.activeLineIndex
    if (index < 0) return fallback
    val line = renderState.lyrics.getOrNull(index) ?: return fallback
    val (original, translation) = lyricOriginalAndTranslation(renderState, index, splitEnabled)
        ?: return fallback
    if (original.isBlank()) return fallback
    val karaokeLine = line.takeIf { it.cues.isNotEmpty() }
    return PhotoStackImmersiveCaption(
        title = original,
        subtitle = translation ?: LyricsDisplayProjection.subtitle(
            song.title,
            ArtistNames.normalizeDisplay(song.artist),
        ),
        karaokeLine = karaokeLine,
        nextLineTimeMs = karaokeLine?.let { renderState.lyrics.getOrNull(index + 1)?.timeMs },
        positionMs = if (karaokeLine != null) renderState.positionMs else 0,
    )
}

/** Compose `basicMarquee`：1/3 视口间距、30dp/s、1200ms 起跑延迟。 */
internal fun photoStackCaptionMarqueeTravelPx(
    contentWidthPx: Float,
    viewportWidthPx: Float,
    elapsedMs: Long,
    velocityPxPerMs: Float,
    initialDelayMs: Long = PhotoStackCaptionMarqueeInitialDelayMs,
): Float {
    if (contentWidthPx <= viewportWidthPx || velocityPxPerMs <= 0f) return 0f
    val cycle = contentWidthPx + viewportWidthPx / 3f
    if (cycle <= 0f || elapsedMs < initialDelayMs) return 0f
    return ((elapsedMs - initialDelayMs) * velocityPxPerMs) % cycle
}

private fun lyricOriginalAndTranslation(
    renderState: LyricsRenderState,
    index: Int,
    splitEnabled: Boolean,
): Pair<String, String?>? {
    val line = renderState.lyrics.getOrNull(index) ?: return null
    val rows = renderState.document.lines.getOrNull(index)?.let { node ->
        LyricDisplayRows.rowsFromParts(
            parts = node.parts,
            mode = LyricsBilingualDisplayMode.ALL,
            readingEnabled = false,
            splitEnabled = splitEnabled,
        )
    } ?: LyricDisplayRows.rowsForBilingualDisplayMode(
        text = line.text,
        enabled = splitEnabled,
        mode = LyricsBilingualDisplayMode.ALL,
    )
    val original = rows.firstOrNull { it.role != LyricTextRole.TRANSLATION }
        ?.text?.trim()
        ?.takeIf { it.isNotBlank() }
        ?: return null
    val translation = rows.firstOrNull { it.role == LyricTextRole.TRANSLATION }
        ?.text?.trim()
        ?.takeIf { it.isNotBlank() }
    return original to translation
}
