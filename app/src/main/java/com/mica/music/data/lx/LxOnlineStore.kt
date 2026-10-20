package com.mica.music.data.lx

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.mica.music.data.remote.RemoteTrackRef
import com.mica.music.data.remote.RemoteTrackSummary
import java.net.URI
import org.json.JSONObject

internal const val LX_ONLINE_SOURCE = "lx-online-tx-v1" // Legacy QQ identity; keep stable for existing playlists.
internal const val LX_ONLINE_KW_SOURCE = "lx-online-kw-v1"
internal const val LX_ONLINE_WY_SOURCE = "lx-online-wy-v1"
internal const val LX_ONLINE_KG_SOURCE = "lx-online-kg-v1"
internal val LX_ONLINE_PLATFORMS = setOf("tx", "kw", "wy", "kg")

internal fun lxOnlineSourceForPlatform(platform: String): String = when (platform) {
    "tx" -> LX_ONLINE_SOURCE
    "kw" -> LX_ONLINE_KW_SOURCE
    "wy" -> LX_ONLINE_WY_SOURCE
    "kg" -> LX_ONLINE_KG_SOURCE
    else -> error("Unsupported LX online platform: $platform")
}

internal fun lxOnlinePlatformForSource(sourceInstanceId: String): String? = when (sourceInstanceId) {
    LX_ONLINE_SOURCE -> "tx"
    LX_ONLINE_KW_SOURCE -> "kw"
    LX_ONLINE_WY_SOURCE -> "wy"
    LX_ONLINE_KG_SOURCE -> "kg"
    else -> null
}

internal fun lxOnlineArtworkUrl(platform: String, musicInfo: String): String? = runCatching {
    val value = JSONObject(musicInfo).optString("img").trim()
    if (value.isBlank()) return@runCatching null
    val uri = URI(value)
    val host = uri.host?.lowercase().orEmpty()
    val allowed = when (platform) {
        "tx" -> host == "y.gtimg.cn" || host.endsWith(".gtimg.cn")
        "kw" -> host == "img1.kwcdn.kuwo.cn" || host.endsWith(".kwcdn.kuwo.cn")
        "wy" -> host == "music.126.net" || host.endsWith(".music.126.net")
        "kg" -> host == "imge.kugou.com" || host.endsWith(".kugou.com")
        else -> false
    }
    value.takeIf { allowed && (uri.scheme == "http" || uri.scheme == "https") }
}.getOrNull()

private data class LxQualityProjection(
    val mimeType: String,
    val suffix: String,
    val bitrateKbps: Int,
    val bitsPerSample: Int?,
)

private fun lxQualityProjection(quality: String): LxQualityProjection = when (quality) {
    "128k" -> LxQualityProjection("audio/mpeg", "mp3", 128, null)
    "320k" -> LxQualityProjection("audio/mpeg", "mp3", 320, null)
    "flac" -> LxQualityProjection("audio/flac", "flac", 0, null)
    "flac24bit" -> LxQualityProjection("audio/flac", "flac", 0, 24)
    else -> LxQualityProjection("", "", 0, null)
}

private fun lxOnlineSummary(
    ref: RemoteTrackRef,
    title: String,
    artist: String,
    album: String,
    durationSec: Int,
    quality: String,
    musicInfo: String,
): RemoteTrackSummary {
    val platform = requireNotNull(lxOnlinePlatformForSource(ref.sourceInstanceId))
    val info = runCatching { JSONObject(musicInfo) }.getOrNull()
    val qualityProjection = lxQualityProjection(quality)
    return RemoteTrackSummary(
        ref = ref,
        title = title,
        artist = artist,
        album = album,
        durationSec = durationSec.coerceAtLeast(0),
        mimeTypeHint = qualityProjection.mimeType,
        suffix = qualityProjection.suffix,
        bitrateKbps = qualityProjection.bitrateKbps,
        bitsPerSample = qualityProjection.bitsPerSample,
        albumOpaqueId = info?.optString("albumId").orEmpty().take(128),
        artworkOpaqueId = if (lxOnlineArtworkUrl(platform, musicInfo) != null) ref.opaqueTrackId else "",
    )
}

internal data class LxOnlineResult(
    val id: String, val title: String, val artist: String, val album: String,
    val durationSec: Int, val musicInfo: String, val qualities: List<String>,
    val platform: String = "tx",
) {
    fun record(quality: String): LxOnlineRecord {
        require(platform in LX_ONLINE_PLATFORMS)
        require(id.matches(Regex("[A-Za-z0-9]{1,80}")))
        require(quality in qualities && quality in listOf("128k", "320k", "flac", "flac24bit"))
        require(musicInfo.toByteArray().size <= 8192)
        require(listOf(title, artist, album).all { it.length <= 256 })
        val opaqueId = if (platform == "tx") "$id:$quality" else "$platform-$id:$quality"
        val ref = RemoteTrackRef(lxOnlineSourceForPlatform(platform), opaqueId)
        return LxOnlineRecord(
            lxOnlineSummary(ref, title, artist, album, durationSec, quality, musicInfo),
            quality,
            musicInfo,
        )
    }
}

internal data class LxOnlineRecord(val track: RemoteTrackSummary, val quality: String, val musicInfo: String) {
    val platform: String get() = requireNotNull(lxOnlinePlatformForSource(track.ref.sourceInstanceId))
}

internal interface LxOnlineStore {
    fun summaries(): List<RemoteTrackSummary>
    fun read(ref: RemoteTrackRef): LxOnlineRecord?
    /** Only the source owner's gate may call this. Never persist resolved audio URLs. */
    fun write(record: LxOnlineRecord)
}

internal class MemoryLxOnlineStore : LxOnlineStore {
    private val records = linkedMapOf<RemoteTrackRef, LxOnlineRecord>()
    override fun summaries() = records.values.map { it.track }
    override fun read(ref: RemoteTrackRef) = records[ref]
    override fun write(record: LxOnlineRecord) { records[record.track.ref] = record }
}

/** Separate experimental storage, never a MusicLibrary/remote catalog snapshot writer. */
internal class AndroidLxOnlineStore(context: Context) : SQLiteOpenHelper(context, "lx-online.db", null, 1), LxOnlineStore {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE tracks (id TEXT PRIMARY KEY, title TEXT NOT NULL, artist TEXT NOT NULL, album TEXT NOT NULL, duration INTEGER NOT NULL, quality TEXT NOT NULL, info TEXT NOT NULL)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = error("Unsupported LX store version")

    private fun sourceForStoredOpaque(id: String): String = when {
        id.startsWith("kw-") -> LX_ONLINE_KW_SOURCE
        id.startsWith("wy-") -> LX_ONLINE_WY_SOURCE
        id.startsWith("kg-") -> LX_ONLINE_KG_SOURCE
        else -> LX_ONLINE_SOURCE
    }

    private fun summary(c: android.database.Cursor): RemoteTrackSummary {
        val ref = RemoteTrackRef(sourceForStoredOpaque(c.getString(0)), c.getString(0))
        return lxOnlineSummary(
            ref = ref,
            title = c.getString(1),
            artist = c.getString(2),
            album = c.getString(3),
            durationSec = c.getInt(4),
            quality = c.getString(5),
            musicInfo = c.getString(6),
        )
    }

    override fun summaries(): List<RemoteTrackSummary> = readableDatabase.rawQuery(
        "SELECT id,title,artist,album,duration,quality,info FROM tracks LIMIT 10000", null).use { c ->
        buildList { while (c.moveToNext()) add(summary(c)) }
    }

    override fun read(ref: RemoteTrackRef): LxOnlineRecord? {
        if (lxOnlinePlatformForSource(ref.sourceInstanceId) == null) return null
        return readableDatabase.rawQuery(
            "SELECT id,title,artist,album,duration,quality,info FROM tracks WHERE id=?",
            arrayOf(ref.opaqueTrackId),
        ).use { c ->
            if (!c.moveToFirst()) return@use null
            val track = summary(c)
            if (track.ref.sourceInstanceId != ref.sourceInstanceId) return@use null
            LxOnlineRecord(track, c.getString(5), c.getString(6))
        }
    }

    override fun write(record: LxOnlineRecord) {
        require(record.musicInfo.toByteArray().size <= 8192)
        require(lxOnlinePlatformForSource(record.track.ref.sourceInstanceId) != null)
        val db = writableDatabase
        db.beginTransaction()
        try {
            val exists = read(record.track.ref) != null
            if (!exists) db.rawQuery("SELECT COUNT(*) FROM tracks", null).use { c ->
                c.moveToFirst(); check(c.getInt(0) < 10000) { "在线收藏已达 10,000 条上限" }
            }
            val t = record.track
            val values = ContentValues().apply {
                put("id", t.ref.opaqueTrackId); put("title", t.title); put("artist", t.artist)
                put("album", t.album); put("duration", t.durationSec); put("quality", record.quality); put("info", record.musicInfo)
            }
            if (exists) {
                check(db.update("tracks", values, "id=?", arrayOf(t.ref.opaqueTrackId)) == 1) { "在线歌曲元数据刷新失败" }
            } else {
                db.insertOrThrow("tracks", null, values)
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
}
