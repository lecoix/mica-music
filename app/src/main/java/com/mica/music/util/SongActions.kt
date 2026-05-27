package com.mica.music.util

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.net.toUri
import com.mica.music.data.DsdSupport
import com.mica.music.data.Song

fun shareSong(context: Context, song: Song): Boolean {
    val shareText = buildSongShareText(song)
    val uri = song.mediaUri.toUri()
    val mime = resolveShareMimeType(context, song, uri)

    if (uri.scheme == "content" || uri.scheme == "file") {
        val streamIntent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TITLE, song.title)
            putExtra(Intent.EXTRA_TEXT, shareText)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = ClipData.newUri(context.contentResolver, song.title, uri)
        }
        if (launchShareChooser(context, streamIntent)) return true
    }

    val textIntent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, shareText)
        putExtra(Intent.EXTRA_SUBJECT, song.title)
    }
    return launchShareChooser(context, textIntent)
}

private fun buildSongShareText(song: Song): String = buildString {
    append(song.title)
    if (song.artist.isNotBlank()) append(" — ").append(song.artist)
    if (song.album.isNotBlank()) append("\n专辑：").append(song.album)
    append("\n")
    append(song.formatLabel)
    if (song.sampleRateLabel.isNotBlank()) append(" · ").append(song.sampleRateLabel)
    if (song.filePath.isNotBlank()) append("\n").append(song.filePath)
}

private fun resolveShareMimeType(context: Context, song: Song, uri: Uri): String {
    song.metadata.playbackMimeType.takeIf { it.isNotBlank() }?.let { return it }
    runCatching { context.contentResolver.getType(uri) }.getOrNull()
        ?.takeIf { it.isNotBlank() }
        ?.let { return it }
    val path = song.filePath.ifBlank { uri.lastPathSegment.orEmpty() }
    val ext = path.substringAfterLast('.', "")
    if (DsdSupport.isDsdExtension(ext)) {
        return DsdSupport.mimeForExtension(ext)
    }
    return when {
        path.endsWith(".flac", ignoreCase = true) -> "audio/flac"
        path.endsWith(".mp3", ignoreCase = true) -> "audio/mpeg"
        path.endsWith(".wav", ignoreCase = true) -> "audio/wav"
        path.endsWith(".m4a", ignoreCase = true) -> "audio/mp4"
        path.endsWith(".aac", ignoreCase = true) -> "audio/aac"
        path.endsWith(".ogg", ignoreCase = true) -> "audio/ogg"
        else -> "audio/*"
    }
}

private fun launchShareChooser(context: Context, intent: Intent): Boolean =
    runCatching {
        val chooser = Intent.createChooser(intent, "分享歌曲")
        if (context !is Activity) {
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
        true
    }.getOrDefault(false)

fun openSongInTagEditor(context: Context, song: Song): Boolean {
    val uri = song.mediaUri.toUri()
    val mime = resolveShareMimeType(context, song, uri)
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
                val chooser = Intent.createChooser(intent, "编辑音乐标签")
                if (context !is Activity) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(chooser)
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
