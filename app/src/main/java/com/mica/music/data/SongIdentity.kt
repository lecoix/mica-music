package com.mica.music.data

import java.security.MessageDigest

/** Stable identities for songs whose provider does not expose a MediaStore row ID. */
object SongIdentity {
    const val LEGACY_DOCUMENT_PREFIX = "doc_"
    const val DOCUMENT_PREFIX = "doc_sha256_"

    fun documentId(mediaUri: String): String =
        DOCUMENT_PREFIX + sha256(mediaUri)

    fun legacyDocumentId(mediaUri: String): String =
        LEGACY_DOCUMENT_PREFIX + mediaUri.hashCode()

    fun isLegacyDocumentId(id: String): Boolean =
        id.startsWith(LEGACY_DOCUMENT_PREFIX) && !id.startsWith(DOCUMENT_PREFIX)

    private fun sha256(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
        return buildString(bytes.size * 2) {
            bytes.forEach { byte ->
                val unsigned = byte.toInt() and 0xff
                append(HEX[unsigned ushr 4])
                append(HEX[unsigned and 0x0f])
            }
        }
    }

    private const val HEX = "0123456789abcdef"
}
