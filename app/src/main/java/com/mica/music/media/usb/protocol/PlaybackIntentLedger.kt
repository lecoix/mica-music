package com.mica.music.media.usb.protocol

enum class PlaybackIntent {
    PLAY,
    PAUSE,
}

data class IntentRevision(val value: Long)

data class IntentSnapshot(
    val revision: IntentRevision,
    val desired: PlaybackIntent,
)

/**
 * Service/product-lifetime semantic PLAY/PAUSE authority.
 *
 * Repeated publication of the already-current semantic intent is idempotent. Only semantic edges
 * mint a newer revision. Technical Media3 execution state never belongs here.
 */
class PlaybackIntentLedger(
    initial: PlaybackIntent = PlaybackIntent.PAUSE,
) {
    private var revision = 0L
    private var desired = initial

    @Synchronized
    fun publish(next: PlaybackIntent): IntentSnapshot {
        if (next != desired) {
            revision++
            desired = next
        }
        return IntentSnapshot(IntentRevision(revision), desired)
    }

    @Synchronized
    fun snapshot(): IntentSnapshot = IntentSnapshot(IntentRevision(revision), desired)
}
