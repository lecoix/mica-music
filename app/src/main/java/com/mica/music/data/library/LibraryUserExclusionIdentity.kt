package com.mica.music.data.library

import com.mica.music.data.Song

/**
 * Current scanner identity is already stable for a MediaStore object (ms_<id>) and for a SAF
 * document URI (SongIdentity.documentId). Keep exclusions bound to that object identity rather
 * than to mutable file fingerprints.
 */
internal fun userExclusionStableObjectKey(song: Song): String = song.id
