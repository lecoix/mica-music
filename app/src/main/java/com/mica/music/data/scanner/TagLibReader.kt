package com.mica.music.data.scanner

import android.content.Context
import android.net.Uri
import com.kyant.taglib.AudioPropertiesReadStyle
import com.kyant.taglib.TagLib
import com.mica.music.data.ReleaseDates
import com.mica.music.data.ReplayGainTags

/**
 * 基于 TagLib（io.github.kyant0:taglib）的标签/封面/歌词/音频属性读取。
 * 任何失败（无法打开、native 异常、属性无效）返回 null，由调用方回退 MediaMetadataRetriever。
 * 位深等技术参数见 [AudioTechnicalProbe]。
 */
internal object TagLibReader {

    class Result(
        val title: String,
        val artist: String,
        val album: String,
        val albumArtist: String,
        val copyright: String,
        val year: Int,
        val releaseDate: String,
        val durationSec: Int,
        val sampleRateHz: Int,
        val bitrateKbps: Int,
        val channelCount: Int,
        /** 0 表示未知 */
        val trackNumber: Int,
        /** 0 表示未知 */
        val discNumber: Int,
        val lyricsCandidates: List<EmbeddedLyricsTextCandidate>,
        val frontCoverBytes: ByteArray?,
        val replayGain: ReplayGainTags,
    )

    fun read(context: Context, uri: Uri): Result? = runCatching {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
            val metadata = TagLib.getMetadata(pfd.dup().detachFd(), readPictures = true)
                ?: return@use null
            val props = TagLib.getAudioProperties(pfd.dup().detachFd(), AudioPropertiesReadStyle.Average)
            if (props == null || props.sampleRate <= 0) return@use null
            val tags = metadata.propertyMap
            val rawDates = tags.valuesFor("DATE", "YEAR", "ORIGINALDATE", "ICRD")
            val releaseDate = rawDates.firstNotNullOfOrNull { raw ->
                ReleaseDates.canonicalFullDate(raw).takeIf { it.isNotEmpty() }
            }.orEmpty()
            val frontCover = metadata.pictures.firstOrNull { it.pictureType == "Front Cover" }
                ?: metadata.pictures.firstOrNull()
            Result(
                title = tags.firstValue("TITLE", "INAM"),
                artist = tags.firstValue("ARTIST", "ARTISTS", "PERFORMER", "IART"),
                album = tags.firstValue("ALBUM", "IPRD"),
                albumArtist = tags.firstValue("ALBUMARTIST", "ALBUM ARTIST"),
                copyright = tags.firstValue("COPYRIGHT", "ICOP"),
                year = ReleaseDates.yearFromFullDate(releaseDate).takeIf { it > 0 }
                    ?: rawDates.firstNotNullOfOrNull { parseYear(it).takeIf { year -> year > 0 } }
                    ?: 0,
                releaseDate = releaseDate,
                durationSec = props.length / 1000,
                sampleRateHz = props.sampleRate,
                bitrateKbps = props.bitrate,
                channelCount = props.channels,
                trackNumber = MetadataTextFix.parseTrackNumber(
                    tags.firstValue("TRACKNUMBER", "TRCK", "TRACK", "IPRT"),
                ),
                discNumber = MetadataTextFix.parseDiscNumber(
                    tags.firstValue("DISCNUMBER", "TPOS", "DISC"),
                ),
                lyricsCandidates = lyricsCandidates(tags),
                frontCoverBytes = frontCover?.data?.takeIf { it.isNotEmpty() },
                replayGain = ReplayGainTags.fromProperties(tags),
            )
        }
    }.getOrNull()

    private fun Map<String, Array<String>>.firstValue(vararg keys: String): String {
        for (key in keys) {
            this[key]?.firstOrNull { it.isNotBlank() }?.let { return it.trim() }
        }
        return ""
    }

    private fun Map<String, Array<String>>.valuesFor(vararg keys: String): List<String> =
        keys.flatMap { key -> this[key].orEmpty().map(String::trim).filter(String::isNotBlank) }

    private fun lyricsCandidates(tags: Map<String, Array<String>>): List<EmbeddedLyricsTextCandidate> =
        tags.entries
            .filter { (key, _) ->
                val normalized = key.uppercase()
                normalized == "LYRICS" || normalized.startsWith("LYRICS:") ||
                    normalized.startsWith("SYNCEDLYRICS") || normalized == "SYNCED LYRICS" ||
                    normalized == "TTML" || normalized.startsWith("TTML:") ||
                    normalized.startsWith("UNSYNCEDLYRICS") || normalized == "UNSYNCED LYRICS"
            }
            .flatMap { (key, values) ->
                values.mapNotNull { value ->
                    value.takeIf { it.isNotBlank() }?.let { EmbeddedLyricsTextCandidate(key, it) }
                }
            }

    private val yearRegex = Regex("""\d{4}""")

    private fun parseYear(raw: String): Int =
        yearRegex.find(raw)?.value?.toIntOrNull()?.coerceAtLeast(0) ?: 0
}
