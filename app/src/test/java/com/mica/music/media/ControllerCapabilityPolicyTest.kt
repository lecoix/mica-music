package com.mica.music.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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

    private fun identity(packageName: String, trusted: Boolean) = ControllerIdentity(
        packageName = packageName,
        uid = 1000,
        isTrusted = trusted,
        controllerVersion = 1,
        connectionHintKeys = emptySet(),
    )
}
