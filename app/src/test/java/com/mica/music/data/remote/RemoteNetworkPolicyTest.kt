package com.mica.music.data.remote

import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RemoteNetworkPolicyTest {
    @Test
    fun manifestAllowsExplicitLanHttpRemoteSources() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val cleartextAllowed =
            context.applicationInfo.flags and ApplicationInfo.FLAG_USES_CLEARTEXT_TRAFFIC != 0

        assertTrue(
            "Remote source settings accept explicit http:// LAN endpoints, so the packaged app must allow cleartext traffic",
            cleartextAllowed,
        )
    }
}
