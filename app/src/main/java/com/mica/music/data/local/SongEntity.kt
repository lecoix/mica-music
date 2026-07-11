package com.mica.music.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.mica.music.data.LyricLine
import com.mica.music.data.LyricCue
import com.mica.music.data.LyricLineNode
import com.mica.music.data.LyricTextPart
import com.mica.music.data.LyricTextRole
import com.mica.music.data.LyricToken
import com.mica.music.data.LyricsDocument
import com.mica.music.data.LyricsSource
import com.mica.music.data.ReplayGainTags
import com.mica.music.data.Song
import com.mica.music.data.TrackMetadata
import com.mica.music.data.toLegacyLyricLines
import com.mica.music.data.toLyricsDocumentCompat
import org.json.JSONArray
import org.json.JSONObject

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
    val lyricsJson: String,
    val queueOrder: Int,
    val replayGainTrackDb: Float? = null,
    val replayGainTrackPeak: Float? = null,
    val replayGainAlbumDb: Float? = null,
    val replayGainAlbumPeak: Float? = null,
)

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
    lyrics = decodeLyrics(lyricsJson),
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
    append(lyricsJson)
}

fun Song.toEntity(queueOrder: Int): SongEntity = SongEntity(
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
    lyricsJson = encodeLyrics(lyrics),
    queueOrder = queueOrder,
    replayGainTrackDb = replayGain.trackGainDb,
    replayGainTrackPeak = replayGain.trackPeak,
    replayGainAlbumDb = replayGain.albumGainDb,
    replayGainAlbumPeak = replayGain.albumPeak,
)

private fun encodeLyrics(lines: List<LyricLine>): String {
    return encodeLyricsDocument(lines.toLyricsDocumentCompat())
}

private fun decodeLyrics(json: String): List<LyricLine> {
    if (json.isBlank() || json == "[]") return emptyList()
    return runCatching {
        if (json.trimStart().startsWith("[")) decodeLegacyLyrics(JSONArray(json))
        else decodeLyricsDocument(JSONObject(json)).toLegacyLyricLines()
    }.getOrDefault(emptyList())
}

private fun encodeLyricsDocument(document: LyricsDocument): String = JSONObject()
    .put("version", document.version)
    .put("source", document.source.name)
    .put("lines", JSONArray().apply {
        document.lines.forEach { line ->
            put(JSONObject()
                .put("id", line.id)
                .put("startMs", line.startMs)
                .put("parts", JSONArray().apply {
                    line.parts.forEach { part -> put(JSONObject().put("role", part.role.name).put("text", part.text)) }
                })
                .put("tokens", JSONArray().apply {
                    line.tokens.forEach { token -> put(JSONObject()
                        .put("text", token.text)
                        .put("startMs", token.startMs)
                        .put("partRole", token.partRole.name)
                        .apply { token.endMs?.let { put("endMs", it) } }) }
                })
                .apply { line.endMs?.let { put("endMs", it) } })
        }
    })
    .toString()

private fun decodeLyricsDocument(json: JSONObject): LyricsDocument {
    val source = runCatching { LyricsSource.valueOf(json.optString("source")) }.getOrDefault(LyricsSource.COMPATIBILITY)
    val lines = json.optJSONArray("lines") ?: return LyricsDocument(source = source)
    return LyricsDocument(
        version = json.optInt("version", 1),
        source = source,
        lines = buildList(lines.length()) {
            for (index in 0 until lines.length()) {
                val line = lines.optJSONObject(index) ?: continue
                if (!line.has("startMs")) continue
                val parts = line.optJSONArray("parts")?.let { values ->
                    buildList(values.length()) {
                        for (partIndex in 0 until values.length()) {
                            val part = values.optJSONObject(partIndex) ?: continue
                            if (!part.has("text")) continue
                            val role = runCatching { LyricTextRole.valueOf(part.optString("role")) }
                                .getOrDefault(LyricTextRole.EXTRA)
                            add(LyricTextPart(role, part.getString("text")))
                        }
                    }
                }.orEmpty()
                if (parts.isEmpty()) continue
                val tokens = line.optJSONArray("tokens")?.let { values ->
                    buildList(values.length()) {
                        for (tokenIndex in 0 until values.length()) {
                            val token = values.optJSONObject(tokenIndex) ?: continue
                            if (!token.has("text") || !token.has("startMs")) continue
                            val role = runCatching { LyricTextRole.valueOf(token.optString("partRole")) }
                                .getOrDefault(LyricTextRole.ORIGINAL)
                            add(LyricToken(
                                text = token.getString("text"),
                                startMs = token.getInt("startMs"),
                                endMs = token.optInt("endMs").takeIf { token.has("endMs") },
                                partRole = role,
                            ))
                        }
                    }
                }.orEmpty()
                add(LyricLineNode(
                    id = line.optString("id").ifBlank { "$index-${line.getInt("startMs")}" },
                    startMs = line.getInt("startMs"),
                    endMs = line.optInt("endMs").takeIf { line.has("endMs") },
                    parts = parts,
                    tokens = tokens,
                ))
            }
        },
    )
}

private fun decodeLegacyLyrics(array: JSONArray): List<LyricLine> = buildList(array.length()) {
    for (i in 0 until array.length()) {
        val obj = array.optJSONObject(i) ?: continue
        if (!obj.has("t") || !obj.has("x")) continue
        val cues = obj.optJSONArray("c")?.let { cueArray ->
            buildList(cueArray.length()) {
                for (cueIndex in 0 until cueArray.length()) {
                    val cue = cueArray.optJSONObject(cueIndex) ?: continue
                    if (!cue.has("t") || !cue.has("x")) continue
                    add(LyricCue(timeMs = cue.getInt("t"), text = cue.getString("x")))
                }
            }
        }.orEmpty()
        add(LyricLine(
            timeMs = obj.getInt("t"),
            text = obj.getString("x"),
            cues = cues,
            endTimeMs = obj.optInt("e").takeIf { obj.has("e") },
        ))
    }
}
