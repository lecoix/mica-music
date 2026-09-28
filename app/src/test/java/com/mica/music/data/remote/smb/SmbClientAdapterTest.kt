package com.mica.music.data.remote.smb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
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

    @Test
    fun successfulReadKeepsItsResultWhenSessionCleanupFails() {
        val closeFailure = java.io.IOException("cleanup")
        val session = object : SmbSessionHandle {
            override fun list(serverPath: String) = emptyList<SmbDirectoryEntry>()
            override fun openFile(serverPath: String): SmbRandomAccessFile = error("unused")
            override fun close() = throw closeFailure
        }
        var reported: Throwable? = null

        val result = session.useReadSession(onCloseFailure = { reported = it }) { "ok" }

        assertEquals("ok", result)
        assertSame(closeFailure, reported)
    }

    @Test
    fun failedReadRemainsPrimaryWhenSessionCleanupAlsoFails() {
        val readFailure = IllegalStateException("read")
        val closeFailure = java.io.IOException("cleanup")
        val session = object : SmbSessionHandle {
            override fun list(serverPath: String) = emptyList<SmbDirectoryEntry>()
            override fun openFile(serverPath: String): SmbRandomAccessFile = error("unused")
            override fun close() = throw closeFailure
        }

        try {
            session.useReadSession { throw readFailure }
            fail("Expected read failure")
        } catch (failure: Throwable) {
            assertSame(readFailure, failure)
            assertEquals(listOf(closeFailure), failure.suppressed.toList())
        }
    }
}
