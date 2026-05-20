package com.mica.music.data

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class SongDetailRow(
    val label: String,
    val value: String,
)

object SongDetails {

    fun buildRows(song: Song, library: MusicLibrary): List<SongDetailRow> = listOf(
        SongDetailRow("标题", song.title),
        SongDetailRow("艺术家", ArtistNames.normalizeDisplay(song.artist)),
        SongDetailRow("专辑", song.album.ifBlank { "—" }),
        SongDetailRow("专辑艺术家", song.albumArtist.ifBlank { "—" }),
        SongDetailRow("媒体来源", mediaSourceLabel(song, library)),
        SongDetailRow("播放次数", song.playCount.toString()),
        SongDetailRow("时长", song.durationLabel),
        SongDetailRow("比特率", song.metadata.bitrateLabel),
        SongDetailRow("采样率", sampleRateOnly(song.metadata.sampleRateHz)),
        SongDetailRow("位深", bitDepthLabel(song.metadata.bitsPerSample)),
        SongDetailRow("大小", formatFileSize(song.sizeBytes)),
        SongDetailRow("格式", song.metadata.containerName.ifBlank { "—" }),
        SongDetailRow("路径", displayPath(song)),
        SongDetailRow("文件名", song.fileName.ifBlank { "—" }),
        SongDetailRow("编码", song.codecLabel.ifBlank { song.metadata.playbackMimeType }.ifBlank { "—" }),
        SongDetailRow("版权", song.copyright.ifBlank { "—" }),
        SongDetailRow("添加时间", formatTimestamp(song.dateAddedMs)),
        SongDetailRow("修改时间", formatTimestamp(song.dateModifiedMs)),
    )

    fun mediaSourceLabel(song: Song, library: MusicLibrary): String = when {
        song.id.startsWith("ms_") -> "MediaStore 媒体库"
        song.id.startsWith("doc_") -> {
            val folder = library.libraryFolderLabel
            if (folder.isNullOrBlank()) "文件夹（SAF）" else "文件夹 · $folder"
        }
        library.lastScanSource == ScanSource.FOLDER -> "文件夹扫描"
        else -> "MediaStore 媒体库"
    }

    private fun sampleRateOnly(sampleRateHz: Int): String {
        if (sampleRateHz <= 0) return "—"
        val khz = sampleRateHz / 1000.0
        return if (kotlin.math.abs(khz - khz.toInt()) < 0.05) {
            "${khz.toInt()} kHz"
        } else {
            "%.1f kHz".format(khz)
        }
    }

    private fun bitDepthLabel(bits: Int?): String =
        bits?.takeIf { it > 0 }?.let { "$it bit" } ?: "—"

    fun formatFileSize(bytes: Long): String = when {
        bytes <= 0L -> "—"
        bytes < 1024L -> "$bytes B"
        bytes < 1024L * 1024L -> "%.1f KB".format(bytes / 1024.0)
        bytes < 1024L * 1024L * 1024L -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
        else -> "%.2f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
    }

    fun formatTimestamp(ms: Long): String {
        if (ms <= 0L) return "—"
        val pattern = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        return pattern.format(Date(ms))
    }

    fun displayPath(song: Song): String {
        if (song.filePath.isNotBlank()) return song.filePath
        if (song.folderPath.isNotBlank() && song.fileName.isNotBlank()) {
            return "${song.folderPath.trimEnd('/')}/${song.fileName}"
        }
        if (song.folderPath.isNotBlank()) return song.folderPath
        return song.mediaUri
    }
}
