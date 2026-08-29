package com.mica.music.data.remote.smb

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2Dialect
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.mserref.NtStatus
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import java.io.Closeable
import java.io.IOException
import java.util.EnumSet
import java.util.concurrent.TimeUnit

internal enum class SmbFailureKind {
    AUTH,
    CONNECT,
    IO,
    PROTOCOL,
    STALE_OPERATION,
}

internal class SmbException(
    val kind: SmbFailureKind,
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

internal data class SmbLogin(
    val username: String,
    val password: String,
    val domain: String?,
) {
    override fun toString(): String = "SmbLogin(username=<redacted>, password=<redacted>, domain=<redacted>)"

    companion object {
        fun parse(username: String, password: String): SmbLogin {
            val trimmed = username.trim()
            val slash = trimmed.indexOf('\\')
            return if (slash > 0 && slash < trimmed.lastIndex) {
                SmbLogin(
                    username = trimmed.substring(slash + 1),
                    password = password,
                    domain = trimmed.substring(0, slash),
                )
            } else {
                SmbLogin(username = trimmed, password = password, domain = null)
            }
        }
    }
}

internal data class SmbDirectoryEntry(
    val name: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
)

internal interface SmbRandomAccessFile : Closeable {
    val length: Long
    fun read(fileOffset: Long, buffer: ByteArray, offset: Int, length: Int): Int
}

internal interface SmbSessionHandle : Closeable {
    fun list(serverPath: String): List<SmbDirectoryEntry>
    fun openFile(serverPath: String): SmbRandomAccessFile
}

internal fun interface SmbSessionFactory {
    fun open(endpoint: SmbEndpoint, login: SmbLogin): SmbSessionHandle
}

internal class SmbjSessionFactory : SmbSessionFactory {
    override fun open(endpoint: SmbEndpoint, login: SmbLogin): SmbSessionHandle {
        val client = SMBClient(
            SmbConfig.builder()
                .withDialects(
                    SMB2Dialect.SMB_2_0_2,
                    SMB2Dialect.SMB_2_1,
                    SMB2Dialect.SMB_3_0,
                    SMB2Dialect.SMB_3_0_2,
                    SMB2Dialect.SMB_3_1_1,
                )
                .withTimeout(20, TimeUnit.SECONDS)
                .withSoTimeout(20, TimeUnit.SECONDS)
                .build(),
        )
        var connection: Connection? = null
        var session: Session? = null
        var share: DiskShare? = null
        try {
            connection = client.connect(endpoint.host, endpoint.port)
            session = connection.authenticate(
                AuthenticationContext(
                    login.username,
                    login.password.toCharArray(),
                    login.domain,
                ),
            )
            val connectedShare = session.connectShare(endpoint.share)
            share = connectedShare as? DiskShare
                ?: throw SmbException(SmbFailureKind.PROTOCOL, "SMB source is not a disk share")
            return SmbjSessionHandle(client, connection, session, share)
        } catch (failure: Throwable) {
            runCatching { share?.close() }
            runCatching { session?.close() }
            runCatching { connection?.close() }
            runCatching { client.close() }
            if (failure is SmbException) throw failure
            val kind = (failure as? SMBApiException)?.status?.let(::classifySmbStatus)
                ?: SmbFailureKind.CONNECT
            val message = when (kind) {
                SmbFailureKind.AUTH -> "SMB authentication failed"
                SmbFailureKind.PROTOCOL -> "SMB share or configured path is unavailable"
                else -> "SMB connection failed"
            }
            throw SmbException(kind, message, failure)
        }
    }
}

internal fun classifySmbStatus(status: NtStatus): SmbFailureKind = when (status) {
    NtStatus.STATUS_LOGON_FAILURE,
    NtStatus.STATUS_PASSWORD_EXPIRED,
    NtStatus.STATUS_ACCOUNT_DISABLED,
    NtStatus.STATUS_LOGON_TYPE_NOT_GRANTED,
    NtStatus.STATUS_ACCESS_DENIED,
    -> SmbFailureKind.AUTH

    NtStatus.STATUS_BAD_NETWORK_NAME,
    NtStatus.STATUS_BAD_NETWORK_PATH,
    NtStatus.STATUS_OBJECT_PATH_NOT_FOUND,
    NtStatus.STATUS_NOT_A_DIRECTORY,
    -> SmbFailureKind.PROTOCOL

    else -> SmbFailureKind.CONNECT
}

private class SmbjSessionHandle(
    private val client: SMBClient,
    private val connection: Connection,
    private val session: Session,
    private val share: DiskShare,
) : SmbSessionHandle {
    override fun list(serverPath: String): List<SmbDirectoryEntry> = try {
        share.list(serverPath).map { entry ->
            SmbDirectoryEntry(
                name = entry.fileName,
                isDirectory = (entry.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value) != 0L,
                sizeBytes = entry.endOfFile.coerceAtLeast(0L),
            )
        }
    } catch (failure: Throwable) {
        throw SmbException(SmbFailureKind.IO, "SMB directory listing failed", failure)
    }

    override fun openFile(serverPath: String): SmbRandomAccessFile = try {
        val file = share.openFile(
            serverPath,
            EnumSet.of(AccessMask.FILE_READ_DATA, AccessMask.FILE_READ_ATTRIBUTES),
            null,
            EnumSet.of(
                SMB2ShareAccess.FILE_SHARE_READ,
                SMB2ShareAccess.FILE_SHARE_WRITE,
                SMB2ShareAccess.FILE_SHARE_DELETE,
            ),
            SMB2CreateDisposition.FILE_OPEN,
            null,
        )
        SmbjRandomAccessFile(file)
    } catch (failure: Throwable) {
        throw SmbException(SmbFailureKind.IO, "SMB file open failed", failure)
    }

    override fun close() {
        var firstFailure: Throwable? = null
        fun closePart(block: () -> Unit) {
            try {
                block()
            } catch (failure: Throwable) {
                if (firstFailure == null) firstFailure = failure else firstFailure?.addSuppressed(failure)
            }
        }
        closePart { share.close() }
        closePart { session.close() }
        closePart { connection.close() }
        closePart { client.close() }
        firstFailure?.let { throw SmbException(SmbFailureKind.IO, "SMB session close failed", it) }
    }
}

private class SmbjRandomAccessFile(
    private val file: com.hierynomus.smbj.share.File,
) : SmbRandomAccessFile {
    override val length: Long = try {
        file.length.coerceAtLeast(0L)
    } catch (failure: Throwable) {
        runCatching { file.close() }
        throw SmbException(SmbFailureKind.IO, "SMB file length query failed", failure)
    }

    override fun read(fileOffset: Long, buffer: ByteArray, offset: Int, length: Int): Int = try {
        file.read(buffer, fileOffset, offset, length)
    } catch (failure: Throwable) {
        throw SmbException(SmbFailureKind.IO, "SMB file read failed", failure)
    }

    override fun close() {
        try {
            file.close()
        } catch (failure: Throwable) {
            throw SmbException(SmbFailureKind.IO, "SMB file close failed", failure)
        }
    }
}