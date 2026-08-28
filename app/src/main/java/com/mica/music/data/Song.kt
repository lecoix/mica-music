package com.mica.music.data

enum class SongSource {
    LIBRARY,
    TRANSIENT_EXTERNAL,
    REMOTE,
}

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
    /** Strict, real yyyy-MM-dd tag value; blank when only [year] or no release date is known. */
    val releaseDate: String = "",
    /** Scanner metadata contract used to invalidate legacy cached rows after fields are added. */
    val metadataScanVersion: Int = 1,
    /** 专辑内音轨号；0 表示未知 */
    val trackNumber: Int = 0,
    /** 专辑内碟号；小于等于 0 表示未知/未刷新 */
    val discNumber: Int = 0,
    /** 相对曲库或父目录路径 */
    val folderPath: String = "",
    /** 绝对或可读文件路径（扫描时写入） */
    val filePath: String = "",
    val copyright: String = "",
    /** 文件 tag COMMENT / COMM / ©cmt 等只读注释；无则空字符串 */
    val comment: String = "",
    /** 编码器/转码信息（ENCODERSETTINGS、TSSE、FLAC vendor、MP4 ©too 等；无则回退音轨 MIME） */
    val codecLabel: String = "",
    val dateAddedMs: Long = 0L,
    val dateModifiedMs: Long = 0L,
    val externalLyricsSignature: String = "",
    /** File fingerprint for the last successful embedded-lyrics probe; empty means unknown. */
    val embeddedLyricsProbeRevision: String = "",
    val playCount: Int = 0,
    val totalListenSeconds: Long = 0L,
    /** 最近一次开始播放的时间戳（毫秒），未播放过为 0 */
    val lastPlayedAtMs: Long = 0L,
    val replayGain: ReplayGainTags = ReplayGainTags(),
    val loudnessAnalysis: LoudnessAnalysis = LoudnessAnalysis(),
    val lyricsDocument: LyricsDocument = LyricsDocument(),
    /** False when the lyrics payload was intentionally omitted from a lightweight library row. */
    val lyricsLoaded: Boolean = true,
    /** Optional silent looping MP4 used only by the standard full-player cover. */
    val videoCoverUri: String? = null,
    /** Whether this song belongs to the persisted library or the current process session only. */
    val source: SongSource = SongSource.LIBRARY,
) {
    val isTransient: Boolean get() = source == SongSource.TRANSIENT_EXTERNAL
    val isRemote: Boolean get() = source == SongSource.REMOTE

    val effectivePlaybackUri: String get() = playbackUri ?: mediaUri

    val durationLabel: String
        get() = "${durationSec / 60}:${(durationSec % 60).toString().padStart(2, '0')}"

    val lyricsCacheRevision: String
        get() = "$sizeBytes:$dateModifiedMs:$externalLyricsSignature"
}
