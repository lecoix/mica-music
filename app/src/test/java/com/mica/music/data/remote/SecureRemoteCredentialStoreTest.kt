package com.mica.music.data.remote

import org.junit.Assert.assertFalse
import org.junit.Test

class SecureRemoteCredentialStoreTest {
    @Test
    fun `credential snapshots redact secret material from string form`() {
        val snapshot = RemoteCredentialSnapshot(
            credentialRef = "credential-1",
            revision = 4,
            material = RemoteCredentialMaterial.UsernamePassword("alice", "super-secret-password"),
        )

        assertFalse(snapshot.toString().contains("super-secret-password"))
        assertFalse(snapshot.material.toString().contains("super-secret-password"))
    }
}
