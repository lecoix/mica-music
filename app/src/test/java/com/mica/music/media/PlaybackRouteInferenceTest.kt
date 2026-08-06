package com.mica.music.media

import android.media.AudioDeviceInfo
import android.media.AudioManager
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackRouteInferenceTest {

    @Test
    fun inferRouteFromOutputs_prefersUsbOverBluetooth() {
        val usb = device(deviceType = AudioDeviceInfo.TYPE_USB_DEVICE, name = "USB DAC")
        val bt = device(deviceType = AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, name = "BT")
        val route = AudioOutputCapabilities.inferRouteFromOutputs(
            managerWithOutputs(usb, bt),
        )
        assertEquals(AudioDeviceInfo.TYPE_USB_DEVICE, route.deviceType)
        assertEquals("USB DAC", route.deviceName)
        assertTrue(route.usb)
    }

    @Test
    fun inferRouteFromOutputs_prefersA2dpOverSco() {
        val sco = device(deviceType = AudioDeviceInfo.TYPE_BLUETOOTH_SCO, name = "SCO")
        val a2dp = device(deviceType = AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, name = "A2DP")
        val route = AudioOutputCapabilities.inferRouteFromOutputs(
            managerWithOutputs(sco, a2dp),
        )
        assertEquals(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, route.deviceType)
        assertEquals("A2DP", route.deviceName)
        assertTrue(route.bluetooth)
    }

    @Test
    fun inferRouteFromOutputs_fallsBackToSpeaker() {
        val speaker = device(deviceType = AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, name = "Speaker")
        val route = AudioOutputCapabilities.inferRouteFromOutputs(
            managerWithOutputs(speaker),
        )
        assertEquals(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, route.deviceType)
        assertEquals("Speaker", route.deviceName)
    }

    private fun device(deviceType: Int, name: String): AudioDeviceInfo =
        mockk<AudioDeviceInfo> {
            every { type } returns deviceType
            every { productName } returns name
        }

    private fun managerWithOutputs(vararg devices: AudioDeviceInfo): AudioManager =
        mockk {
            every { getDevices(AudioManager.GET_DEVICES_OUTPUTS) } returns devices
        }
}
