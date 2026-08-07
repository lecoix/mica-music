package com.mica.music.media

import androidx.media3.common.Player
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AudioPipelineCoordinatorTest {
    @Test
    fun initialConfigurationIsAppliedWithoutFlushing() {
        val effects = mutableListOf<String>()
        val coordinator = AudioPipelineCoordinator(
            initialState = AudioPipelineState(false, true, true),
            invalidateCircuitBreaker = { effects += "invalidate" },
            resetCircuitBreaker = { effects += "reset" },
            applyConfiguration = { effects += "apply:offload=${it.offloadEnabled}" },
            persistQualityMode = { effects += "quality=$it" },
            flushPipeline = { effects += "flush:$it" },
        )

        coordinator.applyInitialConfiguration()

        assertEquals(listOf("apply:offload=false"), effects)
    }

    @Test
    fun enablingEqualizerInvalidatesBreakerBeforeApplyingPcmAndFlushingOnce() {
        val effects = mutableListOf<String>()
        val coordinator = AudioPipelineCoordinator(
            initialState = AudioPipelineState(
                equalizerEnabled = false,
                spectrumTapEnabled = false,
                offloadPreferenceEnabled = true,
            ),
            invalidateCircuitBreaker = { effects += "invalidate" },
            resetCircuitBreaker = { effects += "reset" },
            applyConfiguration = { effects += "apply:offload=${it.offloadEnabled}" },
            persistQualityMode = { effects += "quality=$it" },
            flushPipeline = { effects += "flush:$it" },
        )

        coordinator.onEqualizerEnabledChanged(true)

        assertEquals(
            listOf(
                "invalidate",
                "quality=DSP",
                "apply:offload=false",
                "flush:equalizer-enabled=true",
            ),
            effects,
        )
    }

    @Test
    fun offloadPreferenceChangeDoesNotFlushWhenEffectiveOffloadStaysDisabled() {
        val effects = mutableListOf<String>()
        val coordinator = AudioPipelineCoordinator(
            initialState = AudioPipelineState(
                equalizerEnabled = true,
                spectrumTapEnabled = false,
                offloadPreferenceEnabled = true,
            ),
            invalidateCircuitBreaker = { effects += "invalidate" },
            resetCircuitBreaker = { effects += "reset" },
            applyConfiguration = { effects += "apply:offload=${it.offloadEnabled}" },
            persistQualityMode = { effects += "quality=$it" },
            flushPipeline = { effects += "flush:$it" },
        )

        coordinator.onOffloadPreferenceChanged(false)

        assertEquals(
            listOf("invalidate", "apply:offload=false"),
            effects,
        )
    }

    @Test
    fun staleOffloadStallCannotCrossANewerSpectrumConfiguration() {
        val effects = mutableListOf<String>()
        val scheduler = ManualScheduler()
        val snapshot = AudioOffloadPlaybackSnapshot(
            mediaId = "song-a",
            uriScheme = "content",
            playbackState = Player.STATE_BUFFERING,
            playWhenReady = true,
            isPlaying = false,
            playbackSuppressionReason = Player.PLAYBACK_SUPPRESSION_REASON_NONE,
            totalBufferedDurationMs = 5_000L,
            currentPositionMs = 0L,
        )
        lateinit var breaker: AudioOffloadCircuitBreaker
        val coordinator = AudioPipelineCoordinator(
            initialState = AudioPipelineState(
                equalizerEnabled = false,
                spectrumTapEnabled = false,
                offloadPreferenceEnabled = true,
            ),
            invalidateCircuitBreaker = { breaker.invalidateExternalBoundary() },
            resetCircuitBreaker = { breaker.resetForManualRetry() },
            applyConfiguration = { effects += "apply:offload=${it.offloadEnabled}" },
            persistQualityMode = { effects += "quality=$it" },
            flushPipeline = { effects += "flush:$it" },
        )
        breaker = AudioOffloadCircuitBreaker(
            snapshot = { snapshot },
            scheduler = scheduler,
            onFallbackToPcm = { effects += "stale-fallback" },
            onVerifiedFailure = { effects += "verified-failure" },
        )
        breaker.onOffloadedPlayback(true)
        val staleStall = scheduler.lastTask()

        coordinator.onSpectrumTapEnabledChanged(true)
        scheduler.run(staleStall)

        assertFalse(breaker.sessionDisabled)
        assertEquals(
            listOf(
                "apply:offload=false",
                "flush:spectrum-enabled=true",
            ),
            effects,
        )
    }

    @Test
    fun circuitOpeningAppliesPcmBeforeFlushingThePipeline() {
        val effects = mutableListOf<String>()
        val coordinator = AudioPipelineCoordinator(
            initialState = AudioPipelineState(
                equalizerEnabled = false,
                spectrumTapEnabled = false,
                offloadPreferenceEnabled = true,
            ),
            invalidateCircuitBreaker = { effects += "invalidate" },
            resetCircuitBreaker = { effects += "reset" },
            applyConfiguration = { effects += "apply:offload=${it.offloadEnabled}" },
            persistQualityMode = { effects += "quality=$it" },
            flushPipeline = { effects += "flush:$it" },
        )

        coordinator.onOffloadCircuitOpened()

        assertEquals(
            listOf(
                "apply:offload=false",
                "flush:offload-stall-fallback",
            ),
            effects,
        )
    }

    @Test
    fun routeChangeInvalidatesBreakerAndFlushesWithoutReapplyingConfiguration() {
        val effects = mutableListOf<String>()
        val coordinator = AudioPipelineCoordinator(
            initialState = AudioPipelineState(false, false, true),
            invalidateCircuitBreaker = { effects += "invalidate" },
            resetCircuitBreaker = { effects += "reset" },
            applyConfiguration = { effects += "apply" },
            persistQualityMode = { effects += "quality" },
            flushPipeline = { effects += "flush:$it" },
        )

        coordinator.onRouteChanged("route-change wired->bluetooth")

        assertEquals(
            listOf("invalidate", "flush:route-change wired->bluetooth"),
            effects,
        )
    }

    private class ManualScheduler : AudioOffloadWatchdogScheduler {
        private val tasks = mutableListOf<Runnable>()

        override fun postDelayed(task: Runnable, delayMs: Long) {
            tasks += task
        }

        override fun remove(task: Runnable) {
            tasks -= task
        }

        fun lastTask(): Runnable = tasks.last()

        fun run(task: Runnable) {
            tasks -= task
            task.run()
        }
    }
}
