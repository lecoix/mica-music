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

    @Test
    fun `same-config catalog refresh fences earlier refresh without invalidating concurrent reads`() {
        val owner = RemoteSourceOwner(source())
        val readToken = owner.beginOperation()
        val firstRefresh = owner.beginCatalogRefresh()
        assertTrue(owner.isCurrent(readToken))
        assertTrue(owner.isCurrent(firstRefresh))

        val secondRefresh = owner.beginCatalogRefresh()

        assertFalse(owner.isCurrent(firstRefresh))
        assertTrue(owner.isCurrent(secondRefresh))
        assertTrue(owner.isCurrent(readToken))
        assertEquals(1L, secondRefresh.configRevision)
        assertEquals(1L, secondRefresh.operationGeneration)
        assertEquals(2L, secondRefresh.catalogRefreshGeneration)
    }

    @Test
    fun `source edit and operation invalidation also invalidate active catalog refresh`() {
        val owner = RemoteSourceOwner(source(endpoint = "https://old.example"))
        val refreshBeforeEdit = owner.beginCatalogRefresh()

        owner.replace(source(endpoint = "https://new.example"))
        assertFalse(owner.isCurrent(refreshBeforeEdit))

        val refreshBeforeInvalidate = owner.beginCatalogRefresh()
        owner.invalidateOperations()
        assertFalse(owner.isCurrent(refreshBeforeInvalidate))
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
