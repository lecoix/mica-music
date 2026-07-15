package com.mica.music.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.mica.music.data.ReplayGainTags
import com.mica.music.data.Song
import com.mica.music.data.TrackMetadata

@Entity(tableName = "song_lyrics", primaryKeys = ["songId", "slot"])
data class SongLyricsEntity(
    val songId: String,
    val slot: String,
    val revision: String,
    val lyricsJson: String,
)

/** Deprecated v10 staging schema retained until a later explicit Room migration removes the table. */
@Entity(tableName = "song_lyrics_pending", primaryKeys = ["scanId", "songId"])
data class PendingSongLyricsEntity(
    val scanId: String,
    val songId: String,
    val revision: String,
    val embeddedJson: String? = null,
    val externalLrcJson: String? = null,
    val externalTtmlJson: String? = null,
)

@Entity(tableName = "songs")
data class SongEntity(
    @PrimaryKey val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val albumArtist: String = "",
    val durationSec: Int,
    val containerName: String,
    val sampleRateHz: Int,
    val bitsPerSample: Int?,
    val bitrateKbps: Int,
    val channelCount: Int,
    val playbackMimeType: String,
    val albumArtUri: String?,
    val coverColorArgb: Int,
    val mediaUri: String,
    val fileName: String,
    val sizeBytes: Long,
    val year: Int,
    val trackNumber: Int = 0,
    val discNumber: Int = 0,
    val folderPath: String,
    val filePath: String = "",
    val copyright: String = "",
    val codecLabel: String = "",
    val dateAddedMs: Long,
    val dateModifiedMs: Long,
    val externalLyricsSignature: String = "",
    val playCount: Int,
    /**
     * Legacy v8 payload copied into `song_lyrics` by MIGRATION_8_9.
     *
     * Schema v9+ keeps this deprecated column for migration and compatibility code. It is not
     * authoritative: runtime lyrics selection/loading must use `song_lyrics`. Remove this field
     * only through a later explicit Room schema migration.
     */
    val lyricsJson: String,
    val queueOrder: Int,
    val replayGainTrackDb: Float? = null,
    val replayGainTrackPeak: Float? = null,
    val replayGainAlbumDb: Float? = null,
    val replayGainAlbumPeak: Float? = null,
)

/** Room projection used by catalog loading; deliberately excludes the large lyrics payload. */
data class SongSummaryEntity(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val albumArtist: String,
    val durationSec: Int,
    val containerName: String,
    val sampleRateHz: Int,
    val bitsPerSample: Int?,
    val bitrateKbps: Int,
    val channelCount: Int,
    val playbackMimeType: String,
    val albumArtUri: String?,
    val coverColorArgb: Int,
    val mediaUri: String,
    val fileName: String,
    val sizeBytes: Long,
    val year: Int,
    val trackNumber: Int,
    val discNumber: Int,
    val folderPath: String,
    val filePath: String,
    val copyright: String,
    val codecLabel: String,
    val dateAddedMs: Long,
    val dateModifiedMs: Long,
    val externalLyricsSignature: String,
    val playCount: Int,
    val queueOrder: Int,
    val replayGainTrackDb: Float?,
    val replayGainTrackPeak: Float?,
    val replayGainAlbumDb: Float?,
    val replayGainAlbumPeak: Float?,
)

data class LyricsJsonRow(val lyricsJson: String)

@Entity(tableName = "library_meta")
data class LibraryMetaEntity(
    @PrimaryKey val id: Int = 1,
    val lastScanAtMs: Long,
    val lastScanSource: String,
    val totalSizeMb: Int,
    val songCount: Int,
    val sortField: String = "",
    val sortDirection: String = "",
    val fastScrollSectionsJson: String = "",
)

fun SongEntity.toSong(): Song = Song(
    id = id,
    title = title,
    artist = artist,
    album = album,
    albumArtist = albumArtist,
    durationSec = durationSec,
    metadata = TrackMetadata(
        containerName = containerName,
        sampleRateHz = sampleRateHz,
        bitsPerSample = bitsPerSample,
        bitrateKbps = bitrateKbps,
        channelCount = channelCount,
        playbackMimeType = playbackMimeType,
    ),
    albumArtUri = albumArtUri,
    coverColorArgb = coverColorArgb,
    mediaUri = mediaUri,
    playbackUri = null,
    fileName = fileName,
    sizeBytes = sizeBytes,
    year = year,
    trackNumber = trackNumber,
    discNumber = discNumber,
    folderPath = folderPath,
    filePath = filePath,
    copyright = copyright,
    codecLabel = codecLabel,
    dateAddedMs = dateAddedMs,
    dateModifiedMs = dateModifiedMs,
    externalLyricsSignature = externalLyricsSignature,
    playCount = playCount,
    replayGain = ReplayGainTags(replayGainTrackDb, replayGainTrackPeak, replayGainAlbumDb, replayGainAlbumPeak),
    lyricsDocument = LyricsDocumentCodec.decode(lyricsJson),
)

fun SongSummaryEntity.toSong(): Song = Song(
    id = id,
    title = title,
    artist = artist,
    album = album,
    albumArtist = albumArtist,
    durationSec = durationSec,
    metadata = TrackMetadata(
        containerName = containerName,
        sampleRateHz = sampleRateHz,
        bitsPerSample = bitsPerSample,
        bitrateKbps = bitrateKbps,
        channelCount = channelCount,
        playbackMimeType = playbackMimeType,
    ),
    albumArtUri = albumArtUri,
    coverColorArgb = coverColorArgb,
    mediaUri = mediaUri,
    fileName = fileName,
    sizeBytes = sizeBytes,
    year = year,
    trackNumber = trackNumber,
    discNumber = discNumber,
    folderPath = folderPath,
    filePath = filePath,
    copyright = copyright,
    codecLabel = codecLabel,
    dateAddedMs = dateAddedMs,
    dateModifiedMs = dateModifiedMs,
    externalLyricsSignature = externalLyricsSignature,
    playCount = playCount,
    replayGain = ReplayGainTags(replayGainTrackDb, replayGainTrackPeak, replayGainAlbumDb, replayGainAlbumPeak),
    lyricsLoaded = false,
)

/** 用于增量扫描：元数据或路径变化时判定为「已更新」。 */
fun SongEntity.scanFingerprint(): String = buildString {
    append(title); append('\u0001')
    append(artist); append('\u0001')
    append(album); append('\u0001')
    append(albumArtist); append('\u0001')
    append(durationSec); append('\u0001')
    append(mediaUri); append('\u0001')
    append(dateModifiedMs); append('\u0001')
    append(containerName); append('\u0001')
    append(sampleRateHz); append('\u0001')
    append(bitsPerSample); append('\u0001')
    append(bitrateKbps); append('\u0001')
    append(trackNumber); append('\u0001')
    append(discNumber); append('\u0001')
    append(albumArtUri); append('\u0001')
    append(externalLyricsSignature); append('\u0001')
}

fun Song.toEntity(queueOrder: Int, preservedLyricsJson: String? = null): SongEntity = SongEntity(
    id = id,
    title = title,
    artist = artist,
    album = album,
    albumArtist = albumArtist,
    durationSec = durationSec,
    containerName = metadata.containerName,
    sampleRateHz = metadata.sampleRateHz,
    bitsPerSample = metadata.bitsPerSample,
    bitrateKbps = metadata.bitrateKbps,
    channelCount = metadata.channelCount,
    playbackMimeType = metadata.playbackMimeType,
    albumArtUri = albumArtUri,
    coverColorArgb = coverColorArgb,
    mediaUri = mediaUri,
    fileName = fileName,
    sizeBytes = sizeBytes,
    year = year,
    trackNumber = trackNumber,
    discNumber = discNumber,
    folderPath = folderPath,
    filePath = filePath,
    copyright = copyright,
    codecLabel = codecLabel,
    dateAddedMs = dateAddedMs,
    dateModifiedMs = dateModifiedMs,
    externalLyricsSignature = externalLyricsSignature,
    playCount = playCount,
    lyricsJson = if (lyricsLoaded) LyricsDocumentCodec.encode(lyricsDocument) else preservedLyricsJson.orEmpty(),
    queueOrder = queueOrder,
    replayGainTrackDb = replayGain.trackGainDb,
    replayGainTrackPeak = replayGain.trackPeak,
    replayGainAlbumDb = replayGain.albumGainDb,
    replayGainAlbumPeak = replayGain.albumPeak,
)
