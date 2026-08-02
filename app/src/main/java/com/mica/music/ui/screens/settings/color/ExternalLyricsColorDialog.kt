package com.mica.music.ui.screens.settings.color

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.dp
import com.mica.music.data.DEFAULT_EXTERNAL_LYRICS_COLORS
import com.mica.music.data.MAX_EXTERNAL_LYRICS_COLORS
import com.mica.music.ui.theme.HifiSpacing
import com.mica.music.ui.theme.MicaTheme

@Composable
internal fun ExternalLyricsColorDialog(
    initialColors: List<Int>,
    colorCount: Int,
    onDismiss: () -> Unit,
    onConfirm: (List<Int>) -> Unit,
) {
    val safeCount = colorCount.coerceIn(1, MAX_EXTERNAL_LYRICS_COLORS)
    val defaults = (initialColors + DEFAULT_EXTERNAL_LYRICS_COLORS).take(MAX_EXTERNAL_LYRICS_COLORS)
    var color0 by remember(initialColors) { mutableIntStateOf(defaults[0]) }
    var color1 by remember(initialColors) { mutableIntStateOf(defaults[1]) }
    var color2 by remember(initialColors) { mutableIntStateOf(defaults[2]) }
    var color3 by remember(initialColors) { mutableIntStateOf(defaults[3]) }
    val colors = listOf(color0, color1, color2, color3)

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RectangleShape,
        title = {
            Text(
                text = "桌面歌词颜色",
                style = MicaTheme.typography.titleMd,
                color = MicaTheme.colors.textPrimary,
            )
        },
        text = {
            androidx.compose.foundation.layout.Column(
                verticalArrangement = Arrangement.spacedBy(HifiSpacing.md),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                repeat(safeCount) { index ->
                    HsvColorEditor(
                        title = "颜色 ${index + 1}",
                        initialColorArgb = colors[index],
                        onColorChange = { value ->
                            when (index) {
                                0 -> color0 = value
                                1 -> color1 = value
                                2 -> color2 = value
                                3 -> color3 = value
                            }
                        },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(colors.take(safeCount)) }) {
                Text("应用")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        },
    )
}
