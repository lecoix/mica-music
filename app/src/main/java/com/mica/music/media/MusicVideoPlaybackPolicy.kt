package com.mica.music.media

import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.Player

internal object MusicVideoPlaybackPolicyCodec {
    private const val ENABLED_EXTRA = "mica.musicVideoEnabledForSource"
    private const val FALLBACK_REVISION_EXTRA = "mica.musicVideoFallbackRevision"

    fun isEnabled(mediaItem: MediaItem): Boolean =
        mediaItem.mediaMetadata.extras?.getBoolean(ENABLED_EXTRA, false) == true &&
            fallbackRevision(mediaItem).isNullOrBlank()

    fun withEnabled(mediaItem: MediaItem, enabled: Boolean): MediaItem {
        val oldExtras = mediaItem.mediaMetadata.extras
        if (oldExtras?.containsKey(ENABLED_EXTRA) == true && oldExtras.getBoolean(ENABLED_EXTRA) == enabled) {
            return mediaItem
        }
        val extras = Bundle(oldExtras ?: Bundle()).apply { putBoolean(ENABLED_EXTRA, enabled) }
        return mediaItem.buildUpon()
            .setMediaMetadata(mediaItem.mediaMetadata.buildUpon().setExtras(extras).build())
            .build()
    }

    fun afterFailure(mediaItem: MediaItem, revision: String): MediaItem {
        val disabled = withEnabled(mediaItem, false)
        val extras = Bundle(disabled.mediaMetadata.extras ?: Bundle()).apply {
            putString(FALLBACK_REVISION_EXTRA, revision)
        }
        return disabled.buildUpon()
            .setMediaMetadata(disabled.mediaMetadata.buildUpon().setExtras(extras).build())
            .build()
    }

    fun fallbackRevision(mediaItem: MediaItem): String? =
        mediaItem.mediaMetadata.extras?.getString(FALLBACK_REVISION_EXTRA)
}

internal class MusicVideoFailureRegistry {
    private val failed = linkedSetOf<String>()

    fun tripIfFirst(songId: String, revision: String): Boolean =
        revision.isNotBlank() && failed.add(key(songId, revision))

    fun isFailed(songId: String, revision: String): Boolean =
        revision.isNotBlank() && key(songId, revision) in failed

    private fun key(songId: String, revision: String): String = "$songId\u0001$revision"
}

/** Service-main-thread owner for the requested setting and per-queue-item effective policy. */
internal class MusicVideoPreferenceOwner(initialRequested: Boolean) {
    private var requested = initialRequested
    private var generation = 0L
    private var player: Player? = null
    private val listener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            refreshNonCurrent(generation)
        }
    }

    fun attach(player: Player) {
        if (this.player === player) return
        this.player?.removeListener(listener)
        this.player = player
        player.addListener(listener)
    }

    fun releasePlayer(player: Player? = this.player) {
        if (player != null && this.player === player) {
            player.removeListener(listener)
            this.player = null
        }
    }

    fun decorateNew(mediaItem: MediaItem): MediaItem =
        MusicVideoPlaybackPolicyCodec.withEnabled(mediaItem, requested)

    fun decorateNew(mediaItems: List<MediaItem>): List<MediaItem> = mediaItems.map(::decorateNew)

    fun updateRequested(enabled: Boolean) {
        if (requested == enabled) return
        requested = enabled
        generation++
        refreshNonCurrent(generation)
    }

    internal fun refreshNonCurrent(expectedGeneration: Long) {
        if (expectedGeneration != generation) return
        val target = player ?: return
        val currentIndex = target.currentMediaItemIndex
        val count = target.mediaItemCount
        for (index in 0 until count) {
            if (expectedGeneration != generation) return
            if (index == currentIndex) continue
            val existing = target.getMediaItemAt(index)
            val updated = MusicVideoPlaybackPolicyCodec.withEnabled(existing, requested)
            if (updated !== existing) target.replaceMediaItem(index, updated)
        }
    }

    internal fun generationForTests(): Long = generation
}
