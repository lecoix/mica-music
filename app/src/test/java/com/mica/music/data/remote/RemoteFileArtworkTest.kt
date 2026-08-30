package com.mica.music.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RemoteFileArtworkTest {
    @Test
    fun fileArtworkIdRoundTripsEncodedRelativePathAndRevision() {
        val encoded = RemoteFileArtworkIdCodec.encode("专辑 A/cover:1.jpg", "etag=\"a:b\";mtime=7")

        assertEquals(
            RemoteFileArtworkTarget("专辑 A/cover:1.jpg", "etag=\"a:b\";mtime=7"),
            RemoteFileArtworkIdCodec.decode(encoded),
        )
        assertNull(RemoteFileArtworkIdCodec.decode("cover-42"))

        val publicRef = RemoteArtworkRef("smb/source 1", encoded)
        assertEquals(publicRef, RemoteArtworkUriCodec.decode(RemoteArtworkUriCodec.encode(publicRef)))
    }

    @Test
    fun sameStemArtworkIsSelectedWithoutFallingBackToFolderArtwork() {
        val candidates = listOf(
            candidate("folder.png", "Album/folder.png"),
            candidate("cover.jpg", "Album/cover.jpg"),
            candidate("Song.webp", "Album/Song.webp"),
        )

        assertEquals("Album/Song.webp", selectRemoteTrackSidecarArtwork("Song.flac", candidates)?.resourceId)
        assertNull(selectRemoteTrackSidecarArtwork("Other.flac", candidates))
    }

    @Test
    fun conventionalArtworkSelectionIsDeterministic() {
        val candidates = listOf(
            candidate("folder.jpg", "Album/folder.jpg"),
            candidate("cover.png", "Album/cover.png"),
            candidate("COVER.JPG", "Album/COVER.JPG"),
        )

        assertEquals("Album/COVER.JPG", selectRemoteFolderSidecarArtwork(candidates)?.resourceId)
    }

    @Test
    fun folderArtworkRequiresSingleTrackOrOneNonBlankAlbum() {
        assertEquals(true, canUseRemoteFolderArtwork(listOf(track("Only"))))
        assertEquals(
            true,
            canUseRemoteFolderArtwork(listOf(track("A", "Album"), track("B", "album"))),
        )
        assertEquals(
            false,
            canUseRemoteFolderArtwork(listOf(track("A", "Album 1"), track("B", "Album 2"))),
        )
        assertEquals(
            false,
            canUseRemoteFolderArtwork(listOf(track("A", "Album"), track("B", ""))),
        )
    }

    private fun track(title: String, album: String = "") = RemoteTrackSummary(
        ref = RemoteTrackRef("source", title),
        title = title,
        album = album,
    )

    private fun candidate(fileName: String, resourceId: String) = RemoteSidecarArtworkCandidate(
        fileName = fileName,
        resourceId = resourceId,
        contentRevision = "rev",
        sizeBytes = 10,
    )
}
