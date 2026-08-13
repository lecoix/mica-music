package com.mica.music.media.usbprototype

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import com.mica.music.media.dsd.DsdContainerReader
import com.mica.music.media.dsd.DsdContainerReaders
import com.mica.music.media.dsd.DsdContainerType
import com.mica.music.media.dsd.DsdStreamInfo

internal data class UsbDoPContentSourceCandidate(
    val uri: Uri,
    val displayName: String,
    val sizeBytes: Long,
    val modifiedSeconds: Long,
)

internal data class UsbDoPSelectedContentSource(
    val candidate: UsbDoPContentSourceCandidate,
    val reader: DsdContainerReader,
)

/** Enumerates actual MediaStore DSF files, but trusts only P5 reader info for source semantics. */
internal object UsbDoPContentSourceSelector {
    private const val DSD64 = 2_822_400
    private const val DSD128 = 5_644_800
    private const val PREFERRED_DURATION_US = 6_000_000L

    fun select(context: Context, publish: (String) -> Unit): UsbDoPSelectedContentSource? {
        val resolver = context.contentResolver
        val filesUri = MediaStore.Files.getContentUri("external")
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.DATE_MODIFIED,
            MediaStore.Files.FileColumns.DATE_ADDED,
        )
        val candidates = mutableListOf<UsbDoPContentSourceCandidate>()
        resolver.query(
            filesUri,
            projection,
            "LOWER(${MediaStore.Files.FileColumns.DISPLAY_NAME}) LIKE ?",
            arrayOf("%.dsf"),
            "${MediaStore.Files.FileColumns.DATE_ADDED} DESC",
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
            val sizeCol = cursor.getColumnIndex(MediaStore.Files.FileColumns.SIZE)
            val modifiedCol = cursor.getColumnIndex(MediaStore.Files.FileColumns.DATE_MODIFIED)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                candidates += UsbDoPContentSourceCandidate(
                    uri = ContentUris.withAppendedId(filesUri, id),
                    displayName = cursor.getString(nameCol).orEmpty(),
                    sizeBytes = if (sizeCol >= 0 && !cursor.isNull(sizeCol)) cursor.getLong(sizeCol) else -1L,
                    modifiedSeconds = if (modifiedCol >= 0 && !cursor.isNull(modifiedCol)) {
                        cursor.getLong(modifiedCol)
                    } else {
                        0L
                    },
                )
            }
        }

        publish("dopContentProbe=sourceDiscovery dsfCandidates=${candidates.size}")
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
                publish(
                    "dopContentProbe=sourceCandidate uri=${candidate.uri} status=reader_rejected " +
                        "detail=${sanitize(error.message)}",
                )
                continue
            }
            val info = reader.info
            publish(
                "dopContentProbe=sourceCandidate uri=${candidate.uri} size=${candidate.sizeBytes} " +
                    "generation=${candidate.modifiedSeconds} container=${info.container} " +
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
            val score = score(info)
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

    private fun score(info: DsdStreamInfo): Long {
        val durationTier = if (info.durationUs >= PREFERRED_DURATION_US) 1_000_000_000_000L else 0L
        val rateTier = when (info.sampleRateHz) {
            DSD64 -> 100_000_000_000L
            DSD128 -> 50_000_000_000L
            else -> 0L
        }
        return durationTier + rateTier + info.durationUs.coerceAtMost(49_999_999_999L)
    }

    private fun sanitize(value: String?): String =
        value.orEmpty().replace('\n', ' ').replace('\r', ' ').take(400)
}