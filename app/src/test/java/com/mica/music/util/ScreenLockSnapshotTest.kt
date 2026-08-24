package com.mica.music.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenLockSnapshotTest {
    @Test
    fun nonInteractiveScreenWinsOverGenericBackgroundState() {
        val snapshot = snapshot(interactive = false, displayState = "OFF")

        assertEquals("screen-not-interactive", snapshot.reasonHint())
        assertTrue(snapshot.toLogText().contains("interactive=false display=OFF"))
    }

    @Test
    fun lockedInteractiveScreenIsClassifiedAsKeyguard() {
        val snapshot = snapshot(interactive = true, keyguardLocked = true)

        assertEquals("keyguard-locked", snapshot.reasonHint())
    }

    @Test
    fun finishingAndConfigurationChangeRemainDistinguishable() {
        assertEquals("activity-finishing", snapshot(finishing = true).reasonHint())
        assertEquals(
            "configuration-change",
            snapshot(changingConfigurations = true).reasonHint(),
        )
    }

    @Test
    fun visiblePowerStateWithoutKeyguardRemainsCoveredOrBackgrounded() {
        val snapshot = snapshot(interactive = true, keyguardLocked = false, deviceLocked = false)

        assertEquals("covered-or-backgrounded", snapshot.reasonHint())
    }

    private fun snapshot(
        interactive: Boolean? = true,
        displayState: String = "ON",
        keyguardLocked: Boolean? = false,
        deviceLocked: Boolean? = false,
        finishing: Boolean? = false,
        changingConfigurations: Boolean? = false,
    ) = ScreenLockSnapshot(
        interactive = interactive,
        powerSaveMode = false,
        deviceIdleMode = false,
        keyguardLocked = keyguardLocked,
        deviceLocked = deviceLocked,
        displayState = displayState,
        processImportance = ActivityManagerImportance.FOREGROUND,
        lifecycleState = "RESUMED",
        hasWindowFocus = true,
        windowVisibility = 0,
        decorShown = true,
        finishing = finishing,
        changingConfigurations = changingConfigurations,
        destroyed = false,
        taskId = 1,
        windowFlags = 0,
        keepScreenOn = false,
        showWhenLocked = false,
        turnScreenOn = false,
        screenOffTimeoutMs = 60_000L,
        sinceUserInteractionMs = 100L,
        sinceUserLeaveHintMs = null,
    )

    private object ActivityManagerImportance {
        const val FOREGROUND = 100
    }
}
