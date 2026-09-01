package com.mica.music.ui.screens.player

import com.mica.music.data.ArtistNames
import com.mica.music.data.LyricDisplayRows
import com.mica.music.data.LyricTextRole
import com.mica.music.data.LyricsBilingualDisplayMode
import com.mica.music.data.LyricsRenderState
import com.mica.music.data.Song
import com.mica.music.media.NotificationLyrics

internal data class PhotoStackImmersiveCaption(
    val title: String,
    val subtitle: String,
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
    val (original, translation) = lyricOriginalAndTranslation(renderState, index, splitEnabled)
        ?: return fallback
    if (original.isBlank()) return fallback
    return PhotoStackImmersiveCaption(
        title = original,
        subtitle = translation ?: NotificationLyrics.subtitle(
            song.title,
            ArtistNames.normalizeDisplay(song.artist),
        ),
    )
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
