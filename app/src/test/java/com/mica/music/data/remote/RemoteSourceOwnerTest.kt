package com.mica.music.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteSourceOwnerTest {
    @Test
    fun `source edit invalidates operations captured before edit`() {
        val owner = RemoteSourceOwner(source(endpoint = "https://old.example"))
        val oldOperation = owner.beginOperation()

        val updated = owner.replace(source(endpoint = "https://new.example"))

        assertFalse(owner.isCurrent(oldOperation))
        assertEquals(2L, updated.configRevision)
        assertEquals(2L, updated.operationGeneration)
        assertEquals("https://new.example", updated.instance.endpoint)
        assertTrue(owner.isCurrent(owner.beginOperation()))
    }

    @Test
    fun `operation invalidation does not pretend config changed`() {
        val owner = RemoteSourceOwner(source())
        val oldOperation = owner.beginOperation()

        val invalidated = owner.invalidateOperations()

        assertFalse(owner.isCurrent(oldOperation))
        assertEquals(1L, invalidated.configRevision)
        assertEquals(2L, invalidated.operationGeneration)
    }

    @Test
    fun `one source generation never invalidates another source`() {
        val a = RemoteSourceOwner(source(id = "a"))
        val b = RemoteSourceOwner(source(id = "b"))
        val aOperation = a.beginOperation()
        val bOperation = b.beginOperation()

        a.invalidateOperations()

        assertFalse(a.isCurrent(aOperation))
        assertTrue(b.isCurrent(bOperation))
    }

    private fun source(
        id: String = "source-1",
        endpoint: String = "https://music.example",
    ) = RemoteSourceInstance(
        id = id,
        type = RemoteSourceType.NAVIDROME,
        displayName = "Music",
        endpoint = endpoint,
        credentialRef = "credential-$id",
    )
}
