package com.mica.music.media.usbprototype

import android.os.Looper
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UsbDirectDsdPrototypeCommandThreadingTest {
    @Test
    fun controllerWorkPostedFromWorkerRunsOnDeclaredApplicationLooper() {
        val applicationLooper = Looper.getMainLooper()
        val applicationThread = MediaControllerApplicationThread(applicationLooper)
        val observedLooper = AtomicReference<Looper?>()
        val executed = AtomicBoolean(false)

        Thread {
            applicationThread.execute {
                observedLooper.set(Looper.myLooper())
                executed.set(true)
            }
        }.apply {
            start()
            join()
        }

        shadowOf(applicationLooper).idle()

        assertTrue(executed.get())
        assertSame(applicationLooper, observedLooper.get())
    }
}
