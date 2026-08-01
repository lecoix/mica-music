package com.mica.music.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SongIdentityTest {
    @Test
    fun legacyHashCanCollideButDocumentIdentityDoesNotForKnownPair() {
        val first = "content://provider/Aa"
        val second = "content://provider/BB"

        assertEquals(SongIdentity.legacyDocumentId(first), SongIdentity.legacyDocumentId(second))
        assertNotEquals(SongIdentity.documentId(first), SongIdentity.documentId(second))
        assertTrue(SongIdentity.documentId(first).startsWith(SongIdentity.DOCUMENT_PREFIX))
    }
}
