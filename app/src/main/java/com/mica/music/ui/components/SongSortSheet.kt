package com.mica.music.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
    customSortLocked: Boolean = false,
    onMultiSelectClick: (() -> Unit)? = null,
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
                text = "排序方式",
                style = MicaTheme.typography.caption,
                color = MicaTheme.colors.textSecondary,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(HifiSpacing.sm),
                verticalArrangement = Arrangement.spacedBy(HifiSpacing.sm),
            ) {
                sortFields.forEach { field ->
                    val customLocked = field == SongSortField.CUSTOM &&
                        currentField == SongSortField.CUSTOM &&
                        customSortLocked
                    AccentTextChoice(
                        label = if (customLocked) "${field.label}·锁定" else field.label,
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
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(HifiSpacing.sm),
                    verticalArrangement = Arrangement.spacedBy(HifiSpacing.sm),
                ) {
                    SortDirection.entries.forEach { direction ->
                        AccentTextChoice(
                            label = direction.label,
                            selected = direction == currentDirection,
                            onClick = { onApply(currentField, direction) },
                        )
                    }
                }
            }
            if (onMultiSelectClick != null) {
                Text(
                    text = "批量操作",
                    style = MicaTheme.typography.caption,
                    color = MicaTheme.colors.textSecondary,
                )
                AccentTextChoice(
                    label = "多选",
                    selected = false,
                    onClick = {
                        onMultiSelectClick()
                        onDismiss()
                    },
                )
            }
        }
    }
}
