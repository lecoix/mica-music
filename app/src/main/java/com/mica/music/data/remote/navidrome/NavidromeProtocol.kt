package com.mica.music.data.remote.navidrome

import org.json.JSONObject

enum class NavidromeFailureKind {
    AUTH,
    HTTP,
    PROTOCOL,
    INVALID_RESPONSE,
    REDIRECT_ORIGIN,
    STALE_OPERATION,
}

class NavidromeException(
    val kind: NavidromeFailureKind,
    message: String,
    val httpStatus: Int? = null,
    val protocolCode: Int? = null,
    cause: Throwable? = null,
) : Exception(message, cause)

internal object NavidromeJsonParser {
    fun validateResponse(body: String): JSONObject {
        val root = runCatching { JSONObject(body) }.getOrElse { cause ->
            throw NavidromeException(
                kind = NavidromeFailureKind.INVALID_RESPONSE,
                message = "Invalid Subsonic JSON response",
                cause = cause,
            )
        }
        val response = root.optJSONObject("subsonic-response")
            ?: throw NavidromeException(
                kind = NavidromeFailureKind.INVALID_RESPONSE,
                message = "Missing subsonic-response object",
            )
        if (response.optString("status").equals("failed", ignoreCase = true)) {
            val error = response.optJSONObject("error")
            val code = error?.optInt("code")?.takeIf { it != 0 }
            val message = error?.optString("message").orEmpty().ifBlank { "Subsonic request failed" }
            throw NavidromeException(
                kind = if (code == 40) NavidromeFailureKind.AUTH else NavidromeFailureKind.PROTOCOL,
                message = message,
                protocolCode = code,
            )
        }
        return response
    }

    fun searchSongsPage(body: String): NavidromeSongPage {
        val response = validateResponse(body)
        val songs = response.optJSONObject("searchResult3")?.optJSONArray("song")
            ?: return NavidromeSongPage(emptyList(), rawCount = 0)
        return NavidromeSongPage(
            songs = buildList {
                repeat(songs.length()) { index ->
                    parseTrack(songs.optJSONObject(index))?.let(::add)
                }
            },
            rawCount = songs.length(),
        )
    }

    fun albumIds(body: String): List<String> {
        val response = validateResponse(body)
        val albums = response.optJSONObject("albumList2")?.optJSONArray("album") ?: return emptyList()
        return buildList {
            repeat(albums.length()) { index ->
                albums.optJSONObject(index)?.optString("id")?.takeIf(String::isNotBlank)?.let(::add)
            }
        }
    }

    fun albumSongs(body: String): List<NavidromeTrack> {
        val response = validateResponse(body)
        val songs = response.optJSONObject("album")?.optJSONArray("song") ?: return emptyList()
        return buildList {
            repeat(songs.length()) { index ->
                parseTrack(songs.optJSONObject(index))?.let(::add)
            }
        }
    }

    private fun parseTrack(item: JSONObject?): NavidromeTrack? {
        item ?: return null
        val id = item.optString("id").takeIf(String::isNotBlank) ?: return null
        val title = item.optString("title").ifBlank { id }
        val albumArtist = item.optString("albumArtist")
            .ifBlank { item.optString("displayAlbumArtist") }
            .ifBlank {
                item.optJSONArray("albumArtists")
                    ?.optJSONObject(0)
                    ?.optString("name")
                    .orEmpty()
            }
        return NavidromeTrack(
            remoteId = id,
            title = title,
            artist = item.optString("artist"),
            album = item.optString("album"),
            albumArtist = albumArtist,
            albumId = item.optString("albumId"),
            artistId = item.optString("artistId"),
            durationSec = item.optInt("duration", 0).coerceAtLeast(0),
            contentType = item.optString("contentType"),
            suffix = item.optString("suffix"),
            coverArtId = item.optString("coverArt"),
            bitRateKbps = item.optInt("bitRate", 0).coerceAtLeast(0),
            samplingRateHz = item.optInt("samplingRate", 0).coerceAtLeast(0),
            bitDepth = item.optInt("bitDepth", 0).coerceAtLeast(0),
            channelCount = item.optInt("channelCount", 0).coerceAtLeast(0),
            sizeBytes = item.optLong("size", 0L).coerceAtLeast(0L),
            year = item.optInt("year", 0).coerceAtLeast(0),
            trackNumber = item.optInt("track", 0).coerceAtLeast(0),
            discNumber = item.optInt("discNumber", 0).coerceAtLeast(0),
            serverPath = item.optString("path"),
        )
    }
}
