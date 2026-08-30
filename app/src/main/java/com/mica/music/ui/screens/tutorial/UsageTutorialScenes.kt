package com.mica.music.ui.screens.tutorial

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.mica.music.data.SongSortField
import com.mica.music.data.SortDirection
import com.mica.music.data.PlayerInfoVisibility
import com.mica.music.data.PlaybackTuning
import com.mica.music.data.HiResBadgeAppearance
import com.mica.music.playback.PlaybackSurfaceState
import com.mica.music.ui.components.*
import com.mica.music.ui.screens.SongTitleSection
import com.mica.music.ui.screens.HiFiBadgeSection
import com.mica.music.ui.theme.*

/** A complete standard player composition using its production title, seek and controls renderers. */
@Composable
internal fun TutorialPlayerMenu(time: Float) {
    val colors = MicaTheme.colors
    val playerColors = PlayerContentColors(colors.textPrimary, colors.textSecondary, colors.textTertiary)
    val song = TutorialSongs[0]
    val menu = tutorialStep(time, .30f, .42f)
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(16.dp))
            SongCover(
                albumArtUri = null, fallbackColor = colors.accent, contentDescription = "长按专辑封面",
                modifier = Modifier.size(300.dp), publishHoldoverOnSuccess = false, allowPreviousImageUnderlay = false,
            )
            Spacer(Modifier.height(24.dp))
            SongTitleSection(song.title, song.artist, song.album, false, null, playerColors, 0f)
            Spacer(Modifier.height(16.dp))
            PlayerPlaybackBottomSection(
                surfaceState = PlaybackSurfaceState(currentSong = song), colors = playerColors,
                seekState = PlaybackSeekState(42f, 42, song.durationSec, 0f..song.durationSec.toFloat(), {}, {}),
                showStandardProgress = true, afterProgress = HifiSpacing.md,
                onCyclePlaybackQueueMode = {}, onPrevious = {}, onTogglePlay = {}, onNext = {}, onOpenQueue = {},
            )
            Spacer(Modifier.height(20.dp))
            HiFiBadgeSection(song, playerColors, PlayerInfoVisibility(showCurrentTime = false), PlaybackTuning(), HiResBadgeAppearance())
        }
        if (menu > 0f) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .28f * menu)))
            Column(
                Modifier.fillMaxWidth().height(576.dp).graphicsLayer { translationY = (44 + (1 - menu) * 620).dp.toPx() }
                    .background(if (colors.isDark) HifiPalette.MicaFogDarkEnd else HifiPalette.MicaFogStart),
            ) {
                // Same order, labels, icons and item renderer as SongActionMenuSheet on the player.
                SongMenuHeader(song, {}, {})
                HorizontalDivider(color = colors.divider, thickness = HifiSize.dividerHairline)
                SongMenuItem(Icons.Outlined.PlaylistAdd, "添加到歌单", {})
                SongMenuItem(Icons.Outlined.SkipNext, "下一首播放", {})
                SongMenuItem(Icons.Outlined.Bedtime, "睡眠定时", {})
                SongMenuItem(Icons.Outlined.Speed, "速度 / 音高", {})
                SongMenuItem(Icons.Outlined.Tune, "歌词偏移", {})
                SongMenuItem(Icons.Outlined.Share, "分享", {})
                SongMenuItem(Icons.Outlined.Edit, "使用Lyrico编辑音乐标签", {})
                SongMenuItem(Icons.Outlined.Info, "歌曲信息", {})
                SongMenuItem(Icons.Outlined.Delete, "删除音乐", {}, tint = colors.like)
            }
        }
    }
}

/** Scripted local row positions: select custom, move two tracks, reopen, lock, show handle-free result. */
@Composable
internal fun TutorialSortScene(time: Float) {
    val colors = MicaTheme.colors
    val custom = time >= .13f
    val locked = time >= .80f
    val firstMove = tutorialStep(time, .32f, .43f)
    val secondMove = tutorialStep(time, .50f, .61f)
    val initial = listOf(0f, 1f, 2f, 3f)
    val reordered = listOf(2f, 0f, 1f, 3f)
    val final = listOf(3f, 0f, 2f, 1f)
    val sheet = if (time < .5f) 1 - tutorialStep(time, .20f, .26f)
        else tutorialStep(time, .67f, .72f) * (1 - tutorialStep(time, .87f, .92f))
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            TutorialTopBar("歌曲")
            Row(Modifier.fillMaxWidth().height(44.dp).padding(horizontal = HifiSpacing.lg), verticalAlignment = Alignment.CenterVertically) {
                Text("4 首", style = MicaTheme.typography.caption, color = colors.textSecondary, modifier = Modifier.weight(1f))
                Text(if (locked) "自定义·锁定" else if (custom) "自定义" else "标题 ↑", style = MicaTheme.typography.caption, color = colors.textSecondary)
                Spacer(Modifier.width(HifiSpacing.md))
                Icon(Icons.Outlined.Sort, "排序", tint = colors.textSecondary, modifier = Modifier.size(HifiSize.iconMd))
            }
            Layout(modifier = Modifier.fillMaxWidth().height(HifiSize.listRowHeight * 4), content = {
                TutorialSongs.forEachIndexed { index, song ->
                    val dragging = (index == 0 && time in .30f.. .46f) || (index == 3 && time in .49f.. .64f)
                    Row(Modifier.fillMaxWidth().testTag("sort-${song.id}").background(if (dragging) colors.surfaceCard else Color.Transparent), verticalAlignment = Alignment.CenterVertically) {
                        SongRow(song, isCurrent = index == 0, isPlaying = false, onClick = {}, modifier = Modifier.weight(1f))
                        if (custom && !locked) {
                            // Matches the live PlaylistSongListPanel drag-handle row.
                            Icon(Icons.Outlined.DragHandle, "拖动排序", tint = if (dragging) colors.accent else colors.textTertiary,
                                modifier = Modifier.padding(end = HifiSpacing.md).size(HifiSize.iconMd))
                        }
                    }
                }
            }) { measurables, constraints ->
                val height = HifiSize.listRowHeight.roundToPx()
                val rows = measurables.map { it.measure(Constraints.fixed(constraints.maxWidth, height)) }
                layout(constraints.maxWidth, height * 4) {
                    rows.forEachIndexed { index, row ->
                        val slot = initial[index] + (reordered[index] - initial[index]) * firstMove + (final[index] - reordered[index]) * secondMove
                        val dragging = (index == 0 && time in .30f.. .46f) || (index == 3 && time in .49f.. .64f)
                        row.placeRelative(0, (slot * height).toInt(), zIndex = if (dragging) 1f else 0f)
                    }
                }
            }
        }
        if (sheet > 0f) {
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(240.dp).graphicsLayer { translationY = ((1 - sheet) * 240).dp.toPx() }
                    .background(if (colors.isDark) HifiPalette.MicaFogDarkEnd else HifiPalette.MicaFogStart).padding(HifiSpacing.lg),
                verticalArrangement = Arrangement.spacedBy(HifiSpacing.md),
            ) {
                Text("排序方式", style = MicaTheme.typography.caption, color = colors.textSecondary)
                SongSortChoices(if (custom) SongSortField.CUSTOM else SongSortField.TITLE, SortDirection.ASC, true, locked) { _, _ -> }
            }
        }
    }
}
