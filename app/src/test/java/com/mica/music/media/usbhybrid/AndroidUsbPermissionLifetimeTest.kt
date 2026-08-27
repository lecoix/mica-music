package com.mica.music.media.usbhybrid

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidUsbPermissionLifetimeTest {
    @Test fun oldInstanceBroadcastCannotConsumeNewInstancesPermissionRequest() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val first = AndroidUsbHybridControlEffects(application, {}, {})
        val second = AndroidUsbHybridControlEffects(application, {}, {})

        try {
            assertNotEquals(first.permissionAction(), second.permissionAction())
        } finally {
            first.close()
            second.close()
        }
    }

    @Test fun attachedBroadcastPublishesRuntimeIdentityAndAudioRelevance() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val events = mutableListOf<UsbTopologyEvent>()
        val effects = AndroidUsbHybridControlEffects(application, {}, events::add)
        val device = mockk<UsbDevice>()
        every { device.configurationCount } returns 0
        every { device.vendorId } returns 0x1234
        every { device.productId } returns 0x5678
        every { device.version } returns "1.00"
        every { device.deviceId } returns 17
        every { device.deviceName } returns "/dev/bus/usb/001/017"
        every { device.manufacturerName } returns "Generic"
        every { device.productName } returns "USB accessory"

        try {
            effects.topologyReceiver().onReceive(
                application,
                Intent(UsbManager.ACTION_USB_DEVICE_ATTACHED).putExtra(UsbManager.EXTRA_DEVICE, device),
            )

            assertEquals(
                listOf(
                    UsbTopologyEvent.Attached(
                        UsbRuntimeHandle(17, "/dev/bus/usb/001/017"),
                        hasAudioOutput = false,
                    ),
                ),
                events,
            )
        } finally {
            effects.close()
        }
    }

    private fun AndroidUsbHybridControlEffects.permissionAction(): String =
        javaClass.getDeclaredField("permissionAction")
            .apply { isAccessible = true }
            .get(this) as String

    private fun AndroidUsbHybridControlEffects.topologyReceiver(): BroadcastReceiver =
        javaClass.getDeclaredField("topologyReceiver")
            .apply { isAccessible = true }
            .get(this) as BroadcastReceiver
}
