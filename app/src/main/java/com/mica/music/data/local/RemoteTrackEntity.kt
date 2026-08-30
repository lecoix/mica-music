package com.mica.music.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import com.mica.music.data.remote.RemoteTrackRef
import com.mica.music.data.remote.RemoteTrackSummary

@Entity(
    tableName = "remote_tracks",
    primaryKeys = ["sourceInstanceId", "opaqueTrackId"],
    foreignKeys = [
        ForeignKey(
            entity = RemoteSourceEntity::class,
            parentColumns = ["id"],
            childColumns = ["sourceInstanceId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("sourceInstanceId")],
)
data class RemoteTrackEntity(
    val sourceInstanceId: String,
    val opaqueTrackId: String,
    val title: String,
    val artist: String,
    val album: String,
    val albumArtist: String,
    val durationSec: Int,
    val mimeTypeHint: String,
    val fileName: String,
    val suffix: String,
    val sizeBytes: Long,
    @ColumnInfo(defaultValue = "''") val contentRevision: String = "",
    @ColumnInfo(defaultValue = "0") val metadataProbeRevision: Int = 0,
    val year: Int,
    val trackNumber: Int,
    val discNumber: Int,
    val albumOpaqueId: String,
    val artistOpaqueId: String,
    val artworkOpaqueId: String,
    val catalogPosition: Int,
)

internal fun RemoteTrackEntity.toRemoteTrackSummary(): RemoteTrackSummary = RemoteTrackSummary(
    ref = RemoteTrackRef(sourceInstanceId, opaqueTrackId),
    title = title,
    artist = artist,
    album = album,
    albumArtist = albumArtist,
    durationSec = durationSec,
    mimeTypeHint = mimeTypeHint,
    fileName = fileName,
    suffix = suffix,
    sizeBytes = sizeBytes,
    contentRevision = contentRevision,
    metadataProbeRevision = metadataProbeRevision,
    year = year,
    trackNumber = trackNumber,
    discNumber = discNumber,
    albumOpaqueId = albumOpaqueId,
    artistOpaqueId = artistOpaqueId,
    artworkOpaqueId = artworkOpaqueId,
)

internal fun RemoteTrackSummary.toEntity(position: Int): RemoteTrackEntity = RemoteTrackEntity(
    sourceInstanceId = ref.sourceInstanceId,
    opaqueTrackId = ref.opaqueTrackId,
    title = title,
    artist = artist,
    album = album,
    albumArtist = albumArtist,
    durationSec = durationSec,
    mimeTypeHint = mimeTypeHint,
    fileName = fileName,
    suffix = suffix,
    sizeBytes = sizeBytes,
    contentRevision = contentRevision,
    metadataProbeRevision = metadataProbeRevision,
    year = year,
    trackNumber = trackNumber,
    discNumber = discNumber,
    albumOpaqueId = albumOpaqueId,
    artistOpaqueId = artistOpaqueId,
    artworkOpaqueId = artworkOpaqueId,
    catalogPosition = position,
)
