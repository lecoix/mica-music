package com.mica.music.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mica.music.data.MusicLibrary
import com.mica.music.data.Song
import com.mica.music.data.SongDetails
import com.mica.music.ui.components.SongCover
import com.mica.music.ui.theme.HifiSize
import com.mica.music.ui.theme.HifiSpacing
import com.mica.music.ui.theme.MicaPreset
import com.mica.music.ui.theme.MicaTheme
import com.mica.music.ui.theme.micaBackground
import com.mica.music.util.shareSong

@Composable
fun SongDetailScreen(
    song: Song,
    library: MusicLibrary,
    onBack: () -> Unit,
    contentPadding: PaddingValues = PaddingValues(),
) {
    val context = LocalContext.current
    val rows = remember(song, library.songs.size, library.lastScanSource) {
        SongDetails.buildRows(song, library)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .micaBackground(MicaPreset.Dawn)
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
                text = "歌曲详情",
                style = MicaTheme.typography.titleMd,
                color = MicaTheme.colors.textPrimary,
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = { shareSong(context, song) },
                modifier = Modifier.size(HifiSize.touchTarget),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Share,
                    contentDescription = "分享",
                    tint = MicaTheme.colors.textPrimary,
                )
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = HifiSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(HifiSpacing.lg),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(HifiSpacing.md),
            ) {
                SongCover(
                    albumArtUri = song.albumArtUri,
                    fallbackColor = song.coverColor,
                    contentDescription = song.title,
                    modifier = Modifier.size(HifiSize.coverMd),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = song.title,
                        style = MicaTheme.typography.titleMd,
                        color = MicaTheme.colors.textPrimary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(HifiSpacing.xs))
                    Text(
                        text = song.artist,
                        style = MicaTheme.typography.bodyMd,
                        color = MicaTheme.colors.textSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                rows.forEachIndexed { index, row ->
                    SongDetailFieldRow(label = row.label, value = row.value)
                    if (index < rows.lastIndex) {
                        HorizontalDivider(
                            thickness = HifiSize.dividerHairline,
                            color = MicaTheme.colors.divider,
                        )
                    }
                }
            }

            Spacer(Modifier.height(HifiSpacing.xl))
        }
    }
}

@Composable
private fun SongDetailFieldRow(
    label: String,
    value: String,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = HifiSpacing.md),
    ) {
        Text(
            text = label,
            style = MicaTheme.typography.caption,
            color = MicaTheme.colors.textTertiary,
        )
        Spacer(Modifier.height(HifiSpacing.xxs))
        Text(
            text = value,
            style = MicaTheme.typography.bodyMd,
            color = MicaTheme.colors.textPrimary,
        )
    }
}
