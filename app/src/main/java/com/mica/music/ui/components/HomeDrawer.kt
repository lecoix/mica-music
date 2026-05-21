package com.mica.music.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Album
import androidx.compose.material.icons.outlined.Analytics
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PlaylistPlay
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mica.music.data.UserPlaylist
import com.mica.music.ui.screens.HomeSection
import com.mica.music.ui.theme.HifiSize
import com.mica.music.ui.theme.HifiSpacing
import com.mica.music.ui.theme.MicaTheme
import com.mica.music.ui.theme.micaAppBackground

/** 侧栏占屏宽比例；与主页内容右移量一致。 */
const val HomeDrawerWidthFraction = 0.5f

@Composable
fun homeDrawerWidth(): Dp {
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    return screenWidth * HomeDrawerWidthFraction
}

/**
 * 左侧导航抽屉（无分隔线、无全屏遮罩）。
 * [bottomInset] 用于将底部「设置」抬到迷你播放栏之上。
 */
@Composable
fun HomeDrawerPanel(
    selectedSection: HomeSection,
    activePlaylistId: String?,
    playlists: List<UserPlaylist>,
    statusBarTop: Dp,
    bottomInset: Dp,
    onSectionSelected: (HomeSection) -> Unit,
    onPlaylistSelected: (String) -> Unit,
    onCreatePlaylist: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .width(homeDrawerWidth())
            .fillMaxHeight()
            .micaAppBackground(),
    ) {
        Column(Modifier.fillMaxHeight()) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(
                        top = statusBarTop + HifiSpacing.lg,
                        bottom = HifiSpacing.md,
                    ),
            ) {
                DrawerHeader()

                Spacer(Modifier.height(HifiSpacing.xl))

                DrawerSectionLabel("曲库")
                DrawerNavItem(
                    label = "歌曲",
                    icon = Icons.Outlined.LibraryMusic,
                    selected = selectedSection == HomeSection.Songs,
                    onClick = { onSectionSelected(HomeSection.Songs) },
                )
                DrawerNavItem(
                    label = "歌手",
                    icon = Icons.Outlined.Person,
                    selected = selectedSection == HomeSection.Artists,
                    onClick = { onSectionSelected(HomeSection.Artists) },
                )
                DrawerNavItem(
                    label = "专辑",
                    icon = Icons.Outlined.Album,
                    selected = selectedSection == HomeSection.Albums,
                    onClick = { onSectionSelected(HomeSection.Albums) },
                )

                Spacer(Modifier.height(HifiSpacing.xl))

                DrawerSectionLabel("发现")
                DrawerNavItem(
                    label = "最近播放",
                    icon = Icons.Outlined.History,
                    selected = selectedSection == HomeSection.Recent,
                    onClick = { onSectionSelected(HomeSection.Recent) },
                )
                DrawerNavItem(
                    label = "音乐库分析",
                    icon = Icons.Outlined.Analytics,
                    selected = selectedSection == HomeSection.LibraryAnalysis,
                    onClick = { onSectionSelected(HomeSection.LibraryAnalysis) },
                )

                Spacer(Modifier.height(HifiSpacing.xl))

                DrawerSectionLabel("歌单")
                playlists.forEach { playlist ->
                    val selected =
                        selectedSection == HomeSection.Playlist && activePlaylistId == playlist.id
                    DrawerNavItem(
                        label = playlist.name,
                        icon = Icons.Outlined.PlaylistPlay,
                        selected = selected,
                        onClick = { onPlaylistSelected(playlist.id) },
                    )
                }
                DrawerNavItem(
                    label = "新建歌单",
                    icon = Icons.Outlined.Add,
                    selected = false,
                    muted = true,
                    onClick = onCreatePlaylist,
                )
            }

            DrawerNavItem(
                label = "设置",
                icon = Icons.Outlined.Settings,
                selected = selectedSection == HomeSection.Settings,
                onClick = { onSectionSelected(HomeSection.Settings) },
                modifier = Modifier.padding(
                    bottom = HifiSpacing.xl + bottomInset,
                ),
            )
        }
    }
}

@Composable
private fun DrawerHeader() {
    Column(
        modifier = Modifier.padding(horizontal = HifiSpacing.lg),
    ) {
        Text(
            text = "Mica",
            style = MicaTheme.typography.titleMd,
            color = MicaTheme.colors.textPrimary,
        )
        Spacer(Modifier.height(HifiSpacing.xxs))
        Text(
            text = "本地音乐",
            style = MicaTheme.typography.caption,
            color = MicaTheme.colors.textTertiary,
        )
    }
}

@Composable
private fun DrawerSectionLabel(text: String) {
    Text(
        text = text,
        style = MicaTheme.typography.monoSm,
        color = MicaTheme.colors.textTertiary,
        modifier = Modifier.padding(
            start = HifiSpacing.lg,
            end = HifiSpacing.lg,
            top = HifiSpacing.xs,
            bottom = HifiSpacing.sm,
        ),
    )
}

@Composable
private fun DrawerNavItem(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    muted: Boolean = false,
) {
    val textColor = when {
        selected -> MicaTheme.colors.accent
        muted -> MicaTheme.colors.textSecondary
        else -> MicaTheme.colors.textPrimary
    }
    val iconTint = when {
        selected -> MicaTheme.colors.accent
        muted -> MicaTheme.colors.textTertiary
        else -> MicaTheme.colors.textSecondary
    }
    val textStyle = MicaTheme.typography.bodyMd.copy(
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
    )

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .height(HifiSize.touchTarget)
            .clickable(onClick = onClick)
            .padding(horizontal = HifiSpacing.lg),
    ) {
        Box(
            modifier = Modifier
                .width(HifiSize.accentBarWidth)
                .height(20.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (selected) {
                Box(
                    Modifier
                        .fillMaxHeight()
                        .width(HifiSize.accentBarWidth)
                        .background(MicaTheme.colors.accent),
                )
            }
        }
        Spacer(Modifier.width(HifiSpacing.sm))
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier.size(HifiSize.iconMd),
        )
        Spacer(Modifier.width(HifiSpacing.md))
        Text(
            text = label,
            style = textStyle,
            color = textColor,
            maxLines = 1,
        )
    }
}
