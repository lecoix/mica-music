package com.mica.music.media

import androidx.media3.common.Player
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ControllerCapabilityPolicyTest {
    private val ownPackage = "com.mica.music"

    @Test
    fun ownAppUsesItsQueueAndHasNoImplicitCustomCommands() {
        val capabilities = ControllerCapabilityPolicy.evaluate(
            identity = identity(packageName = ownPackage, trusted = false),
            ownPackageName = ownPackage,
        )

        assertEquals(ControllerClass.OWN_APP, capabilities.controllerClass)
        assertFalse(capabilities.resolveMediaItemsFromCatalog)
        assertTrue(capabilities.allowedIncomingCustomActions.isEmpty())
    }

    @Test
    fun trustedExternalControllerUsesCatalogResolution() {
        val capabilities = ControllerCapabilityPolicy.evaluate(
            identity = identity(packageName = "com.android.systemui", trusted = true),
            ownPackageName = ownPackage,
        )

        assertEquals(ControllerClass.TRUSTED_EXTERNAL, capabilities.controllerClass)
        assertTrue(capabilities.resolveMediaItemsFromCatalog)
    }

    @Test
    fun untrustedExternalControllerCannotInvokeCustomActions() {
        val identity = identity(packageName = "com.example.remote", trusted = false)

        assertEquals(
            ControllerClass.UNTRUSTED_EXTERNAL,
            ControllerCapabilityPolicy.evaluate(identity, ownPackage).controllerClass,
        )
        assertFalse(
            ControllerCapabilityPolicy.allowsIncomingCustomAction(
                identity,
                ownPackage,
                "mica.some-mutating-action",
            ),
        )
    }

    @Test
    fun largePlatformQueueOmitsOnlyTheFullTimelineCommand() {
        val commands = Player.Commands.Builder()
            .add(Player.COMMAND_GET_TIMELINE)
            .add(Player.COMMAND_GET_CURRENT_MEDIA_ITEM)
            .add(Player.COMMAND_PLAY_PAUSE)
            .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
            .add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
            .build()

        assertEquals(commands, ControllerCapabilityPolicy.mediaNotificationCommands(commands, 5_000))
        val limited = ControllerCapabilityPolicy.mediaNotificationCommands(commands, 10_000)
        assertFalse(limited.contains(Player.COMMAND_GET_TIMELINE))
        assertTrue(limited.contains(Player.COMMAND_GET_CURRENT_MEDIA_ITEM))
        assertTrue(limited.contains(Player.COMMAND_PLAY_PAUSE))
        assertTrue(limited.contains(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM))
        assertTrue(limited.contains(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM))
    }

    private fun identity(packageName: String, trusted: Boolean) = ControllerIdentity(
        packageName = packageName,
        uid = 1000,
        isTrusted = trusted,
        controllerVersion = 1,
        connectionHintKeys = emptySet(),
    )
}
