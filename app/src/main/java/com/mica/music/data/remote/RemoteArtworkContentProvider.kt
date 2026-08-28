package com.mica.music.data.remote

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.mica.music.MicaApp
import com.mica.music.data.remote.navidrome.NavidromeArtworkHttpStreamer
import com.mica.music.data.remote.navidrome.NavidromeArtworkRequestResolver
import com.mica.music.util.DiagnosticLog
import java.io.FileNotFoundException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/** Read-only JIT bridge for authenticated remote artwork. The public URI never contains auth state. */
class RemoteArtworkContentProvider : ContentProvider() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val streamer = NavidromeArtworkHttpStreamer()

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
        val resolver = NavidromeArtworkRequestResolver(
            sourceOwnerById = { sourceId -> app.remoteCatalogRepository.sourceOwner(sourceId) },
            credentialStore = app.remoteCredentialStore,
        )
        val request = runBlocking(Dispatchers.IO) { resolver.resolve(ref) }
            ?: throw FileNotFoundException("Remote artwork source is unavailable")
        val pipe = ParcelFileDescriptor.createPipe()
        val readSide = pipe[0]
        val writeSide = pipe[1]
        scope.launch {
            ParcelFileDescriptor.AutoCloseOutputStream(writeSide).use { output ->
                runCatching { streamer.stream(request, output) }
                    .onFailure { failure ->
                        DiagnosticLog.event(
                            "RemoteArtwork",
                            "stream-failed source=${ref.sourceInstanceId} error=${failure.javaClass.simpleName}",
                        )
                    }
            }
        }
        return readSide
    }
}
