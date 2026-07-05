package com.mica.music.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mica.music.ui.theme.MicaTheme

private val OuterSize = 14.dp
private val InnerSize = 8.dp

@Composable
fun SongSelectionCheckbox(
    selected: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(OuterSize)
            .border(1.dp, MicaTheme.colors.textTertiary),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Box(
                Modifier
                    .size(InnerSize)
                    .background(MicaTheme.colors.accent),
            )
        }
    }
}
