package com.mica.music.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import com.mica.music.ui.theme.HifiSpacing
import com.mica.music.ui.theme.MicaTheme

/**
 * 统一激活态：仅字色 + 字重区分（无下划线、无背景）。
 */
@Composable
fun AccentTextChoice(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    horizontalPadding: Dp = HifiSpacing.sm,
) {
    val colors = MicaTheme.colors
    val typography = MicaTheme.typography
    val textColor = when {
        !enabled -> colors.textTertiary.copy(alpha = 0.5f)
        selected -> colors.accent
        else -> colors.textTertiary
    }
    val textStyle = if (selected && enabled) typography.titleSm else typography.bodyMd

    Text(
        text = label,
        style = textStyle,
        color = textColor,
        modifier = modifier
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = horizontalPadding, vertical = HifiSpacing.xxs),
    )
}
