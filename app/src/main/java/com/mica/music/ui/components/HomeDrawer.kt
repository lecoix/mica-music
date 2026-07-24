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
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Info
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
import com.mica.music.ui.screens.home.HomeSection
import com.mica.music.ui.theme.HifiSize
import com.mica.music.ui.theme.HifiSpacing
import com.mica.music.ui.theme.MicaTheme
import com.mica.music.ui.theme.micaAppBackground

/** 侧栏占屏宽比例；与主页内容右移量一致。 */
const val HomeDrawerWidthFraction = 0.5f
internal val HomeDrawerMaxWidth = 420.dp

internal fun homeDrawerWidthFor(screenWidth: Dp): Dp =
    (screenWidth * HomeDrawerWidthFraction).coerceAtMost(HomeDrawerMaxWidth)

@Composable
fun homeDrawerWidth(): Dp {
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    return homeDrawerWidthFor(screenWidth)
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
    onOpenEqualizer: () -> Unit,
    onOpenAbout: () -> Unit,
    onPlaylistSelected: (String) -> Unit,
    onCreatePlaylist: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val configuration = LocalConfiguration.current
    val columns = drawerColumnsFor(
        widthDp = configuration.screenWidthDp,
        heightDp = configuration.screenHeightDp,
    )
    val libraryItems =
        listOf(
            DrawerItem("歌曲", Icons.Outlined.LibraryMusic, selectedSection == HomeSection.Songs) {
                onSectionSelected(HomeSection.Songs)
            },
            DrawerItem("艺术家", Icons.Outlined.Person, selectedSection == HomeSection.Artists) {
                onSectionSelected(HomeSection.Artists)
            },
            DrawerItem("专辑", Icons.Outlined.Album, selectedSection == HomeSection.Albums) {
                onSectionSelected(HomeSection.Albums)
            },
            DrawerItem("文件夹", Icons.Outlined.Folder, selectedSection == HomeSection.Folders) {
                onSectionSelected(HomeSection.Folders)
            },
        )
    val discoveryItems =
        listOf(
            DrawerItem("最近播放", Icons.Outlined.History, selectedSection == HomeSection.Recent) {
                onSectionSelected(HomeSection.Recent)
            },
            DrawerItem(
                "音乐库分析",
                Icons.Outlined.Analytics,
                selectedSection == HomeSection.LibraryAnalysis,
            ) {
                onSectionSelected(HomeSection.LibraryAnalysis)
            },
        )
    val playlistItems = playlists.map { playlist ->
        DrawerItem(
            label = playlist.name,
            icon = Icons.Outlined.PlaylistPlay,
            selected = selectedSection == HomeSection.Playlist && activePlaylistId == playlist.id,
            onClick = { onPlaylistSelected(playlist.id) },
        )
    } + DrawerItem(
        label = "新建歌单",
        icon = Icons.Outlined.Add,
        selected = false,
        muted = true,
        onClick = onCreatePlaylist,
    )
    val bottomItems = listOf(
        DrawerItem("均衡器", Icons.Outlined.GraphicEq, false, onClick = onOpenEqualizer),
        DrawerItem("关于", Icons.Outlined.Info, false, onClick = onOpenAbout),
        DrawerItem("设置", Icons.Outlined.Settings, selectedSection == HomeSection.Settings) {
            onSectionSelected(HomeSection.Settings)
        },
    )

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
                        top = statusBarTop + HifiSpacing.md,
                        bottom = HifiSpacing.md,
                    ),
            ) {
                DrawerSectionLabel("曲库")
                DrawerNavGrid(items = libraryItems, columns = columns)

                Spacer(Modifier.height(HifiSpacing.xl))

                DrawerSectionLabel("发现")
                DrawerNavGrid(items = discoveryItems, columns = columns)

                Spacer(Modifier.height(HifiSpacing.xl))

                DrawerSectionLabel("歌单")
                DrawerNavGrid(items = playlistItems, columns = columns)
            }

            DrawerNavGrid(
                items = bottomItems,
                columns = columns,
                modifier = Modifier.padding(
                    bottom = HifiSpacing.xl + bottomInset,
                ),
            )
        }
    }
}

internal fun drawerColumnsFor(widthDp: Int, heightDp: Int): Int =
    if (widthDp > heightDp) 2 else 1

private data class DrawerItem(
    val label: String,
    val icon: ImageVector,
    val selected: Boolean,
    val muted: Boolean = false,
    val onClick: () -> Unit,
)

@Composable
private fun DrawerNavGrid(
    items: List<DrawerItem>,
    columns: Int,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        items.chunked(columns).forEach { rowItems ->
            Row(Modifier.fillMaxWidth()) {
                rowItems.forEach { item ->
                    DrawerNavItem(
                        label = item.label,
                        icon = item.icon,
                        selected = item.selected,
                        muted = item.muted,
                        onClick = item.onClick,
                        compact = columns > 1,
                        modifier = Modifier.weight(1f),
                    )
                }
                repeat(columns - rowItems.size) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
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
    compact: Boolean = false,
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
            .padding(horizontal = if (compact) HifiSpacing.sm else HifiSpacing.lg),
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
        Spacer(Modifier.width(if (compact) HifiSpacing.sm else HifiSpacing.md))
        Text(
            text = label,
            style = textStyle,
            color = textColor,
            maxLines = 1,
        )
    }
}
