package com.mica.music.media

import com.mica.music.testutil.SongFixtures
import org.junit.Test

class SharedPcmPipelineDiagnosticsTest {

    @Test
    fun logSongFormat_doesNotThrow() {
        SharedPcmPipelineDiagnostics.logSongFormat(
            SongFixtures.song("flac-test", fileExtension = "flac"),
        )
    }
}
