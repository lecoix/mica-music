package com.mica.music.data

import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Owns the generation and serialization protocol for custom-wallpaper files and
 * the published wallpaper path. Candidate files are private to one request;
 * only the latest request may commit or publish them.
 */
internal class AppWallpaperStore(
    private val directory: File,
    private val publishPath: (String?) -> Unit,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val idProvider: () -> String = { UUID.randomUUID().toString() },
) {
    enum class ReplaceResult {
        PREPARED,
        APPLIED,
        SUPERSEDED,
        PREPARE_FAILED,
        COMMIT_FAILED,
    }

    class PreparedWallpaper internal constructor(
        internal val requestId: Long,
        internal val candidate: File,
        internal val finalFile: File,
    ) {
        val previewPath: String get() = candidate.absolutePath
    }

    data class PrepareOutcome(
        val result: ReplaceResult,
        val wallpaper: PreparedWallpaper? = null,
    )

    private val generationLock = Any()
    private val storeSyncMutex = Mutex()
    private var generation: Long = 0L

    suspend fun prepare(prepareCandidate: suspend (File) -> Boolean): PrepareOutcome {
        val requestId = newRequest()
        val fileId = idProvider()
        val candidate = File(directory, ".wallpaper-$fileId.pending")
        val finalFile = File(directory, "wallpaper-$fileId.jpg")

        val prepared = try {
            withContext(ioDispatcher) {
                if (!directory.exists() && !directory.mkdirs()) return@withContext false
                prepareCandidate(candidate)
            }
        } catch (cancellation: CancellationException) {
            deleteOwnedFile(candidate)
            throw cancellation
        } catch (_: Exception) {
            deleteOwnedFile(candidate)
            return PrepareOutcome(ReplaceResult.PREPARE_FAILED)
        }
        if (!prepared) {
            deleteOwnedFile(candidate)
            return PrepareOutcome(ReplaceResult.PREPARE_FAILED)
        }
        if (!isCurrent(requestId)) {
            deleteOwnedFile(candidate)
            return PrepareOutcome(ReplaceResult.SUPERSEDED)
        }

        return PrepareOutcome(
            result = ReplaceResult.PREPARED,
            wallpaper = PreparedWallpaper(requestId, candidate, finalFile),
        )
    }

    suspend fun commit(
        prepared: PreparedWallpaper,
        publish: (String) -> Unit = { path -> publishPath(path) },
    ): ReplaceResult {
        val requestId = prepared.requestId
        val candidate = prepared.candidate
        val finalFile = prepared.finalFile

        return storeSyncMutex.withLock {
            if (!isCurrent(requestId)) {
                deleteOwnedFile(candidate)
                return@withLock ReplaceResult.SUPERSEDED
            }

            val committed = withContext(ioDispatcher) {
                commitIfCurrent(requestId, candidate, finalFile)
            }
            if (!committed) {
                deleteOwnedFile(candidate)
                deleteOwnedFile(finalFile)
                return@withLock if (isCurrent(requestId)) {
                    ReplaceResult.COMMIT_FAILED
                } else {
                    ReplaceResult.SUPERSEDED
                }
            }

            val published = publishIfCurrent(requestId) {
                publish(finalFile.absolutePath)
            }
            if (!published) {
                deleteOwnedFile(finalFile)
                return@withLock ReplaceResult.SUPERSEDED
            }

            withContext(ioDispatcher) {
                cleanupDirectoryIfCurrent(requestId, keep = finalFile)
            }
            ReplaceResult.APPLIED
        }
    }

    suspend fun discard(prepared: PreparedWallpaper) {
        synchronized(generationLock) {
            if (generation == prepared.requestId) generation += 1L
        }
        deleteOwnedFile(prepared.candidate)
        deleteOwnedFile(prepared.finalFile)
    }

    suspend fun replace(prepareCandidate: suspend (File) -> Boolean): ReplaceResult {
        val outcome = prepare(prepareCandidate)
        val prepared = outcome.wallpaper ?: return outcome.result
        return commit(prepared)
    }

    suspend fun clear() {
        val requestId = newRequest()
        storeSyncMutex.withLock {
            if (!publishIfCurrent(requestId) { publishPath(null) }) return@withLock
            withContext(ioDispatcher) {
                cleanupDirectoryIfCurrent(requestId, keep = null)
            }
        }
    }

    private fun newRequest(): Long = synchronized(generationLock) {
        generation += 1L
        generation
    }

    private fun isCurrent(requestId: Long): Boolean = synchronized(generationLock) {
        generation == requestId
    }

    private fun publishIfCurrent(requestId: Long, publish: () -> Unit): Boolean = synchronized(generationLock) {
        if (generation != requestId) return@synchronized false
        publish()
        true
    }

    private fun commitIfCurrent(requestId: Long, candidate: File, finalFile: File): Boolean =
        synchronized(generationLock) {
            if (generation != requestId) return@synchronized false
            runCatching {
                if (candidate.renameTo(finalFile)) {
                    true
                } else {
                    candidate.copyTo(finalFile, overwrite = true)
                    candidate.delete()
                    true
                }
            }.getOrElse {
                finalFile.delete()
                false
            }
        }

    private fun cleanupDirectoryIfCurrent(requestId: Long, keep: File?) = synchronized(generationLock) {
        if (generation != requestId) return@synchronized
        directory.listFiles()?.forEach { file ->
            if (file != keep) file.delete()
        }
    }

    private suspend fun deleteOwnedFile(file: File) {
        withContext(ioDispatcher + NonCancellable) {
            file.delete()
        }
    }
}
