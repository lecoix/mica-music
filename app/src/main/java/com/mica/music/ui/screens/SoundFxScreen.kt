package com.mica.music.ui.screens

import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mica.music.audio.fx.SoundFxSettings
import com.mica.music.media.MicaEqualizerManager
import com.mica.music.ui.components.SettingsSectionTitle
import com.mica.music.ui.components.SettingsSliderRow
import com.mica.music.ui.components.TextToggle
import com.mica.music.ui.theme.HifiSize
import com.mica.music.ui.theme.HifiSpacing
import com.mica.music.ui.theme.MicaTheme
import com.mica.music.ui.theme.micaAppBackground

@Composable
fun SoundFxScreen(
    onBack: () -> Unit,
    contentPadding: PaddingValues = PaddingValues(),
    bottomContentClearance: Dp = 0.dp,
) {
    val context = LocalContext.current
    var revision by remember { mutableIntStateOf(0) }
    val settings = remember(revision) { MicaEqualizerManager.soundFxSettings(context) }

    LaunchedEffect(Unit) { revision++ }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .micaAppBackground()
            .padding(contentPadding),
    ) {
        SoundFxTopBar(
            enabled = settings.enabled,
            onBack = onBack,
            onEnabledChange = { enabled ->
                MicaEqualizerManager.applySoundFx(context, settings.copy(enabled = enabled))
                revision++
            },
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                text = if (settings.isDspActive()) {
                    "正在软件处理当前播放 · 硬件 offload 已关闭"
                } else if (settings.enabled) {
                    "已打开，参数仍是中性，出声仍旁路"
                } else {
                    "已关闭。打开后只有非默认参数才会进入软件处理"
                },
                style = MicaTheme.typography.caption,
                color = MicaTheme.colors.textTertiary,
                modifier = Modifier.padding(horizontal = HifiSpacing.lg),
            )

            Spacer(Modifier.height(HifiSpacing.lg))

            SettingsSectionTitle("立体声")
            SettingsSliderRow(
                title = "立体声宽度",
                subtitle = "100% 为原声。低于 100% 收窄，0% 为单声道。",
                value = settings.stereoWidthPercent,
                valueRange = SoundFxSettings.MIN_WIDTH_PERCENT..SoundFxSettings.MAX_WIDTH_PERCENT,
                suffix = "%",
                enabled = settings.enabled,
                onValueChange = { value ->
                    MicaEqualizerManager.applySoundFx(
                        context,
                        settings.copy(stereoWidthPercent = value),
                    )
                    revision++
                },
            )

            SettingsSectionTitle("音色")
            SettingsSliderRow(
                title = "低音",
                subtitle = "100 Hz 低架",
                value = settings.bassDb,
                valueRange = SoundFxSettings.MIN_TONE_DB..SoundFxSettings.MAX_TONE_DB,
                suffix = " dB",
                enabled = settings.enabled,
                onValueChange = { value ->
                    MicaEqualizerManager.applySoundFx(context, settings.copy(bassDb = value))
                    revision++
                },
            )
            SettingsSliderRow(
                title = "高音",
                subtitle = "10 kHz 高架",
                value = settings.trebleDb,
                valueRange = SoundFxSettings.MIN_TONE_DB..SoundFxSettings.MAX_TONE_DB,
                suffix = " dB",
                enabled = settings.enabled,
                onValueChange = { value ->
                    MicaEqualizerManager.applySoundFx(context, settings.copy(trebleDb = value))
                    revision++
                },
            )

            SettingsSectionTitle("混响")
            SettingsSliderRow(
                title = "房间大小",
                subtitle = "越大尾音越长",
                value = settings.reverbRoomPercent,
                valueRange = SoundFxSettings.MIN_REVERB_PERCENT..SoundFxSettings.MAX_REVERB_PERCENT,
                suffix = "%",
                enabled = settings.enabled,
                onValueChange = { value ->
                    MicaEqualizerManager.applySoundFx(
                        context,
                        settings.copy(reverbRoomPercent = value),
                    )
                    revision++
                },
            )
            SettingsSliderRow(
                title = "高频阻尼",
                subtitle = "越大尾音越闷",
                value = settings.reverbDampingPercent,
                valueRange = SoundFxSettings.MIN_REVERB_PERCENT..SoundFxSettings.MAX_REVERB_PERCENT,
                suffix = "%",
                enabled = settings.enabled,
                onValueChange = { value ->
                    MicaEqualizerManager.applySoundFx(
                        context,
                        settings.copy(reverbDampingPercent = value),
                    )
                    revision++
                },
            )
            SettingsSliderRow(
                title = "湿比",
                subtitle = "0 为关闭",
                value = settings.reverbWetPercent,
                valueRange = SoundFxSettings.MIN_REVERB_PERCENT..SoundFxSettings.MAX_REVERB_PERCENT,
                suffix = "%",
                enabled = settings.enabled,
                onValueChange = { value ->
                    MicaEqualizerManager.applySoundFx(
                        context,
                        settings.copy(reverbWetPercent = value),
                    )
                    revision++
                },
            )

            SettingsSectionTitle("360° 环绕")
            SettingsSliderRow(
                title = "强度",
                subtitle = "0 为关闭。耳机上更明显；开环绕时不再叠加立体声宽度。",
                value = settings.surroundIntensityPercent,
                valueRange = SoundFxSettings.MIN_SURROUND_PERCENT..SoundFxSettings.MAX_SURROUND_PERCENT,
                suffix = "%",
                enabled = settings.enabled,
                onValueChange = { value ->
                    MicaEqualizerManager.applySoundFx(
                        context,
                        settings.copy(surroundIntensityPercent = value),
                    )
                    revision++
                },
            )
            SettingsSliderRow(
                title = "转速",
                subtitle = "绕头旋转，0 为定点",
                value = settings.surroundRotationDegPerSec,
                valueRange = SoundFxSettings.MIN_SURROUND_ROTATION..SoundFxSettings.MAX_SURROUND_ROTATION,
                suffix = " °/s",
                enabled = settings.enabled,
                onValueChange = { value ->
                    MicaEqualizerManager.applySoundFx(
                        context,
                        settings.copy(surroundRotationDegPerSec = value),
                    )
                    revision++
                },
            )

            Spacer(Modifier.height(HifiSpacing.md))
            Text(
                text = "重置默认",
                style = MicaTheme.typography.bodyMd,
                color = MicaTheme.colors.accent,
                modifier = Modifier
                    .clickable {
                        MicaEqualizerManager.applySoundFx(
                            context,
                            SoundFxSettings(enabled = settings.enabled),
                        )
                        revision++
                    }
                    .padding(horizontal = HifiSpacing.lg, vertical = HifiSpacing.md),
            )

            Text(
                text = "只作用于 Shared PCM。USB 独占输出保持旁路。默认关闭，不会改变 HiFi 直通。",
                style = MicaTheme.typography.caption,
                color = MicaTheme.colors.textTertiary,
                modifier = Modifier.padding(horizontal = HifiSpacing.lg),
            )

            Spacer(Modifier.height(HifiSpacing.xxl + bottomContentClearance))
        }
    }
}

@Composable
private fun SoundFxTopBar(
    enabled: Boolean,
    onBack: () -> Unit,
    onEnabledChange: (Boolean) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
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
            text = "音效实验室",
            style = MicaTheme.typography.display,
            color = MicaTheme.colors.textPrimary,
            modifier = Modifier.weight(1f),
        )
        TextToggle(checked = enabled, onCheckedChange = onEnabledChange)
    }
}
