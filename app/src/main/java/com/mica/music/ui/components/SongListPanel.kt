package com.mica.music.ui.components

import android.os.SystemClock
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mica.music.data.LibraryFastScrollIndex
import com.mica.music.data.MusicLibrary
import com.mica.music.data.Song
import com.mica.music.data.SongListInfoVisibility
import com.mica.music.data.SongSortField
import com.mica.music.data.SortDirection
import com.mica.music.ui.theme.MicaTheme
import com.mica.music.util.DiagnosticLog

@Composable
fun SongListPanel(
    songs: List<Song>,
    library: MusicLibrary,
    currentSongId: String?,
    isPlaying: Boolean,
    onSongClick: (String) -> Unit,
    onSongOpenMenu: ((Song) -> Unit)? = null,
    emptyMessage: String,
    listState: LazyListState? = null,
    fastScrollSortField: SongSortField? = library.sortField,
    fastScrollSortDirection: SortDirection = library.sortDirection,
    fastScrollLabels: List<String>? = null,
    fastScrollSectionTargets: Map<String, Int>? = null,
    listBottomPadding: Dp = 0.dp,
    selectionMode: Boolean = false,
    selectedSongIds: Set<String> = emptySet(),
    onSelectionToggle: (String) -> Unit = {},
    infoVisibility: SongListInfoVisibility = SongListInfoVisibility(),
    modifier: Modifier = Modifier,
) {
    val lazyListState = listState ?: rememberLazyListState()
    val resolvedFastScrollLabels = when {
        fastScrollLabels != null -> fastScrollLabels
        fastScrollSortField != null -> songs.timedFastScrollLabels(fastScrollSortField)
        else -> null
    }

    if (songs.isEmpty()) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = emptyMessage,
                style = MicaTheme.typography.bodyMd,
                color = MicaTheme.colors.textTertiary,
            )
        }
        return
    }

    if (resolvedFastScrollLabels == null) {
        SongRows(
            songs = songs,
            currentSongId = currentSongId,
            isPlaying = isPlaying,
            onSongClick = onSongClick,
            onSongOpenMenu = onSongOpenMenu,
            listState = lazyListState,
            listBottomPadding = listBottomPadding,
            selectionMode = selectionMode,
            selectedSongIds = selectedSongIds,
            onSelectionToggle = onSelectionToggle,
            infoVisibility = infoVisibility,
            modifier = modifier.fillMaxSize(),
        )
    } else {
        AlphabetFastScroller(
            labels = resolvedFastScrollLabels,
            sectionTargetsOverride = fastScrollSectionTargets,
            scrollToIndex = { lazyListState.scrollToItem(it) },
            descending = fastScrollSortDirection == SortDirection.DESC,
            modifier = modifier.fillMaxSize(),
        ) {
            SongRows(
                songs = songs,
                currentSongId = currentSongId,
                isPlaying = isPlaying,
                onSongClick = onSongClick,
                onSongOpenMenu = onSongOpenMenu,
                listState = lazyListState,
                listBottomPadding = listBottomPadding,
                selectionMode = selectionMode,
                selectedSongIds = selectedSongIds,
                onSelectionToggle = onSelectionToggle,
                infoVisibility = infoVisibility,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

private fun List<Song>.timedFastScrollLabels(field: SongSortField): List<String>? {
    val startedMs = SystemClock.elapsedRealtime()
    val labels = LibraryFastScrollIndex.labelsForSongs(this, field)
    DiagnosticLog.event(
        "LibraryUi",
        "songList fastScrollLabels durMs=${SystemClock.elapsedRealtime() - startedMs} " +
            "songs=$size field=$field labels=${labels?.size ?: 0}",
    )
    return labels
}

@Composable
private fun SongRows(
    songs: List<Song>,
    currentSongId: String?,
    isPlaying: Boolean,
    onSongClick: (String) -> Unit,
    onSongOpenMenu: ((Song) -> Unit)?,
    listState: LazyListState,
    listBottomPadding: Dp,
    selectionMode: Boolean = false,
    selectedSongIds: Set<String> = emptySet(),
    onSelectionToggle: (String) -> Unit = {},
    infoVisibility: SongListInfoVisibility,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        state = listState,
        modifier = modifier,
        contentPadding = PaddingValues(bottom = listBottomPadding),
    ) {
        items(songs, key = { it.id }) { song ->
            val isCurrent = currentSongId == song.id
            SongRow(
                song = song,
                isCurrent = isCurrent,
                isPlaying = isCurrent && isPlaying,
                onClick = {
                    if (selectionMode) {
                        onSelectionToggle(song.id)
                    } else {
                        onSongClick(song.id)
                    }
                },
                onLongClick = if (selectionMode) null else onSongOpenMenu?.let { open -> { open(song) } },
                selectionMode = selectionMode,
                isSelected = song.id in selectedSongIds,
                infoVisibility = infoVisibility,
            )
        }
    }
}
