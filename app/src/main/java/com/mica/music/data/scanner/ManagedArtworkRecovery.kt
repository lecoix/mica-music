package com.mica.music.data.scanner

import android.content.Context
import com.mica.music.data.local.MicaDatabase
import com.mica.music.util.DiagnosticLog
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async

/**
 * Lazy integrity recovery for content-addressed managed artwork.
 *
 * Normal startup and file opens are metadata-only. A full SHA-256 pass is only performed after an
 * actual image load fails, and concurrent failures for the same content key join one verification.
 */
internal object ManagedArtworkRecovery {
    private val verificationDispatcher = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "Mica-ArtworkIntegrity").apply {
            priority = Thread.MIN_PRIORITY
            isDaemon = true
        }
    }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + verificationDispatcher)
    private val inFlight = ConcurrentHashMap<String, Deferred<Boolean>>()

    suspend fun repairAfterLoadFailure(context: Context, uriString: String): Boolean {
        val appContext = context.applicationContext
        val managed = AlbumArtCache.parseManagedArtworkUri(appContext, uriString) ?: return false

        inFlight[managed.contentKey]?.let { return it.await() }

        val created = scope.async(start = CoroutineStart.LAZY) {
            verifyAndRepair(appContext, uriString, managed)
        }
        val active = inFlight.putIfAbsent(managed.contentKey, created)
        if (active != null) {
            created.cancel()
            return active.await()
        }

        created.invokeOnCompletion {
            inFlight.remove(managed.contentKey, created)
        }
        created.start()
        return created.await()
    }

    private suspend fun restoreNow(context: Context, uriString: String): Boolean {
        val appContext = context.applicationContext
        val managed = AlbumArtCache.parseManagedArtworkUri(appContext, uriString) ?: return false
        val song = MicaDatabase.get(appContext).songDao().getById(managed.songId) ?: return false
        val bytes = AudioMetadataProbe.readEmbeddedArtworkBytes(appContext, song) ?: return false

        if (AlbumArtCache.contentKeyFor(bytes) != managed.contentKey) {
            DiagnosticLog.important(
                "AlbumArtCache",
                "lazy-repair source-changed song=${managed.songId} content=${managed.contentKey.takeLast(12)}",
            )
            return false
        }

        val restored = AlbumArtCache.storeEmbeddedPicture(appContext, bytes)
        if (restored.nameWithoutExtension != managed.contentKey) return false
        AlbumArtCache.trimToBudget(appContext, protectedFile = restored)
        AlbumArtCache.clearManagedArtworkFailureState(managed.contentKey)
        return true
    }

    private suspend fun verifyAndRepair(
        context: Context,
        uriString: String,
        managed: AlbumArtCache.ManagedArtwork,
    ): Boolean {
        val file = AlbumArtCache.fileForManagedArtwork(context, uriString)
        if (file != null && file.isFile && file.length() > 0L) {
            val valid = AlbumArtCache.managedArtworkFileIsValid(context, uriString)
            if (valid) {
                AlbumArtCache.clearManagedArtworkFailureState(managed.contentKey)
                return false
            }
        }

        AlbumArtCache.markManagedArtworkCorrupt(managed.contentKey)
        DiagnosticLog.important(
            "AlbumArtCache",
            "lazy-verify corrupt-or-missing song=${managed.songId} content=${managed.contentKey.takeLast(12)}",
        )
        val repaired = restoreNow(context, uriString)
        DiagnosticLog.event(
            "AlbumArtCache",
            "lazy-repair result=$repaired song=${managed.songId} content=${managed.contentKey.takeLast(12)}",
        )
        return repaired
    }
}
