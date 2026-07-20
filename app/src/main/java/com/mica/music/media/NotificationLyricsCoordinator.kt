package com.mica.music.media

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.mica.music.data.LyricsDocument
import com.mica.music.data.LyricsSession
import com.mica.music.data.SharedLyricsMemoryCache
import com.mica.music.data.Song
import com.mica.music.data.local.LibraryRepository
import com.mica.music.data.preferences.LibraryScanSettings
import com.mica.music.data.preferences.LyricsPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Updates notification metadata at lyric boundaries. Playback, retry and watchdog deadlines share
 * one replaceable Handler callback, so the coordinator never owns more than one scheduled wake-up.
 */
@UnstableApi
internal class NotificationLyricsCoordinator(
    private val context: Context,
    private val player: Player,
    handler: Handler,
    private val songLoader: suspend (LyricsLoadSpec) -> Song? = { spec ->
        LibraryRepository(context.applicationContext).songById(spec.songId, spec.priority)
    },
) {
    private val appContext = context.applicationContext
    private val playerHandler = if (handler.looper == player.applicationLooper) {
        handler
    } else {
        Handler(player.applicationLooper)
    }
    private val lyricsScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val songCache = NotificationLyricsSongCache(
        scope = lyricsScope,
        handler = playerHandler,
        loadSong = songLoader,
    )

    private var started = false
    private var released = false
    private var syncing = false
    private var generation = 0L
    private var overlaySequence = 0L
    private var trackedSongId: String? = null

    private var activeSpec: LyricsLoadSpec? = null
    private var activeDocument: LyricsDocument? = null
    private var pendingSpec: LyricsLoadSpec? = null
    private var failedSpec: LyricsLoadSpec? = null
    private var loadFailureCount = 0
    private var retryAtRealtimeMs: Long? = null
    private var forceReload = false

    private var sessionDocument: LyricsDocument? = null
    private var lyricsSession: LyricsSession? = null
    private var lineStartTimesMs = IntArray(0)

    private var lastPublishedIndex: Int? = null
    private var lastPublishedRealtimeMs: Long? = null
    private var lastSignature: String? = null
    private var lastOverlayToken: String? = null

    private var unregisterPreferenceListener: (() -> Unit)? = null
    private var invalidationJob: Job? = null

    private val wakeUp = Runnable {
        if (!released) reconcile()
    }

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (released || syncing) return
            val schedulingEvent = RECONCILE_EVENTS.any(events::contains)
            if (!schedulingEvent) return

            if (events.contains(Player.EVENT_MEDIA_METADATA_CHANGED)) {
                val currentToken = player.currentMediaItem?.mediaMetadata?.let(NotificationLyrics::overlayToken)
                val selfWrite = currentToken != null && currentToken == lastOverlayToken
                val hasOtherSchedulingEvent = RECONCILE_EVENTS
                    .asSequence()
                    .filter { it != Player.EVENT_MEDIA_METADATA_CHANGED }
                    .any(events::contains)
                if (selfWrite && !hasOtherSchedulingEvent) return
                if (!selfWrite) {
                    lastPublishedIndex = null
                    lastSignature = null
                }
            }
            reconcile()
        }
    }

    fun start() = onPlayerLooper {
        if (started || released) return@onPlayerLooper
        started = true
        player.addListener(listener)
        unregisterPreferenceListener = LyricsPreferences.registerNotificationLyricsChangeListener(appContext) { change ->
            playerHandler.post {
                if (released) return@post
                when (change) {
                    LyricsPreferences.NotificationLyricsChange.DISPLAY -> {
                        lastPublishedIndex = null
                        lastSignature = null
                    }
                    LyricsPreferences.NotificationLyricsChange.SOURCE -> resetPendingLoad()
                    LyricsPreferences.NotificationLyricsChange.ENABLED -> Unit
                }
                reconcile()
            }
        }
        invalidationJob = lyricsScope.launch {
            SharedLyricsMemoryCache.invalidations.collect { songIds ->
                if (trackedSongId in songIds) {
                    playerHandler.post {
                        if (!released && trackedSongId in songIds) {
                            resetPendingLoad()
                            reconcile()
                        }
                    }
                }
            }
        }
        reconcile()
    }

    fun release() = onPlayerLooper {
        if (released) return@onPlayerLooper
        released = true
        generation += 1
        playerHandler.removeCallbacks(wakeUp)
        if (started) player.removeListener(listener)
        unregisterPreferenceListener?.invoke()
        unregisterPreferenceListener = null
        invalidationJob?.cancel()
        invalidationJob = null
        lyricsScope.cancel()
        songCache.clear()
    }

    private fun reconcile() {
        if (released || !started || Looper.myLooper() != player.applicationLooper) {
            if (!released && started) playerHandler.post(wakeUp)
            return
        }
        playerHandler.removeCallbacks(wakeUp)
        val nowRealtimeMs = SystemClock.elapsedRealtime()
        val item = player.currentMediaItem
        val decoded = item?.let(SongMediaItemCodec::decode)
        if (item == null || decoded == null) {
            resetForSong(null)
            return
        }
        if (trackedSongId != decoded.id) resetForSong(decoded.id)

        if (!LyricsPreferences.notificationLyricsEnabled(appContext)) {
            restoreDefaultMetadataIfNeeded(decoded, item)
            return
        }

        val priority = LyricsPreferences.lyricsSlotPriority(appContext).toList()
        val priorityRevision = priority.joinToString(",") { it.name }
        val spec = LyricsLoadSpec(
            songId = decoded.id,
            lyricsRevision = "${SongMediaItemCodec.lyricsRevision(item)}:$priorityRevision",
            lyricsDataVersion = LibraryScanSettings.lyricsParserVersion(appContext),
            priority = priority,
        )
        ensureLyrics(decoded, spec, nowRealtimeMs)

        val document = activeDocument.takeIf { activeSpec?.songId == decoded.id }
        var plannedWakeInMs: Long? = null
        if (document != null) {
            val session = sessionFor(document)
            val plan = NotificationLyricsBoundaryPlanner.plan(
                lineStartTimesMs = lineStartTimesMs,
                positionMs = player.currentPosition.coerceAtLeast(0L),
                playbackSpeed = player.playbackParameters.speed,
                isAdvancing = player.isPlaying,
                publishedIndex = lastPublishedIndex,
                nowRealtimeMs = nowRealtimeMs,
                lastPublishedRealtimeMs = lastPublishedRealtimeMs,
            )
            plan.publishIndex?.let { index ->
                publish(decoded, item, activeSpec ?: spec, session, index, nowRealtimeMs)
            }
            plannedWakeInMs = plan.wakeInMs
        } else {
            restoreDefaultMetadataIfNeeded(decoded, item)
        }

        val retryWakeInMs = retryAtRealtimeMs
            ?.takeIf { it != Long.MAX_VALUE }
            ?.let { (it - nowRealtimeMs).coerceAtLeast(1L) }
        val watchdogWakeInMs = WATCHDOG_MS.takeIf {
            player.isPlaying || pendingSpec != null || retryWakeInMs != null
        }
        scheduleEarliest(plannedWakeInMs, retryWakeInMs, watchdogWakeInMs)
    }

    private fun ensureLyrics(decoded: Song, spec: LyricsLoadSpec, nowRealtimeMs: Long) {
        if (!forceReload && activeSpec == spec && activeDocument != null) return
        if (pendingSpec == spec) return
        if (failedSpec == spec) {
            val retryAt = retryAtRealtimeMs ?: Long.MAX_VALUE
            if (nowRealtimeMs < retryAt) return
        } else {
            failedSpec = null
            loadFailureCount = 0
            retryAtRealtimeMs = null
        }

        pendingSpec = spec
        forceReload = false
        retryAtRealtimeMs = null
        val requestGeneration = ++generation
        when (val state = songCache.request(decoded, spec) { result ->
            if (released || requestGeneration != generation || pendingSpec != spec) return@request
            pendingSpec = null
            when (result) {
                is NotificationLyricsLoadState.Ready -> acceptDocument(spec, result.song.lyricsDocument)
                NotificationLyricsLoadState.Absent -> acceptAbsent(spec)
                is NotificationLyricsLoadState.Failed -> recordLoadFailure(spec)
                NotificationLyricsLoadState.Loading -> Unit
            }
            reconcile()
        }) {
            is NotificationLyricsLoadState.Ready -> {
                pendingSpec = null
                acceptDocument(spec, state.song.lyricsDocument)
            }
            NotificationLyricsLoadState.Absent -> {
                pendingSpec = null
                acceptAbsent(spec)
            }
            is NotificationLyricsLoadState.Failed -> {
                pendingSpec = null
                recordLoadFailure(spec)
            }
            NotificationLyricsLoadState.Loading -> Unit
        }
    }

    private fun acceptDocument(spec: LyricsLoadSpec, document: LyricsDocument) {
        if (trackedSongId != spec.songId) return
        activeSpec = spec
        activeDocument = document
        failedSpec = null
        loadFailureCount = 0
        retryAtRealtimeMs = null
        lastPublishedIndex = null
        lastSignature = null
    }

    private fun acceptAbsent(spec: LyricsLoadSpec) {
        if (trackedSongId != spec.songId) return
        activeSpec = spec
        activeDocument = null
        sessionDocument = null
        lyricsSession = null
        lineStartTimesMs = IntArray(0)
        failedSpec = null
        loadFailureCount = 0
        retryAtRealtimeMs = null
        lastPublishedIndex = null
        lastSignature = null
    }

    private fun recordLoadFailure(spec: LyricsLoadSpec) {
        failedSpec = spec
        loadFailureCount += 1
        val delayMs = when (loadFailureCount) {
            1 -> FIRST_RETRY_MS
            2 -> SECOND_RETRY_MS
            else -> Long.MAX_VALUE
        }
        retryAtRealtimeMs = if (delayMs == Long.MAX_VALUE) {
            Long.MAX_VALUE
        } else {
            SystemClock.elapsedRealtime() + delayMs
        }
    }

    private fun publish(
        song: Song,
        item: MediaItem,
        spec: LyricsLoadSpec,
        session: LyricsSession,
        index: Int,
        nowRealtimeMs: Long,
    ) {
        val display = NotificationLyrics.displayOptions(appContext)
        val displayLine = NotificationLyrics.lyricLineText(session.lyrics, index, display)
        if (displayLine == null) {
            // Preserve phase-one behavior: a blank line keeps the previous notification lyric.
            lastPublishedIndex = index
            return
        }
        val inputRevision = listOf(
            SongMediaItemCodec.metadataRevision(item).orEmpty(),
            spec.lyricsRevision,
            spec.lyricsDataVersion.toString(),
            display.splitEnabled.toString(),
            display.bilingualMode.name,
        ).joinToString("|")
        val signature = NotificationLyrics.signature(song.id, index, "$inputRevision|$displayLine")
        if (signature == lastSignature) {
            lastPublishedIndex = index
            return
        }
        val currentMetadata = item.mediaMetadata
        val visibleMetadataAlreadyMatches =
            NotificationLyrics.overlayToken(currentMetadata) != null &&
                currentMetadata.title?.toString() == displayLine &&
                currentMetadata.displayTitle?.toString() == displayLine &&
                currentMetadata.artist?.toString() == NotificationLyrics.subtitle(song.title, song.artist)
        if (visibleMetadataAlreadyMatches) {
            // Preserve the existing repeated-text behavior: advance logically without a system write.
            lastPublishedIndex = index
            lastSignature = signature
            return
        }

        val token = "${System.identityHashCode(this)}:${++overlaySequence}"
        val metadata = NotificationLyrics.metadataWithLyric(
            song = song,
            line = displayLine,
            base = item.mediaMetadata,
            overlayToken = token,
        ) ?: return
        if (!metadataMatches(item.mediaMetadata, metadata)) {
            replaceCurrentItem(item, metadata)
        }
        lastPublishedIndex = index
        lastPublishedRealtimeMs = nowRealtimeMs
        lastSignature = signature
        lastOverlayToken = token
    }

    private fun restoreDefaultMetadataIfNeeded(song: Song, item: MediaItem) {
        if (NotificationLyrics.overlayToken(item.mediaMetadata) == null && lastSignature == null) return
        val metadata = NotificationLyrics.defaultPlaybackMetadata(song, item.mediaMetadata)
        if (!metadataMatches(item.mediaMetadata, metadata)) replaceCurrentItem(item, metadata)
        lastPublishedIndex = null
        lastPublishedRealtimeMs = null
        lastSignature = null
        lastOverlayToken = null
    }

    private fun replaceCurrentItem(item: MediaItem, metadata: MediaMetadata) {
        if (player.currentMediaItem?.mediaId != item.mediaId) return
        val itemIndex = player.currentMediaItemIndex
        if (itemIndex < 0) return
        syncing = true
        try {
            player.replaceMediaItem(itemIndex, item.buildUpon().setMediaMetadata(metadata).build())
        } finally {
            syncing = false
        }
    }

    private fun sessionFor(document: LyricsDocument): LyricsSession {
        if (sessionDocument !== document) {
            sessionDocument = document
            lyricsSession = LyricsSession(document)
            lineStartTimesMs = document.lines.map { it.startMs }.toIntArray()
        }
        return checkNotNull(lyricsSession)
    }

    private fun resetPendingLoad() {
        generation += 1
        pendingSpec = null
        failedSpec = null
        loadFailureCount = 0
        retryAtRealtimeMs = null
        forceReload = true
    }

    private fun resetForSong(songId: String?) {
        generation += 1
        trackedSongId = songId
        activeSpec = null
        activeDocument = null
        pendingSpec = null
        failedSpec = null
        loadFailureCount = 0
        retryAtRealtimeMs = null
        forceReload = false
        sessionDocument = null
        lyricsSession = null
        lineStartTimesMs = IntArray(0)
        lastPublishedIndex = null
        lastPublishedRealtimeMs = null
        lastSignature = null
        lastOverlayToken = null
    }

    private fun metadataMatches(current: MediaMetadata, target: MediaMetadata): Boolean =
        current.title?.toString() == target.title?.toString() &&
            current.displayTitle?.toString() == target.displayTitle?.toString() &&
            current.artist?.toString() == target.artist?.toString() &&
            SongMediaItemCodec.metadataRevisionFromMetadata(current) ==
            SongMediaItemCodec.metadataRevisionFromMetadata(target) &&
            NotificationLyrics.overlayToken(current) == NotificationLyrics.overlayToken(target)

    private fun scheduleEarliest(vararg delaysMs: Long?) {
        val delayMs = delaysMs.filterNotNull().minOrNull() ?: return
        playerHandler.postDelayed(wakeUp, delayMs.coerceAtLeast(1L))
    }

    private inline fun onPlayerLooper(crossinline block: () -> Unit) {
        if (Looper.myLooper() == player.applicationLooper) block() else playerHandler.post { block() }
    }

    private companion object {
        const val FIRST_RETRY_MS = 5_000L
        const val SECOND_RETRY_MS = 30_000L
        const val WATCHDOG_MS = 30_000L

        val RECONCILE_EVENTS = intArrayOf(
            Player.EVENT_MEDIA_ITEM_TRANSITION,
            Player.EVENT_POSITION_DISCONTINUITY,
            Player.EVENT_PLAYBACK_PARAMETERS_CHANGED,
            Player.EVENT_IS_PLAYING_CHANGED,
            Player.EVENT_PLAY_WHEN_READY_CHANGED,
            Player.EVENT_PLAYBACK_STATE_CHANGED,
            Player.EVENT_PLAYBACK_SUPPRESSION_REASON_CHANGED,
            Player.EVENT_PLAYER_ERROR,
            Player.EVENT_MEDIA_METADATA_CHANGED,
            Player.EVENT_REPEAT_MODE_CHANGED,
        )
    }
}
