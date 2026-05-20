package com.mica.music.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.mica.music.ui.theme.HifiSize
import com.mica.music.ui.theme.HifiSpacing
import com.mica.music.ui.theme.MicaTheme

@Composable
fun MinimalTabRow(
    tabs: List<String>,
    selectedIndex: Int,
    onTabSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(HifiSpacing.xl),
        modifier = modifier
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = HifiSpacing.lg)
    ) {
        tabs.forEachIndexed { index, label ->
            val active = index == selectedIndex
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .clickable { onTabSelected(index) }
                    .padding(vertical = HifiSpacing.xs),
            ) {
                Text(
                    text = label,
                    style = if (active) MicaTheme.typography.titleSm
                            else MicaTheme.typography.bodyMd,
                    color = if (active) MicaTheme.colors.textPrimary
                            else MicaTheme.colors.textTertiary,
                    modifier = Modifier.padding(vertical = HifiSpacing.xs),
                )
                Spacer(Modifier.height(HifiSpacing.xxs))
                Box(
                    Modifier
                        .width(20.dp)
                        .height(HifiSize.accentBarWidth)
                        .background(
                            if (active) MicaTheme.colors.accent else Color.Transparent
                        )
                )
            }
        }
    }
}
