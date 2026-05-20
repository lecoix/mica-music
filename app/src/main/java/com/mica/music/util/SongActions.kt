package com.mica.music.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.core.net.toUri
import com.mica.music.data.Song

fun shareSong(context: Context, song: Song): Boolean {
    val uri = song.mediaUri.toUri()
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = song.metadata.playbackMimeType.ifBlank { "audio/*" }
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_TITLE, song.title)
        putExtra(Intent.EXTRA_TEXT, "${song.title} — ${song.artist}")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    return runCatching {
        context.startActivity(Intent.createChooser(intent, "分享歌曲").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    }.getOrDefault(false)
}

fun openSongInTagEditor(context: Context, song: Song): Boolean {
    val uri = song.mediaUri.toUri()
    val mime = song.metadata.playbackMimeType.ifBlank { "audio/*" }
    val candidates = listOf(
        Intent(Intent.ACTION_EDIT).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        },
        Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        },
    )
    for (intent in candidates) {
        if (intent.resolveActivity(context.packageManager) != null) {
            return runCatching {
                context.startActivity(Intent.createChooser(intent, "编辑音乐标签").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                true
            }.getOrDefault(false)
        }
    }
    return false
}

/** 尝试从设备删除音频文件；SAF 与 MediaStore 分别处理。 */
fun deleteSongFile(context: Context, song: Song): Boolean {
    val uri = song.mediaUri.toUri()
    return when (uri.scheme) {
        "content" -> {
            if (DocumentsContract.isDocumentUri(context, uri)) {
                runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }.getOrDefault(false)
            } else {
                runCatching { context.contentResolver.delete(uri, null, null) > 0 }.getOrDefault(false)
            }
        }
        else -> false
    }
}
