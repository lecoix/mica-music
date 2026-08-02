package com.mica.music.data

/**
 * Synchronous song lookup used by the process-scoped playback facade.
 *
 * The implementation is independent from Activity and ViewModel lifecycles.
 */
fun interface PlaybackSongResolver {
    fun resolve(id: String): Song?
}

/**
 * Process-scoped resolver for the transient service queue mirror.
 *
 * Library resolution is deliberately supplied as a one-shot bootstrap dependency instead of
 * being retained here. This keeps the process-scoped player from retaining a whole library or a
 * ViewModel after the Activity lifecycle ends.
 */
class ProcessPlaybackSongResolver(
    private val transientPlaybackCatalog: TransientPlaybackCatalog,
) : PlaybackSongResolver {
    override fun resolve(id: String): Song? =
        transientPlaybackCatalog.songById(id)
}
