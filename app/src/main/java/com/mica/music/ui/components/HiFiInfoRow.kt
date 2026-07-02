package com.mica.music.ui.components

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.mica.music.ui.theme.MicaTheme

@Composable
fun HiFiInfoRow(
    segments: List<String>,
    modifier: Modifier = Modifier,
    textColor: Color = MicaTheme.colors.textTertiary,
) {
    if (segments.isEmpty()) return
    Text(
        text = segments.joinToString(" · "),
        style = MicaTheme.typography.monoMd,
        color = textColor,
        modifier = modifier,
    )
}
