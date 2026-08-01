package com.mica.music.media

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaItemRequestPolicyTest {
    @Test
    fun emptyControllerRequestIsRecognized() {
        assertTrue(MediaItemRequestPolicy.isEmptyRequest(MediaItem.Builder().build()))
    }

    @Test
    fun mediaIdUriAndSearchRequestsAreNotTreatedAsEmpty() {
        assertFalse(
            MediaItemRequestPolicy.isEmptyRequest(
                MediaItem.Builder().setMediaId("song-1").build(),
            ),
        )
        assertFalse(
            MediaItemRequestPolicy.isEmptyRequest(
                MediaItem.Builder()
                    .setMediaMetadata(MediaMetadata.Builder().setTitle("caller title").build())
                    .build(),
            ),
        )
    }
}
