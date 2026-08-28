package com.mica.music.data.remote

import android.content.ComponentName
import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.test.core.app.ApplicationProvider
import com.mica.music.BuildConfig
import org.junit.Assert.assertEquals
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

    @Test
    fun remoteArtworkProviderIsSystemReadableButNotPublic() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val info = context.packageManager.getProviderInfo(
            ComponentName(context, RemoteArtworkContentProvider::class.java),
            0,
        )

        assertEquals("${BuildConfig.APPLICATION_ID}.remoteart", info.authority)
        assertTrue(info.exported)
        assertTrue(info.grantUriPermissions)
        assertEquals("android.permission.MEDIA_CONTENT_CONTROL", info.readPermission)
        assertEquals("android.permission.MEDIA_CONTENT_CONTROL", info.writePermission)
        val uri = android.net.Uri.parse(
            RemoteArtworkUriCodec.encode(RemoteArtworkRef("nav-1", "cover-1")),
        )
        // Same-UID app callers remain able to resolve the provider despite the privileged ACL.
        assertEquals("image/*", context.contentResolver.getType(uri))
    }
}
