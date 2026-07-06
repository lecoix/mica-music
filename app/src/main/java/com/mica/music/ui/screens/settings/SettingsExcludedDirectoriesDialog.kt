package com.mica.music.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import com.mica.music.data.scanner.ExcludedScanDirectories
import com.mica.music.ui.theme.HifiSpacing
import com.mica.music.ui.theme.MicaTheme

@Composable
internal fun ExcludedDirectoriesDialog(
    excludedDirectories: List<String>,
    candidateDirectories: List<String>,
    isScanning: Boolean,
    onConfirm: (List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    var pendingDirectories by remember(excludedDirectories) {
        mutableStateOf(ExcludedScanDirectories.normalizeAll(excludedDirectories))
    }
    val listMaxHeight = (LocalConfiguration.current.screenHeightDp.dp * 0.55f)
        .coerceIn(180.dp, 360.dp)
    val availableDirectories = candidateDirectories.filterNot {
        ExcludedScanDirectories.isExcluded(it, pendingDirectories)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RectangleShape,
        title = {
            Text(
                text = "排除目录",
                style = MicaTheme.typography.titleMd,
                color = MicaTheme.colors.textPrimary,
            )
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(HifiSpacing.md),
                modifier = Modifier
                    .heightIn(max = listMaxHeight)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = "已排除",
                    style = MicaTheme.typography.titleSm,
                    color = MicaTheme.colors.textPrimary,
                )
                if (pendingDirectories.isEmpty()) {
                    Text(
                        text = "暂无",
                        style = MicaTheme.typography.bodySm,
                        color = MicaTheme.colors.textSecondary,
                    )
                } else {
                    pendingDirectories.forEach { directory ->
                        DirectoryActionRow(
                            path = directory,
                            action = "移除",
                            destructive = true,
                            enabled = !isScanning,
                            onClick = {
                                pendingDirectories = pendingDirectories - directory
                            },
                        )
                    }
                }

                Text(
                    text = "可添加",
                    style = MicaTheme.typography.titleSm,
                    color = MicaTheme.colors.textPrimary,
                )
                if (availableDirectories.isEmpty()) {
                    Text(
                        text = "没有可添加的已扫描目录",
                        style = MicaTheme.typography.bodySm,
                        color = MicaTheme.colors.textSecondary,
                    )
                } else {
                    availableDirectories.forEach { directory ->
                        DirectoryActionRow(
                            path = directory,
                            action = "添加",
                            enabled = !isScanning,
                            onClick = {
                                pendingDirectories = ExcludedScanDirectories.normalizeAll(
                                    pendingDirectories + directory,
                                )
                            },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !isScanning,
                onClick = { onConfirm(pendingDirectories) },
            ) {
                Text("完成")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        },
    )
}

@Composable
internal fun DirectoryActionRow(
    path: String,
    action: String,
    destructive: Boolean = false,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(HifiSpacing.sm),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = path,
            style = MicaTheme.typography.bodySm,
            color = MicaTheme.colors.textPrimary,
            modifier = Modifier.weight(1f),
        )
        TextButton(
            enabled = enabled,
            onClick = onClick,
        ) {
            Text(
                text = action,
                color = when {
                    destructive && enabled -> Color(0xFFE5484D)
                    destructive -> MicaTheme.colors.textSecondary
                    else -> Color.Unspecified
                },
            )
        }
    }
}
