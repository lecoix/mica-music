package com.mica.music.data.scanner

import android.content.Context
import com.mica.music.data.local.MicaDatabase
import com.mica.music.util.DiagnosticLog
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async

/**
 * On-demand materialization and lazy integrity recovery for two-tier managed artwork.
 *
 * - [ensureThumbnail]: fast compact-thumbnail materialization (`cache/album_art_thumb`, <=320px WebP)
 *   for lists, queues, search, mini-player, and blurred backgrounds.
 * - [ensureOriginal]: on-demand full-res materialization (`cache/album_art`, 48 MiB LRU) for
 *   NowPlaying hero covers and external [AlbumArtContentProvider] readers.
 * - [repairAfterLoadFailure]: invoked only after an actual image decode/open failure; detects
 *   corrupt resident files and rebuilds them without permanently blacklisting transient failures.
 */
internal object ManagedArtworkRecovery {
    private val workerSequence = AtomicInteger(0)
    private val recoveryDispatcher = Executors.newFixedThreadPool(2) { runnable ->
        Thread(runnable, "Mica-ArtworkRestore-${workerSequence.incrementAndGet()}").apply {
            priority = Thread.NORM_PRIORITY - 1
            isDaemon = true
        }
    }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + recoveryDispatcher)
    private val inFlightOriginal = ConcurrentHashMap<String, Deferred<Boolean>>()
    private val inFlightThumb = ConcurrentHashMap<String, Deferred<Boolean>>()

    @Volatile
    internal var embeddedArtworkLoader: suspend (Context, String) -> ByteArray? =
        ::defaultLoadEmbeddedArtworkBytes

    internal fun resetLoaderForTest() {
        embeddedArtworkLoader = ::defaultLoadEmbeddedArtworkBytes
    }

    /**
     * Ensures the full-resolution managed artwork file exists on disk.
     *
     * Returns `true` whenever a readable resident file is available after this call (whether
     * restored by this call or by a concurrent caller that just finished), eliminating the
     * false-`FileNotFoundException` race under concurrent requests.
     */
    suspend fun ensureOriginal(context: Context, uriString: String): Boolean {
        val appContext = context.applicationContext
        val managed = AlbumArtCache.parseManagedArtworkUri(appContext, uriString) ?: return false
        if (AlbumArtCache.hasResidentOriginalArtwork(appContext, uriString)) return true

        return runSingleFlight(inFlightOriginal, managed.contentKey) {
            if (AlbumArtCache.hasResidentOriginalArtwork(appContext, uriString)) {
                return@runSingleFlight true
            }
            val restored = restoreOriginalNow(appContext, uriString, managed)
            DiagnosticLog.event(
                "AlbumArtCache",
                "ensure-original result=$restored song=${managed.songId} content=${managed.contentKey.takeLast(12)}",
            )
            restored
        }
    }

    /**
     * Ensures the compact thumbnail (`<=384px`) exists in `cache/album_art_thumb`.
     *
     * Prefers downscaling an already-resident original before touching the audio file, and never
     * inflates the 48 MiB original LRU cache when only a thumbnail is needed.
     */
    suspend fun ensureThumbnail(context: Context, uriString: String): Boolean {
        val appContext = context.applicationContext
        val managed = AlbumArtCache.parseManagedArtworkUri(appContext, uriString) ?: return false
        if (AlbumArtCache.hasResidentThumbnailArtwork(appContext, uriString)) return true
        if (AlbumArtCache.storeThumbnailFromResidentOriginal(appContext, uriString) != null) {
            return true
        }

        return runSingleFlight(inFlightThumb, managed.contentKey) {
            if (AlbumArtCache.hasResidentThumbnailArtwork(appContext, uriString)) {
                return@runSingleFlight true
            }
            if (AlbumArtCache.storeThumbnailFromResidentOriginal(appContext, uriString) != null) {
                return@runSingleFlight true
            }
            val restored = restoreThumbnailNow(appContext, uriString, managed)
            DiagnosticLog.event(
                "AlbumArtCache",
                "ensure-thumb result=$restored song=${managed.songId} content=${managed.contentKey.takeLast(12)}",
            )
            restored
        }
    }

    /**
     * Called after an actual Coil open/decode failure. Verifies resident files and rebuilds if
     * missing or corrupt, without permanently poisoning [contentKey] on transient read errors.
     */
    suspend fun repairAfterLoadFailure(context: Context, uriString: String): Boolean {
        val appContext = context.applicationContext
        val managed = AlbumArtCache.parseManagedArtworkUri(appContext, uriString) ?: return false

        return runSingleFlight(inFlightOriginal, managed.contentKey) {
            verifyAndRepair(appContext, uriString, managed)
        }
    }

    private suspend fun runSingleFlight(
        map: ConcurrentHashMap<String, Deferred<Boolean>>,
        contentKey: String,
        block: suspend () -> Boolean,
    ): Boolean {
        map[contentKey]?.let { return it.await() }

        val created = scope.async(start = CoroutineStart.LAZY) { block() }
        val active = map.putIfAbsent(contentKey, created)
        if (active != null) {
            created.cancel()
            return active.await()
        }

        created.invokeOnCompletion {
            map.remove(contentKey, created)
        }
        created.start()
        return created.await()
    }

    private suspend fun defaultLoadEmbeddedArtworkBytes(
        context: Context,
        songId: String,
    ): ByteArray? {
        val song = MicaDatabase.get(context).songDao().getById(songId) ?: return null
        return AudioMetadataProbe.readEmbeddedArtworkBytes(context, song)
    }

    private suspend fun restoreOriginalNow(
        context: Context,
        uriString: String,
        managed: AlbumArtCache.ManagedArtwork,
    ): Boolean {
        val bytes = runCatching { embeddedArtworkLoader(context, managed.songId) }.getOrNull()
            ?: return false

        if (AlbumArtCache.contentKeyFor(bytes) != managed.contentKey) {
            DiagnosticLog.important(
                "AlbumArtCache",
                "lazy-repair source-changed song=${managed.songId} content=${managed.contentKey.takeLast(12)}",
            )
            return false
        }

        val restored = AlbumArtCache.storeEmbeddedPicture(context, bytes)
        if (restored.nameWithoutExtension != managed.contentKey) return false
        AlbumArtCache.trimToBudget(context, protectedFile = restored)
        runCatching {
            AlbumArtCache.storeThumbnailFromOriginalBytes(context, managed.contentKey, bytes)
        }
        AlbumArtCache.clearManagedArtworkFailureState(managed.contentKey)
        return true
    }

    private suspend fun restoreThumbnailNow(
        context: Context,
        uriString: String,
        managed: AlbumArtCache.ManagedArtwork,
    ): Boolean {
        val bytes = runCatching { embeddedArtworkLoader(context, managed.songId) }.getOrNull()
            ?: return false

        if (AlbumArtCache.contentKeyFor(bytes) != managed.contentKey) {
            DiagnosticLog.important(
                "AlbumArtCache",
                "thumb-repair source-changed song=${managed.songId} content=${managed.contentKey.takeLast(12)}",
            )
            return false
        }

        val thumb = AlbumArtCache.storeThumbnailFromOriginalBytes(context, managed.contentKey, bytes)
            ?: return false
        AlbumArtCache.clearManagedArtworkFailureState(managed.contentKey)
        return thumb.isFile && thumb.length() > 0L
    }

    private suspend fun verifyAndRepair(
        context: Context,
        uriString: String,
        managed: AlbumArtCache.ManagedArtwork,
    ): Boolean {
        val origFile = AlbumArtCache.fileForManagedArtwork(context, uriString)
        val thumbFile = AlbumArtCache.thumbFileForManagedArtwork(context, uriString)
        val origPresent = origFile != null && origFile.isFile && origFile.length() > 0L
        val thumbPresent = thumbFile != null && thumbFile.isFile && thumbFile.length() > 0L

        val origCorrupt = origPresent && !AlbumArtCache.managedArtworkFileIsValid(context, uriString)
        val thumbCorrupt = thumbPresent && !AlbumArtCache.isThumbnailFileDecodable(context, uriString)

        if (!origCorrupt && !thumbCorrupt && (origPresent || thumbPresent)) {
            AlbumArtCache.clearManagedArtworkFailureState(managed.contentKey)
            return false
        }

        if (origCorrupt || thumbCorrupt) {
            AlbumArtCache.markManagedArtworkCorrupt(managed.contentKey)
            AlbumArtCache.deleteManagedArtworkFiles(context, managed.contentKey)
            DiagnosticLog.important(
                "AlbumArtCache",
                "lazy-verify corrupt origCorrupt=$origCorrupt thumbCorrupt=$thumbCorrupt " +
                    "song=${managed.songId} content=${managed.contentKey.takeLast(12)}",
            )
        } else {
            DiagnosticLog.important(
                "AlbumArtCache",
                "lazy-verify missing song=${managed.songId} content=${managed.contentKey.takeLast(12)}",
            )
        }

        val repaired = restoreOriginalNow(context, uriString, managed)
        if (!repaired) {
            // Corrupt files were already deleted above; clear transient failure marker so later
            // attempts are not permanently locked out for the rest of the process lifetime.
            AlbumArtCache.clearManagedArtworkFailureState(managed.contentKey)
        }
        DiagnosticLog.event(
            "AlbumArtCache",
            "lazy-repair result=$repaired song=${managed.songId} content=${managed.contentKey.takeLast(12)}",
        )
        return repaired
    }
}
