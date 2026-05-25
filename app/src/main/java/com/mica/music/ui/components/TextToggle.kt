package com.mica.music.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.mica.music.ui.theme.HifiSize
import com.mica.music.ui.theme.HifiSpacing
import com.mica.music.ui.theme.MicaTheme

@Composable
fun TextToggle(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    onLabel: String = "开",
    offLabel: String = "关",
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(HifiSpacing.xs),
        modifier = modifier
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = HifiSpacing.sm, vertical = HifiSpacing.xs),
    ) {
        Text(
            text = if (checked) onLabel else offLabel,
            style = MicaTheme.typography.bodyMd,
            color = if (checked) MicaTheme.colors.accent else MicaTheme.colors.textTertiary,
        )
        Box(
            Modifier
                .size(HifiSize.activeDot)
                .background(if (checked) MicaTheme.colors.accent else Color.Transparent)
        )
    }
}
