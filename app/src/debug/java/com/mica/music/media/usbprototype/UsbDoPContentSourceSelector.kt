package com.mica.music.media.usbprototype

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import com.mica.music.media.dsd.DsdContainerReader
import com.mica.music.media.dsd.DsdContainerReaders
import com.mica.music.media.dsd.DsdContainerType

internal data class UsbDoPContentSourceCandidate(
    val uri: Uri,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val modifiedSeconds: Long,
)

internal data class UsbDoPSelectedContentSource(
    val candidate: UsbDoPContentSourceCandidate,
    val reader: DsdContainerReader,
)

/** Fast candidate discovery facts only; P5 reader info remains source-format authority. */
internal object UsbDoPContentSourceDiscoveryPolicy {
    private val DSF_MIMES = setOf("audio/x-dsf", "audio/dsf")
    private val DFF_MIMES = setOf("audio/x-dsdiff", "audio/dsdiff", "audio/x-dff", "audio/dff")

    fun isCandidate(displayName: String, mimeType: String): Boolean {
        val mime = mimeType.substringBefore(';').trim().lowercase()
        if (mime in DFF_MIMES) return false
        if (mime in DSF_MIMES) return true

        val name = displayName.trim().lowercase()
        if (name.endsWith(".dsf")) return true
        return name.endsWith(".dsf.dsd")
    }

    fun score(sampleRateHz: Int, durationUs: Long): Long {
        val durationTier = if (durationUs >= UsbDoPContentSourceSelector.PREFERRED_DURATION_US) {
            1_000_000_000_000L
        } else {
            0L
        }
        val rateTier = when (sampleRateHz) {
            UsbDoPContentSourceSelector.DSD128 -> 100_000_000_000L
            UsbDoPContentSourceSelector.DSD64 -> 50_000_000_000L
            else -> 0L
        }
        return durationTier + rateTier + durationUs.coerceAtMost(49_999_999_999L)
    }
}

/** Enumerates actual MediaStore audio rows, but trusts only P5 reader info for source semantics. */
internal object UsbDoPContentSourceSelector {
    internal const val DSD64 = 2_822_400
    internal const val DSD128 = 5_644_800
    internal const val PREFERRED_DURATION_US = 6_000_000L

    fun select(context: Context, publish: (String) -> Unit): UsbDoPSelectedContentSource? {
        val resolver = context.contentResolver
        val audioUri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.MIME_TYPE,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.DATE_MODIFIED,
            MediaStore.Audio.Media.DATE_ADDED,
        )
        val candidates = mutableListOf<UsbDoPContentSourceCandidate>()
        var scannedRows = 0
        resolver.query(
            audioUri,
            projection,
            null,
            null,
            "${MediaStore.Audio.Media.DATE_ADDED} DESC",
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
            val mimeCol = cursor.getColumnIndex(MediaStore.Audio.Media.MIME_TYPE)
            val sizeCol = cursor.getColumnIndex(MediaStore.Audio.Media.SIZE)
            val modifiedCol = cursor.getColumnIndex(MediaStore.Audio.Media.DATE_MODIFIED)
            while (cursor.moveToNext()) {
                scannedRows += 1
                val id = cursor.getLong(idCol)
                val displayName = cursor.getString(nameCol).orEmpty()
                val mimeType = if (mimeCol >= 0 && !cursor.isNull(mimeCol)) cursor.getString(mimeCol).orEmpty() else ""
                if (!UsbDoPContentSourceDiscoveryPolicy.isCandidate(displayName, mimeType)) continue
                candidates += UsbDoPContentSourceCandidate(
                    uri = ContentUris.withAppendedId(audioUri, id),
                    displayName = displayName,
                    mimeType = mimeType,
                    sizeBytes = if (sizeCol >= 0 && !cursor.isNull(sizeCol)) cursor.getLong(sizeCol) else -1L,
                    modifiedSeconds = if (modifiedCol >= 0 && !cursor.isNull(modifiedCol)) {
                        cursor.getLong(modifiedCol)
                    } else {
                        0L
                    },
                )
            }
        }

        publish("dopContentProbe=sourceDiscovery audioRows=$scannedRows dsfCandidates=${candidates.size}")
        var best: UsbDoPSelectedContentSource? = null
        var bestScore = Long.MIN_VALUE
        for (candidate in candidates) {
            val source = runCatching {
                AndroidContentSeekableByteSource.open(
                    resolver = resolver,
                    uri = candidate.uri,
                    generation = candidate.modifiedSeconds,
                    knownLength = candidate.sizeBytes.takeIf { it >= 0L },
                )
            }.getOrElse { error ->
                publish(
                    "dopContentProbe=sourceCandidate uri=${candidate.uri} status=open_failed " +
                        "detail=${sanitize(error.message)}",
                )
                continue
            }
            val reader = runCatching { DsdContainerReaders.open(source) }.getOrElse { error ->
                runCatching { source.close() }
                publish(
                    "dopContentProbe=sourceCandidate uri=${candidate.uri} status=reader_rejected " +
                        "detail=${sanitize(error.message)}",
                )
                continue
            }
            val info = reader.info
            publish(
                "dopContentProbe=sourceCandidate uri=${candidate.uri} size=${candidate.sizeBytes} " +
                    "generation=${candidate.modifiedSeconds} mime=${candidate.mimeType} " +
                    "name=${sanitize(candidate.displayName)} container=${info.container} " +
                    "rate=${info.sampleRateHz} channels=${info.channelCount} " +
                    "samples=${info.sampleCountPerChannel} durationUs=${info.durationUs} " +
                    "bitOrder=${info.sourceBitOrder}",
            )
            val compatible = info.container == DsdContainerType.DSF &&
                info.channelCount == 2 &&
                (info.sampleRateHz == DSD64 || info.sampleRateHz == DSD128)
            if (!compatible) {
                reader.close()
                continue
            }
            val score = UsbDoPContentSourceDiscoveryPolicy.score(info.sampleRateHz, info.durationUs)
            if (score > bestScore) {
                best?.reader?.close()
                best = UsbDoPSelectedContentSource(candidate, reader)
                bestScore = score
            } else {
                reader.close()
            }
        }
        best?.let { selected ->
            publish(
                "dopContentProbe=sourceSelected uri=${selected.candidate.uri} " +
                    "stableId=${selected.reader.sourceIdentity.stableId} " +
                    "generation=${selected.reader.sourceIdentity.generation} " +
                    "rate=${selected.reader.info.sampleRateHz} channels=${selected.reader.info.channelCount} " +
                    "durationUs=${selected.reader.info.durationUs}",
            )
        }
        return best
    }

    private fun sanitize(value: String?): String =
        value.orEmpty().replace('\n', ' ').replace('\r', ' ').take(400)
}
