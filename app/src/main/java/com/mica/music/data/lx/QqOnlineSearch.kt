package com.mica.music.data.lx

import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/** QQ search mapping/signing follows LX Music mobile 591ede9 / v1.9.0 (Apache-2.0). */
internal class QqOnlineSearch {
    private class QqBusinessResponseException(message: String) : IllegalStateException(message)

    private val client = OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).build()
    suspend fun search(query: String, page: Int): List<LxOnlineResult> = permits.withPermit { withContext(Dispatchers.IO) {
        require(query.length in 1..100 && page in 1..50)
        var retryNum = 0
        while (true) {
            val comm = JSONObject().apply {
                put("_channelid", "0")
                put("_os_version", "6.2.9200-2")
                put("ct", "19")
                put("cv", "2151")
                put("guid", "1F70E520B2EAA7D25E11760783C53CA9")
                put("patch", "118")
                put("psrf_access_token_expiresAt", 0)
                put("psrf_qqaccess_token", "")
                put("psrf_qqopenid", "")
                put("psrf_qqunionid", "")
                put("tmeAppID", "qqmusic")
                put("tmeLoginType", 0)
                put("uin", "0")
                put("wid", "7223299733393904640")
            }
            val body = JSONObject().put("comm", comm).put(SEARCH_SERVICE_KEY, JSONObject()
                .put("module", SEARCH_SERVICE_KEY)
                .put("method", "DoSearchForQQMusicDesktop")
                .put("param", JSONObject().apply {
                    put("grp", 1)
                    put("num_per_page", 20)
                    put("page_num", page)
                    put("query", query)
                    put("remoteplace", "txt.newclient.top")
                    put("search_type", 0)
                    put("searchid", desktopSearchId())
                })).toString()
            val raw = client.newCall(Request.Builder().url("https://u.y.qq.com/cgi-bin/musics.fcg?sign=${sign(body)}")
                .header("User-Agent", "QQMusic 14090508(android 12)")
                .post(body.toRequestBody("application/json".toMediaType())).build()).execute().use { response ->
                check(response.isSuccessful)
                response.body?.byteStream()?.use { it.readBytesBounded(1024 * 1024).toString(Charsets.UTF_8) } ?: error("Empty response")
            }
            try {
                return@withContext parse(raw)
            } catch (error: QqBusinessResponseException) {
                if (retryNum >= MAX_SEARCH_RETRIES) throw error
                retryNum += 1
                android.util.Log.w("LX_QQ_SEARCH", "retry=$retryNum/$MAX_SEARCH_RETRIES after ${error.message}")
            }
        }
        error("QQ search retry loop exited unexpectedly")
    } }
    companion object {
        private const val MAX_SEARCH_RETRIES = 5
        private const val SEARCH_SERVICE_KEY = "music.search.SearchCgiService"
        private const val SEARCH_ID_HEX = "0123456789ABCDEF"
        private val permits = Semaphore(2)

        private fun desktopSearchId(): String {
            val random = java.util.concurrent.ThreadLocalRandom.current()
            val guid = buildString(32) { repeat(32) { append(SEARCH_ID_HEX[random.nextInt(16)]) } }
            return guid + random.nextInt(100_000).toString().padStart(5, '0')
        }
        internal fun sign(body: String): String {
            val hash = MessageDigest.getInstance("SHA-1").digest(body.toByteArray()).joinToString("") { "%02X".format(it) }
            fun pick(indices: List<Int>) = indices.mapNotNull { hash.getOrNull(it) }.joinToString("")
            val xor = listOf(89,39,179,150,218,82,58,252,177,52,186,123,120,64,242,133,143,161,121,179)
            val bytes = xor.mapIndexed { i, v -> (v xor hash.substring(i * 2, i * 2 + 2).toInt(16)).toByte() }.toByteArray()
            val b64 = Base64.getEncoder().encodeToString(bytes).replace(Regex("[/+=]"), "")
            return ("zzc" + pick(listOf(23,14,6,36,16,40,7,19)) + b64 + pick(listOf(16,1,32,12,19,27,8,5))).lowercase()
        }
        internal fun parse(raw: String): List<LxOnlineResult> {
            val root = JSONObject(raw)
            val rootCode = root.getInt("code")
            if (rootCode != 0) {
                val message = root.optString("msg").take(120)
                android.util.Log.w("LX_QQ_SEARCH", "rootCode=$rootCode msg=$message")
                throw QqBusinessResponseException("QQ search root code=$rootCode msg=$message")
            }
            val req = root.optJSONObject(SEARCH_SERVICE_KEY) ?: root.optJSONObject("req")
                ?: throw QqBusinessResponseException("QQ search response missing service payload")
            val reqCode = req.getInt("code")
            if (reqCode != 0) {
                val message = req.optString("msg").take(120)
                android.util.Log.w("LX_QQ_SEARCH", "reqCode=$reqCode msg=$message")
                throw QqBusinessResponseException("QQ search req code=$reqCode msg=$message")
            }
            val responseBody = req.getJSONObject("data").getJSONObject("body")
            val items = responseBody.optJSONObject("song")?.optJSONArray("list")
                ?: responseBody.optJSONArray("item_song")
                ?: return emptyList()
            return buildList {
                for (i in 0 until minOf(20, items.length())) {
                    val item = items.getJSONObject(i)
                    val file = item.optJSONObject("file") ?: continue
                    val mid = item.optString("mid")
                    val mediaMid = file.optString("media_mid")
                    if (!mid.matches(Regex("[A-Za-z0-9]{1,80}")) || mediaMid.isBlank()) continue
                    val title = item.optString("title").take(256)
                    val singers = item.optJSONArray("singer") ?: JSONArray()
                    val artist = (0 until minOf(singers.length(), 20)).joinToString("、") { singers.getJSONObject(it).optString("name") }.take(256)
                    val album = item.optJSONObject("album") ?: JSONObject()
                    val albumName = album.optString("name").take(256)
                    val duration = item.optInt("interval").coerceIn(0, 86400)
                    val qualities = linkedMapOf("128k" to "size_128mp3", "320k" to "size_320mp3", "flac" to "size_flac", "flac24bit" to "size_hires")
                        .filterValues { file.optLong(it) > 0 }.keys.toList()
                    val types = JSONArray(); val typeMap = JSONObject()
                    qualities.forEach { q ->
                        val value = JSONObject().put("size", "")
                        types.put(JSONObject().put("type", q).put("size", "")); typeMap.put(q, value)
                    }
                    val info = JSONObject().apply {
                        put("source", "tx"); put("songmid", mid); put("songId", item.optLong("id")); put("strMediaMid", mediaMid)
                        put("name", title); put("singer", artist); put("albumName", albumName)
                        put("albumId", album.optString("mid")); put("albumMid", album.optString("mid"))
                        put("interval", "%02d:%02d".format(duration / 60, duration % 60))
                        put("img", "https://y.gtimg.cn/music/photo_new/T002R500x500M000${album.optString("mid")}.jpg")
                        put("types", types); put("_types", typeMap); put("typeUrl", JSONObject())
                    }.toString()
                    if (info.toByteArray().size <= 8192) add(LxOnlineResult(mid, title, artist, albumName, duration, info, qualities, platform = "tx"))
                }
            }.distinctBy { it.id }
        }
    }
}
