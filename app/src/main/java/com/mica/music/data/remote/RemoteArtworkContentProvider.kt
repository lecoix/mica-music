package com.mica.music.data.remote

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.mica.music.MicaApp
import com.mica.music.data.remote.navidrome.NavidromeArtworkHttpStreamer
import com.mica.music.data.remote.navidrome.NavidromeArtworkRequestResolver
import com.mica.music.data.remote.smb.SmbArtworkByteLoader
import com.mica.music.data.remote.smb.SmbArtworkRequestResolver
import com.mica.music.data.remote.smb.SmbEmbeddedArtworkByteLoader
import com.mica.music.data.remote.smb.SmbEmbeddedArtworkRequestResolver
import com.mica.music.data.remote.webdav.WebDavArtworkByteLoader
import com.mica.music.data.remote.webdav.WebDavArtworkRequestResolver
import com.mica.music.data.remote.webdav.WebDavEmbeddedArtworkByteLoader
import com.mica.music.data.remote.webdav.WebDavEmbeddedArtworkRequestResolver
import com.mica.music.util.DiagnosticLog
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/** Read-only JIT bridge for authenticated remote artwork. The public URI never contains auth state. */
class RemoteArtworkContentProvider : ContentProvider() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val navidromeStreamer = NavidromeArtworkHttpStreamer()
    private val webDavLoader = WebDavArtworkByteLoader()
    private val smbLoader = SmbArtworkByteLoader()
    private val webDavEmbeddedLoader by lazy {
        WebDavEmbeddedArtworkByteLoader(requireNotNull(context).applicationContext)
    }
    private val smbEmbeddedLoader by lazy {
        SmbEmbeddedArtworkByteLoader(requireNotNull(context).applicationContext)
    }
    private val artworkCache = RemoteArtworkByteCache()

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? =
        if (RemoteArtworkUriCodec.decode(uri.toString()) != null) "image/*" else null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("Remote artwork provider is read-only")
        val ref = RemoteArtworkUriCodec.decode(uri.toString())
            ?: throw FileNotFoundException("Invalid remote artwork URI")
        val app = context?.applicationContext as? MicaApp
            ?: throw FileNotFoundException("Remote artwork provider is unavailable")
        val request = runBlocking(Dispatchers.IO) { resolveRequest(app, ref) }
            ?: throw FileNotFoundException("Remote artwork source is unavailable")
        val catalogRevision = runBlocking(Dispatchers.IO) {
            app.remoteCatalogRepository.artworkCatalogRevisionIfPublishedForConfig(
                ref,
                request.sourceConfigRevision,
            )
        } ?: throw FileNotFoundException("Remote artwork is not in the current catalog")
        val cacheKey = RemoteArtworkCacheKey(
            sourceInstanceId = request.sourceInstanceId,
            sourceConfigRevision = request.sourceConfigRevision,
            catalogRevision = catalogRevision,
            credentialRevision = request.credentialRevision,
            opaqueArtworkId = ref.opaqueArtworkId,
        )
        val pipe = ParcelFileDescriptor.createPipe()
        val readSide = pipe[0]
        val writeSide = pipe[1]
        scope.launch {
            ParcelFileDescriptor.AutoCloseOutputStream(writeSide).use { output ->
                runCatching {
                    val bytes = artworkCache.getOrLoad(cacheKey) {
                        request.load()
                    }
                    output.write(bytes)
                    output.flush()
                }.onFailure { failure ->
                    DiagnosticLog.event(
                        "RemoteArtwork",
                        "stream-failed source=${ref.sourceInstanceId} error=${failure.javaClass.simpleName}",
                    )
                }
            }
        }
        return readSide
    }

    private suspend fun resolveRequest(
        app: MicaApp,
        ref: RemoteArtworkRef,
    ): RemoteArtworkLoadPlan? {
        val ownerById: suspend (String) -> RemoteSourceOwner? = { sourceId ->
            app.remoteCatalogRepository.sourceOwner(sourceId)
        }
        val navidrome = NavidromeArtworkRequestResolver(ownerById, app.remoteCredentialStore).resolve(ref)
        if (navidrome != null) {
            return RemoteArtworkLoadPlan(
                sourceInstanceId = navidrome.sourceInstanceId,
                sourceConfigRevision = navidrome.sourceConfigRevision,
                credentialRevision = navidrome.credentialRevision,
                load = {
                    ByteArrayOutputStream().use { buffer ->
                        navidromeStreamer.stream(navidrome, buffer)
                        buffer.toByteArray()
                    }
                },
            )
        }
        val webDav = WebDavArtworkRequestResolver(ownerById, app.remoteCredentialStore).resolve(ref)
        if (webDav != null) {
            return RemoteArtworkLoadPlan(
                sourceInstanceId = webDav.sourceInstanceId,
                sourceConfigRevision = webDav.sourceConfigRevision,
                credentialRevision = webDav.credentialRevision,
                load = { webDavLoader.load(webDav) },
            )
        }
        val webDavEmbedded = WebDavEmbeddedArtworkRequestResolver(ownerById, app.remoteCredentialStore).resolve(ref)
        if (webDavEmbedded != null) {
            return RemoteArtworkLoadPlan(
                sourceInstanceId = webDavEmbedded.sourceInstanceId,
                sourceConfigRevision = webDavEmbedded.sourceConfigRevision,
                credentialRevision = webDavEmbedded.credentialRevision,
                load = { webDavEmbeddedLoader.load(webDavEmbedded) },
            )
        }
        val smb = SmbArtworkRequestResolver(ownerById, app.remoteCredentialStore).resolve(ref)
        if (smb != null) {
            return RemoteArtworkLoadPlan(
                sourceInstanceId = smb.sourceInstanceId,
                sourceConfigRevision = smb.sourceConfigRevision,
                credentialRevision = smb.credentialRevision,
                load = { smbLoader.load(smb) },
            )
        }
        val smbEmbedded = SmbEmbeddedArtworkRequestResolver(ownerById, app.remoteCredentialStore).resolve(ref)
            ?: return null
        return RemoteArtworkLoadPlan(
            sourceInstanceId = smbEmbedded.sourceInstanceId,
            sourceConfigRevision = smbEmbedded.sourceConfigRevision,
            credentialRevision = smbEmbedded.credentialRevision,
            load = { smbEmbeddedLoader.load(smbEmbedded) },
        )
    }
}

private data class RemoteArtworkLoadPlan(
    val sourceInstanceId: String,
    val sourceConfigRevision: Long,
    val credentialRevision: Long,
    val load: () -> ByteArray,
)
