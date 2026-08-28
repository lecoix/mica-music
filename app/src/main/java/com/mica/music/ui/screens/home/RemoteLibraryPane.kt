package com.mica.music.ui.screens.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import com.mica.music.data.Song
import com.mica.music.data.remote.RemoteCatalogRepository
import com.mica.music.data.remote.toPlaybackSong
import com.mica.music.ui.components.SongRow
import com.mica.music.ui.theme.HifiSpacing
import com.mica.music.ui.theme.MicaTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun RemoteLibraryPane(
    repository: RemoteCatalogRepository,
    currentSongId: String?,
    isPlaying: Boolean,
    onQueueSongs: (List<Song>) -> Unit,
    onSongClick: (String) -> Unit,
    listState: LazyListState,
    listBottomPadding: Dp,
    modifier: Modifier = Modifier,
) {
    var songs by remember(repository) { mutableStateOf<List<Song>>(emptyList()) }
    var loading by remember(repository) { mutableStateOf(true) }
    var loadError by remember(repository) { mutableStateOf<String?>(null) }
    var refreshRevision by remember { mutableIntStateOf(0) }

    LaunchedEffect(repository, refreshRevision) {
        loading = true
        loadError = null
        runCatching {
            val tracks = repository.tracksForEnabledSources()
            withContext(Dispatchers.Default) {
                tracks.map { track -> track.toPlaybackSong() }
            }
        }.onSuccess { loaded ->
            songs = loaded
        }.onFailure { failure ->
            songs = emptyList()
            loadError = failure.message ?: failure.javaClass.simpleName
        }
        loading = false
    }

    when {
        loading -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        loadError != null -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(HifiSpacing.sm),
                modifier = Modifier.padding(HifiSpacing.xl),
            ) {
                Text(
                    text = "远程曲库读取失败",
                    style = MicaTheme.typography.bodyLg,
                    color = MicaTheme.colors.textPrimary,
                )
                Text(
                    text = loadError.orEmpty(),
                    style = MicaTheme.typography.bodySm,
                    color = MicaTheme.colors.textTertiary,
                )
                TextButton(onClick = { refreshRevision++ }) {
                    Text("重试")
                }
            }
        }
        songs.isEmpty() -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(HifiSpacing.sm),
                modifier = Modifier.padding(HifiSpacing.xl),
            ) {
                Text(
                    text = "暂无已同步的远程歌曲",
                    style = MicaTheme.typography.bodyLg,
                    color = MicaTheme.colors.textPrimary,
                )
                Text(
                    text = "请先在设置 → 曲库 → 远程曲库中添加并同步来源",
                    style = MicaTheme.typography.bodySm,
                    color = MicaTheme.colors.textTertiary,
                )
            }
        }
        else -> LazyColumn(
            state = listState,
            contentPadding = PaddingValues(bottom = listBottomPadding),
            modifier = modifier.fillMaxSize(),
        ) {
            itemsIndexed(
                items = songs,
                key = { _, song -> song.id },
            ) { index, song ->
                val isCurrent = currentSongId == song.id
                SongRow(
                    song = song,
                    trackNumber = (index + 1).toString().padStart(2, '0'),
                    isCurrent = isCurrent,
                    isPlaying = isCurrent && isPlaying,
                    onClick = {
                        onQueueSongs(songs)
                        onSongClick(song.id)
                    },
                    onLongClick = null,
                )
            }
        }
    }
}
