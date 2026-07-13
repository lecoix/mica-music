package com.mica.music.media

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.mica.music.data.MiniPlayerStyle
import com.mica.music.data.PlayerCoverFlowMode
import com.mica.music.data.preferences.MicaSettingsStore
import com.mica.music.data.preferences.PlaybackUiPreferences
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SpectrumAnalyzerStateOwnerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private var owner: SpectrumAnalyzerStateOwner? = null

    @Before
    fun setUp() {
        MicaSettingsStore.prefs(context).edit().clear().commit()
        MicaSpectrumAnalyzer.onEnabledChanged = null
        MicaSpectrumAnalyzer.setEnabled(false, notifyPipeline = false)
    }

    @After
    fun tearDown() {
        owner?.release()
        MicaSpectrumAnalyzer.onEnabledChanged = null
        MicaSpectrumAnalyzer.setEnabled(false, notifyPipeline = false)
    }

    @Test
    fun startRestoresEligibilityWithoutNotifyingPipeline() {
        PlaybackUiPreferences.setSpectrumEnabled(context, true)
        var notifications = 0
        MicaSpectrumAnalyzer.onEnabledChanged = { notifications++ }

        owner = SpectrumAnalyzerStateOwner(context).also { it.start() }

        assertTrue(owner!!.currentEnabled)
        assertTrue(MicaSpectrumAnalyzer.isEnabledForProcessing())
        assertEquals(0, notifications)
    }

    @Test
    fun allEligibilityPreferencesAreAppliedAndDeduplicated() {
        val applied = mutableListOf<Boolean>()
        MicaSpectrumAnalyzer.onEnabledChanged = applied::add
        owner = SpectrumAnalyzerStateOwner(context).also { it.start() }

        PlaybackUiPreferences.setMiniPlayerStyle(context, MiniPlayerStyle.AUDIOPHILE)
        PlaybackUiPreferences.setSpectrumEnabled(context, true)
        PlaybackUiPreferences.setMiniPlayerStyle(context, MiniPlayerStyle.FLOATING_ISLAND)
        PlaybackUiPreferences.setSpectrumEnabled(context, false)
        PlaybackUiPreferences.setPlayerCoverFlowMode(context, PlayerCoverFlowMode.PHOTO_STACK)

        assertTrue(owner!!.currentEnabled)
        assertTrue(MicaSpectrumAnalyzer.isEnabledForProcessing())
        assertEquals(listOf(true, false, true), applied)
    }

    @Test
    fun releaseStopsApplyingPreferenceChanges() {
        owner = SpectrumAnalyzerStateOwner(context).also { it.start() }
        owner!!.release()

        PlaybackUiPreferences.setSpectrumEnabled(context, true)

        assertFalse(owner!!.currentEnabled)
        assertFalse(MicaSpectrumAnalyzer.isEnabledForProcessing())
    }
}
