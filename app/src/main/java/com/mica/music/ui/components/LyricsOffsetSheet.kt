package com.mica.music.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.unit.dp
import com.mica.music.data.LyricsTiming
import com.mica.music.data.MAX_LYRICS_OFFSET_MS
import com.mica.music.data.MIN_LYRICS_OFFSET_MS
import com.mica.music.ui.theme.HifiPalette
import com.mica.music.ui.theme.HifiSpacing
import com.mica.music.ui.theme.MicaTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LyricsOffsetSheet(
    globalOffsetMs: Int,
    songOffsetMs: Int? = null,
    onGlobalOffsetChange: ((Int) -> Unit)? = null,
    onSongOffsetChange: ((Int) -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val background = if (MicaTheme.colors.isDark) HifiPalette.MicaFogDarkEnd else HifiPalette.MicaFogStart
    val editingSong = songOffsetMs != null && onSongOffsetChange != null
    val initial = if (editingSong) songOffsetMs ?: 0 else globalOffsetMs
    var current by remember(editingSong) { mutableIntStateOf(initial) }
    val onChange = if (editingSong) onSongOffsetChange!! else requireNotNull(onGlobalOffsetChange)
    val effective = LyricsTiming.effectiveOffsetMs(globalOffsetMs, if (editingSong) current else 0)

    fun update(value: Int) {
        current = value
        onChange(value)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        containerColor = background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = HifiSpacing.lg, vertical = HifiSpacing.md),
            verticalArrangement = Arrangement.spacedBy(HifiSpacing.md),
        ) {
            Text(
                text = if (editingSong) "本曲歌词偏移" else "全局歌词偏移",
                style = MicaTheme.typography.titleMd,
                color = MicaTheme.colors.textPrimary,
            )
            Text(
                text = "正数让歌词提前，负数让歌词延后；不会修改音频进度或歌词文件。",
                style = MicaTheme.typography.bodySm,
                color = MicaTheme.colors.textSecondary,
            )
            if (editingSong) {
                Text(
                    text = "全局 ${formatLyricsOffset(globalOffsetMs)}  ·  本曲 ${formatLyricsOffset(current)}  ·  实际 ${formatLyricsOffset(effective)}",
                    style = MicaTheme.typography.monoMd,
                    color = MicaTheme.colors.accent,
                )
            } else {
                Text(
                    text = formatLyricsOffset(current),
                    style = MicaTheme.typography.monoMd,
                    color = MicaTheme.colors.accent,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(HifiSpacing.sm),
            ) {
                OffsetButton("−0.5", Modifier.weight(1f), showCornerDots = true) {
                    update((current - 500).coerceIn(MIN_LYRICS_OFFSET_MS, MAX_LYRICS_OFFSET_MS))
                }
                OffsetButton("−0.1", Modifier.weight(1f), showCornerDots = true) {
                    update((current - 100).coerceIn(MIN_LYRICS_OFFSET_MS, MAX_LYRICS_OFFSET_MS))
                }
                OffsetButton("重置", Modifier.weight(1f), selected = current == 0) { update(0) }
                OffsetButton("+0.1", Modifier.weight(1f), showCornerDots = true) {
                    update((current + 100).coerceIn(MIN_LYRICS_OFFSET_MS, MAX_LYRICS_OFFSET_MS))
                }
                OffsetButton("+0.5", Modifier.weight(1f), showCornerDots = true) {
                    update((current + 500).coerceIn(MIN_LYRICS_OFFSET_MS, MAX_LYRICS_OFFSET_MS))
                }
            }
            Text(
                text = "范围 −5.0 秒至 +5.0 秒，步进 0.1 秒",
                style = MicaTheme.typography.caption,
                color = MicaTheme.colors.textTertiary,
                modifier = Modifier.padding(bottom = 20.dp),
            )
        }
    }
}

@Composable
private fun OffsetButton(
    label: String,
    modifier: Modifier,
    selected: Boolean = false,
    showCornerDots: Boolean = false,
    onClick: () -> Unit,
) {
    val dotColor = MicaTheme.colors.accent
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .height(48.dp)
            .then(
                if (showCornerDots) {
                    Modifier.drawBehind {
                        val dotSize = 1.5.dp.toPx()
                        val inset = 5.dp.toPx()
                        val farX = size.width - inset - dotSize
                        val farY = size.height - inset - dotSize
                        val square = androidx.compose.ui.geometry.Size(dotSize, dotSize)
                        drawRect(dotColor, topLeft = androidx.compose.ui.geometry.Offset(inset, inset), size = square)
                        drawRect(dotColor, topLeft = androidx.compose.ui.geometry.Offset(farX, inset), size = square)
                        drawRect(dotColor, topLeft = androidx.compose.ui.geometry.Offset(inset, farY), size = square)
                        drawRect(dotColor, topLeft = androidx.compose.ui.geometry.Offset(farX, farY), size = square)
                    }
                } else {
                    Modifier
                },
            )
            .clickable(onClick = onClick),
    ) {
        Text(
            text = label,
            style = MicaTheme.typography.monoSm,
            color = if (selected) MicaTheme.colors.accent else MicaTheme.colors.textPrimary,
        )
    }
}

fun formatLyricsOffset(offsetMs: Int): String = when {
    offsetMs > 0 -> "+%.1f 秒".format(offsetMs / 1000f)
    else -> "%.1f 秒".format(offsetMs / 1000f)
}
