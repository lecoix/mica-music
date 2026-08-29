package com.mica.music.playback

enum class PlaybackVideoStatus {
    UNAVAILABLE,
    LOADING,
    READY,
    FAILED,
}

data class PlaybackVideoState(
    val mediaId: String? = null,
    val effective: Boolean = false,
    val status: PlaybackVideoStatus = PlaybackVideoStatus.UNAVAILABLE,
    val width: Int = 0,
    val height: Int = 0,
    val pixelWidthHeightRatio: Float = 1f,
    val firstFrameRevision: Long = 0L,
    /** Changes whenever the output must be reacquired (controller/session boundary). */
    val surfaceGeneration: Long = 0L,
)

/**
 * Projects coordinator-owned video state onto the current queue item.
 *
 * Frame readiness and dimensions are media-specific and must reset when the media changes.
 * Surface generation is coordinator-global: dropping it makes the host acquire a lease from
 * an older generation and creates a dispose/attach feedback loop.
 */
internal fun projectMusicVideoState(
    currentMediaId: String?,
    effective: Boolean,
    rawState: PlaybackVideoState,
): PlaybackVideoState =
    if (effective && rawState.mediaId == currentMediaId) {
        rawState.copy(effective = true)
    } else {
        PlaybackVideoState(
            mediaId = currentMediaId,
            effective = effective,
            surfaceGeneration = rawState.surfaceGeneration,
        )
    }

internal interface MusicVideoOutputLeasePort {
    val outputIdentity: Any
    val controllerIdentity: Any
    val mediaId: String
    fun attach()
    fun detach()
    /** Rebind this lease to a newly-created playback controller, if supported. */
    fun rebind(controllerIdentity: Any): Boolean = false
}

/** Serializes ownership of the one real playback-video output. */
internal class MusicVideoOutputCoordinator(
    private val stateSink: (PlaybackVideoState) -> Unit,
) {
    private data class ActiveLease(val id: Long, val port: MusicVideoOutputLeasePort)

    private var nextLeaseId = 0L
    private var active: ActiveLease? = null
    private var firstFrameRevision = 0L
    private var surfaceGeneration = 0L
    private var currentState = PlaybackVideoState()

    fun attach(port: MusicVideoOutputLeasePort): Long {
        active?.let { existing ->
            if (existing.port.outputIdentity === port.outputIdentity &&
                existing.port.controllerIdentity === port.controllerIdentity &&
                existing.port.mediaId == port.mediaId
            ) {
                return existing.id
            }
        }
        active?.let { previous ->
            previous.port.detach()
        }
        val lease = ActiveLease(++nextLeaseId, port)
        active = lease
        port.attach()
        publish(
            PlaybackVideoState(
                mediaId = port.mediaId,
                effective = true,
                status = PlaybackVideoStatus.LOADING,
                firstFrameRevision = firstFrameRevision,
                surfaceGeneration = surfaceGeneration,
            ),
        )
        return lease.id
    }

    fun detach(leaseId: Long, outputIdentity: Any) {
        val lease = active ?: return
        if (lease.id != leaseId || lease.port.outputIdentity !== outputIdentity) {
            return
        }
        detachActive()
    }

    fun detachForMediaChange(mediaId: String?) {
        val lease = active ?: return
        if (lease.port.mediaId != mediaId) {
            detachActive()
        }
    }

    fun detachForController(controllerIdentity: Any) {
        val lease = active ?: return
        if (lease.port.controllerIdentity === controllerIdentity) {
            detachActive()
        }
    }

    fun reattachForPlaybackStack(controllerIdentity: Any, mediaId: String?) {
        val lease = active?.takeIf { it.port.mediaId == mediaId } ?: return
        if (lease.port.controllerIdentity !== controllerIdentity &&
            !lease.port.rebind(controllerIdentity)
        ) {
            return
        }
        // Invalidate delayed detach callbacks from the old playback stack before reusing
        // the same TextureView for the new controller.
        active = ActiveLease(++nextLeaseId, lease.port)
        surfaceGeneration++
        lease.port.attach()
        publish(
            currentState.copy(
                status = PlaybackVideoStatus.LOADING,
                firstFrameRevision = firstFrameRevision,
                surfaceGeneration = surfaceGeneration,
            ),
        )
    }

    fun onVideoSize(
        controllerIdentity: Any,
        mediaId: String?,
        width: Int,
        height: Int,
        pixelWidthHeightRatio: Float,
    ) {
        val lease = activeFor(controllerIdentity, mediaId) ?: return
        publish(
            currentState.copy(
                mediaId = lease.port.mediaId,
                effective = true,
                status = if (currentState.status == PlaybackVideoStatus.READY) {
                    PlaybackVideoStatus.READY
                } else {
                    PlaybackVideoStatus.LOADING
                },
                width = width.coerceAtLeast(0),
                height = height.coerceAtLeast(0),
                pixelWidthHeightRatio = pixelWidthHeightRatio.takeIf { it > 0f } ?: 1f,
                firstFrameRevision = firstFrameRevision,
            ),
        )
    }

    fun onFirstFrame(controllerIdentity: Any, mediaId: String?) {
        val lease = activeFor(controllerIdentity, mediaId) ?: return
        firstFrameRevision++
        publish(
            currentState.copy(
                mediaId = lease.port.mediaId,
                effective = true,
                status = PlaybackVideoStatus.READY,
                firstFrameRevision = firstFrameRevision,
            ),
        )
    }

    fun onFailure(controllerIdentity: Any, mediaId: String?) {
        val lease = activeFor(controllerIdentity, mediaId) ?: return
        lease.port.detach()
        active = null
        publish(
            currentState.copy(
                mediaId = lease.port.mediaId,
                effective = true,
                status = PlaybackVideoStatus.FAILED,
                firstFrameRevision = firstFrameRevision,
            ),
        )
    }

    fun release() = detachActive()

    private fun activeFor(controllerIdentity: Any, mediaId: String?): ActiveLease? =
        active?.takeIf { lease ->
            lease.port.controllerIdentity === controllerIdentity && lease.port.mediaId == mediaId
        }

    private fun detachActive() {
        val lease = active ?: return
        lease.port.detach()
        active = null
        surfaceGeneration++
        publish(
            currentState.copy(
                mediaId = lease.port.mediaId,
                effective = true,
                status = PlaybackVideoStatus.UNAVAILABLE,
                firstFrameRevision = firstFrameRevision,
                surfaceGeneration = surfaceGeneration,
            ),
        )
    }

    private fun publish(state: PlaybackVideoState) {
        currentState = state
        stateSink(state)
    }
}
