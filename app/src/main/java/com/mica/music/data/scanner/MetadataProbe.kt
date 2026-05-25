package com.mica.music.data.scanner

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.mica.music.data.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class MetadataEntry(
    val group: String,
    val key: String,
    val value: String,
)

/**
 * 探测单首歌曲的全部可读元数据（应用内字段、Retriever、ID3 帧、原始标签等）。
 */
object MetadataProbe {

    private val extraRetrieverKeys = listOf(
        "albumartist",
        "album_artist",
        "ALBUMARTIST",
        "discnumber",
        "tracknumber",
        "compilation",
        "date",
        "genre",
        "composer",
        "writer",
        "copyright",
        "encoder",
        "encoder_settings",
        "ENCODERSETTINGS",
        "encodersettings",
        "lyrics",
        "unsyncedlyrics",
        "UNSYNCEDLYRICS",
        "UNSYNCED LYRICS",
        "description",
        "comment",
    )

    suspend fun probe(context: Context, song: Song): List<MetadataEntry> = withContext(Dispatchers.IO) {
        val out = mutableListOf<MetadataEntry>()
        appendSongFields(out, song)
        val uri = Uri.parse(song.mediaUri)
        appendRetriever(out, context, uri)
        val bytes = AudioProbeBytes.read(context, uri)
        if (bytes != null) {
            appendId3Frames(out, bytes)
            appendRawTags(out, bytes)
            appendVorbisComments(out, bytes)
            appendMp4Lyrics(out, bytes)
        }
        appendDerived(out, context, uri)
        out.sortedWith(compareBy({ it.group }, { it.key }))
    }

    private fun appendSongFields(out: MutableList<MetadataEntry>, song: Song) {
        out += entry("应用内", "id", song.id)
        out += entry("应用内", "title", song.title)
        out += entry("应用内", "artist", song.artist)
        out += entry("应用内", "album", song.album)
        out += entry("应用内", "albumArtist", song.albumArtist)
        out += entry("应用内", "copyright", song.copyright)
        out += entry("应用内", "codecLabel", song.codecLabel)
        out += entry("应用内", "fileName", song.fileName)
        out += entry("应用内", "filePath", song.filePath)
        out += entry("应用内", "mediaUri", song.mediaUri)
        out += entry("应用内", "year", song.year.toString())
        out += entry("应用内", "durationSec", song.durationSec.toString())
        out += entry("应用内", "sizeBytes", song.sizeBytes.toString())
        out += entry("应用内", "lyrics.lineCount", song.lyrics.size.toString())
        song.lyrics.take(5).forEachIndexed { i, line ->
            out += entry("应用内", "lyrics[$i]", "[${line.timeMs}ms] ${line.text.take(200)}")
        }
        val m = song.metadata
        out += entry("应用内", "metadata.containerName", m.containerName)
        out += entry("应用内", "metadata.playbackMimeType", m.playbackMimeType)
        out += entry("应用内", "metadata.sampleRateHz", m.sampleRateHz.toString())
        out += entry("应用内", "metadata.bitsPerSample", m.bitsPerSample?.toString().orEmpty())
        out += entry("应用内", "metadata.bitrateKbps", m.bitrateKbps.toString())
    }

    private fun appendRetriever(out: MutableList<MetadataEntry>, context: Context, uri: Uri) {
        val retriever = MediaMetadataRetriever()
        try {
            if (uri.scheme == "file") {
                val path = uri.path
                if (!path.isNullOrBlank()) retriever.setDataSource(path)
            } else {
                retriever.setDataSource(context, uri)
            }
            for (key in retrieverMetadataKeys()) {
                val value = extractMetadataString(retriever, key)
                out += entry("MediaMetadataRetriever", key, value ?: "—")
            }
        } catch (e: Exception) {
            out += entry("MediaMetadataRetriever", "error", e.message ?: e.javaClass.simpleName)
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun appendDerived(out: MutableList<MetadataEntry>, context: Context, uri: Uri) {
        out += entry("解析器", "EncoderSettingsReader", EncoderSettingsReader.read(context, uri).ifBlank { "—" })
        val lyrics = runCatching {
            EmbeddedLyricsReader.read(context, uri, null, null, "")
        }.getOrElse { emptyList() }
        out += entry("解析器", "EmbeddedLyricsReader.lines", lyrics.size.toString())
        lyrics.take(3).forEachIndexed { i, line ->
            out += entry("解析器", "EmbeddedLyricsReader[$i]", line.text.take(200))
        }
    }

    private fun appendId3Frames(out: MutableList<MetadataEntry>, bytes: ByteArray) {
        val frames = Id3FrameLister.listAll(bytes)
        if (frames.isEmpty()) {
            out += entry("ID3v2", "—", "未找到 ID3 标签")
            return
        }
        frames.forEach { frame ->
            val enc = frame.encoding?.toString() ?: "—"
            out += entry(
                "ID3v2",
                "tag${frame.tagIndex} ${frame.frameId} ${frame.size}B enc=$enc",
                frame.preview.ifBlank { "—" },
            )
        }
    }

    private fun appendRawTags(out: MutableList<MetadataEntry>, bytes: ByteArray) {
        val group = "字节扫描(仅诊断)"
        out += entry(
            group,
            "说明",
            "仅在调试页展示原始字节命中，不参与 EmbeddedLyricsReader 歌词入库。",
        )
        val prefixes = listOf(
            "ENCODERSETTINGS=",
            "encoder_settings=",
            "UNSYNCEDLYRICS=",
            "UNSYNCED LYRICS=",
            "LYRICS=",
            "USLT",
        )
        for (prefix in prefixes) {
            val pattern = prefix.toByteArray(Charsets.UTF_8)
            var from = 0
            var hits = 0
            while (hits < 2 && from < bytes.size) {
                val idx = Id3Binary.indexOf(bytes, pattern, from)
                if (idx < 0) break
                val preview = Id3Binary.extractTextAfterMarker(bytes, idx + pattern.size).take(200)
                out += entry(group, prefix, "offset=$idx $preview")
                hits++
                from = idx + 1
            }
        }
    }

    private fun appendVorbisComments(out: MutableList<MetadataEntry>, bytes: ByteArray) {
        VorbisCommentLister.listAll(bytes).forEach { (key, value) ->
            out += entry("Vorbis/FLAC", key, value.take(500))
        }
    }

    private fun appendMp4Lyrics(out: MutableList<MetadataEntry>, bytes: ByteArray) {
        val text = Mp4LyricsReader.read(bytes)
        out += entry("MP4/M4A", "©ly (Mp4LyricsReader)", text?.take(500) ?: "—")
        val tool = Mp4AtomTextReader.read(
            bytes,
            listOf(
                byteArrayOf(0xA9.toByte(), 't'.code.toByte(), 'o'.code.toByte(), 'o'.code.toByte()),
            ),
        )
        out += entry("MP4/M4A", "©too (encoding tool)", tool?.take(500) ?: "—")
    }

    private fun retrieverMetadataKeys(): List<String> {
        val keys = linkedSetOf<String>()
        runCatching {
            MediaMetadataRetriever::class.java.fields.forEach { field ->
                if (!field.name.startsWith("METADATA_KEY_")) return@forEach
                field.get(null)?.toString()?.let { keys += it }
            }
        }
        keys += extraRetrieverKeys
        return keys.sorted()
    }

    private fun extractMetadataString(retriever: MediaMetadataRetriever, key: String): String? =
        runCatching {
            val method = MediaMetadataRetriever::class.java.getMethod(
                "extractMetadata",
                String::class.java,
            )
            method.invoke(retriever, key) as? String
        }.getOrNull()?.let { MetadataTextFix.normalize(it) }

    private fun entry(group: String, key: String, value: String): MetadataEntry =
        MetadataEntry(group, key, value.ifBlank { "—" })
}
