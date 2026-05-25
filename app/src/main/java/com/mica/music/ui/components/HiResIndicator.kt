package com.mica.music.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.mica.music.ui.theme.HifiSize
import com.mica.music.ui.theme.HifiSpacing
import com.mica.music.ui.theme.MicaTheme

@Composable
fun HiResIndicator(
    modifier: Modifier = Modifier,
    label: String = "Hi-Res",
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(HifiSpacing.xs)
    ) {
        Box(
            Modifier
                .size(HifiSize.hiResDot)
                .background(MicaTheme.colors.hiRes)
        )
        Text(
            text = label,
            style = MicaTheme.typography.caption,
            color = MicaTheme.colors.hiRes,
        )
    }
}
