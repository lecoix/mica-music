package com.mica.music.media.usbhybrid

import android.app.Application
import androidx.test.core.app.ApplicationProvider
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

    private fun AndroidUsbHybridControlEffects.permissionAction(): String =
        javaClass.getDeclaredField("permissionAction")
            .apply { isAccessible = true }
            .get(this) as String
}
