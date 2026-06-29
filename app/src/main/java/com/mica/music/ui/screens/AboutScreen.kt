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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mica.music.data.Song
import com.mica.music.data.scanner.AlbumArtCache
import com.mica.music.ui.components.SettingsSectionTitle
import com.mica.music.ui.theme.HifiSize
import com.mica.music.ui.theme.HifiSpacing
import com.mica.music.ui.theme.MicaTheme
import com.mica.music.ui.theme.micaAppBackground
import com.mica.music.util.DiagnosticLog

@Composable
fun AboutScreen(
    songs: List<Song>,
    onBack: () -> Unit,
    contentPadding: PaddingValues = PaddingValues(),
    bottomContentClearance: Dp = 0.dp,
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
            val context = LocalContext.current
            val versionName = remember(context) {
                runCatching {
                    context.packageManager.getPackageInfo(context.packageName, 0).versionName
                }.getOrNull() ?: "0.1.8-Exo-only"
            }

            AboutHero()

            SettingsSectionTitle("版本")
            AboutInfoRow(title = "Mica Music", subtitle = versionName)
            AboutInfoRow(title = "平台", subtitle = "Android · arm64-v8a")
            AboutInfoRow(title = "播放链路", subtitle = "Media3 ExoPlayer · libffmpegJNI · AudioTrack")

            Spacer(Modifier.height(HifiSpacing.lg))

            SettingsSectionTitle("开源许可证")
            AboutParagraph(
                "本应用使用以下主要开源组件",
            )
            LicenseRow("AndroidX Core / Activity / Lifecycle / Navigation / Room / DocumentFile / Palette / Annotation", "Apache License 2.0")
            LicenseRow("Jetpack Compose / Material 3 / Material Icons", "Apache License 2.0")
            LicenseRow("AndroidX Media3", "Apache License 2.0")
            LicenseRow("Media3 FFmpeg 扩展（Java / JNI 层）", "Apache License 2.0")
            LicenseRow("FFmpeg（libffmpegJNI.so 内静态链接）", "LGPL 2.1+；当前仓库构建脚本未启用 GPL 或 nonfree 组件")
            LicenseRow("Kotlin / Kotlinx Coroutines", "Apache License 2.0")
            LicenseRow("Coil", "Apache License 2.0")
            LicenseRow("Guava", "Apache License 2.0")
            LicenseRow("Calvin Reorderable", "Apache License 2.0")
            LicenseRow("BlurView（Dimezis）", "Apache License 2.0")
            LicenseRow("Kyant Taglib（元数据 JNI）", "Apache License 2.0")
            LicenseRow("jAudiotagger", "LGPL 2.1")
            LicenseRow("Three.js（粒子封面 WebView 资产）", "MIT License")
            AboutParagraph(
                "完整开源声明维护在仓库 docs/OPEN_SOURCE_NOTICES.md" ,
            )

            Spacer(Modifier.height(HifiSpacing.lg))

            SettingsSectionTitle("诊断")
            AboutLinkRow(
                title = "导出诊断日志",
                url = "包含闪退、切歌阶段、掉帧和封面绘制耗时",
                onClick = {
                    val health = AlbumArtCache.health(context, songs)
                    DiagnosticLog.event("AlbumArtCache", "about-export ${health.toLogMessage()}")
                    DiagnosticLog.shareReport(
                        context = context,
                        extraReportSection = buildString {
                            appendLine("Album art cache health:")
                            appendLine(health.toLogMessage())
                        },
                    )
                },
            )

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

            Spacer(Modifier.height(HifiSpacing.xxl + bottomContentClearance))
        }
    }
}

@Composable
private fun AboutHero() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = HifiSpacing.lg, vertical = HifiSpacing.md),
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
