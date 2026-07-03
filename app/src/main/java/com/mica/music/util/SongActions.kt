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

internal const val LYRICO_PACKAGE_NAME = "com.lonx.lyrico"
internal const val LYRICO_EDIT_TAG_ACTION = "com.lonx.lyrico.action.EDIT_TAG"
private const val LYRICO_EDIT_TAG_MIME = "audio/*"

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
    val lyricoIntent = buildLyricoEditTagIntent(context, song.title, uri)
    val lyricoActivity = lyricoIntent.resolveActivity(context.packageManager)
    DiagnosticLog.event(
        "TagEditor",
        "lyrico request song=${song.id} uri=$uri scheme=${uri.scheme} authority=${uri.authority} " +
            "document=${isDocumentUri(context, uri)} mime=$mime persisted=${persistedPermissionSummary(context, uri)} " +
            "grants=${permissionFlagsLabel(tagEditorUriGrantFlags(context, uri))} resolved=$lyricoActivity",
    )
    if (lyricoActivity != null) {
        return runCatching {
            context.startActivity(lyricoIntent.withActivityLaunchFlags(context))
            DiagnosticLog.event("TagEditor", "lyrico launch ok song=${song.id} resolved=$lyricoActivity")
            true
        }.getOrElse { error ->
            DiagnosticLog.event("TagEditor", "lyrico launch failed song=${song.id} resolved=$lyricoActivity", error)
            false
        }
    }

    val candidates = listOf(
        Intent(Intent.ACTION_EDIT).apply {
            setDataAndType(uri, mime)
            addFlags(tagEditorUriGrantFlags(context, uri))
            clipData = ClipData.newUri(context.contentResolver, song.title, uri)
        },
        Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(tagEditorUriGrantFlags(context, uri))
            clipData = ClipData.newUri(context.contentResolver, song.title, uri)
        },
    )
    for (intent in candidates) {
        val fallbackActivity = intent.resolveActivity(context.packageManager)
        DiagnosticLog.event(
            "TagEditor",
            "fallback action=${intent.action} song=${song.id} uri=$uri mime=$mime resolved=$fallbackActivity",
        )
        if (fallbackActivity != null) {
            return runCatching {
                val chooser = Intent.createChooser(intent, "编辑音乐标签")
                context.startActivity(chooser.withActivityLaunchFlags(context))
                DiagnosticLog.event(
                    "TagEditor",
                    "fallback launch ok action=${intent.action} song=${song.id} resolved=$fallbackActivity",
                )
                true
            }.getOrElse { error ->
                DiagnosticLog.event(
                    "TagEditor",
                    "fallback launch failed action=${intent.action} song=${song.id} resolved=$fallbackActivity",
                    error,
                )
                false
            }
        }
    }
    DiagnosticLog.event("TagEditor", "no editor available song=${song.id} uri=$uri mime=$mime")
    return false
}

internal fun buildLyricoEditTagIntent(
    context: Context,
    title: String,
    uri: Uri,
): Intent =
    Intent(LYRICO_EDIT_TAG_ACTION).apply {
        setPackage(LYRICO_PACKAGE_NAME)
        setDataAndType(uri, LYRICO_EDIT_TAG_MIME)
        addFlags(tagEditorUriGrantFlags(context, uri))
        clipData = ClipData.newUri(context.contentResolver, title, uri)
    }

private fun Intent.withActivityLaunchFlags(context: Context): Intent =
    apply {
        if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
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

private fun isDocumentUri(context: Context, uri: Uri): Boolean =
    runCatching { DocumentsContract.isDocumentUri(context, uri) }.getOrDefault(false)

private fun tagEditorUriGrantFlags(context: Context, uri: Uri): Int =
    when {
        uri.scheme != "content" -> Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        uri.authority == "media" -> Intent.FLAG_GRANT_READ_URI_PERMISSION
        isDocumentUri(context, uri) -> Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        else -> Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    }

private fun persistedPermissionSummary(context: Context, uri: Uri): String {
    val persistedPermissions = context.contentResolver.persistedUriPermissions
    val uriText = uri.toString()
    val matches = persistedPermissions.filter { permission ->
        val persistedText = permission.uri.toString()
        uriText == persistedText || uriText.startsWith("$persistedText/")
    }
    if (matches.isEmpty()) {
        return "none(total=${persistedPermissions.size})"
    }
    return matches.joinToString(separator = ",") { permission ->
        val modes = buildList {
            if (permission.isReadPermission) add("read")
            if (permission.isWritePermission) add("write")
        }.ifEmpty { listOf("none") }.joinToString("+")
        "${permission.uri}[$modes]"
    }
}

private fun permissionFlagsLabel(flags: Int): String = buildList {
    if (flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0) add("read")
    if (flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0) add("write")
}.ifEmpty { listOf("none") }.joinToString("+")
