package com.mica.music.data

import android.content.Intent
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryFolderStoreTest {
    @Test
    fun treeAccessFlagsIncludeReadAndWrite() {
        val flags = LibraryFolderStore.treeAccessFlags()

        assertTrue(flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertTrue(flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0)
    }
}
