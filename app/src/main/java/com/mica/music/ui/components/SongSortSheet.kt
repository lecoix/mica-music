package com.mica.music.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.mica.music.data.SongSortField
import com.mica.music.data.SortDirection
import com.mica.music.ui.theme.HifiPalette
import com.mica.music.ui.theme.HifiSpacing
import com.mica.music.ui.theme.MicaTheme

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SongSortSheet(
    currentField: SongSortField,
    currentDirection: SortDirection,
    onDismiss: () -> Unit,
    onApply: (SongSortField, SortDirection) -> Unit,
    includeCustomSort: Boolean = false,
) {
    val sortFields = if (includeCustomSort) {
        listOf(SongSortField.CUSTOM) + SongSortField.entries.filter { it != SongSortField.CUSTOM }
    } else {
        SongSortField.entries.filter { it != SongSortField.CUSTOM }
    }
    val showDirection = currentField != SongSortField.CUSTOM
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val isDark = MicaTheme.colors.isDark
    val sheetBackground = if (isDark) {
        HifiPalette.MicaFogDarkEnd
    } else {
        HifiPalette.MicaFogStart
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = sheetBackground,
        scrimColor = Color.Black.copy(alpha = if (isDark) 0.72f else 0.45f),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = HifiSpacing.lg)
                .padding(bottom = HifiSpacing.xxl),
            verticalArrangement = Arrangement.spacedBy(HifiSpacing.md),
        ) {
            Text(
                text = "排序",
                style = MicaTheme.typography.titleMd,
                color = MicaTheme.colors.textPrimary,
            )
            Text(
                text = "排序方式",
                style = MicaTheme.typography.caption,
                color = MicaTheme.colors.textSecondary,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(HifiSpacing.sm),
                verticalArrangement = Arrangement.spacedBy(HifiSpacing.sm),
            ) {
                sortFields.forEach { field ->
                    SortChip(
                        label = field.label,
                        selected = field == currentField,
                        onClick = {
                            onApply(
                                field,
                                if (field == SongSortField.CUSTOM) SortDirection.ASC else currentDirection,
                            )
                        },
                    )
                }
            }
            if (showDirection) {
                Text(
                    text = "顺序",
                    style = MicaTheme.typography.caption,
                    color = MicaTheme.colors.textSecondary,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(HifiSpacing.sm)) {
                    SortDirection.entries.forEach { direction ->
                        SortChip(
                            label = direction.label,
                            selected = direction == currentDirection,
                            onClick = { onApply(currentField, direction) },
                        )
                    }
                }
            } else {
                Text(
                    text = "自定义顺序下可长按右侧把手拖动排序",
                    style = MicaTheme.typography.caption,
                    color = MicaTheme.colors.textTertiary,
                )
            }
        }
    }
}

@Composable
private fun SortChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val accent = MicaTheme.colors.accent
    Text(
        text = label,
        style = MicaTheme.typography.bodyMd,
        color = if (selected) Color.White else MicaTheme.colors.textPrimary,
        modifier = Modifier
            .clickable(onClick = onClick)
            .then(
                if (selected) {
                    Modifier.background(accent)
                } else {
                    Modifier
                        .background(MicaTheme.colors.divider.copy(alpha = 0.35f))
                        .border(1.dp, accent.copy(alpha = 0.55f))
                },
            )
            .padding(horizontal = HifiSpacing.md, vertical = HifiSpacing.xs),
    )
}
