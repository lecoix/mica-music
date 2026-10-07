package com.mica.music.ui.screens.home

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mica.music.data.remote.smb.SmbFolderLibraryLoader
import com.mica.music.ui.theme.MicaTheme
import kotlinx.coroutines.launch

@Composable
internal fun SmbFolderLoadingStatus(loader: SmbFolderLibraryLoader) {
    val state by loader.state.collectAsState()
    val scope = rememberCoroutineScope()
    if (state.message.isNotEmpty()) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Text(state.message, style = MicaTheme.typography.bodySm,
            color = MicaTheme.colors.textSecondary,
            modifier = Modifier.weight(1f).padding(vertical = 12.dp))
        if (state.active) TextButton(onClick = { scope.launch { loader.cancel() } },
            modifier = Modifier.heightIn(min = 48.dp)) { Text("停止") }
    }
}
