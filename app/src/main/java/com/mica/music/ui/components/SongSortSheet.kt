package com.mica.music.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.mica.music.data.SongSortField
import com.mica.music.data.SortDirection
import com.mica.music.data.AppUiSettings
import com.mica.music.data.SongTrailingInfo
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
    uiSettings: AppUiSettings? = null,
    playlistActions: List<Pair<String, () -> Unit>> = emptyList(),
) {
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
                .verticalScroll(rememberScrollState())
                .padding(horizontal = HifiSpacing.lg)
                .padding(bottom = HifiSpacing.xxl),
            verticalArrangement = Arrangement.spacedBy(HifiSpacing.md),
        ) {
            Text(
                text = "排序方式",
                style = MicaTheme.typography.caption,
                color = MicaTheme.colors.textSecondary,
            )
            SongSortChoices(currentField, currentDirection, includeCustomSort, customSortLocked, onApply)
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
            if (playlistActions.isNotEmpty()) {
                Text(
                    text = "歌单操作",
                    style = MicaTheme.typography.caption,
                    color = MicaTheme.colors.textSecondary,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(HifiSpacing.sm),
                    verticalArrangement = Arrangement.spacedBy(HifiSpacing.sm),
                ) {
                    playlistActions.forEach { (label, action) ->
                        AccentTextChoice(
                            label = label,
                            selected = false,
                            onClick = {
                                action()
                                onDismiss()
                            },
                        )
                    }
                }
            }
            if (uiSettings != null) {
                val info = uiSettings.songListInfoVisibility
                fun update(transform: (com.mica.music.data.SongListInfoVisibility) -> com.mica.music.data.SongListInfoVisibility) {
                    uiSettings.updateSongListInfoVisibility(transform(uiSettings.songListInfoVisibility))
                }
                Text("信息行内容", style = MicaTheme.typography.caption, color = MicaTheme.colors.textSecondary)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(HifiSpacing.sm),
                    verticalArrangement = Arrangement.spacedBy(HifiSpacing.sm),
                ) {
                    listOf(
                        "歌曲数量" to info.showSongCount,
                        "曲库大小" to info.showLibrarySize,
                        "排序方式" to info.showSortOrder,
                        "上次扫描时间" to info.showLastScanTime,
                        "自定义文字" to info.showCustomText,
                    ).forEach { (label, selected) ->
                        AccentTextChoice(label, selected, onClick = {
                            update {
                                when (label) {
                                    "歌曲数量" -> it.copy(showSongCount = !selected)
                                    "曲库大小" -> it.copy(showLibrarySize = !selected)
                                    "排序方式" -> it.copy(showSortOrder = !selected)
                                    "上次扫描时间" -> it.copy(showLastScanTime = !selected)
                                    else -> it.copy(showCustomText = !selected)
                                }
                            }
                        })
                    }
                }
                SettingsTextFieldRow(
                    value = info.customText,
                    onValueChange = { text -> update { it.copy(customText = text) } },
                    placeholder = "输入自定义文本",
                    enabled = info.showCustomText,
                )
                Text("歌曲副行", style = MicaTheme.typography.caption, color = MicaTheme.colors.textSecondary)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(HifiSpacing.sm),
                    verticalArrangement = Arrangement.spacedBy(HifiSpacing.sm),
                ) {
                    listOf(
                        "艺术家" to info.showSongArtist,
                        "专辑" to info.showSongAlbum,
                        "播放次数" to info.showSongPlayCount,
                        "歌曲时长" to info.showSongDuration,
                    ).forEach { (label, selected) ->
                        AccentTextChoice(label, selected, onClick = {
                            update {
                                when (label) {
                                    "艺术家" -> it.copy(showSongArtist = !selected)
                                    "专辑" -> it.copy(showSongAlbum = !selected)
                                    "播放次数" -> it.copy(showSongPlayCount = !selected)
                                    else -> it.copy(showSongDuration = !selected)
                                }
                            }
                        })
                    }
                }
                Text("右侧信息", style = MicaTheme.typography.caption, color = MicaTheme.colors.textSecondary)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(HifiSpacing.sm),
                    verticalArrangement = Arrangement.spacedBy(HifiSpacing.sm),
                ) {
                    SongTrailingInfo.entries.forEach { trailing ->
                        AccentTextChoice(trailing.label, trailing == info.trailingInfo, onClick = {
                            update { it.copy(trailingInfo = trailing) }
                        })
                    }
                }
            }
        }
    }
}

/** Shared rendering for the real sort sheet and its read-only tutorial demonstration. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SongSortChoices(
    currentField: SongSortField,
    currentDirection: SortDirection,
    includeCustomSort: Boolean,
    customSortLocked: Boolean,
    onApply: (SongSortField, SortDirection) -> Unit,
) {
    val fields = if (includeCustomSort) {
        listOf(SongSortField.CUSTOM) + SongSortField.entries.filter { it != SongSortField.CUSTOM }
    } else {
        SongSortField.entries.filter { it != SongSortField.CUSTOM }
    }
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(HifiSpacing.sm),
        verticalArrangement = Arrangement.spacedBy(HifiSpacing.sm),
    ) {
        fields.forEach { field ->
            val locked = field == SongSortField.CUSTOM && currentField == SongSortField.CUSTOM && customSortLocked
            AccentTextChoice(
                label = if (locked) "${field.label}·锁定" else field.label,
                selected = field == currentField,
                onClick = { onApply(field, if (field == SongSortField.CUSTOM) SortDirection.ASC else currentDirection) },
            )
        }
    }
}
