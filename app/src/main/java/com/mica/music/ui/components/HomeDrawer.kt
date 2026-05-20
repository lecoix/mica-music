package com.mica.music.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.Icon
import androidx.compose.ui.unit.Dp
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.mica.music.data.UserPlaylist
import com.mica.music.ui.screens.HomeSection
import com.mica.music.ui.theme.HifiSize
import com.mica.music.ui.theme.HifiSpacing
import com.mica.music.ui.theme.MicaTheme
import kotlin.math.roundToInt

private val DrawerWidthFraction = 0.72f

@Composable
fun HomeDrawerOverlay(
    open: Boolean,
    selectedSection: HomeSection,
    activePlaylistId: String?,
    playlists: List<UserPlaylist>,
    statusBarTop: Dp,
    onSectionSelected: (HomeSection) -> Unit,
    onPlaylistSelected: (String) -> Unit,
    onCreatePlaylist: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    val drawerWidth = screenWidth * DrawerWidthFraction
    val offsetX by animateFloatAsState(
        targetValue = if (open) 0f else -drawerWidth.value,
        animationSpec = tween(280),
        label = "drawerSlide",
    )
    val scrimAlpha by animateFloatAsState(
        targetValue = if (open) 0.35f else 0f,
        animationSpec = tween(280),
        label = "drawerScrim",
    )

    if (open || scrimAlpha > 0f) {
        Box(modifier = modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = scrimAlpha))
                    .clickable(enabled = open, onClick = onDismiss),
            )
            Column(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(drawerWidth)
                    .offset { IntOffset(offsetX.roundToInt(), 0) }
                    .background(
                        MicaTheme.colors.surfaceGlass.copy(
                            alpha = if (MicaTheme.colors.isDark) 0.58f else 0.88f,
                        ),
                    )
                    .verticalScroll(rememberScrollState())
                    .padding(top = statusBarTop + HifiSpacing.sm, bottom = HifiSpacing.xl),
            ) {
                Text(
                    text = "浏览",
                    style = MicaTheme.typography.caption,
                    color = MicaTheme.colors.textTertiary,
                    modifier = Modifier.padding(horizontal = HifiSpacing.lg, vertical = HifiSpacing.sm),
                )
                DrawerItem("歌曲", selectedSection == HomeSection.Songs) {
                    onSectionSelected(HomeSection.Songs)
                }
                DrawerItem("歌手", selectedSection == HomeSection.Artists) {
                    onSectionSelected(HomeSection.Artists)
                }
                DrawerItem("专辑", selectedSection == HomeSection.Albums) {
                    onSectionSelected(HomeSection.Albums)
                }

                Spacer(Modifier.height(HifiSpacing.md))
                DrawerDivider()
                Spacer(Modifier.height(HifiSpacing.md))

                DrawerItem("最近播放", selectedSection == HomeSection.Recent) {
                    onSectionSelected(HomeSection.Recent)
                }
                DrawerItem("我喜欢", selectedSection == HomeSection.Favorites) {
                    onSectionSelected(HomeSection.Favorites)
                }

                Spacer(Modifier.height(HifiSpacing.md))
                DrawerDivider()
                Spacer(Modifier.height(HifiSpacing.md))

                Text(
                    text = "歌单",
                    style = MicaTheme.typography.caption,
                    color = MicaTheme.colors.textTertiary,
                    modifier = Modifier.padding(horizontal = HifiSpacing.lg, vertical = HifiSpacing.sm),
                )
                playlists.forEach { playlist ->
                    val selected = selectedSection == HomeSection.Playlist && activePlaylistId == playlist.id
                    DrawerItem(playlist.name, selected) {
                        onPlaylistSelected(playlist.id)
                    }
                }
                DrawerItemWithIcon(
                    label = "新建歌单",
                    selected = false,
                    icon = Icons.Outlined.Add,
                    onClick = onCreatePlaylist,
                )
                DrawerItem("音乐库分析", selectedSection == HomeSection.LibraryAnalysis) {
                    onSectionSelected(HomeSection.LibraryAnalysis)
                }

                Spacer(Modifier.height(HifiSpacing.md))
                DrawerDivider()
                Spacer(Modifier.height(HifiSpacing.md))

                DrawerItem("设置", selectedSection == HomeSection.Settings) {
                    onSectionSelected(HomeSection.Settings)
                }
            }
        }
    }
}

@Composable
private fun DrawerItem(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    DrawerItemRow(label = label, selected = selected, onClick = onClick)
}

@Composable
private fun DrawerItemWithIcon(
    label: String,
    selected: Boolean,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
) {
    DrawerItemRow(
        label = label,
        selected = selected,
        onClick = onClick,
        leading = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MicaTheme.colors.textSecondary,
                modifier = Modifier
                    .padding(start = HifiSpacing.lg + HifiSize.accentBarWidth + HifiSpacing.sm)
                    .width(HifiSize.iconMd)
                    .height(HifiSize.iconMd),
            )
        },
    )
}

@Composable
private fun DrawerItemRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    leading: (@Composable () -> Unit)? = null,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = HifiSpacing.sm),
    ) {
        if (selected) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .width(HifiSize.accentBarWidth)
                    .height(24.dp)
                    .background(MicaTheme.colors.accent),
            )
        }
        if (leading != null) {
            Box(modifier = Modifier.align(Alignment.CenterStart)) {
                leading()
            }
            Text(
                text = label,
                style = MicaTheme.typography.bodyLg,
                color = if (selected) MicaTheme.colors.accent else MicaTheme.colors.textPrimary,
                modifier = Modifier.padding(
                    start = HifiSpacing.lg + HifiSize.accentBarWidth + HifiSpacing.sm + HifiSize.iconMd + HifiSpacing.sm,
                ),
            )
        } else {
            Text(
                text = label,
                style = MicaTheme.typography.bodyLg,
                color = if (selected) MicaTheme.colors.accent else MicaTheme.colors.textPrimary,
                modifier = Modifier.padding(start = HifiSpacing.lg + HifiSize.accentBarWidth + HifiSpacing.sm),
            )
        }
    }
}

@Composable
private fun DrawerDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = HifiSpacing.lg)
            .height(HifiSize.dividerHairline)
            .background(MicaTheme.colors.divider),
    )
}
