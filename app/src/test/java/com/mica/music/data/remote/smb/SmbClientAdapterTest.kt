package com.mica.music.data.remote.smb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmbClientAdapterTest {
    @Test
    fun anonymousLoginUsesGuestContextInsteadOfSmbjAnonymousContext() {
        val context = SmbLogin("", "", null, anonymous = true).toAuthenticationContext()

        assertTrue(context.isGuest)
        assertFalse(context.isAnonymous)
        assertEquals("Guest", context.username)
        assertEquals(0, context.password.size)
    }

    @Test
    fun credentialedLoginKeepsUsernamePasswordAndDomain() {
        val context = SmbLogin("alice", "secret", "WORKGROUP").toAuthenticationContext()

        assertFalse(context.isGuest)
        assertFalse(context.isAnonymous)
        assertEquals("alice", context.username)
        assertEquals("secret", String(context.password))
        assertEquals("WORKGROUP", context.domain)
    }
}
