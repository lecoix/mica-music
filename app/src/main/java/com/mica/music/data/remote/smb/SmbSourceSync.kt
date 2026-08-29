package com.mica.music.data.remote.smb

import com.mica.music.data.remote.RemoteCatalogRepository
import com.mica.music.data.remote.RemoteCredentialMaterial
import com.mica.music.data.remote.RemoteOperationSnapshot
import com.mica.music.data.remote.RemoteSourceOwner
import com.mica.music.data.remote.RemoteSourceType
import com.mica.music.data.remote.RemoteTrackRef
import com.mica.music.data.remote.RemoteTrackSummary
import com.mica.music.data.remote.SecureRemoteCredentialStore
import java.util.ArrayDeque
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class SmbSyncResult(
    val sourceInstanceId: String,
    val configRevision: Long,
    val trackCount: Int,
)

/**
 * Full source-scoped SMB2/3 refresh.
 *
 * SMBJ owns protocol negotiation/authentication. Mica owns source generation, credential lifetime,
 * bounded traversal and atomic catalog publication. Every blocking SMB operation runs on IO.
 */
internal class SmbSourceSync(
    private val catalogRepository: RemoteCatalogRepository,
    private val credentialStore: SecureRemoteCredentialStore,
    private val sessionFactory: SmbSessionFactory = SmbjSessionFactory(),
) {
    suspend fun testConnection(sourceInstanceId: String) {
        val session = beginSession(sourceInstanceId)
        try {
            listDirectory(session, relativeDirectory = "")
            ensureCurrent(session)
        } finally {
            closeSession(session.handle)
        }
    }

    suspend fun sync(
        sourceInstanceId: String,
        limit: Int = Int.MAX_VALUE,
    ): SmbSyncResult {
        require(limit >= 0) { "limit must not be negative" }
        val session = beginSession(sourceInstanceId)
        try {
            val tracks = if (limit == 0) emptyList() else scan(session, limit)
            ensureCurrent(session)
            val published = catalogRepository.publishCatalogIfCurrent(
                token = session.operation.token,
                tracks = tracks,
            )
            if (!published) {
                throw SmbException(
                    SmbFailureKind.STALE_OPERATION,
                    "SMB catalog became stale before storage publication",
                )
            }
            return SmbSyncResult(
                sourceInstanceId = sourceInstanceId,
                configRevision = session.operation.source.configRevision,
                trackCount = tracks.size,
            )
        } finally {
            closeSession(session.handle)
        }
    }

    private suspend fun beginSession(sourceInstanceId: String): Session {
        val owner = catalogRepository.sourceOwner(sourceInstanceId)
            ?: throw SmbException(SmbFailureKind.PROTOCOL, "Unknown SMB source")
        val operation = owner.beginOperationSnapshot()
        val source = operation.source.instance
        if (source.type != RemoteSourceType.SMB) {
            throw SmbException(SmbFailureKind.PROTOCOL, "Remote source is not SMB")
        }
        if (!source.enabled) {
            throw SmbException(SmbFailureKind.PROTOCOL, "SMB source is disabled")
        }
        val credential = credentialStore.resolve(source.credentialRef)
            ?: throw SmbException(SmbFailureKind.AUTH, "SMB credential is unavailable")
        val material = credential.material as? RemoteCredentialMaterial.UsernamePassword
            ?: throw SmbException(SmbFailureKind.AUTH, "SMB requires username/password credentials")
        ensureCurrent(owner, operation)

        val endpoint = try {
            SmbPathCodec.parse(source.endpoint)
        } catch (failure: IllegalArgumentException) {
            throw SmbException(SmbFailureKind.PROTOCOL, failure.message ?: "Invalid SMB address", failure)
        }
        val login = SmbLogin.parse(material.username, material.password)
        val handle = withContext(Dispatchers.IO) {
            sessionFactory.open(endpoint, login)
        }
        if (!owner.isCurrent(operation.token)) {
            closeSession(handle)
            throw SmbException(SmbFailureKind.STALE_OPERATION, "SMB source changed while connecting")
        }
        return Session(owner, operation, endpoint, handle)
    }

    private suspend fun scan(session: Session, limit: Int): List<RemoteTrackSummary> {
        val sourceId = session.operation.source.instance.id
        val pending = ArrayDeque<String>()
        val visited = linkedSetOf<String>()
        val tracks = ArrayList<RemoteTrackSummary>()
        pending.addLast("")

        while (pending.isNotEmpty() && tracks.size < limit) {
            ensureCurrent(session)
            val relativeDirectory = pending.removeFirst()
            if (!visited.add(relativeDirectory)) continue
            val entries = listDirectory(session, relativeDirectory)
            ensureCurrent(session)
            for (entry in entries) {
                if (tracks.size >= limit) break
                if (entry.name == "." || entry.name == "..") continue
                val relativePath = try {
                    SmbPathCodec.appendChild(relativeDirectory, entry.name)
                } catch (failure: IllegalArgumentException) {
                    throw SmbException(SmbFailureKind.PROTOCOL, "SMB server returned an invalid path", failure)
                }
                if (entry.isDirectory) {
                    if (relativePath !in visited) pending.addLast(relativePath)
                    continue
                }
                val suffix = extension(entry.name)
                if (suffix !in AUDIO_EXTENSIONS) continue
                tracks += RemoteTrackSummary(
                    ref = RemoteTrackRef(sourceId, relativePath),
                    title = entry.name.substringBeforeLast('.', entry.name).ifBlank { entry.name },
                    mimeTypeHint = MIME_BY_EXTENSION[suffix].orEmpty(),
                    fileName = entry.name,
                    suffix = suffix,
                    sizeBytes = entry.sizeBytes.coerceAtLeast(0L),
                )
            }
        }
        return tracks
    }

    private suspend fun listDirectory(session: Session, relativeDirectory: String): List<SmbDirectoryEntry> {
        ensureCurrent(session)
        val serverPath = session.endpoint.serverPath(relativeDirectory)
        return withContext(Dispatchers.IO) {
            session.handle.list(serverPath)
        }
    }

    private fun ensureCurrent(session: Session) = ensureCurrent(session.owner, session.operation)

    private fun ensureCurrent(owner: RemoteSourceOwner, operation: RemoteOperationSnapshot) {
        if (!owner.isCurrent(operation.token)) {
            throw SmbException(SmbFailureKind.STALE_OPERATION, "SMB source changed during operation")
        }
    }

    private suspend fun closeSession(handle: SmbSessionHandle) {
        withContext(Dispatchers.IO) {
            handle.close()
        }
    }

    private fun extension(value: String): String = value
        .substringAfterLast('.', "")
        .lowercase(Locale.US)

    private data class Session(
        val owner: RemoteSourceOwner,
        val operation: RemoteOperationSnapshot,
        val endpoint: SmbEndpoint,
        val handle: SmbSessionHandle,
    )

    companion object {
        private val AUDIO_EXTENSIONS = setOf(
            "mp3", "flac", "m4a", "aac", "ogg", "opus", "wav", "ape", "wma", "alac", "aiff", "aif",
        )
        private val MIME_BY_EXTENSION = mapOf(
            "mp3" to "audio/mpeg",
            "flac" to "audio/flac",
            "m4a" to "audio/mp4",
            "aac" to "audio/aac",
            "ogg" to "audio/ogg",
            "opus" to "audio/opus",
            "wav" to "audio/wav",
            "ape" to "audio/ape",
            "wma" to "audio/x-ms-wma",
            "alac" to "audio/alac",
            "aiff" to "audio/aiff",
            "aif" to "audio/aiff",
        )
    }
}