package com.mica.music.media

import android.os.Bundle
import androidx.media3.session.SessionCommand
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong

internal data class PlaybackShuffleRequest(
    val enabled: Boolean,
    val seed: Long?,
    val playbackIndices: List<Int>? = null,
    val sourceIndices: List<Int>? = null,
    val physicalFingerprint: String? = null,
    val requestId: Long = 0L,
)

internal object PlaybackShuffleSessionCommand {
    const val ACTION = "com.mica.music.action.SET_APP_SHUFFLE"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_SEED = "seed"
    private const val KEY_HAS_SEED = "has_seed"
    private const val KEY_ORDER = "order"
    private const val KEY_SOURCE = "source"
    private const val KEY_FINGERPRINT = "physical_fingerprint"
    private const val KEY_REQUEST = "request"
    const val KEY_QUERY = "query"
    // App and service run in the same process. A queue intent invalidates already posted commands,
    // including A -> B -> A where physical membership alone cannot prove request validity.
    private val requests = AtomicLong()
    fun invalidateRequests(): Long = requests.incrementAndGet()
    fun isCurrent(requestId: Long): Boolean = requestId == requests.get()

    val command: SessionCommand
        get() = SessionCommand(ACTION, Bundle.EMPTY)

    fun encode(enabled: Boolean, seed: Long?): Bundle = Bundle().apply {
        putBoolean(KEY_ENABLED, enabled)
        putBoolean(KEY_HAS_SEED, seed != null)
        if (seed != null) putLong(KEY_SEED, seed)
    }

    fun fingerprint(ids: List<String>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        ids.forEach { id ->
            val bytes = id.toByteArray(Charsets.UTF_8)
            digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.size).array())
            digest.update(bytes)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun encodeOrder(
        physicalIds: List<String>, playbackIds: List<String>, sourceIds: List<String>,
        enabled: Boolean, requestId: Long,
    ): Bundle? {
        val indices = physicalIds.withIndex().associate { it.value to it.index }
        if (indices.size != physicalIds.size || playbackIds.size != physicalIds.size ||
            sourceIds.size != physicalIds.size || playbackIds.toSet() != indices.keys ||
            sourceIds.toSet() != indices.keys) return null
        return encode(enabled, null).apply {
            putIntArray(KEY_ORDER, playbackIds.map(indices::getValue).toIntArray())
            putIntArray(KEY_SOURCE, sourceIds.map(indices::getValue).toIntArray())
            putString(KEY_FINGERPRINT, fingerprint(physicalIds))
            putLong(KEY_REQUEST, requestId)
        }
    }

    fun decode(command: SessionCommand, args: Bundle): PlaybackShuffleRequest? {
        if (command.customAction != ACTION) return null
        val enabled = args.getBoolean(KEY_ENABLED, false)
        val seed = args.getLong(KEY_SEED).takeIf { args.getBoolean(KEY_HAS_SEED, false) }
        val order = args.getIntArray(KEY_ORDER)?.toList()
        val source = args.getIntArray(KEY_SOURCE)?.toList()
        if (order != null) {
            if (source == null || order.size != source.size ||
                order.toSet() != order.indices.toSet() || source.toSet() != order.indices.toSet() ||
                args.getString(KEY_FINGERPRINT) == null) return null
        } else if (enabled && seed == null) return null
        return PlaybackShuffleRequest(enabled, seed, order, source,
            args.getString(KEY_FINGERPRINT), args.getLong(KEY_REQUEST))
    }
}
