package com.mica.music.ui.screens.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import com.mica.music.ui.motion.MicaMotion
import com.mica.music.ui.theme.HifiSize
import com.mica.music.ui.theme.HifiSpacing
import com.mica.music.data.FolderBrowseMode
import com.mica.music.ui.theme.MicaTheme
import kotlinx.coroutines.delay

internal fun resolveTopBarTitle(
    appName: String,
    section: HomeSection,
    playlistName: String?,
    searchOpen: Boolean,
    browseDestination: BrowseDestination,
    folderBrowseMode: FolderBrowseMode = FolderBrowseMode.HIERARCHY,
): String = when {
    searchOpen -> "搜索"
    browseDestination is BrowseDestination.Album -> "专辑"
    browseDestination is BrowseDestination.Folder -> when {
        browseDestination.scopePathSegments.isNotEmpty() ->
            if (folderBrowseMode == FolderBrowseMode.MUSIC_FOLDERS) {
                browseDestination.scopePathSegments.last()
            } else {
                browseDestination.scopePathSegments.joinToString(" / ")
            }
        browseDestination.depth > 0 -> "第 ${browseDestination.depth + 1} 层文件夹"
        else -> "文件夹"
    }
    section == HomeSection.Playlist && playlistName != null -> playlistName
    else -> when (section) {
        HomeSection.Songs -> appName
        HomeSection.Artists -> "艺术家"
        HomeSection.Albums -> "专辑"
        HomeSection.Folders -> "文件夹"
        HomeSection.Remote -> "远程曲库"
        HomeSection.Recent -> "最近播放"
        HomeSection.LibraryAnalysis -> "音乐库分析"
        HomeSection.Settings -> "设置"
        HomeSection.Playlist -> "歌单"
    }
}

@Composable
internal fun HomeTopBar(
    title: String,
    showBack: Boolean,
    searchOpen: Boolean,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    motionEnabled: Boolean,
    showSearchAction: Boolean = true,
    onLeadingClick: () -> Unit,
    onSearchClick: () -> Unit,
) {
    val searchFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(HifiSize.topBarHeight)
            .padding(horizontal = HifiSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AnimatedContent(
            targetState = showBack,
            transitionSpec = {
                fadeIn(MicaMotion.tweenFloat(motionEnabled, MicaMotion.DurationShortMs)) togetherWith
                    fadeOut(MicaMotion.tweenFloat(motionEnabled, MicaMotion.DurationShortMs))
            },
            label = "topBarLeading",
        ) { back ->
            IconButton(
                onClick = onLeadingClick,
                modifier = Modifier.size(HifiSize.touchTarget),
            ) {
                Icon(
                    imageVector = if (back) {
                        Icons.AutoMirrored.Outlined.ArrowBack
                    } else {
                        Icons.Outlined.Menu
                    },
                    contentDescription = if (back) "返回" else "菜单",
                    tint = MicaTheme.colors.textPrimary,
                    modifier = Modifier.size(HifiSize.iconLg),
                )
            }
        }

        AnimatedContent(
            targetState = searchOpen,
            modifier = Modifier.weight(1f),
            transitionSpec = MicaMotion.topBarSearchTransition(motionEnabled),
            label = "topBarSearch",
        ) { open ->
            if (open) {
                LaunchedEffect(Unit) {
                    if (motionEnabled) delay(MicaMotion.DurationShortMs.toLong())
                    searchFocusRequester.requestFocus()
                    keyboardController?.show()
                }
                TextField(
                    value = searchQuery,
                    onValueChange = onSearchQueryChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(searchFocusRequester),
                    placeholder = {
                        Text(
                            text = "搜索歌曲、艺术家、专辑",
                            style = MicaTheme.typography.bodyMd,
                            color = MicaTheme.colors.textTertiary,
                        )
                    },
                    textStyle = MicaTheme.typography.bodyMd,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions.Default,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        disabledContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor = Color.Transparent,
                    ),
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { onSearchQueryChange("") }) {
                                Icon(
                                    imageVector = Icons.Outlined.Close,
                                    contentDescription = "清除",
                                    tint = MicaTheme.colors.textSecondary,
                                )
                            }
                        }
                    },
                )
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = title,
                        style = MicaTheme.typography.titleMd.copy(
                            fontSize = 22.sp,
                            lineHeight = 30.sp,
                        ),
                        color = MicaTheme.colors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = HifiSpacing.xs),
                        textAlign = TextAlign.Center,
                    )
                    if (showSearchAction) {
                        IconButton(
                            onClick = onSearchClick,
                            modifier = Modifier.size(HifiSize.touchTarget),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Search,
                                contentDescription = "搜索",
                                tint = MicaTheme.colors.textPrimary,
                                modifier = Modifier.size(HifiSize.iconLg),
                            )
                        }
                    } else {
                        Spacer(Modifier.size(HifiSize.touchTarget))
                    }
                }
            }
        }
    }
}
