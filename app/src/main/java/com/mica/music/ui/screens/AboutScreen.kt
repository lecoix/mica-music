package com.mica.music.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import com.mica.music.ui.components.SettingsSectionTitle
import com.mica.music.ui.theme.HifiSize
import com.mica.music.ui.theme.HifiSpacing
import com.mica.music.ui.theme.MicaTheme
import com.mica.music.ui.theme.micaAppBackground

@Composable
fun AboutScreen(
    onBack: () -> Unit,
    contentPadding: PaddingValues = PaddingValues(),
) {
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
                text = "关于",
                style = MicaTheme.typography.display,
                color = MicaTheme.colors.textPrimary,
            )
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            AboutHero()

            SettingsSectionTitle("版本")
            AboutInfoRow(title = "Mica Music", subtitle = "0.1.0 beta")
            AboutInfoRow(title = "平台", subtitle = "Android · arm64-v8a")
            AboutInfoRow(title = "播放链路", subtitle = "Media3 / FFmpeg / AudioTrack")

            Spacer(Modifier.height(HifiSpacing.lg))

            SettingsSectionTitle("开源许可证")
            AboutParagraph(
                "本应用使用 FFmpeg 进行本地音频解码。FFmpeg 是 FFmpeg Project 的开源软件，按 LGPL 2.1 或更高版本授权发布。\n\n" +
                    "本应用随 APK 分发的 FFmpeg 二进制由项目内构建脚本生成，当前构建未启用 GPL 或 nonfree 组件。FFmpeg 源码可从 https://ffmpeg.org 获取；" +
                    "本应用使用的构建脚本位于项目仓库的 ffmpeg/docker/build.sh 与 scripts/build-ffmpeg-arm64.ps1。",
            )
            LicenseRow("AndroidX / Jetpack Compose / Material3", "Apache License 2.0")
            LicenseRow("Kotlin / Kotlinx Coroutines", "Apache License 2.0")
            LicenseRow("Media3", "Apache License 2.0")
            LicenseRow("Room / Navigation / Lifecycle / Activity", "Apache License 2.0")
            LicenseRow("Coil", "Apache License 2.0")
            LicenseRow("Calvin Reorderable", "Apache License 2.0")
            LicenseRow("FFmpeg", "LGPL 2.1+；当前构建脚本未启用 GPL / nonfree 组件")

            Spacer(Modifier.height(HifiSpacing.lg))

            SettingsSectionTitle("项目")
            val uriHandler = LocalUriHandler.current
            AboutLinkRow(
                title = "GitHub 仓库",
                url = "https://github.com/lecoix/mica-music",
                onClick = { uriHandler.openUri("https://github.com/lecoix/mica-music") },
            )
            AboutParagraph(
                "本播放器完全由AI制作、构建，" +
                    "不保证完全能用。",
            )

            Spacer(Modifier.height(HifiSpacing.xxl))
        }
    }
}

@Composable
private fun AboutHero() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = HifiSpacing.lg, vertical = HifiSpacing.sm)
            .background(MicaTheme.colors.surfaceGlass)
            .padding(HifiSpacing.lg),
    ) {
        Text(
            text = "Mica",
            style = MicaTheme.typography.titleLg,
            color = MicaTheme.colors.textPrimary,
        )
        Text(
            text = "本地音乐播放器",
            style = MicaTheme.typography.bodyMd,
            color = MicaTheme.colors.textSecondary,
            modifier = Modifier.padding(top = HifiSpacing.xxs),
        )
        Text(
            text = "一款以直角与极简为设计语言的播放器",
            style = MicaTheme.typography.caption,
            color = MicaTheme.colors.textTertiary,
            modifier = Modifier.padding(top = HifiSpacing.sm),
        )
    }
}

@Composable
private fun AboutInfoRow(
    title: String,
    subtitle: String,
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
            text = subtitle,
            style = MicaTheme.typography.caption,
            color = MicaTheme.colors.textTertiary,
            modifier = Modifier.padding(top = HifiSpacing.xxs),
        )
    }
}

@Composable
private fun LicenseRow(
    name: String,
    license: String,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = HifiSpacing.lg, vertical = HifiSpacing.sm),
    ) {
        Text(
            text = name,
            style = MicaTheme.typography.bodyMd,
            color = MicaTheme.colors.textPrimary,
        )
        Text(
            text = license,
            style = MicaTheme.typography.monoSm,
            color = MicaTheme.colors.textSecondary,
            modifier = Modifier.padding(top = HifiSpacing.xxs),
        )
        HorizontalDivider(
            color = MicaTheme.colors.divider,
            modifier = Modifier.padding(top = HifiSpacing.sm),
        )
    }
}

@Composable
private fun AboutLinkRow(
    title: String,
    url: String,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = HifiSpacing.lg, vertical = HifiSpacing.sm),
    ) {
        Text(
            text = title,
            style = MicaTheme.typography.bodyLg,
            color = MicaTheme.colors.accent,
        )
        Text(
            text = url,
            style = MicaTheme.typography.caption,
            color = MicaTheme.colors.textTertiary,
            modifier = Modifier.padding(top = HifiSpacing.xxs),
        )
    }
}

@Composable
private fun AboutParagraph(text: String) {
    Text(
        text = text,
        style = MicaTheme.typography.bodyMd,
        color = MicaTheme.colors.textSecondary,
        modifier = Modifier.padding(horizontal = HifiSpacing.lg, vertical = HifiSpacing.sm),
    )
}
