package com.mica.music.media

import android.os.Handler
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import java.time.Duration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
class PlaybackRouteMonitorTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val handler = Handler(Looper.getMainLooper())
    private var monitors = mutableListOf<PlaybackRouteMonitor>()

    @After
    fun tearDown() {
        monitors.forEach { it.release() }
        monitors.clear()
    }

    @Test
    fun releaseBeforeDebounceDoesNotInvokeRouteChanged() {
        var invocations = 0
        val monitor = PlaybackRouteMonitor(context, handler) { _, _, _ ->
            invocations++
        }
        monitors += monitor
        monitor.install()
        monitor.release()
        shadowOf(Looper.getMainLooper()).idleFor(
            Duration.ofMillis(PlaybackRouteMonitor.ROUTE_DEBOUNCE_MS + 50),
        )
        assertEquals(0, invocations)
    }
}
