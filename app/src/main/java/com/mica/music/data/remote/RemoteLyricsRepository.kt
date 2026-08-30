package com.mica.music.data.remote

import android.content.Context
import com.mica.music.data.LyricsDocument
import com.mica.music.data.SharedLyricsMemoryCache
import com.mica.music.data.Song
import com.mica.music.data.SongSource
import com.mica.music.data.remote.navidrome.NavidromeHttpExecutor
import com.mica.music.data.remote.navidrome.NavidromeLyricsLoader
import com.mica.music.data.remote.navidrome.NavidromeRequestFactory
import com.mica.music.data.remote.navidrome.UrlConnectionNavidromeHttpExecutor
import com.mica.music.data.remote.smb.SmbLyricsLoader
import com.mica.music.data.remote.smb.SmbStreamRequestResolver
import com.mica.music.data.remote.webdav.WebDavLyricsLoader
import com.mica.music.data.remote.webdav.WebDavStreamRequestResolver
import com.mica.music.util.DiagnosticLog
import kotlinx.coroutines.CancellationException

/** On-demand remote lyric hydration. Remote catalog synchronization never downloads lyric payloads. */
class RemoteLyricsRepository internal constructor(
    private val catalogRepository: RemoteCatalogRepository,
    credentialStore: SecureRemoteCredentialStore,
    context: Context? = null,
    navidromeExecutor: NavidromeHttpExecutor = UrlConnectionNavidromeHttpExecutor(),
    navidromeRequestFactory: NavidromeRequestFactory = NavidromeRequestFactory(),
) {
    private val navidromeLoader = NavidromeLyricsLoader(
        catalogRepository = catalogRepository,
        credentialStore = credentialStore,
        executor = navidromeExecutor,
        requestFactory = navidromeRequestFactory,
    )
    private val embeddedLyricsLoader = context?.applicationContext?.let(::AndroidTagLibEmbeddedLyricsLoader)
    private val webDavLoader = embeddedLyricsLoader?.let { embedded ->
        WebDavLyricsLoader(
            requestResolver = WebDavStreamRequestResolver(catalogRepository::sourceOwner, credentialStore),
            embeddedLoader = embedded,
        )
    }
    private val smbLoader = embeddedLyricsLoader?.let { embedded ->
        SmbLyricsLoader(
            requestResolver = SmbStreamRequestResolver(catalogRepository::sourceOwner, credentialStore),
            embeddedLoader = embedded,
        )
    }

    suspend fun songWithLyrics(song: Song, isPrefetch: Boolean = false): Song {
        if (song.source != SongSource.REMOTE || song.lyricsLoaded) return song
        val ref = RemoteMediaIdCodec.decode(song.id)
            ?: return song.copy(lyricsDocument = LyricsDocument(), lyricsLoaded = true)
        val status = catalogRepository.sourceStatus(ref.sourceInstanceId)
            ?: return song.copy(lyricsDocument = LyricsDocument(), lyricsLoaded = true)
        if (!status.instance.enabled) {
            return song.copy(lyricsDocument = LyricsDocument(), lyricsLoaded = true)
        }
        val revision = buildString {
            append("remote-lyrics-v2:")
            append(status.instance.type.name)
            append(':')
            append(status.configRevision)
            append(':')
            append(status.catalogRevision)
            append(':')
            append(song.lyricsCacheRevision)
        }
        val document = try {
            SharedLyricsMemoryCache.load(
                songId = song.id,
                revision = revision,
                lyricsDataVersion = REMOTE_LYRICS_DATA_VERSION,
                isPrefetch = isPrefetch,
            ) {
                when (status.instance.type) {
                    RemoteSourceType.NAVIDROME -> navidromeLoader.load(song)
                    RemoteSourceType.WEBDAV -> webDavLoader?.load(song) ?: LyricsDocument()
                    RemoteSourceType.SMB -> smbLoader?.load(song) ?: LyricsDocument()
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            DiagnosticLog.event(
                "RemoteLyrics",
                "load-failed source=${ref.sourceInstanceId} song=${song.id.takeLast(12)} " +
                    "error=${error.javaClass.simpleName}",
            )
            return song
        }
        return song.copy(lyricsDocument = document, lyricsLoaded = true)
    }

    private companion object {
        const val REMOTE_LYRICS_DATA_VERSION = 2
    }
}
