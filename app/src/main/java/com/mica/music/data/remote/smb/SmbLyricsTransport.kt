package com.mica.music.data.remote.smb

import com.mica.music.data.LyricsDocument
import com.mica.music.data.LyricsSlots
import com.mica.music.data.Song
import com.mica.music.data.remote.RemoteEmbeddedLyricsLoader
import com.mica.music.data.scanner.ExternalLyricsReader
import com.mica.music.data.scanner.LyricsSanitizer
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** On-demand file lyrics over SMB. Catalog sync never opens lyric payloads. */
internal class SmbLyricsLoader(
    private val requestResolver: SmbPlaybackRequestResolver,
    private val embeddedLoader: RemoteEmbeddedLyricsLoader,
    private val sessionFactory: SmbSessionFactory = SmbjSessionFactory(),
) {
    suspend fun load(song: Song): LyricsDocument {
        val request = requestResolver.resolve(song.id) ?: return LyricsDocument()
        return withContext(Dispatchers.IO) {
            var session: SmbSessionHandle? = null
            try {
                session = sessionFactory.open(request.endpoint, request.login)
                val parentPath = request.relativePath.substringBeforeLast('/', "")
                val audioName = request.relativePath.substringAfterLast('/')
                val baseName = audioName.substringBeforeLast('.').trim()
                val entries = session.list(request.endpoint.serverPath(parentPath))
                val sidecars = entries
                    .asSequence()
                    .filterNot(SmbDirectoryEntry::isDirectory)
                    .filter { entry ->
                        entry.name.equals("$baseName.lrc", ignoreCase = true) ||
                            entry.name.equals("$baseName.ttml", ignoreCase = true)
                    }
                    .sortedWith(
                        compareBy<SmbDirectoryEntry> {
                            if (it.name.endsWith(".ttml", ignoreCase = true)) 0 else 1
                        }.thenBy { it.name.lowercase() },
                    )
                    .toList()

                val ttml = readSidecars(
                    session = session,
                    endpoint = request.endpoint,
                    parentPath = parentPath,
                    entries = sidecars.filter { it.name.endsWith(".ttml", ignoreCase = true) },
                )
                LyricsSanitizer.pickBestDocument(ttml)?.let { return@withContext LyricsSlots(externalTtml = it).selected() }
                val lrc = readSidecars(
                    session = session,
                    endpoint = request.endpoint,
                    parentPath = parentPath,
                    entries = sidecars.filter { it.name.endsWith(".lrc", ignoreCase = true) },
                )
                LyricsSanitizer.pickBestDocument(lrc)?.let { return@withContext LyricsSlots(externalLrc = it).selected() }

                val embedded = SmbSeekableByteSource(
                    session.openFile(request.endpoint.serverPath(request.relativePath)),
                ).use(embeddedLoader::load)
                LyricsSlots(embedded = embedded).selected()
            } catch (failure: Throwable) {
                throw if (failure is IOException) failure else IOException("SMB lyrics read failed", failure)
            } finally {
                runCatching { session?.close() }
            }
        }
    }

    private fun readSidecars(
        session: SmbSessionHandle,
        endpoint: SmbEndpoint,
        parentPath: String,
        entries: List<SmbDirectoryEntry>,
    ): List<LyricsDocument> = entries.mapNotNull { entry ->
        val relativePath = SmbPathCodec.normalizeRelativePath(
            if (parentPath.isBlank()) entry.name else "$parentPath/${entry.name}",
        )
        readSidecar(session, endpoint.serverPath(relativePath))
    }

    private fun readSidecar(session: SmbSessionHandle, serverPath: String): LyricsDocument? {
        val file = session.openFile(serverPath)
        return file.use { input ->
            val length = input.length
            if (length < 0L || length > ExternalLyricsReader.MAX_EXTERNAL_LYRICS_BYTES || length > Int.MAX_VALUE) {
                throw IOException("SMB lyrics exceeds size limit")
            }
            val bytes = ByteArray(length.toInt())
            var offset = 0
            while (offset < bytes.size) {
                val read = input.read(offset.toLong(), bytes, offset, bytes.size - offset)
                if (read < 0) throw IOException("SMB lyrics ended before declared length")
                if (read == 0) throw IOException("SMB lyrics read made no progress")
                offset += read
            }
            ExternalLyricsReader.parseRemoteDocument(bytes)
        }
    }
}
