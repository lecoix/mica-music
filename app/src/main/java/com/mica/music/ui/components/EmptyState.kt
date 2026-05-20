package com.mica.music.ui.components



import androidx.compose.foundation.background

import androidx.compose.foundation.border

import androidx.compose.foundation.clickable

import androidx.compose.foundation.layout.Arrangement

import androidx.compose.foundation.layout.Box

import androidx.compose.foundation.layout.Column

import androidx.compose.foundation.layout.fillMaxSize

import androidx.compose.foundation.layout.padding

import androidx.compose.foundation.layout.size

import androidx.compose.material.icons.Icons

import androidx.compose.material.icons.outlined.LibraryMusic

import androidx.compose.material3.CircularProgressIndicator

import androidx.compose.material3.Icon

import androidx.compose.material3.Text

import androidx.compose.runtime.Composable

import androidx.compose.ui.Alignment

import androidx.compose.ui.Modifier

import androidx.compose.ui.graphics.Color

import androidx.compose.ui.graphics.vector.ImageVector

import androidx.compose.ui.text.style.TextAlign

import androidx.compose.ui.unit.dp

import com.mica.music.ui.theme.HifiSize

import com.mica.music.ui.theme.HifiSpacing

import com.mica.music.ui.theme.MicaTheme



/**

 * 通用空态：图标 + 标题 + 副标题 + 可选的主/次 CTA。

 */

@Composable

fun EmptyState(

    icon: ImageVector,

    title: String,

    subtitle: String,

    primaryActionLabel: String? = null,

    onPrimaryAction: (() -> Unit)? = null,

    secondaryActionLabel: String? = null,

    onSecondaryAction: (() -> Unit)? = null,

    isLoading: Boolean = false,

    modifier: Modifier = Modifier,

) {

    Box(

        modifier = modifier

            .fillMaxSize()

            .padding(HifiSpacing.xl),

        contentAlignment = Alignment.Center,

    ) {

        Column(

            horizontalAlignment = Alignment.CenterHorizontally,

            verticalArrangement = Arrangement.spacedBy(HifiSpacing.md),

        ) {

            if (isLoading) {

                CircularProgressIndicator(

                    color = MicaTheme.colors.accent,

                    strokeWidth = 1.5.dp,

                    modifier = Modifier.size(HifiSize.iconXl),

                )

            } else {

                Icon(

                    imageVector = icon,

                    contentDescription = null,

                    tint = MicaTheme.colors.textTertiary,

                    modifier = Modifier.size(HifiSize.iconXxl),

                )

            }



            Text(

                text = title,

                style = MicaTheme.typography.titleMd,

                color = MicaTheme.colors.textPrimary,

                textAlign = TextAlign.Center,

            )

            Text(

                text = subtitle,

                style = MicaTheme.typography.bodyMd,

                color = MicaTheme.colors.textSecondary,

                textAlign = TextAlign.Center,

            )



            if (primaryActionLabel != null && onPrimaryAction != null) {

                CtaButton(label = primaryActionLabel, filled = true, onClick = onPrimaryAction)

            }

            if (secondaryActionLabel != null && onSecondaryAction != null) {

                CtaButton(label = secondaryActionLabel, filled = false, onClick = onSecondaryAction)

            }

        }

    }

}



@Composable

private fun CtaButton(label: String, filled: Boolean, onClick: () -> Unit) {

    val accent = MicaTheme.colors.accent

    Box(

        modifier = Modifier

            .padding(top = if (filled) HifiSpacing.md else HifiSpacing.xs)

            .clickable(onClick = onClick)

            .then(

                if (filled) {

                    Modifier.background(accent)

                } else {

                    Modifier.border(width = 1.dp, color = accent)

                },

            )

            .padding(horizontal = HifiSpacing.xl, vertical = HifiSpacing.sm),

    ) {

        Text(

            text = label,

            style = MicaTheme.typography.bodyLg,

            color = if (filled) Color.White else accent,

        )

    }

}



/** 几种预设场景，避免在 HomeScreen 里写一大堆字串 */

object EmptyStatePresets {



    /** 首次进入：选文件夹（无需整机权限）或全盘扫描 */

    @Composable

    fun InitialLibrarySetup(

        onPickFolderClick: () -> Unit,

        onScanAllClick: () -> Unit,

    ) {

        EmptyState(

            icon = Icons.Outlined.LibraryMusic,

            title = "还没有导入音乐",

            subtitle = "选择曲库文件夹，只扫描该目录；\n或扫描本机全部音频（需读取权限）。",

            primaryActionLabel = "选择曲库文件夹",

            onPrimaryAction = onPickFolderClick,

            secondaryActionLabel = "扫描全部音乐",

            onSecondaryAction = onScanAllClick,

        )

    }



    @Composable

    fun PermissionDeniedOpenSettings(onOpenSettings: () -> Unit) {

        EmptyState(

            icon = Icons.Outlined.LibraryMusic,

            title = "需要读取音频权限",

            subtitle = "你之前拒绝了访问权限。\n请在系统设置中允许「音乐与音频」或「存储」权限。",

            primaryActionLabel = "打开设置",

            onPrimaryAction = onOpenSettings,

        )

    }



    @Composable

    fun ReadyToScan(

        folderLabel: String?,

        onScanClick: () -> Unit,

    ) {

        val subtitle = if (!folderLabel.isNullOrBlank()) {

            "将扫描「$folderLabel」中的音频文件。\n不会上传任何内容。"

        } else {

            "将扫描本机音频并生成列表。\n不会上传任何内容。"

        }

        EmptyState(

            icon = Icons.Outlined.LibraryMusic,

            title = "还没有导入本地音乐",

            subtitle = subtitle,

            primaryActionLabel = "开始扫描",

            onPrimaryAction = onScanClick,

        )

    }



    @Composable

    fun Scanning(progressLabel: String? = null) {

        EmptyState(

            icon = Icons.Outlined.LibraryMusic,

            title = "正在扫描…",

            subtitle = progressLabel ?: "正在读取音质、封面与歌曲列表",

            isLoading = true,

        )

    }



    @Composable

    fun NoMusicFound(

        folderLabel: String?,

        onRescanClick: () -> Unit,

        onPickFolderClick: (() -> Unit)? = null,

    ) {

        val subtitle = if (!folderLabel.isNullOrBlank()) {

            "在「$folderLabel」中未找到符合条件的音频，\n可换目录或改用扫描全部音乐。"

        } else {

            "在本机存储中未找到符合条件的音频，\n可添加音乐后重新扫描。"

        }

        EmptyState(

            icon = Icons.Outlined.LibraryMusic,

            title = "没找到音乐文件",

            subtitle = subtitle,

            primaryActionLabel = "重新扫描",

            onPrimaryAction = onRescanClick,

            secondaryActionLabel = onPickFolderClick?.let { "更换曲库文件夹" },

            onSecondaryAction = onPickFolderClick,

        )

    }

}

