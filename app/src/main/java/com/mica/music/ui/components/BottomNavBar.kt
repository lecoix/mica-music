package com.mica.music.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.mica.music.ui.theme.HifiSize
import com.mica.music.ui.theme.HifiSpacing
import com.mica.music.ui.theme.MicaTheme

data class BottomNavItem(
    val label: String,
    val icon: ImageVector,
)

val DefaultBottomNavItems = listOf(
    BottomNavItem("本地", Icons.Outlined.LibraryMusic),
    BottomNavItem("播放中", Icons.Outlined.PlayCircle),
    BottomNavItem("设置", Icons.Outlined.Settings),
)

@Composable
fun BottomNavBar(
    selectedIndex: Int,
    onItemSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
    items: List<BottomNavItem> = DefaultBottomNavItems,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceEvenly,
        modifier = modifier
            .fillMaxWidth()
            .height(HifiSize.bottomNavHeight)
            .background(MicaTheme.colors.surfaceGlass)
            .padding(vertical = HifiSpacing.sm),
    ) {
        items.forEachIndexed { index, item ->
            val active = index == selectedIndex
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .weight(1f)
                    .clickable { onItemSelected(index) }
                    .padding(vertical = HifiSpacing.xs),
            ) {
                Icon(
                    imageVector = item.icon,
                    contentDescription = item.label,
                    tint = if (active) MicaTheme.colors.accent else MicaTheme.colors.textTertiary,
                    modifier = Modifier.size(HifiSize.iconLg),
                )
                Spacer(Modifier.height(HifiSpacing.xs))
                Text(
                    text = item.label,
                    style = MicaTheme.typography.caption,
                    color = if (active) MicaTheme.colors.accent else MicaTheme.colors.textTertiary,
                )
                Spacer(Modifier.height(HifiSpacing.xxs))
                Box(
                    Modifier
                        .width(20.dp)
                        .height(HifiSize.accentBarWidth)
                        .background(if (active) MicaTheme.colors.accent else Color.Transparent)
                )
            }
        }
    }
}
