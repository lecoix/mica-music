package com.mica.music.widget

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.mica.music.MicaMainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class WidgetPlayerLaunchTest {
    @Test
    fun widgetIntentTargetsMainActivityAndRequestsPlayer() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()

        val intent = widgetPlayerIntent(context)

        assertEquals(MicaMainActivity::class.java.name, intent.component?.className)
        assertEquals(ACTION_OPEN_PLAYER_FROM_WIDGET, intent.action)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_CLEAR_TOP != 0)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_SINGLE_TOP != 0)
        assertTrue(isWidgetPlayerLaunch(intent))
    }

    @Test
    fun unrelatedLaunchDoesNotRequestPlayer() {
        assertFalse(isWidgetPlayerLaunch(Intent(Intent.ACTION_MAIN)))
        assertFalse(isWidgetPlayerLaunch(null))
    }
}
