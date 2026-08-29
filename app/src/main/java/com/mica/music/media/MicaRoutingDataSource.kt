package com.mica.music.media

import android.content.Context
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.mica.music.data.remote.RemoteHttpAuthentication
import com.mica.music.data.remote.RemoteHttpPlaybackRequest
import com.mica.music.data.remote.RemoteHttpPlaybackRequestResolver
import com.mica.music.data.remote.RemoteHttpRangePolicy
import com.mica.music.data.remote.RemotePlaybackUriCodec
import com.mica.music.data.remote.webdav.WebDavHttpAuthenticator
import com.mica.music.data.remote.webdav.WebDavStrictRangeInterceptor
import java.io.IOException
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient

/**
 * Routes ordinary/local media through Media3's normal DefaultDataSource and resolves only
 * `mica-remote://` items just-in-time to the protocol-owned HTTP request.
 *
 * Remote HTTP redirects are intentionally disabled. The authenticated URL and transient WebDAV
 * credential material exist only inside DataSource.open() and are never written back to
 * MediaItem/session/queue state.
 */
@UnstableApi
internal class MicaRoutingDataSourceFactory(
    context: Context,
    private val remoteResolver: RemoteHttpPlaybackRequestResolver,
    private val remoteHttpClient: OkHttpClient = secureRemoteHttpClient(),
) : DataSource.Factory {
    private val localFactory: DataSource.Factory = DefaultDataSource.Factory(context)

    override fun createDataSource(): DataSource = MicaRoutingDataSource(
        localFactory = localFactory,
        remoteFactoryFor = ::remoteFactoryFor,
        remoteResolver = remoteResolver,
    )

    private fun remoteFactoryFor(request: RemoteHttpPlaybackRequest): DataSource.Factory {
        val builder = remoteHttpClient.newBuilder()
        when (val authentication = request.authentication) {
            null -> Unit
            is RemoteHttpAuthentication.UsernamePassword -> builder.authenticator(
                WebDavHttpAuthenticator(
                    origin = authentication.origin,
                    username = authentication.username,
                    password = authentication.password,
                ),
            )
        }
        if (request.rangePolicy == RemoteHttpRangePolicy.STRICT_PARTIAL_CONTENT) {
            builder.addNetworkInterceptor(WebDavStrictRangeInterceptor())
        }
        return OkHttpDataSource.Factory(builder.build()).setUserAgent("Mica")
    }

    companion object {
        internal fun secureRemoteHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }
}

@UnstableApi
private class MicaRoutingDataSource(
    private val localFactory: DataSource.Factory,
    private val remoteFactoryFor: (RemoteHttpPlaybackRequest) -> DataSource.Factory,
    private val remoteResolver: RemoteHttpPlaybackRequestResolver,
) : DataSource {
    private val transferListeners = mutableListOf<TransferListener>()
    private var delegate: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        transferListeners += transferListener
        delegate?.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        check(delegate == null) { "DataSource is already open" }
        val remoteMediaId = RemotePlaybackUriCodec.decode(dataSpec.uri.toString())
        val selectedFactory: DataSource.Factory
        val selectedSpec: DataSpec
        if (remoteMediaId == null) {
            selectedFactory = localFactory
            selectedSpec = dataSpec
        } else {
            val resolved = runBlocking { remoteResolver.resolve(remoteMediaId) }
                ?: throw IOException("Remote playback request is unavailable")
            selectedFactory = remoteFactoryFor(resolved)
            selectedSpec = dataSpec.buildUpon()
                .setUri(Uri.parse(resolved.url))
                .setHttpRequestHeaders(dataSpec.httpRequestHeaders + resolved.requestHeaders)
                .build()
        }

        val next = selectedFactory.createDataSource()
        transferListeners.forEach(next::addTransferListener)
        delegate = next
        return try {
            next.open(selectedSpec)
        } catch (failure: Throwable) {
            runCatching { next.close() }
            delegate = null
            throw failure
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        delegate?.read(buffer, offset, length)
            ?: throw IOException("DataSource is not open")

    override fun getUri(): Uri? = delegate?.uri

    override fun getResponseHeaders(): Map<String, List<String>> =
        delegate?.responseHeaders ?: emptyMap()

    override fun close() {
        val current = delegate ?: return
        delegate = null
        current.close()
    }
}
