package com.mica.music.data.remote

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteAutoSyncTest {
    private val enabled = RemoteSourceInstance(
        id = "source",
        type = RemoteSourceType.NAVIDROME,
        displayName = "Source",
        endpoint = "https://music.example",
        credentialRef = "credential/source",
        enabled = true,
    )

    @Test
    fun neverSyncedAndStaleEnabledSourcesNeedAutomaticSync() {
        assertTrue(status(enabled, lastSyncAtMs = 0L).needsAutomaticSync(nowMs = 10_000L, staleAfterMs = 5_000L))
        assertTrue(status(enabled, lastSyncAtMs = 5_000L).needsAutomaticSync(nowMs = 10_000L, staleAfterMs = 5_000L))
    }

    @Test
    fun freshOrDisabledSourcesDoNotNeedAutomaticSync() {
        assertFalse(status(enabled, lastSyncAtMs = 5_001L).needsAutomaticSync(nowMs = 10_000L, staleAfterMs = 5_000L))
        assertFalse(
            status(enabled.copy(enabled = false), lastSyncAtMs = 0L)
                .needsAutomaticSync(nowMs = 10_000L, staleAfterMs = 5_000L),
        )
    }

    private fun status(instance: RemoteSourceInstance, lastSyncAtMs: Long) = RemoteSourceStatus(
        instance = instance,
        configRevision = 1L,
        catalogRevision = 0L,
        catalogConfigRevision = 0L,
        lastSyncAtMs = lastSyncAtMs,
        trackCount = 0,
    )
}