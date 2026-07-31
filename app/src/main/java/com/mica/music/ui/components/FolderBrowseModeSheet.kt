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
import com.mica.music.data.FolderBrowseMode
import com.mica.music.ui.theme.HifiPalette
import com.mica.music.ui.theme.HifiSpacing
import com.mica.music.ui.theme.MicaTheme

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun FolderBrowseModeSheet(
    currentMode: FolderBrowseMode,
    onModeSelected: (FolderBrowseMode) -> Unit,
    onDismiss: () -> Unit,
) {
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
                text = "文件夹显示模式",
                style = MicaTheme.typography.titleMd,
                color = MicaTheme.colors.textPrimary,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(HifiSpacing.sm),
                verticalArrangement = Arrangement.spacedBy(HifiSpacing.sm),
            ) {
                FolderBrowseMode.entries.forEach { mode ->
                    AccentTextChoice(
                        label = mode.label,
                        selected = mode == currentMode,
                        onClick = { onModeSelected(mode) },
                    )
                }
            }
            Text(
                text = when (currentMode) {
                    FolderBrowseMode.HIERARCHY -> "逐层浏览所有目录。"
                    FolderBrowseMode.MUSIC_FOLDERS -> "扁平列出直接包含歌曲的目录，不包含仅有音乐子目录的父目录。"
                },
                style = MicaTheme.typography.bodySm,
                color = MicaTheme.colors.textSecondary,
            )
        }
    }
}
