package com.mica.music.data.scanner

import com.mica.music.data.Song
import com.mica.music.util.DiagnosticLog
import java.text.Normalizer
import java.util.Locale

internal data class VideoCoverFile(
    val uri: String,
    val folderPath: String,
    val baseName: String,
)

private val VideoCoverWhitespace = Regex("\\s+")
private val UnknownAlbumKeys = setOf(
    normalizeVideoCoverName("未知专辑"),
    normalizeVideoCoverName("Unknown Album"),
)

internal fun attachVideoCovers(
    songs: List<Song>,
    files: List<VideoCoverFile>,
): List<Song> {
    if (songs.isEmpty() || files.isEmpty()) return songs.map { it.copy(videoCoverUri = null) }
    val index = buildVideoCoverIndex(files)
    return songs.map { song ->
        song.copy(videoCoverUri = matchVideoCover(song, index[song.folderPath]))
    }
}

internal fun normalizeVideoCoverName(value: String): String =
    Normalizer.normalize(value, Normalizer.Form.NFKC)
        .trim()
        .replace(VideoCoverWhitespace, " ")
        .lowercase(Locale.ROOT)

private data class FolderVideoCoverIndex(
    val exact: Map<String, VideoCoverFile?>,
    val normalized: Map<String, VideoCoverFile?>,
)

private fun buildVideoCoverIndex(files: List<VideoCoverFile>): Map<String, FolderVideoCoverIndex> =
    files.groupBy(VideoCoverFile::folderPath).mapValues { (_, folderFiles) ->
        FolderVideoCoverIndex(
            exact = uniqueCandidates(folderFiles, VideoCoverFile::baseName),
            normalized = uniqueCandidates(folderFiles) { normalizeVideoCoverName(it.baseName) },
        )
    }

private fun uniqueCandidates(
    files: List<VideoCoverFile>,
    keyOf: (VideoCoverFile) -> String,
): Map<String, VideoCoverFile?> {
    val out = LinkedHashMap<String, VideoCoverFile?>()
    files.forEach { file ->
        val key = keyOf(file)
        if (out.containsKey(key)) {
            out[key] = null
        } else {
            out[key] = file
        }
    }
    return out
}

private fun matchVideoCover(song: Song, index: FolderVideoCoverIndex?): String? {
    index ?: return null
    val album = song.album
    val normalizedAlbum = normalizeVideoCoverName(album)
    if (album.isBlank() || normalizedAlbum in UnknownAlbumKeys) return null

    if (index.exact.containsKey(album)) {
        return index.exact[album]?.uri ?: ambiguous(song, "exact")
    }
    if (index.normalized.containsKey(normalizedAlbum)) {
        return index.normalized[normalizedAlbum]?.uri ?: ambiguous(song, "normalized")
    }
    return null
}

private fun ambiguous(song: Song, kind: String): Nothing? {
    DiagnosticLog.event("VideoCover", "ambiguous kind=$kind folder=${song.folderPath} album=${song.album}")
    return null
}
