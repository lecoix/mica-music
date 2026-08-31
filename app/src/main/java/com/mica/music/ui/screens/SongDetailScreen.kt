package com.mica.music.ui.screens

import android.widget.Toast
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
import androidx.compose.material.icons.outlined.Analytics
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mica.music.data.MusicLibrary
import com.mica.music.data.Song
import com.mica.music.data.SongDetails
import com.mica.music.imaging.CoverDecodeTarget
import com.mica.music.media.loudness.LoudnessScanManager
import com.mica.music.ui.components.SongCover
import com.mica.music.ui.theme.HifiSize
import com.mica.music.ui.theme.HifiSpacing
import com.mica.music.ui.theme.MicaTheme
import com.mica.music.ui.theme.coverColor
import com.mica.music.ui.theme.micaAppBackground
import com.mica.music.util.shareSong
import kotlinx.coroutines.launch

@Composable
fun SongDetailScreen(
    song: Song,
    library: MusicLibrary,
    onBack: () -> Unit,
    contentPadding: PaddingValues = PaddingValues(),
    bottomContentClearance: Dp = 0.dp,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var analyzing by remember(song.id) { mutableStateOf(false) }
    val displaySong = library.songs.firstOrNull { it.id == song.id } ?: song
    val rows = remember(displaySong, library.lastScanSource) {
        SongDetails.buildRows(displaySong, library)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .micaAppBackground()
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
            if (!displaySong.isRemote) {
                IconButton(
                    onClick = {
                        if (analyzing) return@IconButton
                        analyzing = true
                        scope.launch {
                            val result = LoudnessScanManager.analyzeSingle(context, displaySong, library)
                            analyzing = false
                            result.fold(
                                onSuccess = { analysis ->
                                    Toast.makeText(
                                        context,
                                        "响度分析完成 · %.1f LUFS".format(analysis.integratedLufs),
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                },
                                onFailure = { error ->
                                    Toast.makeText(
                                        context,
                                        "响度分析失败：${error.message ?: "未知错误"}",
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                },
                            )
                        }
                    },
                    enabled = !analyzing,
                    modifier = Modifier.size(HifiSize.touchTarget),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Analytics,
                        contentDescription = if (analyzing) "正在分析响度" else "分析响度",
                        tint = MicaTheme.colors.textPrimary,
                    )
                }
            }
            IconButton(
                onClick = { shareSong(context, displaySong) },
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
                    albumArtUri = displaySong.albumArtUri,
                    fallbackColor = displaySong.coverColor,
                    contentDescription = displaySong.title,
                    decodeTarget = CoverDecodeTarget.forCompactCover(),
                    modifier = Modifier.size(HifiSize.coverMd),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = displaySong.title,
                        style = MicaTheme.typography.titleMd,
                        color = MicaTheme.colors.textPrimary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(HifiSpacing.xs))
                    Text(
                        text = displaySong.artist,
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

            Spacer(Modifier.height(HifiSpacing.xl + bottomContentClearance))
        }
    }

    if (analyzing) {
        AlertDialog(
            onDismissRequest = {},
            shape = RectangleShape,
            title = {
                Text(
                    text = "正在扫描响度数据",
                    style = MicaTheme.typography.titleMd,
                    color = MicaTheme.colors.textPrimary,
                )
            },
            text = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(HifiSpacing.md),
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(HifiSize.iconLg),
                        color = MicaTheme.colors.accent,
                        strokeWidth = 2.dp,
                    )
                    Text(
                        text = "正在读取整首音频并计算综合响度与采样峰值。完成后会自动保存分析结果。",
                        style = MicaTheme.typography.bodyMd,
                        color = MicaTheme.colors.textSecondary,
                    )
                }
            },
            confirmButton = {},
        )
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
