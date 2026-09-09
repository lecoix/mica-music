package com.mica.music.ui.components

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.mica.music.MicaApp
import com.mica.music.data.DEFAULT_LYRICS_SLOT_PRIORITY
import com.mica.music.data.LyricDisplayRows
import com.mica.music.data.LyricsDocument
import com.mica.music.data.LyricsSlot
import com.mica.music.data.MusicLibrary
import com.mica.music.data.SharedLyricsMemoryCache
import com.mica.music.data.Song
import com.mica.music.data.SongSource
import com.mica.music.data.preferences.LyricsPreferences
import com.mica.music.util.DiagnosticLog
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

@Composable
internal fun rememberSongWithLyrics(
    library: MusicLibrary,
    song: Song,
    nextSong: Song? = null,
    priority: List<LyricsSlot> = DEFAULT_LYRICS_SLOT_PRIORITY,
): Song {
    val lyricsDataVersion = library.lyricsDataVersion
    val context = LocalContext.current.applicationContext
    val app = context as? MicaApp
    var resolved by remember(song.id, song.source, song.lyricsCacheRevision, lyricsDataVersion, priority) {
        mutableStateOf(song)
    }
    LaunchedEffect(
        song.id,
        song.source,
        song.lyricsCacheRevision,
        nextSong?.id,
        nextSong?.source,
        nextSong?.lyricsCacheRevision,
        lyricsDataVersion,
        priority,
    ) {
        suspend fun hydrate(target: Song, isPrefetch: Boolean = false): Song {
            val hydrated = when {
                target.source == SongSource.REMOTE && app != null ->
                    app.remoteLyricsRepository.songWithLyrics(target, isPrefetch)
                target.source == SongSource.REMOTE -> target
                else -> library.songWithLyrics(target, priority, isPrefetch)
            }
            if (!isPrefetch && target.id == song.id) {
                logLyricsRenderInput(context, hydrated)
            }
            return hydrated
        }

        coroutineScope {
            launch {
                SharedLyricsMemoryCache.invalidations.collect { songIds ->
                    if (song.id in songIds) resolved = hydrate(song)
                }
            }
            resolved = hydrate(song)
            nextSong?.let { upcoming ->
                when {
                    upcoming.source == SongSource.REMOTE && app != null ->
                        launch { app.remoteLyricsRepository.songWithLyrics(upcoming, isPrefetch = true) }
                    upcoming.source != SongSource.REMOTE -> library.prefetchLyrics(upcoming, priority)
                }
            }
        }
    }
    return resolved
}

private fun logLyricsRenderInput(context: Context, song: Song) {
    val document = song.lyricsDocument
    val splitEnabled = LyricsPreferences.lyricSplitEnabled(context)
    val readingEnabled = LyricsPreferences.lyricReadingEnabled(context)
    val bilingualMode = LyricsPreferences.lyricsBilingualDisplayMode(context)
    DiagnosticLog.event(
        "LyricsRenderInput",
        "song=${song.id.takeLast(12)} source=${song.source.name} loaded=${song.lyricsLoaded} " +
            "format=${document.format.name} origin=${document.origin.name} lines=${document.lines.size} " +
            "rawShapes=${document.rawRoleShapeSummary()} " +
            "displayShapes=${document.displayRoleShapeSummary(bilingualMode, readingEnabled, splitEnabled)} " +
            "split=$splitEnabled mode=${bilingualMode.name} reading=$readingEnabled " +
            "fontSp=${LyricsPreferences.lyricsPageFontSizeSp(context)} " +
            "translationFontSp=${LyricsPreferences.lyricsPageTranslationFontSizeSp(context)}",
    )
}

private fun LyricsDocument.rawRoleShapeSummary(): String =
    roleShapeSummary { line ->
        line.parts.joinToString("+") { it.role.name }.ifBlank { "EMPTY" }
    }

private fun LyricsDocument.displayRoleShapeSummary(
    mode: com.mica.music.data.LyricsBilingualDisplayMode,
    readingEnabled: Boolean,
    splitEnabled: Boolean,
): String = roleShapeSummary { line ->
    LyricDisplayRows.rowsFromParts(
        parts = line.parts,
        mode = mode,
        readingEnabled = readingEnabled,
        splitEnabled = splitEnabled,
    )?.joinToString("+") { it.role.name }
        ?.ifBlank { "EMPTY" }
        ?: "FALLBACK"
}

private inline fun LyricsDocument.roleShapeSummary(
    crossinline shape: (com.mica.music.data.LyricLineNode) -> String,
): String {
    val counts = lines.groupingBy { line -> shape(line) }.eachCount()
    return counts.entries
        .sortedByDescending { it.value }
        .take(6)
        .joinToString(",") { "${it.key}:${it.value}" }
        .ifBlank { "none" }
}
