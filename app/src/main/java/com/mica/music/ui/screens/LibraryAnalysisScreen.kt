package com.mica.music.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mica.music.data.LabeledCount
import com.mica.music.data.LibraryAnalysis
import com.mica.music.data.LibraryAnalyzer
import com.mica.music.data.MusicLibrary
import com.mica.music.ui.theme.HifiSpacing
import com.mica.music.ui.theme.MicaTheme

@Composable
fun LibraryAnalysisContent(
    library: MusicLibrary,
    modifier: Modifier = Modifier,
) {
    val analysis = remember(library.songs) { LibraryAnalyzer.analyze(library.songs) }
    if (library.songs.isEmpty()) {
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "暂无曲库数据，请先扫描",
                style = MicaTheme.typography.bodyMd,
                color = MicaTheme.colors.textTertiary,
            )
        }
        return
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = HifiSpacing.lg, vertical = HifiSpacing.md),
        verticalArrangement = Arrangement.spacedBy(HifiSpacing.lg),
    ) {
        OverviewSection(analysis)
        BreakdownSection(
            title = "容器格式",
            items = analysis.formatBreakdown,
            total = analysis.totalSongs,
        )
        BreakdownSection(
            title = "采样率分布",
            items = analysis.sampleRateBreakdown,
            total = analysis.totalSongs,
        )
        BreakdownSection(
            title = "码率区间",
            items = analysis.bitrateBreakdown,
            total = analysis.totalSongs,
        )
    }
}

@Composable
private fun OverviewSection(analysis: LibraryAnalysis) {
    Column(verticalArrangement = Arrangement.spacedBy(HifiSpacing.sm)) {
        Text(
            text = "概览",
            style = MicaTheme.typography.titleMd,
            color = MicaTheme.colors.textPrimary,
        )
        StatLine("曲目总数", "${analysis.totalSongs} 首")
        StatLine("曲库体积", formatSize(analysis.totalSizeBytes, analysis.totalSizeMb))
        StatLine(
            "Hi‑Res",
            "${analysis.hiResCount} 首（${analysis.hiResPercent}%）",
        )
        StatLine(
            "无损格式",
            "${analysis.losslessCount} 首（${analysis.losslessPercent}%）",
        )
    }
}

@Composable
private fun BreakdownSection(
    title: String,
    items: List<LabeledCount>,
    total: Int,
) {
    if (items.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(HifiSpacing.sm)) {
        Text(
            text = title,
            style = MicaTheme.typography.titleSm,
            color = MicaTheme.colors.textPrimary,
        )
        items.forEach { item ->
            DistributionBar(
                label = item.label,
                count = item.count,
                total = total,
            )
        }
    }
}

@Composable
private fun StatLine(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MicaTheme.typography.bodyMd,
            color = MicaTheme.colors.textSecondary,
        )
        Text(
            text = value,
            style = MicaTheme.typography.bodyMd,
            color = MicaTheme.colors.textPrimary,
        )
    }
}

@Composable
private fun DistributionBar(
    label: String,
    count: Int,
    total: Int,
) {
    val fraction = if (total > 0) count.toFloat() / total else 0f
    val percent = (fraction * 100).toInt()
    Column(verticalArrangement = Arrangement.spacedBy(HifiSpacing.xxs)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = label,
                style = MicaTheme.typography.bodySm,
                color = MicaTheme.colors.textPrimary,
            )
            Text(
                text = "$count（$percent%）",
                style = MicaTheme.typography.monoSm,
                color = MicaTheme.colors.textSecondary,
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .background(MicaTheme.colors.divider.copy(alpha = 0.45f)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(fraction.coerceIn(0f, 1f))
                    .background(MicaTheme.colors.accent),
            )
        }
    }
}

private fun formatSize(bytes: Long, sizeMb: Int): String {
    if (bytes <= 0L) return "${sizeMb.coerceAtLeast(0)} MB"
    val gb = bytes / (1024.0 * 1024.0 * 1024.0)
    return if (gb >= 1.0) {
        "%.2f GB".format(gb)
    } else {
        "${sizeMb.coerceAtLeast(1)} MB"
    }
}
