package com.mica.music.data.scanner

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.mica.music.data.local.MicaDatabase
import java.io.File
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
        val file = resolveArtworkFile(uri)
            ?: throw FileNotFoundException("Unable to resolve album artwork: $uri")
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    private fun resolveArtworkFile(uri: Uri): File? {
        val appContext = context?.applicationContext ?: return null
        val managed = AlbumArtCache.parseManagedArtworkUri(appContext, uri.toString()) ?: return null
        AlbumArtCache.fileForManagedArtwork(appContext, uri.toString())
            ?.takeIf { it.isFile && it.length() > 0L }
            ?.let { resident ->
                resident.setLastModified(System.currentTimeMillis())
                return resident
            }

        return runBlocking(Dispatchers.IO) {
            val song = MicaDatabase.get(appContext).songDao().getById(managed.songId)
                ?: return@runBlocking null
            val bytes = AudioMetadataProbe.readEmbeddedArtworkBytes(appContext, song)
                ?: return@runBlocking null
            val restored = AlbumArtCache.storeEmbeddedPicture(appContext, bytes)
            if (restored.nameWithoutExtension != managed.contentKey) {
                restored.delete()
                return@runBlocking null
            }
            AlbumArtCache.trimToBudget(appContext, protectedFile = restored)
            restored
        }
    }
}
