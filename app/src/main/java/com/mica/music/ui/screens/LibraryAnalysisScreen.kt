package com.mica.music.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mica.music.data.LabeledCount
import com.mica.music.data.LibraryAnalysis
import com.mica.music.data.LibraryAnalyzer
import com.mica.music.data.MusicLibrary
import com.mica.music.ui.theme.HifiPalette
import com.mica.music.ui.components.HiResIndicator
import com.mica.music.ui.theme.HifiSize
import com.mica.music.ui.theme.HifiSpacing
import com.mica.music.ui.theme.MicaTheme
import java.util.Locale

private val OverviewCellHeight = 88.dp
private const val WaffleGridColumns = 10
private val WaffleCellGap = 4.dp
private const val WaffleGridWidthFraction = 0.58f

private val WafflePaletteLight = listOf(
    Color(0xFF8B7AFF),
    Color(0xFF5B9BD5),
    Color(0xFF5BA88C),
    Color(0xFFD4AC4F),
    Color(0xFFE07A5F),
    Color(0xFF9B7BB8),
    Color(0xFF4E9AA8),
    Color(0xFFC97B8E),
    Color(0xFF7A8C99),
    Color(0xFFB5A642),
)

private val WafflePaletteDark = listOf(
    Color(0xFF9D92FF),
    Color(0xFF72B0E8),
    Color(0xFF6BBF9A),
    Color(0xFFE0BE6A),
    Color(0xFFEC9478),
    Color(0xFFB394D4),
    Color(0xFF62B5C4),
    Color(0xFFD88FA0),
    Color(0xFF92A3B0),
    Color(0xFFC9B85A),
)

private val QualityTierColorsLight = mapOf(
    LibraryAnalyzer.TIER_HR to HifiPalette.HiResGold,
    LibraryAnalyzer.TIER_SQ to HifiPalette.PurplePrimary,
    LibraryAnalyzer.TIER_HQ to Color(0xFF5B9BD5),
    LibraryAnalyzer.TIER_OTHER to Color(0xFF5BA88C),
)

private val QualityTierColorsDark = mapOf(
    LibraryAnalyzer.TIER_HR to Color(0xFFE0BE6A),
    LibraryAnalyzer.TIER_SQ to Color(0xFF9D92FF),
    LibraryAnalyzer.TIER_HQ to Color(0xFF72B0E8),
    LibraryAnalyzer.TIER_OTHER to Color(0xFF6BBF9A),
)

@Composable
fun LibraryAnalysisContent(
    library: MusicLibrary,
    listBottomPadding: Dp = 0.dp,
    modifier: Modifier = Modifier,
) {
    val analysis = remember(library.songs) { LibraryAnalyzer.analyze(library.songs) }
    if (library.songs.isEmpty()) {
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "暂无曲库数据",
                    style = MicaTheme.typography.titleSm,
                    color = MicaTheme.colors.textPrimary,
                )
                Spacer(Modifier.height(HifiSpacing.sm))
                Text(
                    text = "请先在设置中扫描本地音乐",
                    style = MicaTheme.typography.bodySm,
                    color = MicaTheme.colors.textTertiary,
                )
            }
        }
        return
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(
                start = HifiSpacing.lg,
                end = HifiSpacing.lg,
                bottom = HifiSpacing.md + listBottomPadding,
            ),
        verticalArrangement = Arrangement.spacedBy(HifiSpacing.xxl),
    ) {
        AnalysisOverviewPanel(analysis)

        AnalysisBreakdownBlock(
            tag = "FORMAT",
            title = "容器格式",
            items = analysis.formatBreakdown,
            total = analysis.totalSongs,
        )
        AnalysisBreakdownBlock(
            tag = "QUALITY",
            title = "音质分级",
            items = analysis.qualityTierBreakdown,
            total = analysis.totalSongs,
            fixedColorByLabel = if (MicaTheme.colors.isDark) {
                QualityTierColorsDark
            } else {
                QualityTierColorsLight
            },
        )

        Spacer(Modifier.height(HifiSpacing.md))
    }
}

@Composable
private fun AnalysisOverviewPanel(analysis: LibraryAnalysis) {
    val panelBg = MicaTheme.colors.surfaceCard.copy(
        alpha = if (MicaTheme.colors.isDark) 0.28f else 0.55f,
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(panelBg),
    ) {
        Row(Modifier.fillMaxWidth().height(OverviewCellHeight)) {
            OverviewMetricCell(
                value = formatSongCount(analysis.totalSongs),
                unit = "首",
                caption = "曲目",
                modifier = Modifier.weight(1f),
            )
            AnalysisHairlineVertical()
            OverviewMetricCell(
                value = formatSizeValue(analysis.totalSizeBytes, analysis.totalSizeMb),
                unit = formatSizeUnit(analysis.totalSizeBytes, analysis.totalSizeMb),
                caption = "曲库体积",
                modifier = Modifier.weight(1f),
            )
        }
        AnalysisHairlineHorizontal()
        Row(Modifier.fillMaxWidth().height(OverviewCellHeight)) {
            OverviewHiResCell(
                count = analysis.hiResCount,
                percent = analysis.hiResPercent,
                modifier = Modifier.weight(1f),
            )
            AnalysisHairlineVertical()
            OverviewMetricCell(
                value = analysis.losslessCount.toString(),
                unit = "首",
                caption = "无损格式",
                subCaption = "${analysis.losslessPercent}%",
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun OverviewMetricCell(
    value: String,
    caption: String,
    modifier: Modifier = Modifier,
    unit: String? = null,
    subCaption: String? = null,
) {
    Column(
        modifier = modifier
            .fillMaxHeight()
            .padding(horizontal = HifiSpacing.lg, vertical = HifiSpacing.md),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = value,
                style = MicaTheme.typography.titleLg,
                color = MicaTheme.colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (unit != null) {
                Text(
                    text = unit,
                    style = MicaTheme.typography.caption,
                    color = MicaTheme.colors.textTertiary,
                    modifier = Modifier.padding(start = HifiSpacing.xxs, bottom = HifiSpacing.xxs),
                )
            }
        }
        Column {
            Text(
                text = caption,
                style = MicaTheme.typography.caption,
                color = MicaTheme.colors.textSecondary,
            )
            if (subCaption != null) {
                Text(
                    text = subCaption,
                    style = MicaTheme.typography.monoSm,
                    color = MicaTheme.colors.textTertiary,
                )
            }
        }
    }
}

@Composable
private fun OverviewHiResCell(
    count: Int,
    percent: Int,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxHeight()
            .padding(horizontal = HifiSpacing.lg, vertical = HifiSpacing.md),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = count.toString(),
                style = MicaTheme.typography.titleLg,
                color = MicaTheme.colors.textPrimary,
            )
            Text(
                text = "首",
                style = MicaTheme.typography.caption,
                color = MicaTheme.colors.textTertiary,
                modifier = Modifier.padding(start = HifiSpacing.xxs, bottom = HifiSpacing.xxs),
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(HifiSpacing.xxs)) {
            HiResIndicator()
            Text(
                text = "$percent%",
                style = MicaTheme.typography.monoSm,
                color = MicaTheme.colors.textTertiary,
            )
        }
    }
}

@Composable
private fun AnalysisBreakdownBlock(
    tag: String,
    title: String,
    items: List<LabeledCount>,
    total: Int,
    fixedColorByLabel: Map<String, Color>? = null,
) {
    if (items.isEmpty()) return
    val sortedItems = remember(items, fixedColorByLabel) {
        if (fixedColorByLabel != null) items else items.sortedByDescending { it.count }
    }
    val colorIndexByLabel = remember(sortedItems) {
        sortedItems.withIndex().associate { (index, item) -> item.label to index }
    }

    Column(verticalArrangement = Arrangement.spacedBy(HifiSpacing.md)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = tag,
                style = MicaTheme.typography.monoSm,
                color = MicaTheme.colors.textTertiary,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = title,
                style = MicaTheme.typography.titleSm,
                color = MicaTheme.colors.textPrimary,
            )
        }
        DistributionWaffleChart(
            items = sortedItems,
            total = total,
            colorIndexByLabel = colorIndexByLabel,
            fixedColorByLabel = fixedColorByLabel,
        )
    }
}

@Composable
private fun DistributionWaffleChart(
    items: List<LabeledCount>,
    total: Int,
    colorIndexByLabel: Map<String, Int>,
    fixedColorByLabel: Map<String, Color>? = null,
) {
    val gridCells = remember(items, total) { computeWaffleGrid(items, total) }
    if (gridCells.isEmpty()) return
    val isDark = MicaTheme.colors.isDark

    Column(verticalArrangement = Arrangement.spacedBy(HifiSpacing.md)) {
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(WaffleGridWidthFraction)
                    .aspectRatio(1f),
            ) {
                WaffleGridLayout(
                    cells = gridCells,
                    colorIndexByLabel = colorIndexByLabel,
                    fixedColorByLabel = fixedColorByLabel,
                    isDark = isDark,
                )
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(HifiSpacing.sm)) {
            items.forEach { item ->
                WaffleLegendRow(
                    item = item,
                    total = total,
                    swatchColor = swatchColorForLabel(
                        label = item.label,
                        colorIndex = colorIndexByLabel[item.label] ?: 0,
                        fixedColorByLabel = fixedColorByLabel,
                        isDark = isDark,
                    ),
                )
            }
        }
    }
}

@Composable
private fun WaffleGridLayout(
    cells: List<LabeledCount>,
    colorIndexByLabel: Map<String, Int>,
    fixedColorByLabel: Map<String, Color>? = null,
    isDark: Boolean,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val columns = WaffleGridColumns
        val gap = WaffleCellGap
        val gapTotal = gap * (columns - 1)
        val cellSize = (maxWidth - gapTotal) / columns

        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(gap),
        ) {
            repeat(columns) { row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(gap),
                ) {
                    repeat(columns) { col ->
                        val index = row * columns + col
                        val item = cells.getOrNull(index)
                        if (item != null) {
                            val colorIndex = colorIndexByLabel[item.label] ?: 0
                            Box(
                                modifier = Modifier
                                    .size(cellSize)
                                    .background(
                                        swatchColorForLabel(
                                            label = item.label,
                                            colorIndex = colorIndex,
                                            fixedColorByLabel = fixedColorByLabel,
                                            isDark = isDark,
                                        ),
                                    ),
                            )
                        } else {
                            Spacer(Modifier.size(cellSize))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WaffleLegendRow(
    item: LabeledCount,
    total: Int,
    swatchColor: Color,
) {
    val percent = if (total > 0) item.count * 100 / total else 0
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(12.dp)
                .background(swatchColor),
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = HifiSpacing.sm),
        ) {
            Text(
                text = item.label,
                style = MicaTheme.typography.bodySm,
                color = MicaTheme.colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = formatCountPercent(item.count, percent),
            style = MicaTheme.typography.monoSm,
            color = MicaTheme.colors.textSecondary,
        )
    }
}

@Composable
private fun waffleColorForIndex(index: Int): Color {
    val palette = if (MicaTheme.colors.isDark) WafflePaletteDark else WafflePaletteLight
    return palette[index % palette.size]
}

private fun swatchColorForLabel(
    label: String,
    colorIndex: Int,
    fixedColorByLabel: Map<String, Color>?,
    isDark: Boolean,
): Color {
    fixedColorByLabel?.get(label)?.let { return it }
    val palette = if (isDark) WafflePaletteDark else WafflePaletteLight
    return palette[colorIndex % palette.size]
}

/** 10×10 华夫格：按占比分配 100 格，余数用最大余额法补足。 */
private fun computeWaffleGrid(items: List<LabeledCount>, total: Int): List<LabeledCount> {
    if (items.isEmpty() || total <= 0) return emptyList()

    val slots = WaffleGridColumns * WaffleGridColumns
    data class Alloc(val item: LabeledCount, val exact: Float, var cells: Int)

    val allocs = items.map { item ->
        val exact = item.count * slots.toFloat() / total
        Alloc(item, exact, exact.toInt())
    }
    var used = allocs.sumOf { it.cells }
    val remainderOrder = allocs.indices.sortedByDescending { idx ->
        allocs[idx].exact - allocs[idx].cells
    }
    var pick = 0
    while (used < slots && remainderOrder.isNotEmpty()) {
        val idx = remainderOrder[pick % remainderOrder.size]
        allocs[idx].cells++
        used++
        pick++
    }

    val result = ArrayList<LabeledCount>(slots)
    allocs
        .sortedByDescending { it.item.count }
        .forEach { alloc ->
            repeat(alloc.cells.coerceAtLeast(0)) {
                result.add(alloc.item)
            }
        }
    return result.take(slots)
}

@Composable
private fun AnalysisHairlineHorizontal() {
    Box(
        Modifier
            .fillMaxWidth()
            .height(HifiSize.dividerHairline)
            .background(MicaTheme.colors.divider),
    )
}

@Composable
private fun AnalysisHairlineVertical() {
    Box(
        Modifier
            .width(HifiSize.dividerHairline)
            .fillMaxHeight()
            .background(MicaTheme.colors.divider),
    )
}

private fun formatSongCount(count: Int): String =
    String.format(Locale.getDefault(), "%,d", count)

private fun formatCountPercent(count: Int, percent: Int): String =
    String.format(Locale.getDefault(), "%,d · %d%%", count, percent)

private fun formatSizeValue(bytes: Long, sizeMb: Int): String {
    if (bytes <= 0L) return "${sizeMb.coerceAtLeast(0)}"
    val gb = bytes / (1024.0 * 1024.0 * 1024.0)
    return if (gb >= 1.0) {
        "%.1f".format(Locale.getDefault(), gb)
    } else {
        "${sizeMb.coerceAtLeast(1)}"
    }
}

private fun formatSizeUnit(bytes: Long, sizeMb: Int): String? =
    if (bytes > 0L && bytes / (1024.0 * 1024.0 * 1024.0) >= 1.0) "GB" else "MB"
