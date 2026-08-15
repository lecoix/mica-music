package com.mica.music.media.usb.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackIntentLedgerTest {
    @Test
    fun semanticEdgesAdvanceRevisionAndDuplicatePublicationIsIdempotent() {
        val ledger = PlaybackIntentLedger()

        assertEquals(IntentSnapshot(IntentRevision(0), PlaybackIntent.PAUSE), ledger.snapshot())
        assertEquals(IntentRevision(0), ledger.publish(PlaybackIntent.PAUSE).revision)
        val play = ledger.publish(PlaybackIntent.PLAY)
        assertEquals(IntentRevision(1), play.revision)
        assertEquals(play, ledger.publish(PlaybackIntent.PLAY))
        val pause = ledger.publish(PlaybackIntent.PAUSE)
        assertEquals(IntentRevision(2), pause.revision)
        assertTrue(pause.revision.value > play.revision.value)
    }

    @Test
    fun ledgerSurvivesStackReplacementAndNewStackAdoptsLatestPause() {
        val ledger = PlaybackIntentLedger()
        ledger.publish(PlaybackIntent.PLAY)
        val old = UsbExclusivePlaybackProtocol(ledger, PlaybackStackId(1), OutputTarget.SharedPcm)
        old.adoptLatestIntent()
        old.beginRetiring()

        val pause = ledger.publish(PlaybackIntent.PAUSE)
        val replacement = UsbExclusivePlaybackProtocol(ledger, PlaybackStackId(2), OutputTarget.SharedPcm)

        assertEquals(pause, replacement.snapshot().adoptedIntent)
        assertEquals(PlaybackIntent.PAUSE, replacement.adoptLatestIntent().desired)
    }
}
