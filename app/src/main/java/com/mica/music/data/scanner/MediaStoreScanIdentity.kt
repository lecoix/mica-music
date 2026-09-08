package com.mica.music.data.scanner

import com.mica.music.data.Song
import java.util.Locale

internal fun mediaStoreLyricsFolderPath(
    relativePath: String,
    absoluteOrReadablePath: String,
): String = when {
    relativePath.isNotBlank() -> relativePath
    '/' in absoluteOrReadablePath -> absoluteOrReadablePath.substringBeforeLast('/', "")
    else -> ""
}

internal fun mediaStoreLyricsKey(
    folderPath: String,
    baseName: String,
): String =
    "${folderPath.trim('/').lowercase(Locale.ROOT)}\u0001${baseName.trim().lowercase(Locale.ROOT)}"

internal fun mediaStoreDuplicateKey(
    mediaUri: String,
    filePath: String,
    sizeBytes: Long,
): String =
    "${filePath.ifBlank { mediaUri }.lowercase(Locale.ROOT)}\u0001${sizeBytes.coerceAtLeast(0L)}"

internal fun mediaStoreReadableFilePath(
    relativePath: String,
    displayName: String,
): String {
    val folder = relativePath.trim().trim('/').replace('\\', '/')
    val name = displayName.trim().trimStart('/')
    return when {
        folder.isBlank() -> name
        name.isBlank() -> folder
        else -> "$folder/$name"
    }
}

internal fun mediaStoreBaseName(displayName: String): String =
    displayName.substringBeforeLast('.').trim()

internal fun mediaStoreDeltaRowDuplicateKey(row: DeviceDeltaRow): String =
    mediaStoreDuplicateKey(
        mediaUri = row.mediaUri,
        filePath = mediaStoreReadableFilePath(row.relativePath, row.displayName),
        sizeBytes = row.sizeBytes,
    )

internal fun mediaStoreSongDuplicateKey(song: Song): String =
    mediaStoreDuplicateKey(
        mediaUri = song.mediaUri,
        filePath = song.filePath.ifBlank {
            mediaStoreReadableFilePath(song.folderPath, song.fileName)
        },
        sizeBytes = song.sizeBytes,
    )

internal fun mediaStoreDeltaRowLyricsKey(row: DeviceDeltaRow): String? {
    val baseName = mediaStoreBaseName(row.displayName)
    if (baseName.isBlank()) return null
    return mediaStoreLyricsKey(row.relativePath, baseName)
}

internal fun mediaStoreSongLyricsKey(song: Song): String? {
    val displayName = song.fileName.ifBlank { song.filePath.substringAfterLast('/', "") }
    val baseName = mediaStoreBaseName(displayName)
    if (baseName.isBlank()) return null
    return mediaStoreLyricsKey(song.folderPath, baseName)
}
