package com.mica.music.data.remote.navidrome

import com.mica.music.data.remote.RemoteCredentialMaterial
import com.mica.music.data.remote.RemoteCredentialSnapshot
import com.mica.music.data.remote.RemoteSourceInstance
import com.mica.music.data.remote.RemoteSourceSnapshot
import com.mica.music.data.remote.RemoteSourceType
import java.net.URI
import java.net.URLDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavidromeRequestFactoryTest {
    private val source = RemoteSourceSnapshot(
        instance = RemoteSourceInstance(
            id = "nav-1",
            type = RemoteSourceType.NAVIDROME,
            displayName = "Home",
            endpoint = "https://music.example/navidrome/",
            credentialRef = "credential-nav-1",
        ),
        configRevision = 7,
        operationGeneration = 11,
    )
    private val credential = RemoteCredentialSnapshot(
        credentialRef = "credential-nav-1",
        revision = 4,
        material = RemoteCredentialMaterial.UsernamePassword("alice", "password"),
    )
    private val factory = NavidromeRequestFactory(saltProvider = { "fixedsalt" })

    @Test
    fun `ping is GET and carries one coherent subsonic auth snapshot`() {
        val request = factory.ping(source, credential)
        val query = query(request.url)

        assertEquals(NavidromeHttpMethod.GET, request.method)
        assertEquals("https://music.example/navidrome/rest/ping", URI(request.url).let { "${it.scheme}://${it.authority}${it.path}" })
        assertEquals("alice", query["u"])
        assertEquals("ae4fbbea177fd6d1a0130e385d03d995", query["t"])
        assertEquals("fixedsalt", query["s"])
        assertEquals("1.16.1", query["v"])
        assertEquals("Mica", query["c"])
        assertEquals("json", query["f"])
        assertEquals(7L, request.sourceConfigRevision)
        assertEquals(4L, request.credentialRevision)
    }

    @Test
    fun `default stream keeps original audio`() {
        val query = query(factory.stream(source, credential, "song id/1").url)

        assertEquals("song id/1", query["id"])
        assertFalse(query.containsKey("maxBitRate"))
        assertFalse(query.containsKey("format"))
    }

    @Test
    fun `cover art request keeps artwork id opaque and authentication just in time`() {
        val request = factory.coverArt(source, credential, "cover/id 9")
        val query = query(request.url)

        assertEquals("https://music.example/navidrome/rest/getCoverArt", URI(request.url).let { "${it.scheme}://${it.authority}${it.path}" })
        assertEquals("cover/id 9", query["id"])
        assertEquals("alice", query["u"])
        assertEquals("fixedsalt", query["s"])
        assertFalse(request.toString().contains("fixedsalt"))
        assertTrue(request.toString().contains("url=<redacted>"))
    }

    @Test
    fun `lyrics requests keep song id or legacy lookup fields distinct`() {
        val structured = query(factory.lyricsBySongId(source, credential, "song/id 9").url)
        val legacy = query(factory.legacyLyrics(source, credential, "Artist / Name", "Title ?").url)

        assertEquals("song/id 9", structured["id"])
        assertFalse(structured.containsKey("artist"))
        assertEquals("Artist / Name", legacy["artist"])
        assertEquals("Title ?", legacy["title"])
        assertFalse(legacy.containsKey("id"))
    }

    @Test
    fun `transcoding parameters appear only when explicitly requested`() {
        val query = query(
            factory.stream(
                source = source,
                credential = credential,
                trackId = "track-1",
                maxBitRateKbps = 320,
                format = "mp3",
            ).url,
        )

        assertEquals("320", query["maxBitRate"])
        assertEquals("mp3", query["format"])
    }

    @Test
    fun `request string form never leaks authenticated url or token salt`() {
        val request = factory.stream(source, credential, "track-1")
        val rendered = request.toString()

        assertFalse(rendered.contains("fixedsalt"))
        assertFalse(rendered.contains("ae4fbbea177fd6d1a0130e385d03d995"))
        assertFalse(rendered.contains("https://music.example"))
        assertTrue(rendered.contains("url=<redacted>"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `credential ref mismatch is rejected before request construction`() {
        factory.ping(
            source,
            RemoteCredentialSnapshot(
                credentialRef = "other-credential",
                revision = 1,
                material = RemoteCredentialMaterial.UsernamePassword("alice", "password"),
            ),
        )
    }

    private fun query(url: String): Map<String, String> =
        URI(url).rawQuery
            .split('&')
            .associate { pair ->
                val split = pair.split('=', limit = 2)
                decode(split[0]) to decode(split.getOrElse(1) { "" })
            }

    private fun decode(value: String): String = URLDecoder.decode(value, Charsets.UTF_8.name())
}
