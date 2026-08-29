package com.mica.music.media

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class PlaybackOutputAvailability {
    INACTIVE,
    STABLE,
    SWITCHING,
    WAITING_FOR_PERMISSION,
    WAITING_FOR_DEVICE,
    RECONNECT_REQUIRED,
    FAILED,
}

data class PlaybackOutputStatus(
    val revision: Long = 0L,
    val availability: PlaybackOutputAvailability = PlaybackOutputAvailability.INACTIVE,
    val pendingPlayIntent: Boolean = false,
    val failureMessage: String? = null,
)

/**
 * Process-local, read-only projection of output switching state.
 *
 * Each coordinator receives a unique publisher identity. Opening a newer publisher supersedes every
 * older one, so delayed callbacks from a retired service/coordinator cannot overwrite current UI
 * state. [revision] is monitor-owned and monotonically increases across coordinator generations.
 */
object PlaybackOutputStatusMonitor {
    private val lock = Any()
    private val mutableStatus = MutableStateFlow(PlaybackOutputStatus())
    val status: StateFlow<PlaybackOutputStatus> = mutableStatus.asStateFlow()

    private var nextPublisherId: Long = 0L
    private var activePublisherId: Long? = null
    private var revision: Long = 0L

    internal fun openPublisher(): PlaybackOutputStatusPublisher = synchronized(lock) {
        val publisherId = ++nextPublisherId
        activePublisherId = publisherId
        publishLocked(
            availability = PlaybackOutputAvailability.INACTIVE,
            pendingPlayIntent = false,
            failureMessage = null,
        )
        PlaybackOutputStatusPublisher(publisherId)
    }

    internal fun publish(
        publisherId: Long,
        availability: PlaybackOutputAvailability,
        pendingPlayIntent: Boolean,
        failureMessage: String?,
    ) = synchronized(lock) {
        if (activePublisherId != publisherId) return@synchronized
        publishLocked(availability, pendingPlayIntent, failureMessage)
    }

    internal fun close(publisherId: Long) = synchronized(lock) {
        if (activePublisherId != publisherId) return@synchronized
        activePublisherId = null
        publishLocked(
            availability = PlaybackOutputAvailability.INACTIVE,
            pendingPlayIntent = false,
            failureMessage = null,
        )
    }

    private fun publishLocked(
        availability: PlaybackOutputAvailability,
        pendingPlayIntent: Boolean,
        failureMessage: String?,
    ) {
        revision += 1L
        mutableStatus.value = PlaybackOutputStatus(
            revision = revision,
            availability = availability,
            pendingPlayIntent = pendingPlayIntent,
            failureMessage = failureMessage,
        )
    }
}

internal class PlaybackOutputStatusPublisher(
    private val publisherId: Long,
) : AutoCloseable {
    fun publish(
        availability: PlaybackOutputAvailability,
        pendingPlayIntent: Boolean,
        failureMessage: String? = null,
    ) {
        PlaybackOutputStatusMonitor.publish(
            publisherId = publisherId,
            availability = availability,
            pendingPlayIntent = pendingPlayIntent,
            failureMessage = failureMessage,
        )
    }

    override fun close() {
        PlaybackOutputStatusMonitor.close(publisherId)
    }
}
