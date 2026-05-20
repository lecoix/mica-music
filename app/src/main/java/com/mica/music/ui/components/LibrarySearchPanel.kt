package com.mica.music.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import com.mica.music.data.MusicLibrary
import com.mica.music.data.PlayerController
import com.mica.music.data.Song
import com.mica.music.ui.theme.HifiSpacing
import com.mica.music.ui.theme.MicaTheme

@Composable
fun LibrarySearchPanel(
    query: String,
    onQueryChange: (String) -> Unit,
    library: MusicLibrary,
    playerController: PlayerController,
    onSongClick: (String) -> Unit,
    onSongOpenMenu: ((Song) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val results = library.searchSongs(query)

    Column(modifier = modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = HifiSpacing.lg, vertical = HifiSpacing.sm),
            placeholder = {
                Text(
                    text = "搜索歌曲、艺术家、专辑",
                    style = MicaTheme.typography.bodyMd,
                )
            },
            textStyle = MicaTheme.typography.bodyMd,
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions.Default,
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { onQueryChange("") }) {
                        Icon(
                            imageVector = Icons.Outlined.Close,
                            contentDescription = "清除",
                        )
                    }
                }
            },
        )

        val emptyMessage = if (query.isBlank()) {
            "输入关键词开始搜索"
        } else {
            "未找到「$query」相关歌曲"
        }

        SongListPanel(
            songs = results,
            library = library,
            playerController = playerController,
            onSongClick = onSongClick,
            onSongOpenMenu = onSongOpenMenu,
            emptyMessage = emptyMessage,
            modifier = Modifier.weight(1f),
        )
    }
}
