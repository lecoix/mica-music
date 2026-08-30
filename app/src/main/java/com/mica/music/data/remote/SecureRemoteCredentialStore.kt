package com.mica.music.data.remote

/**
 * Only an opaque reference is persisted with a source. Credential material is resolved just in
 * time and must never be serialized into catalog, queue, history, session extras, or logs.
 */
fun interface SecureRemoteCredentialStore {
    suspend fun resolve(credentialRef: String): RemoteCredentialSnapshot?
}

interface MutableSecureRemoteCredentialStore : SecureRemoteCredentialStore {
    suspend fun put(
        credentialRef: String,
        material: RemoteCredentialMaterial,
    ): RemoteCredentialSnapshot

    suspend fun delete(credentialRef: String): Boolean
}

class RemoteCredentialSnapshot(
    val credentialRef: String,
    val revision: Long,
    val material: RemoteCredentialMaterial,
) {
    override fun toString(): String =
        "RemoteCredentialSnapshot(credentialRef=$credentialRef, revision=$revision, material=<redacted>)"
}

sealed interface RemoteCredentialMaterial {
    data object Anonymous : RemoteCredentialMaterial

    class UsernamePassword(
        val username: String,
        val password: String,
    ) : RemoteCredentialMaterial {
        override fun toString(): String = "UsernamePassword(username=$username, password=<redacted>)"
    }

    class BearerToken(
        val username: String,
        val token: String,
    ) : RemoteCredentialMaterial {
        override fun toString(): String = "BearerToken(username=$username, token=<redacted>)"
    }
}
