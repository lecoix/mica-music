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
import org.junit.Assert.assertTrue
import java.util.concurrent.ConcurrentHashMap
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
        val device = usbAccessoryDevice()

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

    @Test fun outputOperationPermissionCannotConsumeNativeOwnerEpochWithSameNumericId() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val ownerResults = mutableListOf<UsbPermissionResult>()
        val outputResults = mutableListOf<UsbOutputPermissionResult>()
        val effects = AndroidUsbHybridControlEffects(
            application,
            permissionResultSink = ownerResults::add,
            topologyEventSink = {},
            outputPermissionResultSink = outputResults::add,
        )
        val device = usbAccessoryDevice()
        val observed = AndroidUsbIdentityProbe.candidate(device)
        val numericId = 41L
        val ownerRequest = UsbPermissionRequest(
            epoch = UsbRequestEpoch(numericId),
            mode = UsbExclusiveMode.USB_EXACT_PCM,
            identity = observed.identity,
            runtimeHandle = observed.runtimeHandle,
        )
        val outputRequest = UsbOutputPermissionRequest(
            operationId = UsbOutputOperationId(numericId),
            mode = UsbExclusiveMode.USB_EXACT_PCM,
            identity = observed.identity,
            runtimeHandle = observed.runtimeHandle,
        )
        effects.pendingOwnerRequests()[numericId] = ownerRequest
        effects.pendingOutputRequests()[numericId] = outputRequest

        try {
            effects.permissionBroadcastReceiver().onReceive(
                application,
                permissionIntent(
                    effects = effects,
                    requestId = numericId,
                    domain = "output_operation",
                    device = device,
                ),
            )

            assertTrue(ownerResults.isEmpty())
            assertEquals(
                listOf(
                    UsbOutputPermissionResult(
                        operationId = outputRequest.operationId,
                        mode = outputRequest.mode,
                        identity = outputRequest.identity,
                        runtimeHandle = outputRequest.runtimeHandle,
                        granted = true,
                    ),
                ),
                outputResults,
            )
            assertTrue(effects.pendingOwnerRequests().containsKey(numericId))
            assertTrue(!effects.pendingOutputRequests().containsKey(numericId))

            effects.permissionBroadcastReceiver().onReceive(
                application,
                permissionIntent(
                    effects = effects,
                    requestId = numericId,
                    domain = "owner_epoch",
                    device = device,
                ),
            )

            assertEquals(
                listOf(
                    UsbPermissionResult(
                        epoch = ownerRequest.epoch,
                        mode = ownerRequest.mode,
                        identity = ownerRequest.identity,
                        runtimeHandle = ownerRequest.runtimeHandle,
                        granted = true,
                    ),
                ),
                ownerResults,
            )
            assertTrue(!effects.pendingOwnerRequests().containsKey(numericId))
        } finally {
            effects.close()
        }
    }

    private fun usbAccessoryDevice(): UsbDevice = mockk<UsbDevice>().also { device ->
        every { device.configurationCount } returns 0
        every { device.vendorId } returns 0x1234
        every { device.productId } returns 0x5678
        every { device.version } returns "1.00"
        every { device.deviceId } returns 17
        every { device.deviceName } returns "/dev/bus/usb/001/017"
        every { device.manufacturerName } returns "Generic"
        every { device.productName } returns "USB accessory"
    }

    private fun permissionIntent(
        effects: AndroidUsbHybridControlEffects,
        requestId: Long,
        domain: String,
        device: UsbDevice,
    ): Intent = Intent(effects.permissionAction())
        .putExtra("usb_hybrid_permission_request_id", requestId)
        .putExtra("usb_hybrid_permission_domain", domain)
        .putExtra(UsbManager.EXTRA_DEVICE, device)
        .putExtra(UsbManager.EXTRA_PERMISSION_GRANTED, true)

    @Suppress("UNCHECKED_CAST")
    private fun AndroidUsbHybridControlEffects.pendingOwnerRequests(): ConcurrentHashMap<Long, UsbPermissionRequest> =
        javaClass.getDeclaredField("pendingRequests")
            .apply { isAccessible = true }
            .get(this) as ConcurrentHashMap<Long, UsbPermissionRequest>

    @Suppress("UNCHECKED_CAST")
    private fun AndroidUsbHybridControlEffects.pendingOutputRequests(): ConcurrentHashMap<Long, UsbOutputPermissionRequest> =
        javaClass.getDeclaredField("pendingOutputPermissionRequests")
            .apply { isAccessible = true }
            .get(this) as ConcurrentHashMap<Long, UsbOutputPermissionRequest>

    private fun AndroidUsbHybridControlEffects.permissionAction(): String =
        javaClass.getDeclaredField("permissionAction")
            .apply { isAccessible = true }
            .get(this) as String

    private fun AndroidUsbHybridControlEffects.permissionBroadcastReceiver(): BroadcastReceiver =
        javaClass.getDeclaredField("permissionReceiver")
            .apply { isAccessible = true }
            .get(this) as BroadcastReceiver

    private fun AndroidUsbHybridControlEffects.topologyReceiver(): BroadcastReceiver =
        javaClass.getDeclaredField("topologyReceiver")
            .apply { isAccessible = true }
            .get(this) as BroadcastReceiver
}
