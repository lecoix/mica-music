package com.mica.music.data.remote.smb

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import com.mica.music.data.remote.RemoteCredentialMaterial
import com.mica.music.data.remote.RemoteMediaIdCodec
import com.mica.music.data.remote.RemoteSourceOwner
import com.mica.music.data.remote.RemoteSourceType
import com.mica.music.data.remote.SecureRemoteCredentialStore
import java.io.IOException
import kotlin.math.min

/** Ephemeral SMB playback state. Credentials and server path never enter MediaItem persistence. */
internal class SmbPlaybackRequest(
    val sourceInstanceId: String,
    val sourceConfigRevision: Long,
    val credentialRevision: Long,
    val endpoint: SmbEndpoint,
    val relativePath: String,
    val login: SmbLogin,
) {
    override fun toString(): String =
        "SmbPlaybackRequest(sourceInstanceId=$sourceInstanceId, " +
            "sourceConfigRevision=$sourceConfigRevision, credentialRevision=$credentialRevision, " +
            "endpoint=<redacted>, relativePath=<redacted>, login=<redacted>)"
}

internal fun interface SmbPlaybackRequestResolver {
    suspend fun resolve(mediaId: String): SmbPlaybackRequest?
}

internal class SmbStreamRequestResolver(
    private val sourceOwnerById: suspend (String) -> RemoteSourceOwner?,
    private val credentialStore: SecureRemoteCredentialStore,
) : SmbPlaybackRequestResolver {
    override suspend fun resolve(mediaId: String): SmbPlaybackRequest? {
        val ref = RemoteMediaIdCodec.decode(mediaId) ?: return null
        val owner = sourceOwnerById(ref.sourceInstanceId) ?: return null
        val operation = owner.beginOperationSnapshot()
        val source = operation.source.instance
        if (source.type != RemoteSourceType.SMB || !source.enabled) return null
        val credential = credentialStore.resolve(source.credentialRef) ?: return null
        val material = credential.material as? RemoteCredentialMaterial.UsernamePassword ?: return null
        if (!owner.isCurrent(operation.token)) return null
        val endpoint = runCatching { SmbPathCodec.parse(source.endpoint) }.getOrNull() ?: return null
        val relativePath = runCatching { SmbPathCodec.normalizeRelativePath(ref.opaqueTrackId) }.getOrNull()
            ?.takeIf(String::isNotEmpty) ?: return null
        if (!owner.isCurrent(operation.token)) return null
        return SmbPlaybackRequest(
            sourceInstanceId = source.id,
            sourceConfigRevision = operation.source.configRevision,
            credentialRevision = credential.revision,
            endpoint = endpoint,
            relativePath = relativePath,
            login = SmbLogin.parse(material.username, material.password),
        )
    }
}

@UnstableApi
internal class SmbDataSource(
    private val request: SmbPlaybackRequest,
    private val sessionFactory: SmbSessionFactory = SmbjSessionFactory(),
) : BaseDataSource(false) {
    private var session: SmbSessionHandle? = null
    private var file: SmbRandomAccessFile? = null
    private var openedSpec: DataSpec? = null
    private var readPosition: Long = 0L
    private var bytesRemaining: Long = 0L

    override fun open(dataSpec: DataSpec): Long {
        check(openedSpec == null) { "SMB DataSource is already open" }
        transferInitializing(dataSpec)
        var nextSession: SmbSessionHandle? = null
        var nextFile: SmbRandomAccessFile? = null
        try {
            nextSession = sessionFactory.open(request.endpoint, request.login)
            nextFile = nextSession.openFile(request.endpoint.serverPath(request.relativePath))
            val fileLength = nextFile.length
            if (dataSpec.position > fileLength) {
                throw IOException("SMB read position exceeds file length")
            }
            val available = fileLength - dataSpec.position
            val requestedLength = if (dataSpec.length == C.LENGTH_UNSET.toLong()) {
                available
            } else {
                dataSpec.length.coerceAtLeast(0L)
            }
            readPosition = dataSpec.position
            bytesRemaining = min(available, requestedLength)
            session = nextSession
            file = nextFile
            openedSpec = dataSpec
            transferStarted(dataSpec)
            return bytesRemaining
        } catch (failure: Throwable) {
            runCatching { nextFile?.close() }
            runCatching { nextSession?.close() }
            session = null
            file = null
            openedSpec = null
            throw if (failure is IOException) failure else IOException("SMB playback open failed", failure)
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        val currentFile = file ?: throw IOException("SMB DataSource is not open")
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        val requested = min(length.toLong(), bytesRemaining).toInt()
        val read = currentFile.read(readPosition, buffer, offset, requested)
        if (read < 0) {
            throw IOException("SMB file ended before the declared length")
        }
        if (read == 0) {
            throw IOException("SMB file read made no progress")
        }
        readPosition += read
        bytesRemaining -= read
        bytesTransferred(read)
        return read
    }

    override fun getUri(): Uri? = openedSpec?.uri

    override fun close() {
        val wasOpen = openedSpec != null
        openedSpec = null
        val currentFile = file
        val currentSession = session
        file = null
        session = null
        readPosition = 0L
        bytesRemaining = 0L
        var firstFailure: Throwable? = null
        try {
            currentFile?.close()
        } catch (failure: Throwable) {
            firstFailure = failure
        }
        try {
            currentSession?.close()
        } catch (failure: Throwable) {
            if (firstFailure == null) firstFailure = failure else firstFailure?.addSuppressed(failure)
        }
        if (wasOpen) transferEnded()
        firstFailure?.let { throw if (it is IOException) it else IOException("SMB playback close failed", it) }
    }

    internal class Factory(
        private val request: SmbPlaybackRequest,
        private val sessionFactory: SmbSessionFactory = SmbjSessionFactory(),
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource = SmbDataSource(request, sessionFactory)
    }
}