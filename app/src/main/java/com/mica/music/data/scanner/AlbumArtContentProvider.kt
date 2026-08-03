package com.mica.music.data.scanner

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.mica.music.data.local.MicaDatabase
import java.io.FileNotFoundException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/** Read-only bridge that rematerializes an intentionally evicted embedded cover on demand. */
class AlbumArtContentProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? {
        val appContext = context?.applicationContext ?: return null
        return if (AlbumArtCache.parseManagedArtworkUri(appContext, uri.toString()) != null) {
            "image/*"
        } else {
            null
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("Album artwork provider is read-only")
        val appContext = context?.applicationContext
            ?: throw FileNotFoundException("Album artwork provider is unavailable")
        AlbumArtCache.openExistingManagedArtwork(appContext, uri.toString())?.let { return it }
        if (!restoreArtworkFile(uri)) {
            throw FileNotFoundException("Unable to resolve album artwork: $uri")
        }
        return AlbumArtCache.openExistingManagedArtwork(appContext, uri.toString())
            ?: throw FileNotFoundException("Unable to open restored album artwork: $uri")
    }

    private fun restoreArtworkFile(uri: Uri): Boolean {
        val appContext = context?.applicationContext ?: return false
        val managed = AlbumArtCache.parseManagedArtworkUri(appContext, uri.toString()) ?: return false

        return runBlocking(Dispatchers.IO) {
            val song = MicaDatabase.get(appContext).songDao().getById(managed.songId)
                ?: return@runBlocking false
            val bytes = AudioMetadataProbe.readEmbeddedArtworkBytes(appContext, song)
                ?: return@runBlocking false
            val restored = AlbumArtCache.storeEmbeddedPicture(appContext, bytes)
            if (restored.nameWithoutExtension != managed.contentKey) {
                restored.delete()
                return@runBlocking false
            }
            AlbumArtCache.trimToBudget(appContext, protectedFile = restored)
            true
        }
    }
}
