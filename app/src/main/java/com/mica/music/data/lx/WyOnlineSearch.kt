package com.mica.music.data.lx

import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/** NetEase search/EAPI mapping follows LX Music mobile cd37a979 (Apache-2.0). */
internal class WyOnlineSearch {
    private val client = OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).build()

    suspend fun search(query: String, page: Int): List<LxOnlineResult> = permits.withPermit {
        withContext(Dispatchers.IO) {
            require(query.length in 1..100 && page in 1..50)
            var retry = 0
            while (true) {
                val payload = JSONObject().apply {
                    put("keyword", query)
                    put("needCorrect", "1")
                    put("channel", "typing")
                    put("offset", 20 * (page - 1))
                    put("scene", "normal")
                    put("total", page == 1)
                    put("limit", 20)
                }
                val params = eapi(SEARCH_API, payload.toString())
                val raw = client.newCall(
                    Request.Builder()
                        .url(SEARCH_ENDPOINT)
                        .header("User-Agent", USER_AGENT)
                        .header("Origin", "https://music.163.com")
                        .post(FormBody.Builder().add("params", params).build())
                        .build(),
                ).execute().use { response ->
                    check(response.isSuccessful) { "NetEase search HTTP ${response.code}" }
                    response.body?.byteStream()?.use { it.readBytesBounded(1024 * 1024).toString(Charsets.UTF_8) }
                        ?: error("Empty response")
                }
                val root = JSONObject(raw)
                if (root.optInt("code") == 200) return@withContext parse(root)
                if (retry >= MAX_RETRIES) error("NetEase search code=${root.optInt("code")}")
                retry += 1
                android.util.Log.w("LX_WY_SEARCH", "retry=$retry/$MAX_RETRIES code=${root.optInt("code")}")
            }
            error("NetEase search retry loop exited unexpectedly")
        }
    }

    companion object {
        private const val SEARCH_ENDPOINT = "http://interface.music.163.com/eapi/batch"
        private const val SEARCH_API = "/api/search/song/list/page"
        private const val USER_AGENT = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/60.0.3112.90 Safari/537.36"
        private const val EAPI_KEY = "e82ckenh8dichen8"
        private const val MAX_RETRIES = 2
        private val permits = Semaphore(2)

        internal fun eapi(url: String, text: String): String {
            val message = "nobody${url}use${text}md5forencrypt"
            val digest = MessageDigest.getInstance("MD5").digest(message.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            val data = "$url-36cd479b6b5-$text-36cd479b6b5-$digest"
            val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(EAPI_KEY.toByteArray(Charsets.UTF_8), "AES"))
            return cipher.doFinal(data.toByteArray(Charsets.UTF_8)).joinToString("") { "%02X".format(it) }
        }

        internal fun parse(root: JSONObject): List<LxOnlineResult> {
            val resources = root.optJSONObject("data")?.optJSONArray("resources") ?: JSONArray()
            return buildList {
                for (i in 0 until minOf(20, resources.length())) {
                    val simple = resources.optJSONObject(i)?.optJSONObject("baseInfo")?.optJSONObject("simpleSongData") ?: continue
                    val id = simple.optLong("id").takeIf { it > 0 }?.toString() ?: continue
                    val privilege = simple.optJSONObject("privilege") ?: JSONObject()
                    val qualities = mutableListOf<String>()
                    if (privilege.optString("maxBrLevel") == "hires" && simple.optJSONObject("hr") != null) qualities += "flac24bit"
                    when (privilege.optInt("maxbr")) {
                        999000 -> qualities += listOf("flac", "320k", "128k")
                        320000 -> qualities += listOf("320k", "128k")
                        192000, 128000 -> qualities += "128k"
                    }
                    val orderedQualities = listOf("128k", "320k", "flac", "flac24bit").filter { it in qualities }
                    if (orderedQualities.isEmpty()) continue
                    val title = simple.optString("name").take(256)
                    val artists = simple.optJSONArray("ar") ?: JSONArray()
                    val artist = (0 until minOf(artists.length(), 20)).joinToString("、") {
                        artists.optJSONObject(it)?.optString("name").orEmpty()
                    }.take(256)
                    val albumObj = simple.optJSONObject("al") ?: JSONObject()
                    val album = albumObj.optString("name").take(256)
                    val duration = (simple.optLong("dt") / 1000L).toInt().coerceIn(0, 86400)
                    val types = JSONArray()
                    val typeMap = JSONObject()
                    orderedQualities.forEach { quality ->
                        types.put(JSONObject().put("type", quality).put("size", ""))
                        typeMap.put(quality, JSONObject().put("size", ""))
                    }
                    val info = JSONObject().apply {
                        put("source", "wy")
                        put("songmid", id)
                        put("name", title)
                        put("singer", artist)
                        put("albumId", albumObj.optLong("id").toString())
                        put("albumName", album)
                        put("interval", "%02d:%02d".format(duration / 60, duration % 60))
                        put("img", albumObj.optString("picUrl"))
                        put("lrc", JSONObject.NULL)
                        put("types", types)
                        put("_types", typeMap)
                        put("typeUrl", JSONObject())
                    }.toString()
                    if (info.toByteArray().size <= 8192) {
                        add(LxOnlineResult(id, title, artist, album, duration, info, orderedQualities, platform = "wy"))
                    }
                }
            }.distinctBy { it.id }
        }
    }
}
