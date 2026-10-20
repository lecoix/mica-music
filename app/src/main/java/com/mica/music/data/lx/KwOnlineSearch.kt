package com.mica.music.data.lx

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/** Kuwo search mapping follows LX Music mobile cd37a979 / src/utils/musicSdk/kw/musicSearch.js (Apache-2.0). */
internal class KwOnlineSearch {
    private val client = OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).build()

    suspend fun search(query: String, page: Int): List<LxOnlineResult> = permits.withPermit {
        withContext(Dispatchers.IO) {
            require(query.length in 1..100 && page in 1..50)
            var retry = 0
            while (true) {
                val url = SEARCH_URL.toHttpUrl().newBuilder()
                    .addQueryParameter("client", "kt")
                    .addQueryParameter("all", query)
                    .addQueryParameter("pn", (page - 1).toString())
                    .addQueryParameter("rn", "20")
                    .addQueryParameter("uid", "794762570")
                    .addQueryParameter("ver", "kwplayer_ar_9.2.2.1")
                    .addQueryParameter("vipver", "1")
                    .addQueryParameter("show_copyright_off", "1")
                    .addQueryParameter("newver", "1")
                    .addQueryParameter("ft", "music")
                    .addQueryParameter("cluster", "0")
                    .addQueryParameter("strategy", "2012")
                    .addQueryParameter("encoding", "utf8")
                    .addQueryParameter("rformat", "json")
                    .addQueryParameter("vermerge", "1")
                    .addQueryParameter("mobi", "1")
                    .addQueryParameter("issubtitle", "1")
                    .build()
                val raw = client.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
                    check(response.isSuccessful) { "Kuwo search HTTP ${response.code}" }
                    response.body?.byteStream()?.use { it.readBytesBounded(1024 * 1024).toString(Charsets.UTF_8) }
                        ?: error("Empty response")
                }
                val root = JSONObject(raw)
                val total = root.optIntString("TOTAL")
                val show = root.optIntString("SHOW")
                if (total == 0 || show > 0) return@withContext parse(root)
                if (retry >= MAX_RETRIES) error("Kuwo search returned no visible rows")
                retry += 1
                android.util.Log.w("LX_KW_SEARCH", "retry=$retry/$MAX_RETRIES total=$total show=$show")
            }
            error("Kuwo search retry loop exited unexpectedly")
        }
    }

    companion object {
        private const val SEARCH_URL = "http://search.kuwo.cn/r.s"
        private const val MAX_RETRIES = 2
        private val permits = Semaphore(2)
        private val infoPattern = Regex("level:(\\w+),bitrate:(\\d+),format:(\\w+),size:([\\w.]+)")

        private fun JSONObject.optIntString(name: String): Int = optString(name).toIntOrNull() ?: optInt(name)

        internal fun parse(root: JSONObject): List<LxOnlineResult> {
            val rows = root.optJSONArray("abslist") ?: JSONArray()
            return buildList {
                for (i in 0 until minOf(20, rows.length())) {
                    val item = rows.optJSONObject(i) ?: continue
                    val rawRid = item.optString("MUSICRID")
                    val id = rawRid.removePrefix("MUSIC_")
                    if (!id.matches(Regex("[A-Za-z0-9]{1,80}"))) continue
                    val nMinfo = item.optString("N_MINFO")
                    if (nMinfo.isBlank()) continue

                    val ordered = linkedMapOf<String, String>()
                    nMinfo.split(';').forEach { entry ->
                        val match = infoPattern.matchEntire(entry) ?: return@forEach
                        val quality = when (match.groupValues[2]) {
                            "4000" -> "flac24bit"
                            "2000" -> "flac"
                            "320" -> "320k"
                            "128" -> "128k"
                            else -> null
                        } ?: return@forEach
                        ordered.putIfAbsent(quality, match.groupValues[4].uppercase())
                    }
                    val qualities = listOf("128k", "320k", "flac", "flac24bit").filter { it in ordered }
                    if (qualities.isEmpty()) continue

                    val title = item.optString("SONGNAME").ifBlank { item.optString("NAME") }.take(256)
                    val artist = item.optString("ARTIST").replace("&", "、").take(256)
                    val album = item.optString("ALBUM").take(256)
                    val duration = item.optString("DURATION").toIntOrNull()?.coerceIn(0, 86400) ?: 0
                    val types = JSONArray()
                    val typeMap = JSONObject()
                    qualities.forEach { quality ->
                        val size = ordered.getValue(quality)
                        types.put(JSONObject().put("type", quality).put("size", size))
                        typeMap.put(quality, JSONObject().put("size", size))
                    }
                    val info = JSONObject().apply {
                        put("source", "kw")
                        put("songmid", id)
                        put("name", title)
                        put("singer", artist)
                        put("albumId", item.optString("ALBUMID"))
                        put("albumName", album)
                        put("interval", "%02d:%02d".format(duration / 60, duration % 60))
                        put("lrc", JSONObject.NULL)
                        put("img", item.optString("web_albumpic_short").takeIf { it.isNotBlank() }?.let { short ->
                            "http://img1.kwcdn.kuwo.cn/star/albumcover/" + short.replaceFirst(Regex("^\\d+/"), "500/")
                        } ?: JSONObject.NULL)
                        put("otherSource", JSONObject.NULL)
                        put("types", types)
                        put("_types", typeMap)
                        put("typeUrl", JSONObject())
                    }.toString()
                    if (info.toByteArray().size <= 8192) {
                        add(LxOnlineResult(id, title, artist, album, duration, info, qualities, platform = "kw"))
                    }
                }
            }.distinctBy { it.id }
        }
    }
}
