package com.mica.music.ui.screens.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.mica.music.data.LibraryFolderAddOutcome
import com.mica.music.data.LibraryFolderEntry
import com.mica.music.data.LibraryFolderSet
import com.mica.music.data.LibraryFolderStore
import com.mica.music.ui.theme.HifiSpacing
import com.mica.music.ui.theme.MicaTheme

@Composable
internal fun LibraryFoldersDialog(
    folders: List<LibraryFolderEntry>,
    isScanning: Boolean,
    onConfirm: (List<LibraryFolderEntry>) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val initialUris = remember(folders) { folders.mapTo(linkedSetOf()) { it.treeUri } }
    var pendingFolders by remember(folders) {
        mutableStateOf(LibraryFolderSet.normalize(folders))
    }
    var newlyGrantedUris by remember(folders) { mutableStateOf(emptySet<String>()) }
    var statusMessage by remember(folders) { mutableStateOf<String?>(null) }

    val addFolderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        LibraryFolderStore.persistTreeAccess(context, uri)
        val label = LibraryFolderStore.displayName(context, uri)
        val uriString = uri.toString()
        val result = LibraryFolderSet.add(
            existing = pendingFolders,
            candidate = LibraryFolderEntry(treeUri = uriString, label = label),
        )
        pendingFolders = result.folders
        if (uriString !in initialUris && result.folders.any { it.treeUri == uriString }) {
            newlyGrantedUris = newlyGrantedUris + uriString
        }
        statusMessage = when (result.outcome) {
            LibraryFolderAddOutcome.ADDED -> "已添加「$label」"
            LibraryFolderAddOutcome.ALREADY_PRESENT -> "「$label」已在列表中"
            LibraryFolderAddOutcome.COVERED_BY_EXISTING -> {
                if (uriString !in initialUris) {
                    LibraryFolderStore.releaseTreeAccess(context, uri)
                }
                val parent = result.coveringParentLabel ?: "已有目录"
                "「$label」已包含在「$parent」中，无需重复添加"
            }
            LibraryFolderAddOutcome.REPLACED_SUBFOLDERS ->
                "已添加「$label」，并自动合并其子目录"
        }
    }

    fun releaseUnconfirmedGrants() {
        newlyGrantedUris
            .filterNot { it in initialUris }
            .forEach { uri -> LibraryFolderStore.releaseTreeAccess(context, uri.toUri()) }
    }

    val listMaxHeight = (LocalConfiguration.current.screenHeightDp.dp * 0.55f)
        .coerceIn(180.dp, 360.dp)
    val hasChanged = pendingFolders != LibraryFolderSet.normalize(folders)

    AlertDialog(
        onDismissRequest = {
            releaseUnconfirmedGrants()
            onDismiss()
        },
        shape = RectangleShape,
        title = {
            Text(
                text = "曲库文件夹",
                style = MicaTheme.typography.titleMd,
                color = MicaTheme.colors.textPrimary,
            )
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(HifiSpacing.md),
                modifier = Modifier
                    .heightIn(max = listMaxHeight)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = "支持同时授权多个目录，直接读取目录内音频与歌词，不受系统媒体库漏扫影响。",
                    style = MicaTheme.typography.bodySm,
                    color = MicaTheme.colors.textSecondary,
                )

                Text(
                    text = "已授权目录（${pendingFolders.size}）",
                    style = MicaTheme.typography.titleSm,
                    color = MicaTheme.colors.textPrimary,
                )
                if (pendingFolders.isEmpty()) {
                    Text(
                        text = "暂无目录 · 确认后将清空文件夹授权",
                        style = MicaTheme.typography.bodySm,
                        color = MicaTheme.colors.textSecondary,
                    )
                } else {
                    pendingFolders.forEach { folder ->
                        DirectoryActionRow(
                            path = folder.label,
                            action = "移除",
                            destructive = true,
                            enabled = !isScanning,
                            onClick = {
                                pendingFolders = LibraryFolderSet.remove(pendingFolders, folder.treeUri)
                                statusMessage = null
                            },
                        )
                    }
                }

                statusMessage?.let { message ->
                    Text(
                        text = message,
                        style = MicaTheme.typography.bodySm,
                        color = MicaTheme.colors.accent,
                    )
                }

                TextButton(
                    enabled = !isScanning,
                    onClick = { addFolderLauncher.launch(null) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("+ 添加文件夹")
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !isScanning && (hasChanged || pendingFolders.isNotEmpty()),
                onClick = {
                    val keptUris = pendingFolders.mapTo(linkedSetOf()) { it.treeUri }
                    newlyGrantedUris
                        .filterNot { it in keptUris || it in initialUris }
                        .forEach { uri -> LibraryFolderStore.releaseTreeAccess(context, uri.toUri()) }
                    onConfirm(pendingFolders)
                },
            ) {
                Text(
                    when {
                        pendingFolders.isEmpty() -> "清空目录"
                        hasChanged -> "保存并扫描"
                        else -> "重新扫描"
                    },
                )
            }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    releaseUnconfirmedGrants()
                    onDismiss()
                },
            ) {
                Text("取消")
            }
        },
    )
}
