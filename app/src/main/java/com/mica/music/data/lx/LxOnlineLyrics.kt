package com.mica.music.data.lx

import com.mica.music.data.LyricLineNode
import com.mica.music.data.LyricTextPart
import com.mica.music.data.LyricTextRole
import com.mica.music.data.LyricsDocument
import com.mica.music.data.LyricsOrigin
import com.mica.music.data.scanner.LrcParser
import com.mica.music.data.scanner.LyricsSanitizer
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.zip.InflaterInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.FormBody
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Built-in online lyric adapters aligned with LX Music mobile v1.9.0 platform SDKs.
 * Playback URLs remain owned by the user-provided LX source script; lyrics do not depend on it.
 */
internal object LxOnlineLyrics {
    private val client = OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).build()
    private const val MAX_LYRIC_BYTES = 2 * 1024 * 1024
    private const val LYRIC_REQUEST_TIMEOUT_MS = 20_000L

    suspend fun load(record: LxOnlineRecord): LyricsDocument = withContext(Dispatchers.IO) {
        val info = JSONObject(record.musicInfo)
        val raw = when (record.platform) {
            "tx" -> loadTx(info)
            "kw" -> loadKw(info)
            "wy" -> loadWy(info)
            "kg" -> loadKg(info)
            else -> RawLyrics()
        }
        buildDocument(raw)
    }

    private data class RawLyrics(
        val lyric: String = "",
        val translation: String = "",
        val reading: String = "",
        val enhanced: String = "",
    )

    private fun buildDocument(raw: RawLyrics): LyricsDocument {
        val baseText = raw.enhanced.ifBlank { raw.lyric }
        if (baseText.isBlank()) return LyricsDocument(origin = LyricsOrigin.EXTERNAL)
        val base = LyricsSanitizer.finalizeDocument(
            LrcParser.parseDocument(baseText).copy(origin = LyricsOrigin.EXTERNAL),
        )
        if (base.lines.isEmpty()) return base
        val translation = parseSecondary(raw.translation)
        val reading = parseSecondary(raw.reading)
        val merged = base.copy(
            lines = base.lines.map { line ->
                line.copy(parts = mergeParts(line, reading.nearestText(line.startMs), translation.nearestText(line.startMs)))
            },
        )
        return LyricsSanitizer.finalizeDocument(merged)
    }

    private data class TimedText(val timeMs: Int, val text: String)

    private fun parseSecondary(raw: String): List<TimedText> {
        if (raw.isBlank()) return emptyList()
        return LrcParser.parseDocument(raw).lines.mapNotNull { line ->
            val text = line.parts.joinToString("\n") { it.text }.trim()
            text.takeIf { it.isNotBlank() }?.let { TimedText(line.startMs, it) }
        }
    }

    private fun List<TimedText>.nearestText(timeMs: Int): String? {
        if (isEmpty()) return null
        val exact = minByOrNull { kotlin.math.abs(it.timeMs - timeMs) } ?: return null
        return exact.text.takeIf { kotlin.math.abs(exact.timeMs - timeMs) <= 150 }
    }

    private fun mergeParts(line: LyricLineNode, reading: String?, translation: String?): List<LyricTextPart> = buildList {
        reading?.takeIf { it.isNotBlank() }?.let { add(LyricTextPart(LyricTextRole.READING, it)) }
        addAll(line.parts.filterNot { it.role == LyricTextRole.READING || it.role == LyricTextRole.TRANSLATION })
        translation?.takeIf { it.isNotBlank() }?.let { add(LyricTextPart(LyricTextRole.TRANSLATION, it)) }
        if (none { it.role == LyricTextRole.ORIGINAL }) {
            line.parts.firstOrNull()?.let { add(LyricTextPart(LyricTextRole.ORIGINAL, it.text)) }
        }
    }

    // TX: LX still retains this LRC endpoint as its fallback implementation. It currently returns
    // base64 LRC without requiring QRC's non-standard 3DES decoder. QRC word timing can be layered
    // on later without changing the repository contract.
    private suspend fun loadTx(info: JSONObject): RawLyrics {
        val songMid = info.getString("songmid")
        val url = "https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg".toHttpUrl().newBuilder()
            .addQueryParameter("songmid", songMid)
            .addQueryParameter("g_tk", "5381")
            .addQueryParameter("loginUin", "0")
            .addQueryParameter("hostUin", "0")
            .addQueryParameter("format", "json")
            .addQueryParameter("inCharset", "utf8")
            .addQueryParameter("outCharset", "utf-8")
            .addQueryParameter("platform", "yqq")
            .build()
        val root = requestJson(
            Request.Builder().url(url).header("Referer", "https://y.qq.com/portal/player.html").get().build(),
        )
        check(root.optInt("code", root.optInt("retcode", -1)) == 0) { "TX lyric request failed" }
        fun decode(name: String): String {
            val encoded = root.optString(name)
            if (encoded.isBlank()) return ""
            return htmlDecode(String(Base64.getDecoder().decode(encoded), Charsets.UTF_8))
        }
        return RawLyrics(lyric = decode("lyric"), translation = decode("trans"))
    }

    // WY: mirrors LX Music mobile src/utils/musicSdk/wy/lyric.js.
    private suspend fun loadWy(info: JSONObject): RawLyrics {
        val songId = info.getString("songmid")
        val payload = JSONObject().apply {
            put("id", songId)
            put("cp", false)
            put("tv", 0)
            put("lv", 0)
            put("rv", 0)
            put("kv", 0)
            put("yv", 0)
            put("ytv", 0)
            put("yrv", 0)
        }
        val params = WyOnlineSearch.eapi("/api/song/lyric/v1", payload.toString())
        val root = requestJson(
            Request.Builder()
                .url("https://interface3.music.163.com/eapi/song/lyric/v1")
                .header("User-Agent", "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36")
                .header("Origin", "https://music.163.com")
                .post(FormBody.Builder().add("params", params).build())
                .build(),
        )
        check(root.optInt("code") == 200) { "WY lyric request failed" }
        val plain = normalizeWySecondary(root.optJSONObject("lrc")?.optString("lyric").orEmpty())
        val translation = normalizeWySecondary(
            root.optJSONObject("ytlrc")?.optString("lyric").orEmpty().ifBlank {
                root.optJSONObject("tlyric")?.optString("lyric").orEmpty()
            },
        )
        val reading = normalizeWySecondary(
            root.optJSONObject("yromalrc")?.optString("lyric").orEmpty().ifBlank {
                root.optJSONObject("romalrc")?.optString("lyric").orEmpty()
            },
        )
        val enhanced = normalizeWyYrc(root.optJSONObject("yrc")?.optString("lyric").orEmpty())
        return RawLyrics(plain, translation, reading, enhanced)
    }

    private fun fixWyTimeLabels(raw: String): String = raw
        .replace(Regex("\\[(\\d{2}:\\d{2}):(\\d{2})]"), "[$1.$2]")

    private fun normalizeWySecondary(raw: String): String = raw.lineSequence().mapNotNull { source ->
        val line = source.trim()
        if (line.isBlank()) return@mapNotNull null
        if (!line.startsWith("{")) return@mapNotNull fixWyTimeLabels(line)
        runCatching {
            val obj = JSONObject(line)
            val time = obj.optLong("t", -1)
            if (time < 0) return@runCatching null
            val content = obj.optJSONArray("c") ?: JSONArray()
            val text = buildString {
                for (i in 0 until content.length()) append(content.optJSONObject(i)?.optString("tx").orEmpty())
            }
            "${formatTime(time.toInt())}$text"
        }.getOrNull()
    }.joinToString("\n")

    private fun normalizeWyYrc(raw: String): String = raw.lineSequence().mapNotNull { source ->
        val line = source.trim()
        val match = Regex("^\\[(\\d+),(\\d+)](.*)$").matchEntire(line) ?: return@mapNotNull null
        val lineStart = match.groupValues[1].toLongOrNull() ?: return@mapNotNull null
        val lineDuration = match.groupValues[2].toLongOrNull() ?: 0L
        val body = match.groupValues[3]
        val marker = Regex("\\((\\d+),(\\d+),\\d+\\)")
        val out = StringBuilder().append('[').append(lineStart).append(',').append(lineDuration).append(']')
        val matches = marker.findAll(body).toList()
        if (matches.isEmpty()) return@mapNotNull null
        matches.forEachIndexed { index, word ->
            val absolute = word.groupValues[1].toLongOrNull() ?: lineStart
            val duration = word.groupValues[2].toLongOrNull() ?: 0L
            val textStart = word.range.last + 1
            val textEnd = matches.getOrNull(index + 1)?.range?.first ?: body.length
            val text = body.substring(textStart, textEnd)
            out.append('<').append((absolute - lineStart).coerceAtLeast(0)).append(',')
                .append(duration.coerceAtLeast(0)).append(",0>").append(text)
        }
        out.toString()
    }.joinToString("\n")

    // KW: mirrors LX Music mobile's mlyric binary envelope + yeelion transform and timing formula.
    private suspend fun loadKw(info: JSONObject): RawLyrics {
        val id = info.getString("songmid")
        val url = "http://mlyric.kuwo.cn/mobi.s".toHttpUrl().newBuilder()
            .addQueryParameter("f", "web")
            .addQueryParameter("type", "lyric")
            .addQueryParameter("lrcx", "1")
            .addQueryParameter("rid", id)
            .addQueryParameter("encode", "utf8")
            .build()
        val raw = requestBytes(Request.Builder().url(url).get().build())
        val decoded = decodeKw(raw)
        val split = splitKwLyrics(decoded)
        return RawLyrics(
            lyric = split.first,
            translation = split.second,
            enhanced = normalizeKwEnhanced(split.first),
        )
    }

    private fun decodeKw(raw: ByteArray): String {
        val headerEnd = raw.indexOfSequence("\r\n\r\n".toByteArray(Charsets.UTF_8))
        check(headerEnd >= 0 && String(raw.copyOfRange(0, minOf(raw.size, 10)), Charsets.UTF_8).lowercase().startsWith("tp=content")) {
            "KW lyric envelope invalid"
        }
        val inflated = inflate(raw.copyOfRange(headerEnd + 4, raw.size))
        val decoded = Base64.getDecoder().decode(String(inflated, Charsets.UTF_8).trim())
        val key = "yeelion".toByteArray(Charsets.UTF_8)
        decoded.indices.forEach { i -> decoded[i] = (decoded[i].toInt() xor key[i % key.size].toInt()).toByte() }
        return String(decoded, Charsets.UTF_8)
    }

    private data class KwRow(var time: String, val text: String)

    private fun splitKwLyrics(raw: String): Pair<String, String> {
        val tags = mutableListOf<String>()
        val main = mutableListOf<KwRow>()
        val trans = mutableListOf<KwRow>()
        val seen = hashSetOf<String>()
        val time = Regex("^\\[([\\d:.]+)](.*)$")
        raw.lineSequence().forEach { source ->
            val line = source.trim()
            val m = time.matchEntire(line)
            if (m == null) {
                if (line.startsWith("[") && line.endsWith("]")) tags += line
                return@forEach
            }
            val row = KwRow(m.groupValues[1], m.groupValues[2])
            if (!seen.add(row.time)) {
                if (main.size < 2) return@forEach
                val moved = main.removeAt(main.lastIndex)
                moved.time = main.last().time
                trans += moved
                main += row
            } else {
                main += row
            }
        }
        fun render(rows: List<KwRow>, stripWordTiming: Boolean): String = buildString {
            if (tags.isNotEmpty()) append(tags.joinToString("\n")).append('\n')
            rows.forEach { row ->
                val text = if (stripWordTiming) row.text.replace(Regex("<-?\\d+,-?\\d+(?:,-?\\d+)?>"), "") else row.text
                append('[').append(row.time).append(']').append(text).append('\n')
            }
        }.trim()
        return render(main, false) to render(trans, true)
    }

    private fun normalizeKwEnhanced(raw: String): String {
        val kuwo = Regex("^\\[kuwo:\\s*([^]]+)]$", RegexOption.IGNORE_CASE)
        var offset = 1
        var offset2 = 1
        raw.lineSequence().forEach { line ->
            val m = kuwo.matchEntire(line.trim()) ?: return@forEach
            val content = m.groupValues[1].substringBefore("][")
            val value = content.toIntOrNull(8) ?: return@forEach
            val a = value / 10
            val b = value % 10
            if (a != 0 && b != 0) { offset = a; offset2 = b }
        }
        val lineRx = Regex("^\\[([\\d:.]+)](.*)$")
        val wordRx = Regex("<(-?\\d+),(-?\\d+)(?:,-?\\d+)?>")
        return raw.lineSequence().mapNotNull { source ->
            val m = lineRx.matchEntire(source.trim()) ?: return@mapNotNull null
            val lineStart = parseTime(m.groupValues[1]) ?: return@mapNotNull null
            val body = m.groupValues[2]
            val words = wordRx.findAll(body).toList()
            if (words.isEmpty()) return@mapNotNull source.trim()
            data class Cue(var start: Int, var end: Int, val text: String)
            val cues = mutableListOf<Cue>()
            words.forEachIndexed { index, match ->
                val a = match.groupValues[1].toIntOrNull() ?: 0
                val b = match.groupValues[2].toIntOrNull() ?: 0
                val start = kotlin.math.abs((a + b) / (offset * 2))
                val end = kotlin.math.abs((a - b) / (offset2 * 2)) + start
                if (cues.isNotEmpty() && start < cues.last().end) {
                    cues.last().end = start
                    if (cues.last().start > cues.last().end) cues.last().start = cues.last().end
                }
                val textStart = match.range.last + 1
                val textEnd = words.getOrNull(index + 1)?.range?.first ?: body.length
                cues += Cue(start, end, body.substring(textStart, textEnd))
            }
            buildString {
                append(formatTime(lineStart))
                cues.forEach { cue -> append(formatCueTime(lineStart + cue.start)).append(cue.text) }
            }
        }.joinToString("\n")
    }

    // KG: mirrors LX Music mobile lyric search/download and KRC decoder.
    private suspend fun loadKg(info: JSONObject): RawLyrics {
        val name = info.getString("name")
        val hash = info.getString("hash")
        val duration = info.optInt("_interval", parseInterval(info.optString("interval")))
        val searchUrl = "http://lyrics.kugou.com/search".toHttpUrl().newBuilder()
            .addQueryParameter("ver", "1")
            .addQueryParameter("man", "yes")
            .addQueryParameter("client", "pc")
            .addQueryParameter("keyword", name)
            .addQueryParameter("hash", hash)
            .addQueryParameter("timelength", duration.toString())
            .addQueryParameter("lrctxt", "1")
            .build()
        val headers = mapOf(
            "KG-RC" to "1",
            "KG-THash" to "expand_search_manager.cpp:852736169:451",
            "User-Agent" to "KuGou2012-9020-ExpandSearchManager",
        )
        val candidates = requestJson(request(searchUrl.toString(), headers)).optJSONArray("candidates") ?: JSONArray()
        check(candidates.length() > 0) { "KG lyric not found" }
        val candidate = candidates.getJSONObject(0)
        val fmt = if (candidate.optInt("krctype") == 1 && candidate.optInt("contenttype") != 1) "krc" else "lrc"
        val downloadUrl = "http://lyrics.kugou.com/download".toHttpUrl().newBuilder()
            .addQueryParameter("ver", "1")
            .addQueryParameter("client", "pc")
            .addQueryParameter("id", candidate.getString("id"))
            .addQueryParameter("accesskey", candidate.getString("accesskey"))
            .addQueryParameter("fmt", fmt)
            .addQueryParameter("charset", "utf8")
            .build()
        val result = requestJson(request(downloadUrl.toString(), headers))
        val content = result.getString("content")
        if (result.optString("fmt", fmt).equals("lrc", ignoreCase = true)) {
            return RawLyrics(lyric = htmlDecode(String(Base64.getDecoder().decode(content), Charsets.UTF_8)))
        }
        val decoded = decodeKgKrc(content)
        return parseKgKrc(decoded)
    }

    private fun request(url: String, headers: Map<String, String>): Request {
        val builder = Request.Builder().url(url).get()
        headers.forEach { (key, value) -> builder.header(key, value) }
        return builder.build()
    }

    private fun decodeKgKrc(encoded: String): String {
        val raw = Base64.getDecoder().decode(encoded)
        check(raw.size > 4) { "KG lyric payload invalid" }
        val encrypted = raw.copyOfRange(4, raw.size)
        val key = byteArrayOf(0x40,0x47,0x61,0x77,0x5e,0x32,0x74,0x47,0x51,0x36,0x31,0x2d,0xce.toByte(),0xd2.toByte(),0x6e,0x69)
        encrypted.indices.forEach { i -> encrypted[i] = (encrypted[i].toInt() xor key[i % key.size].toInt()).toByte() }
        return String(inflate(encrypted), Charsets.UTF_8)
    }

    private fun parseKgKrc(raw: String): RawLyrics {
        val language = Regex("\\[language:([\\w=\\\\/+]+)]").find(raw)
        var translationRows: JSONArray? = null
        var readingRows: JSONArray? = null
        if (language != null) {
            runCatching {
                val decoded = String(Base64.getDecoder().decode(language.groupValues[1]), Charsets.UTF_8)
                val content = JSONObject(decoded).optJSONArray("content") ?: JSONArray()
                for (i in 0 until content.length()) {
                    val item = content.optJSONObject(i) ?: continue
                    when (item.optInt("type", -1)) {
                        0 -> readingRows = item.optJSONArray("lyricContent")
                        1 -> translationRows = item.optJSONArray("lyricContent")
                    }
                }
            }
        }
        val stripped = raw
            .replace(Regex("^.*\\[id:\\$\\w+]\\n"), "")
            .replace(Regex("\\[language:[\\w=\\\\/+]+]\\n?"), "")
        val starts = Regex("^\\[(\\d+),\\d+]", setOf(RegexOption.MULTILINE)).findAll(stripped)
            .mapNotNull { it.groupValues[1].toIntOrNull() }.toList()
        fun secondary(rows: JSONArray?): String {
            if (rows == null) return ""
            return buildString {
                for (i in 0 until minOf(starts.size, rows.length())) {
                    val row = rows.optJSONArray(i) ?: continue
                    val text = buildString { for (j in 0 until row.length()) append(row.optJSONArray(j)?.optString(0).orEmpty()) }
                    if (text.isNotBlank()) append(formatTime(starts[i])).append(htmlDecode(text)).append('\n')
                }
            }.trim()
        }
        return RawLyrics(
            lyric = stripped.replace(Regex("<\\d+,\\d+(?:,\\d+)?>"), ""),
            translation = secondary(translationRows),
            reading = secondary(readingRows),
            enhanced = htmlDecode(stripped),
        )
    }

    private suspend fun requestJson(request: Request): JSONObject = JSONObject(String(requestBytes(request), Charsets.UTF_8))

    private suspend fun requestBytes(request: Request): ByteArray {
        val call = client.newCall(request)
        return withTimeoutOrNull(LYRIC_REQUEST_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                continuation.invokeOnCancellation { call.cancel() }
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, error: IOException) {
                        if (continuation.isActive) continuation.resumeWithException(error)
                    }

                    override fun onResponse(call: Call, response: Response) {
                        try {
                            val bytes = response.use {
                                check(it.isSuccessful) { "Lyric HTTP ${it.code}" }
                                val stream = it.body?.byteStream() ?: error("Empty lyric response")
                                stream.use { body -> body.readBytesBounded(MAX_LYRIC_BYTES) }
                            }
                            if (continuation.isActive) continuation.resume(bytes)
                        } catch (error: Throwable) {
                            if (continuation.isActive) continuation.resumeWithException(error)
                        }
                    }
                })
            }
        } ?: throw IOException("Lyric request timed out")
    }

    private fun inflate(bytes: ByteArray): ByteArray = InflaterInputStream(ByteArrayInputStream(bytes)).use { input ->
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            output.write(buffer, 0, count)
            check(output.size() <= MAX_LYRIC_BYTES) { "Lyric inflate exceeds limit" }
        }
        output.toByteArray()
    }

    private fun ByteArray.indexOfSequence(needle: ByteArray): Int {
        if (needle.isEmpty() || size < needle.size) return -1
        outer@ for (i in 0..size - needle.size) {
            for (j in needle.indices) if (this[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }

    private fun parseInterval(value: String): Int {
        val parts = value.split(':').mapNotNull(String::toIntOrNull)
        return when (parts.size) {
            2 -> parts[0] * 60 + parts[1]
            3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
            else -> 0
        }
    }

    private fun parseTime(value: String): Int? {
        val match = Regex("^(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?$" ).matchEntire(value) ?: return null
        val min = match.groupValues[1].toIntOrNull() ?: return null
        val sec = match.groupValues[2].toIntOrNull() ?: return null
        val frac = match.groupValues[3]
        val ms = when (frac.length) { 3 -> frac.toIntOrNull() ?: 0; 2 -> (frac.toIntOrNull() ?: 0) * 10; 1 -> (frac.toIntOrNull() ?: 0) * 100; else -> 0 }
        return min * 60_000 + sec * 1_000 + ms
    }

    private fun formatTime(ms: Int): String = "[%02d:%02d.%03d]".format(ms / 60_000, (ms / 1000) % 60, ms % 1000)
    private fun formatCueTime(ms: Int): String = "<%02d:%02d.%03d>".format(ms / 60_000, (ms / 1000) % 60, ms % 1000)

    private fun htmlDecode(value: String): String = value
        .replace("&apos;", "'")
        .replace("&quot;", "\"")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&amp;", "&")
}