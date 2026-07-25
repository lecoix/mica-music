package com.mica.music.ui.screens.player

/**
 * Video cover hosts stay outside wipe *content* slots (those remount AndroidViews).
 *
 * Up to two pinned URIs:
 * - [incomingUri]: current/next track video; prepared during wipe with the incoming clip
 * - [outgoingUri]: previous track video while wiping away (only when different from incoming)
 */
internal data class PinnedVideoCover(
    val incomingUri: String?,
    val outgoingUri: String?,
)

internal fun pinnedVideoCover(
    wiping: Boolean,
    outgoingVideoUri: String?,
    visibleVideoUri: String?,
): PinnedVideoCover {
    val outgoing = outgoingVideoUri?.takeIf { it.isNotBlank() }
    val visible = visibleVideoUri?.takeIf { it.isNotBlank() }
    if (!wiping) {
        return PinnedVideoCover(incomingUri = visible, outgoingUri = null)
    }
    return when {
        outgoing != null && outgoing == visible ->
            // Same file across the wipe — one full-screen host, no clip.
            PinnedVideoCover(incomingUri = outgoing, outgoingUri = null)
        else ->
            PinnedVideoCover(incomingUri = visible, outgoingUri = outgoing)
    }
}
