package com.mica.music.data

import android.content.Context
import com.mica.music.data.local.MicaDatabase
import com.mica.music.data.local.SongLyricsOffsetDao
import com.mica.music.data.local.SongLyricsOffsetEntity
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class SongLyricsOffsetChange(val songId: String)

/** Process owner for persisted library-song offsets and session-only transient-song offsets. */
class SongLyricsOffsetStore internal constructor(
    private val dao: SongLyricsOffsetDao,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private data class TransientOffset(val mediaUri: String, val offsetMs: Int)

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val writeMutex = Mutex()
    private val requestSequence = AtomicLong()
    private val latestRequestBySong = ConcurrentHashMap<String, Long>()
    private val transientOffsets = MutableStateFlow<Map<String, TransientOffset>>(emptyMap())
    private val _changes = MutableSharedFlow<SongLyricsOffsetChange>(extraBufferCapacity = 16)
    val changes = _changes.asSharedFlow()

    fun observe(song: Song): Flow<Int> = if (song.isTransient) {
        transientOffsets.map { values ->
            values[song.id]
                ?.takeIf { it.mediaUri == song.mediaUri }
                ?.offsetMs
                ?: 0
        }
    } else {
        dao.observe(song.id).map { entity -> validOffset(entity, song) }
    }.distinctUntilChanged()

    suspend fun offsetFor(song: Song): Int = if (song.isTransient) {
        transientOffsets.value[song.id]
            ?.takeIf { it.mediaUri == song.mediaUri }
            ?.offsetMs
            ?: 0
    } else {
        validOffset(dao.get(song.id), song)
    }

    /** Assigns the request revision before launching IO so rapid UI requests remain last-write-wins. */
    fun requestSet(song: Song, offsetMs: Int) {
        val normalized = LyricsTiming.normalizeLayer(offsetMs)
        val requestId = requestSequence.incrementAndGet()
        latestRequestBySong[song.id] = requestId
        scope.launch {
            writeMutex.withLock {
                if (latestRequestBySong[song.id] != requestId) return@withLock
                if (song.isTransient) {
                    transientOffsets.value = transientOffsets.value.toMutableMap().apply {
                        if (normalized == 0) remove(song.id)
                        else put(song.id, TransientOffset(song.mediaUri, normalized))
                    }
                } else if (normalized == 0) {
                    dao.delete(song.id)
                } else {
                    dao.upsert(SongLyricsOffsetEntity(song.id, song.mediaUri, normalized))
                }
                if (latestRequestBySong[song.id] != requestId) return@withLock
                _changes.tryEmit(SongLyricsOffsetChange(song.id))
            }
        }
    }

    companion object {
        @Volatile private var instance: SongLyricsOffsetStore? = null

        fun get(context: Context): SongLyricsOffsetStore = instance ?: synchronized(this) {
            instance ?: SongLyricsOffsetStore(MicaDatabase.get(context).songLyricsOffsetDao())
                .also { instance = it }
        }

        internal fun validOffset(entity: SongLyricsOffsetEntity?, song: Song): Int =
            entity
                ?.takeIf { it.songId == song.id && it.mediaUri == song.mediaUri }
                ?.offsetMs
                ?.let(LyricsTiming::normalizeLayer)
                ?: 0
    }
}
