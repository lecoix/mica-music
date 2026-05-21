package com.mica.music.data

import androidx.compose.ui.graphics.Color

data class Song(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val albumArtist: String = "",
    val durationSec: Int,
    val metadata: TrackMetadata,
    /** 专辑封面 URI（MediaStore 或缓存的内嵌图 file://），无图时 UI 用 [coverColor] */
    val albumArtUri: String?,
    val coverColorArgb: Int,
    val mediaUri: String,
    /** 软解缓存 URI（如转码 FLAC）；为空则用 [mediaUri] */
    val playbackUri: String? = null,
    val fileName: String = "",
    val sizeBytes: Long = 0L,
    /** 0 表示未知 */
    val year: Int = 0,
    /** 相对曲库或父目录路径 */
    val folderPath: String = "",
    /** 绝对或可读文件路径（扫描时写入） */
    val filePath: String = "",
    val copyright: String = "",
    /** 编码器/转码信息（ENCODERSETTINGS、TSSE、FLAC vendor、MP4 ©too 等；无则回退音轨 MIME） */
    val codecLabel: String = "",
    val dateAddedMs: Long = 0L,
    val dateModifiedMs: Long = 0L,
    val playCount: Int = 0,
    /** 最近一次开始播放的时间戳（毫秒），未播放过为 0 */
    val lastPlayedAtMs: Long = 0L,
    val lyrics: List<LyricLine> = emptyList(),
) {
    val effectivePlaybackUri: String get() = playbackUri ?: mediaUri

    val coverColor: Color get() = Color(coverColorArgb)

    val durationLabel: String
        get() = "${durationSec / 60}:${(durationSec % 60).toString().padStart(2, '0')}"

    val formatLabel: String get() = metadata.formatLabel
    val sampleRateLabel: String get() = metadata.sampleRateLabel
    val bitrateLabel: String get() = metadata.bitrateLabel
    val isHiRes: Boolean get() = metadata.isHiRes
}

data class LyricLine(
    val timeMs: Int,
    val text: String,
)
