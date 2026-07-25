package com.mica.music.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PinnedVideoCoverTest {
    @Test
    fun settledUsesVisibleVideo() {
        val pinned = pinnedVideoCover(
            wiping = false,
            outgoingVideoUri = "content://old",
            visibleVideoUri = "content://new",
        )
        assertEquals("content://new", pinned.incomingUri)
        assertNull(pinned.outgoingUri)
    }

    @Test
    fun sameUriAcrossWipeKeepsSingleFullScreenHost() {
        val pinned = pinnedVideoCover(
            wiping = true,
            outgoingVideoUri = "content://same",
            visibleVideoUri = "content://same",
        )
        assertEquals("content://same", pinned.incomingUri)
        assertNull(pinned.outgoingUri)
    }

    @Test
    fun leavingVideoPinsOutgoingOnly() {
        val pinned = pinnedVideoCover(
            wiping = true,
            outgoingVideoUri = "content://old",
            visibleVideoUri = null,
        )
        assertNull(pinned.incomingUri)
        assertEquals("content://old", pinned.outgoingUri)
    }

    @Test
    fun enteringVideoPreloadsIncomingDuringWipe() {
        val pinned = pinnedVideoCover(
            wiping = true,
            outgoingVideoUri = null,
            visibleVideoUri = "content://new",
        )
        assertEquals("content://new", pinned.incomingUri)
        assertNull(pinned.outgoingUri)
    }

    @Test
    fun videoToVideoKeepsBothHosts() {
        val pinned = pinnedVideoCover(
            wiping = true,
            outgoingVideoUri = "content://old",
            visibleVideoUri = "content://new",
        )
        assertEquals("content://new", pinned.incomingUri)
        assertEquals("content://old", pinned.outgoingUri)
    }
}
