package com.mica.music.data.library

import com.mica.music.data.FastScrollIndex
import com.mica.music.data.Song

internal data class PreparedLibrarySongs(
    val scanned: List<Song>,
    val visible: List<Song>,
    val fastScrollIndex: FastScrollIndex?,
    val catalogRevision: Long,
    val presentationRevision: Long,
    val customOrderIdsToPersist: List<String>? = null,
)
