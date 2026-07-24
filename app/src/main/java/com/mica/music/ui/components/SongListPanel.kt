package com.mica.music.ui.components

import android.os.SystemClock
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
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
    val configuration = LocalConfiguration.current
    val columns = songListColumnsFor(
        widthDp = configuration.screenWidthDp,
        heightDp = configuration.screenHeightDp,
    )
    val lazyListState = listState ?: rememberLazyListState()
    val lazyGridState = rememberLazyGridState()
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
            gridState = lazyGridState,
            columns = columns,
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
            scrollToIndex = { index ->
                if (columns > 1) {
                    lazyGridState.scrollToItem(index)
                } else {
                    lazyListState.scrollToItem(index)
                }
            },
            descending = fastScrollSortDirection == SortDirection.DESC,
            fullHeightOverlay = columns > 1,
            modifier = modifier.fillMaxSize(),
        ) {
            SongRows(
                songs = songs,
                currentSongId = currentSongId,
                isPlaying = isPlaying,
                onSongClick = onSongClick,
                onSongOpenMenu = onSongOpenMenu,
                listState = lazyListState,
                gridState = lazyGridState,
                columns = columns,
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

internal fun songListColumnsFor(widthDp: Int, heightDp: Int): Int =
    if (widthDp > heightDp) 2 else 1

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
    gridState: androidx.compose.foundation.lazy.grid.LazyGridState,
    columns: Int,
    listBottomPadding: Dp,
    selectionMode: Boolean = false,
    selectedSongIds: Set<String> = emptySet(),
    onSelectionToggle: (String) -> Unit = {},
    infoVisibility: SongListInfoVisibility,
    modifier: Modifier = Modifier,
) {
    val songItem: @Composable (Song) -> Unit = { song ->
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

    if (columns > 1) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            state = gridState,
            modifier = modifier,
            contentPadding = PaddingValues(bottom = listBottomPadding),
        ) {
            gridItems(songs, key = { it.id }) { song ->
                songItem(song)
            }
        }
    } else {
        LazyColumn(
            state = listState,
            modifier = modifier,
            contentPadding = PaddingValues(bottom = listBottomPadding),
        ) {
            items(songs, key = { it.id }) { song ->
                songItem(song)
            }
        }
    }
}
