package com.mica.music.data.remote.navidrome

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NavidromeJsonParserTest {
    @Test
    fun `search page keeps raw count separate from accepted song count`() {
        val body = """
            {
              "subsonic-response": {
                "status": "ok",
                "searchResult3": {
                  "song": [
                    {"id":"1","title":"One","artist":"A","duration":123,"contentType":"audio/flac","suffix":"flac","track":2,"discNumber":1},
                    {"title":"missing id"},
                    {"id":"2","title":"Two","albumArtist":"Album Artist","size":999}
                  ]
                }
              }
            }
        """.trimIndent()

        val page = NavidromeJsonParser.searchSongsPage(body)

        assertEquals(3, page.rawCount)
        assertEquals(listOf("1", "2"), page.songs.map { it.remoteId })
        assertEquals("audio/flac", page.songs.first().contentType)
        assertEquals(123, page.songs.first().durationSec)
        assertEquals("Album Artist", page.songs.last().albumArtist)
    }

    @Test
    fun `album artist falls back to structured album artists`() {
        val body = """
            {"subsonic-response":{"status":"ok","album":{"song":[
              {"id":"1","title":"One","albumArtists":[{"id":"a","name":"Structured Artist"}]}
            ]}}}
        """.trimIndent()

        assertEquals("Structured Artist", NavidromeJsonParser.albumSongs(body).single().albumArtist)
    }

    @Test
    fun `failed response code 40 is auth failure even on successful HTTP`() {
        val error = try {
            NavidromeJsonParser.validateResponse(
                """{"subsonic-response":{"status":"failed","error":{"code":40,"message":"Wrong username"}}}""",
            )
            null
        } catch (caught: NavidromeException) {
            caught
        }

        requireNotNull(error)
        assertEquals(NavidromeFailureKind.AUTH, error.kind)
        assertEquals(40, error.protocolCode)
        assertTrue(error.message.orEmpty().contains("Wrong username"))
    }

    @Test
    fun `album list ignores blank ids`() {
        val body = """
            {"subsonic-response":{"status":"ok","albumList2":{"album":[
              {"id":"a"},{"id":""},{"id":"b"}
            ]}}}
        """.trimIndent()

        assertEquals(listOf("a", "b"), NavidromeJsonParser.albumIds(body))
    }
}
