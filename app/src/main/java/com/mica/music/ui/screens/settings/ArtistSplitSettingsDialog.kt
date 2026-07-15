package com.mica.music.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import com.mica.music.data.ArtistSeparator
import com.mica.music.data.ArtistSplitConfig
import com.mica.music.ui.theme.HifiSpacing
import com.mica.music.ui.theme.MicaTheme
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ArtistSplitSettingsDialog(
    config: ArtistSplitConfig,
    onConfirm: (ArtistSplitConfig) -> Unit,
    onDismiss: () -> Unit,
) {
    var enabled by remember(config) { mutableStateOf(config.enabledSeparators) }
    var whitelistText by remember(config) { mutableStateOf(config.whitelist.joinToString("\n")) }
    val maxHeight = (LocalConfiguration.current.screenHeightDp.dp * 0.62f)
        .coerceIn(260.dp, 520.dp)

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RectangleShape,
        title = {
            Text(
                text = "艺术家分割",
                style = MicaTheme.typography.titleMd,
                color = MicaTheme.colors.textPrimary,
            )
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(HifiSpacing.md),
                modifier = Modifier
                    .heightIn(max = maxHeight)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = "分隔符",
                    style = MicaTheme.typography.titleSm,
                    color = MicaTheme.colors.textPrimary,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(HifiSpacing.sm),
                    verticalArrangement = Arrangement.spacedBy(HifiSpacing.xs),
                ) {
                    ArtistSeparator.entries.forEach { separator ->
                        FilterChip(
                            selected = separator in enabled,
                            onClick = {
                                enabled = if (separator in enabled) {
                                    enabled - separator
                                } else {
                                    enabled + separator
                                }
                            },
                            label = { Text(separator.settingsLabel) },
                        )
                    }
                }
                Text(
                    text = "白名单（每行一个完整艺术家字段，忽略大小写）",
                    style = MicaTheme.typography.titleSm,
                    color = MicaTheme.colors.textPrimary,
                )
                OutlinedTextField(
                    value = whitelistText,
                    onValueChange = { whitelistText = it },
                    placeholder = { Text("例如：AC/DC") },
                    minLines = 3,
                    maxLines = 8,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = "设置立即作用于分组、搜索、排序和显示；不会修改音频文件。",
                    style = MicaTheme.typography.caption,
                    color = MicaTheme.colors.textTertiary,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(
                        ArtistSplitConfig(
                            enabledSeparators = enabled,
                            whitelist = whitelistText.lineSequence()
                                .map(String::trim)
                                .filter(String::isNotEmpty)
                                .distinctBy { it.lowercase(Locale.ROOT) }
                                .toList(),
                        ),
                    )
                },
            ) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        },
    )
}
