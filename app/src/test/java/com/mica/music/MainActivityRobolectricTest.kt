package com.mica.music

import android.os.Bundle
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class MainActivityRobolectricTest {

    @Test
    @Config(sdk = [26])
    fun createsOnApi26() = createAndDestroy()

    @Test
    @Config(sdk = [27])
    fun createsOnApi27() = createAndDestroy()

    @Test
    @Config(sdk = [29])
    fun createsOnApi29() = createAndDestroy()

    @Test
    @Config(sdk = [34])
    fun createsOnApi34() = createAndDestroy()

    @Test
    @Config(sdk = [34])
    fun savedStateCanBeUsedForRecreation() {
        val first = Robolectric.buildActivity(MainActivity::class.java).create()
        val state = Bundle()
        first.saveInstanceState(state).destroy()

        Robolectric.buildActivity(MainActivity::class.java)
            .create(state)
            .destroy()
    }

    @Test
    @Config(sdk = [34])
    fun unexpectedSavedStateTypesDoNotPreventCreation() {
        val state = Bundle().apply {
            putString("player_expanded", "invalid")
            putString("locate_request", "invalid")
        }

        Robolectric.buildActivity(MainActivity::class.java)
            .create(state)
            .destroy()
    }

    private fun createAndDestroy() {
        Robolectric.buildActivity(MainActivity::class.java)
            .create()
            .destroy()
    }
}
