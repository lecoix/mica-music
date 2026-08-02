package com.mica.music.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mica.music.ui.components.SettingsSectionTitle
import com.mica.music.ui.theme.HifiSize
import com.mica.music.ui.theme.HifiSpacing
import com.mica.music.ui.theme.MicaTheme
import com.mica.music.ui.theme.micaAppBackground
import com.mica.music.util.SpatialAudioMonitor

@Composable
fun SpatialAudioScreen(
    onBack: () -> Unit,
    contentPadding: PaddingValues = PaddingValues(),
    bottomContentClearance: Dp = 0.dp,
) {
    val state by SpatialAudioMonitor.state.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .micaAppBackground()
            .padding(contentPadding),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .height(HifiSize.topBarHeight)
                .padding(horizontal = HifiSpacing.sm),
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(HifiSize.touchTarget)) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = "返回",
                    tint = MicaTheme.colors.textPrimary,
                )
            }
            Text(
                text = "空间音频",
                style = MicaTheme.typography.display,
                color = MicaTheme.colors.textPrimary,
            )
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            SettingsSectionTitle("系统状态")
            SpatialAudioInfoRow("平台 API", if (state.apiSupported) "已支持（Android 12L / API 32+）" else "不支持（需要 Android 12L / API 32+）")
            SpatialAudioInfoRow("设备支持", yesNo(state.supported))
            SpatialAudioInfoRow("当前输出可用", yesNo(state.available))
            SpatialAudioInfoRow("系统已启用", yesNo(state.enabled))
            SpatialAudioInfoRow(
                "5.1 PCM 48 kHz 可空间化",
                state.canBeSpatialized?.let { if (it) "是" else "否" } ?: "未知",
            )
            SpatialAudioInfoRow("头部跟踪", yesNo(state.headTrackerAvailable))

            SettingsSectionTitle("说明")
            Text(
                text = "这里只读取 Android 系统 Spatializer 状态，不会强制开启空间音频。5.1 PCM 探测结果不代表普通立体声曲目一定会被空间化。",
                style = MicaTheme.typography.bodyMd,
                color = MicaTheme.colors.textSecondary,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = HifiSpacing.lg, vertical = HifiSpacing.sm),
            )

            Spacer(Modifier.height(HifiSpacing.xxl + bottomContentClearance))
        }
    }
}

@Composable
private fun SpatialAudioInfoRow(
    title: String,
    value: String,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = HifiSpacing.lg, vertical = HifiSpacing.sm),
    ) {
        Text(
            text = title,
            style = MicaTheme.typography.bodyLg,
            color = MicaTheme.colors.textPrimary,
        )
        Text(
            text = value,
            style = MicaTheme.typography.caption,
            color = MicaTheme.colors.textTertiary,
            modifier = Modifier.padding(top = HifiSpacing.xxs),
        )
    }
}

private fun yesNo(value: Boolean): String = if (value) "可用" else "不可用"
