package com.mica.music.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mica.music.data.MusicLibrary
import com.mica.music.data.PlayerController
import com.mica.music.data.Song
import com.mica.music.data.scanner.MetadataEntry
import com.mica.music.data.scanner.MetadataProbe
import com.mica.music.ui.theme.HifiSize
import com.mica.music.ui.theme.HifiSpacing
import com.mica.music.ui.theme.MicaTheme
import kotlinx.coroutines.launch

@Composable
fun MetadataDebugContent(
    library: MusicLibrary,
    playerController: PlayerController,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val songs = library.songs
    var songIndex by remember(songs.size, playerController.currentSong?.id) {
        val current = playerController.currentSong
        val idx = if (current != null) songs.indexOfFirst { it.id == current.id } else -1
        mutableIntStateOf(if (idx >= 0) idx else 0)
    }
    val selectedSong = songs.getOrNull(songIndex.coerceIn(0, (songs.size - 1).coerceAtLeast(0)))
    var entries by remember { mutableStateOf<List<MetadataEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun load(song: Song) {
        loading = true
        error = null
        scope.launch {
            runCatching { MetadataProbe.probe(context, song) }
                .onSuccess { entries = it }
                .onFailure { e ->
                    entries = emptyList()
                    error = e.message ?: e.javaClass.simpleName
                }
            loading = false
        }
    }

    LaunchedEffect(selectedSong?.id) {
        selectedSong?.let { load(it) }
    }

    Column(modifier = modifier.fillMaxSize()) {
        if (songs.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "暂无歌曲，请先扫描曲库",
                    style = MicaTheme.typography.bodyMd,
                    color = MicaTheme.colors.textTertiary,
                )
            }
            return
        }

        SongPickerBar(
            song = selectedSong,
            index = songIndex,
            total = songs.size,
            onPrev = {
                if (songs.isNotEmpty()) {
                    songIndex = (songIndex - 1 + songs.size) % songs.size
                }
            },
            onNext = {
                if (songs.isNotEmpty()) {
                    songIndex = (songIndex + 1) % songs.size
                }
            },
            onRefresh = { selectedSong?.let { load(it) } },
            loading = loading,
        )

        error?.let { msg ->
            Text(
                text = "加载失败：$msg",
                style = MicaTheme.typography.bodySm,
                color = MicaTheme.colors.accent,
                modifier = Modifier.padding(horizontal = HifiSpacing.lg, vertical = HifiSpacing.sm),
            )
        }

        if (loading && entries.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = MicaTheme.colors.accent)
            }
        } else {
            val grouped = entries.groupBy { it.group }
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = HifiSpacing.lg,
                    vertical = HifiSpacing.sm,
                ),
                verticalArrangement = Arrangement.spacedBy(HifiSpacing.xs),
            ) {
                grouped.forEach { (group, items) ->
                    item(key = "h-$group") {
                        Text(
                            text = group,
                            style = MicaTheme.typography.titleSm,
                            color = MicaTheme.colors.textPrimary,
                            modifier = Modifier.padding(vertical = HifiSpacing.sm),
                        )
                    }
                    items(items, key = { "${group}_${it.key}" }) { row ->
                        MetadataRow(row)
                    }
                    item(key = "d-$group") {
                        HorizontalDivider(
                            color = MicaTheme.colors.divider,
                            modifier = Modifier.padding(vertical = HifiSpacing.sm),
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun MetadataDebugScreen(
    library: MusicLibrary,
    playerController: PlayerController,
    onBack: () -> Unit,
    contentPadding: androidx.compose.foundation.layout.PaddingValues =
        androidx.compose.foundation.layout.PaddingValues(),
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = HifiSpacing.sm),
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(HifiSize.touchTarget)) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = "返回",
                    tint = MicaTheme.colors.textPrimary,
                )
            }
            Text(
                text = "元数据调试",
                style = MicaTheme.typography.titleMd,
                color = MicaTheme.colors.textPrimary,
            )
        }
        MetadataDebugContent(
            library = library,
            playerController = playerController,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun SongPickerBar(
    song: Song?,
    index: Int,
    total: Int,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onRefresh: () -> Unit,
    loading: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MicaTheme.colors.surfaceCard)
            .padding(horizontal = HifiSpacing.sm, vertical = HifiSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onPrev, enabled = total > 1) {
            Icon(Icons.Outlined.ChevronLeft, "上一首", tint = MicaTheme.colors.textPrimary)
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = song?.title ?: "—",
                style = MicaTheme.typography.bodyMd,
                color = MicaTheme.colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${index + 1} / $total · ${song?.artist.orEmpty()}",
                style = MicaTheme.typography.bodySm,
                color = MicaTheme.colors.textTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onRefresh, enabled = !loading) {
            Icon(Icons.Outlined.Refresh, "刷新", tint = MicaTheme.colors.textPrimary)
        }
        IconButton(onClick = onNext, enabled = total > 1) {
            Icon(Icons.Outlined.ChevronRight, "下一首", tint = MicaTheme.colors.textPrimary)
        }
    }
}

@Composable
private fun MetadataRow(entry: MetadataEntry) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        Text(
            text = entry.key,
            style = MicaTheme.typography.bodySm,
            color = MicaTheme.colors.textSecondary,
        )
        Text(
            text = entry.value,
            style = MicaTheme.typography.bodySm,
            fontFamily = FontFamily.Monospace,
            color = MicaTheme.colors.textPrimary,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}
