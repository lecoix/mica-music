package com.mica.music.media.usbhybrid

import android.hardware.usb.UsbConfiguration
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AndroidUsbIdentityProbeTest {
    @Test
    fun isochronousAudioOutIsSelectableAudioOutput() {
        val candidate = AndroidUsbIdentityProbe.candidate(
            device(interfaceClass = UsbConstants.USB_CLASS_AUDIO, direction = UsbConstants.USB_DIR_OUT, type = UsbConstants.USB_ENDPOINT_XFER_ISOC),
        )
        assertTrue(candidate.hasAudioOutput)
    }

    @Test
    fun audioInputOnlyIsNotSelectableOutput() {
        val candidate = AndroidUsbIdentityProbe.candidate(
            device(interfaceClass = UsbConstants.USB_CLASS_AUDIO, direction = UsbConstants.USB_DIR_IN, type = UsbConstants.USB_ENDPOINT_XFER_ISOC),
        )
        assertFalse(candidate.hasAudioOutput)
    }

    @Test
    fun nonIsochronousOrNonAudioEndpointsAreNotSelectableOutput() {
        assertFalse(AndroidUsbIdentityProbe.candidate(
            device(interfaceClass = UsbConstants.USB_CLASS_AUDIO, direction = UsbConstants.USB_DIR_OUT, type = UsbConstants.USB_ENDPOINT_XFER_BULK),
        ).hasAudioOutput)
        assertFalse(AndroidUsbIdentityProbe.candidate(
            device(interfaceClass = UsbConstants.USB_CLASS_HID, direction = UsbConstants.USB_DIR_OUT, type = UsbConstants.USB_ENDPOINT_XFER_ISOC),
        ).hasAudioOutput)
    }

    private fun device(interfaceClass: Int, direction: Int, type: Int): UsbDevice {
        val endpoint = mockk<UsbEndpoint>()
        every { endpoint.address } returns 3
        every { endpoint.type } returns type
        every { endpoint.direction } returns direction
        every { endpoint.maxPacketSize } returns 192
        every { endpoint.interval } returns 1

        val usbInterface = mockk<UsbInterface>()
        every { usbInterface.id } returns 1
        every { usbInterface.alternateSetting } returns 1
        every { usbInterface.interfaceClass } returns interfaceClass
        every { usbInterface.endpointCount } returns 1
        every { usbInterface.getEndpoint(0) } returns endpoint

        val configuration = mockk<UsbConfiguration>()
        every { configuration.id } returns 1
        every { configuration.interfaceCount } returns 1
        every { configuration.getInterface(0) } returns usbInterface

        val device = mockk<UsbDevice>()
        every { device.configurationCount } returns 1
        every { device.getConfiguration(0) } returns configuration
        every { device.vendorId } returns 0x1234
        every { device.productId } returns 0x5678
        every { device.version } returns "1.00"
        every { device.deviceId } returns 7
        every { device.deviceName } returns "/dev/bus/usb/001/007"
        every { device.manufacturerName } returns "Generic"
        every { device.productName } returns "USB DAC"
        return device
    }
}
