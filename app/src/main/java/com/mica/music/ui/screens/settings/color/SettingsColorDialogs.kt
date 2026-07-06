package com.mica.music.ui.screens.settings.color

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.dp
import com.mica.music.ui.theme.HifiSpacing
import com.mica.music.ui.theme.MicaTheme

@Composable
internal fun CustomMicaBackgroundDialog(
    initialStartArgb: Int,
    initialEndArgb: Int,
    initialSingleColor: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (Int, Int, Boolean) -> Unit,
) {
    var singleColor by remember(initialSingleColor) { mutableStateOf(initialSingleColor) }
    var startArgb by remember(initialStartArgb) { mutableIntStateOf(initialStartArgb) }
    var endArgb by remember(initialEndArgb) { mutableIntStateOf(initialEndArgb) }
    val previewStart = Color(startArgb)
    val previewEnd = if (singleColor) previewStart else Color(endArgb)

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RectangleShape,
        title = {
            Text(
                text = "自定义云母背景",
                style = MicaTheme.typography.titleMd,
                color = MicaTheme.colors.textPrimary,
            )
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(HifiSpacing.md),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(HifiSpacing.sm)) {
                    MicaColorModeChip(
                        label = "双色",
                        selected = !singleColor,
                        onClick = { singleColor = false },
                    )
                    MicaColorModeChip(
                        label = "单色",
                        selected = singleColor,
                        onClick = { singleColor = true },
                    )
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .background(Brush.verticalGradient(listOf(previewStart, previewEnd)))
                        .border(1.dp, MicaTheme.colors.divider),
                )

                HsvColorEditor(
                    title = if (singleColor) "背景色" else "顶部色",
                    initialColorArgb = initialStartArgb,
                    onColorChange = { startArgb = it },
                )

                if (!singleColor) {
                    HsvColorEditor(
                        title = "底部色",
                        initialColorArgb = initialEndArgb,
                        onColorChange = { endArgb = it },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(startArgb, endArgb, singleColor) }) {
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

@Composable
internal fun MicaColorModeChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    TextButton(
        onClick = onClick,
        modifier = if (selected) {
            Modifier.background(MicaTheme.colors.accent.copy(alpha = 0.18f))
        } else {
            Modifier
        },
    ) {
        Text(
            text = label,
            color = if (selected) MicaTheme.colors.accent else MicaTheme.colors.textSecondary,
        )
    }
}
@Composable
internal fun CustomAccentColorDialog(
    initialColorArgb: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    var colorArgb by remember(initialColorArgb) { mutableIntStateOf(initialColorArgb) }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RectangleShape,
        title = {
            Text(
                text = "自定义强调色",
                style = MicaTheme.typography.titleMd,
                color = MicaTheme.colors.textPrimary,
            )
        },
        text = {
            HsvColorEditor(
                title = "强调色",
                initialColorArgb = initialColorArgb,
                onColorChange = { colorArgb = it },
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(colorArgb) }) {
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
