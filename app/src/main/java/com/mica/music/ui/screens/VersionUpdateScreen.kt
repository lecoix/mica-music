package com.mica.music.ui.screens

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mica.music.data.update.AppUpdateCoordinator
import com.mica.music.data.update.AppUpdateRepository
import com.mica.music.data.update.AppUpdateState
import com.mica.music.data.update.AppVersion
import com.mica.music.data.update.currentAppVersion
import com.mica.music.ui.components.SettingsSectionTitle
import com.mica.music.ui.theme.HifiSize
import com.mica.music.ui.theme.HifiSpacing
import com.mica.music.ui.theme.MicaTheme
import com.mica.music.ui.theme.micaAppBackground

@Composable
fun VersionUpdateScreen(
    onBack: () -> Unit,
    contentPadding: PaddingValues = PaddingValues(),
    bottomContentClearance: Dp = 0.dp,
) {
    val context = LocalContext.current
    val currentVersion = remember(context) {
        runCatching { currentAppVersion(context.packageManager, context.packageName) }
            .getOrElse { AppVersion("未知", 0L) }
    }
    val repository = remember { AppUpdateRepository() }
    val coordinator = remember(repository, currentVersion) {
        AppUpdateCoordinator {
            repository.check(currentVersion)
        }
    }
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(coordinator) {
        coordinator.check(this)
    }

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
                text = "版本与更新",
                style = MicaTheme.typography.display,
                color = MicaTheme.colors.textPrimary,
            )
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(bottom = bottomContentClearance),
        ) {
            SettingsSectionTitle("当前版本")
            VersionTextRow(
                title = currentVersion.name,
                subtitle = "versionCode ${currentVersion.code}",
            )

            when (val state = coordinator.state) {
                AppUpdateState.Idle,
                AppUpdateState.Checking -> {
                    VersionTextRow(
                        title = "正在检查更新…",
                        subtitle = "请稍候",
                    )
                }

                is AppUpdateState.Failure -> {
                    VersionTextRow(
                        title = "检查更新失败",
                        subtitle = state.message,
                    )
                }

                is AppUpdateState.Success -> {
                    val manifest = state.result.manifest
                    SettingsSectionTitle("更新日志")
                    VersionTextRow(
                        title = "${manifest.versionName} · versionCode ${manifest.versionCode}",
                        subtitle = manifest.changelog.ifBlank { "暂无更新日志" },
                    )

                    if (state.result.hasUpdate) {
                        SettingsSectionTitle("下载新版本")
                        DownloadLinkRow(
                            title = "123 云盘下载",
                            url = manifest.domesticUrl,
                        )
                        DownloadLinkRow(
                            title = "GitHub 下载",
                            url = manifest.githubUrl,
                        )
                    } else {
                        VersionTextRow(
                            title = "已是最新版本",
                            subtitle = "当前没有可用更新",
                        )
                    }
                }
            }

            Spacer(Modifier.height(HifiSpacing.sm))
            TextButton(
                onClick = { coordinator.check(coroutineScope) },
                enabled = coordinator.state !is AppUpdateState.Checking,
                modifier = Modifier.padding(horizontal = HifiSpacing.lg),
            ) {
                Text(
                    text = "重新检查",
                    color = MicaTheme.colors.accent,
                )
            }
            Spacer(Modifier.height(HifiSpacing.xxl))
        }
    }
}

@Composable
private fun VersionTextRow(
    title: String,
    subtitle: String,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(HifiSpacing.xxs),
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
            style = MicaTheme.typography.bodyMd,
            color = MicaTheme.colors.textSecondary,
        )
    }
}

@Composable
private fun DownloadLinkRow(
    title: String,
    url: String,
) {
    if (url.isBlank()) return
    val uriHandler = LocalUriHandler.current
    TextButton(
        onClick = { uriHandler.openUri(url) },
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = HifiSpacing.lg),
    ) {
        Text(
            text = title,
            color = MicaTheme.colors.accent,
        )
    }
}
