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
import com.mica.music.data.SortDirection
import com.mica.music.data.AppUiSettings
import com.mica.music.ui.theme.HifiPalette
import com.mica.music.ui.theme.HifiSpacing
import com.mica.music.ui.theme.MicaTheme

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun BrowseGroupDisplaySheet(
    sortFieldLabels: List<String>,
    selectedSortFieldIndex: Int,
    currentDirection: SortDirection,
    currentColumns: Int,
    onDismiss: () -> Unit,
    onSortFieldSelected: (Int) -> Unit,
    onDirectionSelected: (SortDirection) -> Unit,
    onColumnsSelected: (Int) -> Unit,
    uiSettings: AppUiSettings,
    isArtist: Boolean,
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
                .verticalScroll(rememberScrollState())
                .padding(horizontal = HifiSpacing.lg)
                .padding(bottom = HifiSpacing.xxl),
            verticalArrangement = Arrangement.spacedBy(HifiSpacing.md),
        ) {
            Text(
                text = "排序与显示",
                style = MicaTheme.typography.titleMd,
                color = MicaTheme.colors.textPrimary,
            )
            if (sortFieldLabels.isNotEmpty()) {
                Text(
                    text = "排序方式",
                    style = MicaTheme.typography.caption,
                    color = MicaTheme.colors.textSecondary,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(HifiSpacing.sm),
                    verticalArrangement = Arrangement.spacedBy(HifiSpacing.sm),
                ) {
                    sortFieldLabels.forEachIndexed { index, label ->
                        AccentTextChoice(
                            label = label,
                            selected = index == selectedSortFieldIndex,
                            onClick = { onSortFieldSelected(index) },
                        )
                    }
                }
            }
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
                        onClick = { onDirectionSelected(direction) },
                    )
                }
            }
            Text(
                text = "列数",
                style = MicaTheme.typography.caption,
                color = MicaTheme.colors.textSecondary,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(HifiSpacing.sm),
                verticalArrangement = Arrangement.spacedBy(HifiSpacing.sm),
            ) {
                (1..4).forEach { columns ->
                    AccentTextChoice(
                        label = "${columns}列",
                        selected = columns == currentColumns,
                        onClick = { onColumnsSelected(columns) },
                    )
                }
            }
            val info = uiSettings.browseListInfoVisibility
            fun update(transform: (com.mica.music.data.BrowseListInfoVisibility) -> com.mica.music.data.BrowseListInfoVisibility) {
                uiSettings.updateBrowseListInfoVisibility(transform(uiSettings.browseListInfoVisibility))
            }
            val customEnabled = if (isArtist) info.showArtistCustomText else info.showAlbumCustomText
            Text("信息行内容", style = MicaTheme.typography.caption, color = MicaTheme.colors.textSecondary)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(HifiSpacing.sm),
                verticalArrangement = Arrangement.spacedBy(HifiSpacing.sm),
            ) {
                val selections = if (isArtist) {
                    listOf("艺术家数量" to info.showArtistCount, "排序方式" to info.showArtistSortOrder, "列表列数" to info.showArtistGridColumns, "上次扫描时间" to info.showArtistLastScanTime, "自定义文字" to info.showArtistCustomText)
                } else {
                    listOf("专辑数量" to info.showAlbumCount, "排序方式" to info.showAlbumSortOrder, "列表列数" to info.showAlbumGridColumns, "上次扫描时间" to info.showAlbumLastScanTime, "自定义文字" to info.showAlbumCustomText)
                }
                selections.forEach { (label, selected) ->
                    AccentTextChoice(label, selected, onClick = {
                        update {
                            if (isArtist) when (label) {
                                "艺术家数量" -> it.copy(showArtistCount = !selected)
                                "排序方式" -> it.copy(showArtistSortOrder = !selected)
                                "列表列数" -> it.copy(showArtistGridColumns = !selected)
                                "上次扫描时间" -> it.copy(showArtistLastScanTime = !selected)
                                else -> it.copy(showArtistCustomText = !selected)
                            } else when (label) {
                                "专辑数量" -> it.copy(showAlbumCount = !selected)
                                "排序方式" -> it.copy(showAlbumSortOrder = !selected)
                                "列表列数" -> it.copy(showAlbumGridColumns = !selected)
                                "上次扫描时间" -> it.copy(showAlbumLastScanTime = !selected)
                                else -> it.copy(showAlbumCustomText = !selected)
                            }
                        }
                    })
                }
            }
            SettingsTextFieldRow(
                value = if (isArtist) info.artistCustomText else info.albumCustomText,
                onValueChange = { text -> update { if (isArtist) it.copy(artistCustomText = text) else it.copy(albumCustomText = text) } },
                placeholder = "输入自定义文本",
                enabled = customEnabled,
            )
        }
    }
}
