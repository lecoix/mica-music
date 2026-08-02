package com.mica.music.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.mica.music.ui.theme.HifiPalette
import com.mica.music.ui.theme.HifiSize
import com.mica.music.ui.theme.MicaTheme

internal enum class MicaSnackbarKind {
    ScanComplete,
    SleepTimer,
    Error,
    Info,
}

internal fun micaSnackbarKind(message: String): MicaSnackbarKind = when {
    message.contains("扫描完成") -> MicaSnackbarKind.ScanComplete
    message.contains("睡眠定时") || message.contains("停止播放") -> MicaSnackbarKind.SleepTimer
    listOf("失败", "无法", "错误", "不支持", "未找到", "找不到").any(message::contains) ->
        MicaSnackbarKind.Error
    else -> MicaSnackbarKind.Info
}

@Composable
fun MicaSnackbarHost(
    hostState: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    SnackbarHost(
        hostState = hostState,
        modifier = modifier,
    ) { data ->
        MicaSnackbar(data)
    }
}

@Composable
private fun MicaSnackbar(data: SnackbarData) {
    val kind = micaSnackbarKind(data.visuals.message)
    val colors = MicaTheme.colors
    val accent = when (kind) {
        MicaSnackbarKind.ScanComplete -> HifiPalette.SuccessGreen
        MicaSnackbarKind.SleepTimer -> colors.accent
        MicaSnackbarKind.Error -> HifiPalette.LikeRed
        MicaSnackbarKind.Info -> colors.accent
    }
    val (title, detail) = micaSnackbarCopy(data.visuals.message, kind)
    val icon = when (kind) {
        MicaSnackbarKind.ScanComplete -> Icons.Outlined.LibraryMusic
        MicaSnackbarKind.SleepTimer -> Icons.Outlined.Bedtime
        MicaSnackbarKind.Error -> Icons.Outlined.ErrorOutline
        MicaSnackbarKind.Info -> Icons.Outlined.Info
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .shadow(elevation = 12.dp, shape = RectangleShape)
            .semantics { liveRegion = LiveRegionMode.Polite },
        shape = RectangleShape,
        color = colors.surfaceCard.copy(alpha = if (colors.isDark) 0.96f else 0.94f),
        contentColor = colors.textPrimary,
        tonalElevation = 4.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, top = 10.dp, end = 4.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(accent.copy(alpha = 0.14f), RectangleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(HifiSize.iconMd),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                Text(
                    text = title,
                    style = MicaTheme.typography.titleSm,
                    color = colors.textPrimary,
                    maxLines = 1,
                )
                if (detail.isNotBlank()) {
                    Text(
                        text = detail,
                        style = MicaTheme.typography.bodySm,
                        color = colors.textSecondary,
                        maxLines = 2,
                    )
                }
            }
            IconButton(
                onClick = data::dismiss,
                modifier = Modifier.size(40.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Close,
                    contentDescription = "关闭通知",
                    tint = colors.textTertiary,
                    modifier = Modifier.size(HifiSize.iconSm),
                )
            }
        }
    }
}

internal fun micaSnackbarCopy(
    message: String,
    kind: MicaSnackbarKind = micaSnackbarKind(message),
): Pair<String, String> = when (kind) {
    MicaSnackbarKind.ScanComplete -> {
        "曲库扫描完成" to message
            .removePrefix("扫描完成")
            .trimStart('，', ',', ' ')
    }
    MicaSnackbarKind.SleepTimer -> when {
        message.contains("已结束") -> "睡眠定时已结束" to message
            .substringAfter("，", message)
            .trim()
        message.contains("已关闭") -> "睡眠定时已关闭" to ""
        else -> "睡眠定时已生效" to message
    }
    MicaSnackbarKind.Error -> "操作未完成" to message
    MicaSnackbarKind.Info -> "已完成" to message
}
