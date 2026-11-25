package com.mica.music.data.scanner

import org.junit.Assert.assertEquals
import org.junit.Test

class AlbumTagAuthorityTest {
    @Test
    fun successfulReadWithoutAlbumIgnoresMediaStoreFolderFallback() {
        assertEquals("未知专辑", resolveScannedAlbum("", "Music"))
        assertEquals("未知专辑", resolveScannedAlbum("   ", "D0"))
        assertEquals("未知专辑", resolveScannedAlbum("", "未知专辑"))
    }

    @Test
    fun genuineAlbumMatchingFolderIsPreserved() {
        assertEquals("Music", resolveScannedAlbum("Music", "Music"))
        assertEquals("Actual album", resolveScannedAlbum("Actual album", "Music"))
    }

    @Test
    fun unsuccessfulReadRetainsProvisionalDraft() {
        assertEquals("Music", resolveScannedAlbum(null, "Music"))
        assertEquals("未知专辑", resolveScannedAlbum(null, ""))
    }

    @Test
    fun absentTagRemainsAbsentUntilAllSuccessfulReadersAreMerged() {
        val tag = TagInfo("", "", "", "", "", "", 120, 0)
        val missing = mergeTagInfo(tag, tag)
        assertEquals("未知专辑", resolveScannedAlbum(missing.album, "Music"))
        val supplemental = mergeTagInfo(tag, tag.copy(album = "Music"))
        assertEquals("Music", resolveScannedAlbum(supplemental.album, "Music"))
    }
}
