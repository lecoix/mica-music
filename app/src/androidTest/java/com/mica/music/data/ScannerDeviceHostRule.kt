package com.mica.music.data

import android.app.Activity
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.rules.ExternalResource

/** MIUI can put a background instrumentation process in do_freezer_trap even with the screen on.
 * Use a minimal foreground Activity; MainActivity would create a competing library/Room owner.
 */
class ScannerDeviceHostRule : ExternalResource() {
    private var activity: Activity? = null

    override fun before() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val packageName = instrumentation.targetContext.packageName
        check(packageName.endsWith(".qa")) { "Scanner device tests require the isolated QA package" }
        val className = ScannerContractHostActivity::class.java.name
        val monitor = instrumentation.addMonitor(className, null, false)
        try {
            val output = instrumentation.uiAutomation.executeShellCommand(
                "am start -W -n $packageName/$className",
            ).let { descriptor ->
                ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
            }
            check(output.contains("Status: ok")) { "Scanner test host failed: $output" }
            activity = checkNotNull(monitor.waitForActivityWithTimeout(10_000)) {
                "Scanner test host did not start: $output"
            }
            instrumentation.waitForIdleSync()
        } finally {
            instrumentation.removeMonitor(monitor)
        }
    }

    override fun after() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { activity?.finish() }
        activity = null
    }
}
