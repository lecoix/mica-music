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

/** Kugou search mapping follows LX Music mobile cd37a979 (Apache-2.0). */
internal class KgOnlineSearch {
    private val client = OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).build()

    suspend fun search(query: String, page: Int): List<LxOnlineResult> = permits.withPermit {
        withContext(Dispatchers.IO) {
            require(query.length in 1..100 && page in 1..50)
            var retry = 0
            while (true) {
                val url = SEARCH_URL.toHttpUrl().newBuilder()
                    .addQueryParameter("keyword", query)
                    .addQueryParameter("page", page.toString())
                    .addQueryParameter("pagesize", "20")
                    .addQueryParameter("userid", "0")
                    .addQueryParameter("clientver", "")
                    .addQueryParameter("platform", "WebFilter")
                    .addQueryParameter("filter", "2")
                    .addQueryParameter("iscorrection", "1")
                    .addQueryParameter("privilege_filter", "0")
                    .addQueryParameter("area_code", "1")
                    .build()
                val raw = client.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
                    check(response.isSuccessful) { "Kugou search HTTP ${response.code}" }
                    response.body?.byteStream()?.use { it.readBytesBounded(1024 * 1024).toString(Charsets.UTF_8) }
                        ?: error("Empty response")
                }
                val root = JSONObject(raw)
                if (root.optInt("error_code", -1) == 0) return@withContext parse(root)
                if (retry >= MAX_RETRIES) error("Kugou search code=${root.optInt("error_code", -1)}")
                retry += 1
                android.util.Log.w("LX_KG_SEARCH", "retry=$retry/$MAX_RETRIES code=${root.optInt("error_code", -1)}")
            }
            error("Kugou search retry loop exited unexpectedly")
        }
    }

    companion object {
        private const val SEARCH_URL = "https://songsearch.kugou.com/song_search_v2"
        private const val MAX_RETRIES = 2
        private val permits = Semaphore(2)

        internal fun parse(root: JSONObject): List<LxOnlineResult> {
            val lists = root.optJSONObject("data")?.optJSONArray("lists") ?: JSONArray()
            val candidates = buildList<JSONObject> {
                for (i in 0 until lists.length()) {
                    val item = lists.optJSONObject(i) ?: continue
                    add(item)
                    val group = item.optJSONArray("Grp") ?: JSONArray()
                    for (j in 0 until group.length()) group.optJSONObject(j)?.let(::add)
                }
            }
            val seen = hashSetOf<String>()
            return buildList {
                for (item in candidates) {
                    if (size >= 20) break
                    val audioId = item.optLong("Audioid").takeIf { it > 0 }?.toString() ?: continue
                    val mainHash = item.optString("FileHash").uppercase()
                    if (!mainHash.matches(Regex("[A-F0-9]{16,64}"))) continue
                    val identity = audioId + mainHash
                    if (!seen.add(identity)) continue
                    val qualities = mutableListOf<String>()
                    val hashes = linkedMapOf<String, String>()
                    fun addQuality(q: String, sizeKey: String, hashKey: String) {
                        val bytes = item.optLong(sizeKey)
                        val hash = item.optString(hashKey).uppercase()
                        if (bytes > 0 && hash.matches(Regex("[A-F0-9]{16,64}"))) {
                            qualities += q
                            hashes[q] = hash
                        }
                    }
                    addQuality("128k", "FileSize", "FileHash")
                    addQuality("320k", "HQFileSize", "HQFileHash")
                    addQuality("flac", "SQFileSize", "SQFileHash")
                    addQuality("flac24bit", "ResFileSize", "ResFileHash")
                    if (qualities.isEmpty()) continue
                    val singerArray = item.optJSONArray("Singers") ?: JSONArray()
                    val artist = if (singerArray.length() > 0) {
                        (0 until minOf(20, singerArray.length())).joinToString("、") { singerArray.optJSONObject(it)?.optString("name").orEmpty() }
                    } else item.optString("SingerName")
                    val suffix = item.optString("Suffix")
                    val title = (item.optString("OriSongName") + if (suffix.isBlank()) "" else " $suffix").take(256)
                    val album = item.optString("AlbumName").take(256)
                    val duration = item.optInt("Duration").coerceIn(0, 86400)
                    val types = JSONArray()
                    val typeMap = JSONObject()
                    qualities.forEach { q ->
                        val hash = hashes.getValue(q)
                        val size = item.optLong(when (q) { "128k" -> "FileSize"; "320k" -> "HQFileSize"; "flac" -> "SQFileSize"; else -> "ResFileSize" })
                        types.put(JSONObject().put("type", q).put("size", size.toString()).put("hash", hash))
                        typeMap.put(q, JSONObject().put("size", size.toString()).put("hash", hash))
                    }
                    val info = JSONObject().apply {
                        put("source", "kg")
                        put("songmid", audioId)
                        put("hash", mainHash)
                        put("name", title)
                        put("singer", artist.take(256))
                        put("albumId", item.optString("AlbumID"))
                        put("albumName", album)
                        put("interval", "%02d:%02d".format(duration / 60, duration % 60))
                        put("_interval", duration)
                        put("img", item.optString("Image").takeIf { it.isNotBlank() }?.replace("{size}", "480")?.replaceFirst("http://", "https://") ?: JSONObject.NULL)
                        put("lrc", JSONObject.NULL)
                        put("otherSource", JSONObject.NULL)
                        put("types", types)
                        put("_types", typeMap)
                        put("typeUrl", JSONObject())
                    }.toString()
                    if (identity.length <= 80 && info.toByteArray().size <= 8192) {
                        add(LxOnlineResult(identity, title, artist.take(256), album, duration, info, qualities, platform = "kg"))
                    }
                }
            }
        }
    }
}
