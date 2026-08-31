package com.mica.music.data.remote.smb

import android.content.Context
import com.mica.music.data.remote.AndroidTagLibEmbeddedArtworkLoader
import com.mica.music.data.remote.RemoteArtworkRef
import com.mica.music.data.remote.RemoteCredentialMaterial
import com.mica.music.data.remote.DEFAULT_REMOTE_ARTWORK_MAX_BYTES
import com.mica.music.data.remote.RemoteEmbeddedArtworkIdCodec
import com.mica.music.data.remote.RemoteFileArtworkIdCodec
import com.mica.music.data.remote.RemoteSourceOwner
import com.mica.music.data.remote.RemoteSourceType
import com.mica.music.data.remote.SecureRemoteCredentialStore
import java.io.IOException

internal data class SmbArtworkRequest(
    val sourceInstanceId: String,
    val sourceConfigRevision: Long,
    val credentialRevision: Long,
    val endpoint: SmbEndpoint,
    val relativePath: String,
    val login: SmbLogin,
) {
    override fun toString(): String =
        "SmbArtworkRequest(sourceInstanceId=$sourceInstanceId, " +
            "sourceConfigRevision=$sourceConfigRevision, credentialRevision=$credentialRevision, " +
            "endpoint=<redacted>, relativePath=<redacted>, login=<redacted>)"
}

internal class SmbArtworkRequestResolver(
    private val sourceOwnerById: suspend (String) -> RemoteSourceOwner?,
    private val credentialStore: SecureRemoteCredentialStore,
) {
    suspend fun resolve(ref: RemoteArtworkRef): SmbArtworkRequest? {
        val target = RemoteFileArtworkIdCodec.decode(ref.opaqueArtworkId) ?: return null
        val owner = sourceOwnerById(ref.sourceInstanceId) ?: return null
        val operation = owner.beginOperationSnapshot()
        val source = operation.source.instance
        if (source.type != RemoteSourceType.SMB || !source.enabled) return null
        val credential = credentialStore.resolve(source.credentialRef) ?: return null
        val material = credential.material as? RemoteCredentialMaterial.UsernamePassword ?: return null
        if (!owner.isCurrent(operation.token)) return null
        val endpoint = runCatching { SmbPathCodec.parse(source.endpoint) }.getOrNull() ?: return null
        val relativePath = runCatching { SmbPathCodec.normalizeRelativePath(target.resourceId) }.getOrNull()
            ?.takeIf(String::isNotEmpty) ?: return null
        if (!owner.isCurrent(operation.token)) return null
        return SmbArtworkRequest(
            sourceInstanceId = source.id,
            sourceConfigRevision = operation.source.configRevision,
            credentialRevision = credential.revision,
            endpoint = endpoint,
            relativePath = relativePath,
            login = SmbLogin.parse(material.username, material.password),
        )
    }
}

internal class SmbEmbeddedArtworkRequestResolver(
    private val sourceOwnerById: suspend (String) -> RemoteSourceOwner?,
    private val credentialStore: SecureRemoteCredentialStore,
) {
    suspend fun resolve(ref: RemoteArtworkRef): SmbArtworkRequest? {
        val target = RemoteEmbeddedArtworkIdCodec.decode(ref.opaqueArtworkId) ?: return null
        val owner = sourceOwnerById(ref.sourceInstanceId) ?: return null
        val operation = owner.beginOperationSnapshot()
        val source = operation.source.instance
        if (source.type != RemoteSourceType.SMB || !source.enabled) return null
        val credential = credentialStore.resolve(source.credentialRef) ?: return null
        val material = credential.material as? RemoteCredentialMaterial.UsernamePassword ?: return null
        if (!owner.isCurrent(operation.token)) return null
        val endpoint = runCatching { SmbPathCodec.parse(source.endpoint) }.getOrNull() ?: return null
        val relativePath = runCatching { SmbPathCodec.normalizeRelativePath(target.resourceId) }.getOrNull()
            ?.takeIf(String::isNotEmpty) ?: return null
        if (!owner.isCurrent(operation.token)) return null
        return SmbArtworkRequest(
            sourceInstanceId = source.id,
            sourceConfigRevision = operation.source.configRevision,
            credentialRevision = credential.revision,
            endpoint = endpoint,
            relativePath = relativePath,
            login = SmbLogin.parse(material.username, material.password),
        )
    }
}

internal class SmbArtworkByteLoader(
    private val sessionFactory: SmbSessionFactory = SmbjSessionFactory(),
    private val maxBytes: Long = DEFAULT_REMOTE_ARTWORK_MAX_BYTES,
) {
    init {
        require(maxBytes >= 0L) { "SMB artwork byte limit must be non-negative" }
    }

    fun load(request: SmbArtworkRequest): ByteArray {
        var session: SmbSessionHandle? = null
        var file: SmbRandomAccessFile? = null
        try {
            session = sessionFactory.open(request.endpoint, request.login)
            file = session.openFile(request.endpoint.serverPath(request.relativePath))
            val length = file.length
            if (length < 0L || length > maxBytes || length > Int.MAX_VALUE) {
                throw IOException("SMB artwork exceeds size limit")
            }
            val result = ByteArray(length.toInt())
            var offset = 0
            while (offset < result.size) {
                val read = file.read(offset.toLong(), result, offset, result.size - offset)
                if (read < 0) throw IOException("SMB artwork ended before declared length")
                if (read == 0) throw IOException("SMB artwork read made no progress")
                offset += read
            }
            return result
        } catch (failure: Throwable) {
            throw if (failure is IOException) failure else IOException("SMB artwork read failed", failure)
        } finally {
            runCatching { file?.close() }
            runCatching { session?.close() }
        }
    }
}

internal class SmbEmbeddedArtworkByteLoader(
    context: Context,
    private val sessionFactory: SmbSessionFactory = SmbjSessionFactory(),
) {
    private val embeddedLoader = AndroidTagLibEmbeddedArtworkLoader(context)

    fun load(request: SmbArtworkRequest): ByteArray {
        var session: SmbSessionHandle? = null
        try {
            session = sessionFactory.open(request.endpoint, request.login)
            return SmbSeekableByteSource(
                session.openFile(request.endpoint.serverPath(request.relativePath)),
            ).use(embeddedLoader::load)
        } catch (failure: Throwable) {
            throw if (failure is IOException) failure else IOException("SMB embedded artwork read failed", failure)
        } finally {
            runCatching { session?.close() }
        }
    }
}
