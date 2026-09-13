package com.mica.music.data

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * Debug-only controllable DocumentsProvider used by SAF contract tests and the S4 real-provider
 * shadow gate. Scenario changes alter provider visibility/metadata only; backing fixture files are
 * never deleted.
 */
class TestDocumentsProvider : DocumentsProvider() {

    override fun onCreate(): Boolean = true

    override fun queryRoots(projection: Array<out String>?): Cursor =
        MatrixCursor(projection ?: ROOT_COLUMNS).apply {
            newRow()
                .add(Root.COLUMN_ROOT_ID, ROOT_ID)
                .add(Root.COLUMN_DOCUMENT_ID, ROOT_ID)
                .add(Root.COLUMN_TITLE, "Mica contract provider")
                .add(Root.COLUMN_FLAGS, Root.FLAG_SUPPORTS_IS_CHILD)
                .add(Root.COLUMN_MIME_TYPES, "audio/*\nvideo/*\ntext/*")
        }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor =
        MatrixCursor(projection ?: DOCUMENT_COLUMNS).apply { addDocument(documentId) }

    override fun getDocumentType(documentId: String): String = when (documentId) {
        ROOT_ID, MUSIC_ID -> Document.MIME_TYPE_DIR
        else -> currentDocuments()
            .firstOrNull { it.documentId == documentId }
            ?.mimeType
            ?: error("Unknown document: $documentId")
    }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        beforeChildQuery()
        return MatrixCursor(projection ?: DOCUMENT_COLUMNS).apply {
            when (parentDocumentId) {
                ROOT_ID -> addDocument(MUSIC_ID)
                MUSIC_ID -> currentDocuments().forEach { fixture -> addFixtureDocument(fixture) }
            }
        }
    }

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?,
    ): ParcelFileDescriptor {
        val fixture = currentDocuments()
            .firstOrNull { it.documentId == documentId }
            ?: error("Unknown document: $documentId")
        val file = fixture.backingFile(
            requireNotNull(context).cacheDir,
            currentScenario,
        )
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean =
        parentDocumentId == ROOT_ID && (
            documentId == MUSIC_ID ||
                currentDocuments().any { it.documentId == documentId }
            ) ||
            parentDocumentId == MUSIC_ID &&
            currentDocuments().any { it.documentId == documentId }

    private fun MatrixCursor.addFixtureDocument(fixture: FixtureDocument) {
        newRow()
            .add(Document.COLUMN_DOCUMENT_ID, fixture.documentId)
            .add(Document.COLUMN_DISPLAY_NAME, fixture.displayName)
            .add(Document.COLUMN_MIME_TYPE, fixture.mimeType)
            .add(Document.COLUMN_SIZE, fixture.reportedSizeBytes)
            .add(Document.COLUMN_LAST_MODIFIED, fixture.reportedLastModifiedMs)
            .add(Document.COLUMN_FLAGS, 0)
    }

    private fun MatrixCursor.addDocument(documentId: String) {
        when (documentId) {
            ROOT_ID -> newRow()
                .add(Document.COLUMN_DOCUMENT_ID, ROOT_ID)
                .add(Document.COLUMN_DISPLAY_NAME, "Contract root")
                .add(Document.COLUMN_MIME_TYPE, Document.MIME_TYPE_DIR)
                .add(Document.COLUMN_FLAGS, Document.FLAG_DIR_SUPPORTS_CREATE)

            MUSIC_ID -> newRow()
                .add(Document.COLUMN_DOCUMENT_ID, MUSIC_ID)
                .add(Document.COLUMN_DISPLAY_NAME, "Music")
                .add(Document.COLUMN_MIME_TYPE, Document.MIME_TYPE_DIR)
                .add(Document.COLUMN_FLAGS, 0)

            else -> {
                val fixture = currentDocuments()
                    .firstOrNull { it.documentId == documentId }
                    ?: error("Unknown document: $documentId")
                addFixtureDocument(fixture)
            }
        }
    }

    private data class FixtureDocument(
        val documentId: String,
        val displayName: String,
        val mimeType: String,
        val reportedSizeBytes: Long,
        val reportedLastModifiedMs: Long,
        val payload: FixturePayload,
        val backingFileKey: String? = null,
    ) {
        fun backingFile(
            directory: File,
            scenario: FixtureScenario,
        ): File {
            val safeName = (backingFileKey ?: documentId.substringAfterLast('/'))
                .replace(Regex("[^A-Za-z0-9._-]"), "_")
            val file = File(
                directory,
                "mica-s4-provider-" + FIXTURE_REVISION + "-" +
                    scenario.name.lowercase(Locale.ROOT) + "-" + safeName,
            )
            if (!file.exists()) {
                when (val value = payload) {
                    is FixturePayload.SilentWav ->
                        writeSilentWav(file, durationSec = value.durationSec)
                    is FixturePayload.Text ->
                        FileOutputStream(file).use { it.write(value.text.toByteArray(Charsets.UTF_8)) }
                    is FixturePayload.Bytes ->
                        FileOutputStream(file).use { it.write(value.bytes) }
                }
            }
            return file
        }
    }

    private sealed interface FixturePayload {
        data class SilentWav(val durationSec: Int) : FixturePayload
        data class Text(val text: String) : FixturePayload
        data class Bytes(val bytes: ByteArray) : FixturePayload
    }

    enum class FixtureScenario {
        EMPTY_DIRECTORY,
        ADDED_HUNDRED,
        BASELINE,
        CHANGED,
        SERIALIZED_MULTI_CHANGED,
        ADDED,
        REMOVED,
        LYRICS_CHANGED,
        UNKNOWN_CHANGED,
        WEAK_VIDEO,
        TEN_K_METADATA,
        TEN_K_HEAVY_BASELINE,
        TEN_K_HEAVY_CHANGED,
        TEN_K_UNKNOWN,
    }

    companion object {
        const val AUTHORITY = "com.mica.music.test.documents"
        const val ROOT_ID = "root"

        private const val MUSIC_ID = "root/music"
        private const val AUDIO_ID = "root/music/contract.wav"
        private const val AUDIO_2_ID = "root/music/second.wav"
        private const val LRC_ID = "root/music/contract.lrc"
        private const val VIDEO_ID = "root/music/contract.mp4"
        private const val SAMPLE_RATE = 8_000
        private const val BYTES_PER_SAMPLE = 2
        private const val WAV_HEADER_BYTES = 44L
        private const val FIXTURE_REVISION = "v2-65s"

        @Volatile
        private var currentScenario: FixtureScenario = FixtureScenario.BASELINE

        @Volatile
        private var childQueryDelayMs: Long = 0L

        @Volatile
        private var serializeChildQueries: Boolean = true

        @Volatile
        private var ignoreChildQueryInterrupts: Boolean = false

        private val remainingInjectedChildQueryFailures = AtomicInteger(0)
        private val observedChildQueryCount = AtomicInteger(0)
        private val activeChildQueryCount = AtomicInteger(0)
        private val maxObservedConcurrentChildQueries = AtomicInteger(0)
        private val childQuerySerializationLock = Any()
        private val providerStateLock = Any()

        @Volatile
        private var exclusiveLeaseToken: String? = null

        fun authorityForPackage(packageName: String): String =
            "$packageName.test.documents"

        fun beginExclusiveLease(token: String, scenario: String): FixtureScenario =
            synchronized(providerStateLock) {
                require(token.isNotBlank())
                check(exclusiveLeaseToken == null || exclusiveLeaseToken == token) {
                    "TestDocumentsProvider already leased by $exclusiveLeaseToken"
                }
                exclusiveLeaseToken = token
                childQueryDelayMs = 0L
                serializeChildQueries = true
                ignoreChildQueryInterrupts = false
                remainingInjectedChildQueryFailures.set(0)
                observedChildQueryCount.set(0)
                activeChildQueryCount.set(0)
                maxObservedConcurrentChildQueries.set(0)
                parseAndSetScenario(scenario)
            }

        fun setScenarioWithinLease(token: String, value: String): FixtureScenario =
            synchronized(providerStateLock) {
                check(exclusiveLeaseToken == token) {
                    "TestDocumentsProvider lease mismatch: owner=$exclusiveLeaseToken caller=$token"
                }
                parseAndSetScenario(value)
            }

        fun endExclusiveLease(token: String) {
            synchronized(providerStateLock) {
                if (exclusiveLeaseToken == token) {
                    exclusiveLeaseToken = null
                }
            }
        }

        fun setScenario(value: String): FixtureScenario =
            synchronized(providerStateLock) {
                if (exclusiveLeaseToken != null) {
                    currentScenario
                } else {
                    parseAndSetScenario(value)
                }
            }

        fun resetScenario() {
            synchronized(providerStateLock) {
                if (exclusiveLeaseToken == null) {
                    currentScenario = FixtureScenario.BASELINE
                }
            }
        }

        fun scenarioName(): String = currentScenario.name

        fun configureChildQueryBehavior(
            delayMs: Long = 0L,
            failNextQueries: Int = 0,
            serializeQueries: Boolean = true,
            ignoreInterrupts: Boolean = false,
        ) {
            synchronized(providerStateLock) {
                if (exclusiveLeaseToken != null) return
                childQueryDelayMs = delayMs.coerceAtLeast(0L)
                serializeChildQueries = serializeQueries
                ignoreChildQueryInterrupts = ignoreInterrupts
                remainingInjectedChildQueryFailures.set(failNextQueries.coerceAtLeast(0))
                observedChildQueryCount.set(0)
                activeChildQueryCount.set(0)
                maxObservedConcurrentChildQueries.set(0)
            }
        }

        fun resetChildQueryBehavior() {
            synchronized(providerStateLock) {
                if (exclusiveLeaseToken != null) return
                childQueryDelayMs = 0L
                serializeChildQueries = true
                ignoreChildQueryInterrupts = false
                remainingInjectedChildQueryFailures.set(0)
                observedChildQueryCount.set(0)
                activeChildQueryCount.set(0)
                maxObservedConcurrentChildQueries.set(0)
            }
        }

        private fun parseAndSetScenario(value: String): FixtureScenario {
            val parsed = FixtureScenario.valueOf(value.trim().uppercase(Locale.ROOT))
            currentScenario = parsed
            return parsed
        }

        fun observedChildQueryCount(): Int = observedChildQueryCount.get()

        fun maxObservedConcurrentChildQueries(): Int =
            maxObservedConcurrentChildQueries.get()

        private fun beforeChildQuery() {
            if (serializeChildQueries) {
                synchronized(childQuerySerializationLock) {
                    runChildQueryBehavior()
                }
            } else {
                runChildQueryBehavior()
            }
        }

        private fun runChildQueryBehavior() {
            observedChildQueryCount.incrementAndGet()
            val active = activeChildQueryCount.incrementAndGet()
            maxObservedConcurrentChildQueries.updateAndGet { previous ->
                maxOf(previous, active)
            }
            try {
                sleepForChildQueryDelay(childQueryDelayMs)
                while (true) {
                    val remaining = remainingInjectedChildQueryFailures.get()
                    if (remaining <= 0) break
                    if (remainingInjectedChildQueryFailures.compareAndSet(remaining, remaining - 1)) {
                        error("Injected S4 provider child-query failure")
                    }
                }
            } finally {
                activeChildQueryCount.decrementAndGet()
            }
        }

        private fun sleepForChildQueryDelay(delayMs: Long) {
            if (delayMs <= 0L) return
            if (!ignoreChildQueryInterrupts) {
                Thread.sleep(delayMs)
                return
            }

            val deadlineNs = System.nanoTime() + delayMs * 1_000_000L
            while (true) {
                val remainingNs = deadlineNs - System.nanoTime()
                if (remainingNs <= 0L) return
                val remainingMs = (remainingNs / 1_000_000L).coerceAtLeast(1L)
                try {
                    Thread.sleep(remainingMs)
                } catch (_: InterruptedException) {
                    // Deliberately emulate a provider that ignores caller thread interruption.
                }
            }
        }

        private fun currentDocuments(): List<FixtureDocument> = when (currentScenario) {
            FixtureScenario.EMPTY_DIRECTORY -> emptyList()
            FixtureScenario.ADDED_HUNDRED -> listOf(
                audioDocument(AUDIO_ID, "contract.wav", durationSec = 65, modifiedMs = 1_000L),
                audioDocument(AUDIO_2_ID, "second.wav", durationSec = 65, modifiedMs = 1_200L),
                lyricsDocument(text = "[00:00.00]baseline\n", modifiedMs = 1_100L),
            ) + (1..100).map { index ->
                audioDocument(
                    "root/music/burst-$index.wav", "burst-$index.wav",
                    durationSec = 65, modifiedMs = 4_000L,
                )
            }
            FixtureScenario.BASELINE -> listOf(
                audioDocument(AUDIO_ID, "contract.wav", durationSec = 65, modifiedMs = 1_000L),
                lyricsDocument(
                    text = "[00:00.00]baseline\n",
                    modifiedMs = 1_100L,
                ),
            )

            FixtureScenario.CHANGED -> listOf(
                audioDocument(AUDIO_ID, "contract.wav", durationSec = 70, modifiedMs = 2_000L),
                lyricsDocument(
                    text = "[00:00.00]baseline\n",
                    modifiedMs = 1_100L,
                ),
            )

            FixtureScenario.SERIALIZED_MULTI_CHANGED -> listOf(
                audioDocument(AUDIO_ID, "contract.wav", durationSec = 70, modifiedMs = 2_000L),
                audioDocument(AUDIO_2_ID, "second.wav", durationSec = 65, modifiedMs = 1_200L),
                lyricsDocument(
                    text = "[00:00.00]baseline\n",
                    modifiedMs = 1_100L,
                ),
            )

            FixtureScenario.ADDED -> listOf(
                audioDocument(AUDIO_ID, "contract.wav", durationSec = 65, modifiedMs = 1_000L),
                audioDocument(AUDIO_2_ID, "second.wav", durationSec = 65, modifiedMs = 1_200L),
                lyricsDocument(
                    text = "[00:00.00]baseline\n",
                    modifiedMs = 1_100L,
                ),
            )

            FixtureScenario.REMOVED -> listOf(
                lyricsDocument(
                    text = "[00:00.00]baseline\n",
                    modifiedMs = 1_100L,
                ),
            )

            FixtureScenario.LYRICS_CHANGED -> listOf(
                audioDocument(AUDIO_ID, "contract.wav", durationSec = 65, modifiedMs = 1_000L),
                lyricsDocument(
                    text = "[00:00.00]changed lyrics\n",
                    modifiedMs = 3_000L,
                ),
            )

            FixtureScenario.UNKNOWN_CHANGED -> listOf(
                audioDocument(
                    AUDIO_ID,
                    "contract.wav",
                    durationSec = 70,
                    modifiedMs = 0L,
                    reportedSizeBytes = 0L,
                ),
                lyricsDocument(
                    text = "[00:00.00]baseline\n",
                    modifiedMs = 1_100L,
                ),
            )

            FixtureScenario.WEAK_VIDEO -> listOf(
                audioDocument(AUDIO_ID, "contract.wav", durationSec = 65, modifiedMs = 1_000L),
                lyricsDocument(
                    text = "[00:00.00]baseline\n",
                    modifiedMs = 1_100L,
                ),
                FixtureDocument(
                    documentId = VIDEO_ID,
                    displayName = "contract.mp4",
                    mimeType = "video/mp4",
                    reportedSizeBytes = 12L,
                    reportedLastModifiedMs = 0L,
                    payload = FixturePayload.Bytes(
                        byteArrayOf(
                            0, 0, 0, 12,
                            'f'.code.toByte(),
                            't'.code.toByte(),
                            'y'.code.toByte(),
                            'p'.code.toByte(),
                            'm'.code.toByte(),
                            'p'.code.toByte(),
                            '4'.code.toByte(),
                            '2'.code.toByte(),
                        ),
                    ),
                ),
            )

            FixtureScenario.TEN_K_METADATA -> TEN_K_METADATA_DOCUMENTS
            FixtureScenario.TEN_K_HEAVY_BASELINE -> TEN_K_HEAVY_BASELINE_DOCUMENTS
            FixtureScenario.TEN_K_HEAVY_CHANGED -> TEN_K_HEAVY_CHANGED_DOCUMENTS
            FixtureScenario.TEN_K_UNKNOWN -> TEN_K_UNKNOWN_DOCUMENTS
        }

        private val TEN_K_METADATA_DOCUMENTS: List<FixtureDocument> by lazy {
            buildList(10_000) {
                // Keep the playback fixture addressable while the remaining 9,999 rows stress the
                // provider metadata path. Metadata profiling never opens the synthetic scale files.
                add(
                    audioDocument(
                        AUDIO_ID,
                        "contract.wav",
                        durationSec = 65,
                        modifiedMs = 1_000L,
                    ),
                )
                repeat(9_999) { index ->
                    val ordinal = index.toString().padStart(5, '0')
                    add(
                        audioDocument(
                            documentId = "root/music/scale-$ordinal.wav",
                            displayName = "scale-$ordinal.wav",
                            durationSec = 65,
                            modifiedMs = 10_000L + index,
                        ),
                    )
                }
            }
        }

        private val TEN_K_HEAVY_BASELINE_DOCUMENTS: List<FixtureDocument> by lazy {
            tenKHeavyDocuments(modifiedBaseMs = 100_000L)
        }

        private val TEN_K_HEAVY_CHANGED_DOCUMENTS: List<FixtureDocument> by lazy {
            tenKHeavyDocuments(modifiedBaseMs = 200_000L)
        }

        private val TEN_K_UNKNOWN_DOCUMENTS: List<FixtureDocument> by lazy {
            List(10_000) { index ->
                val ordinal = index.toString().padStart(5, '0')
                audioDocument(
                    documentId = "root/music/unknown-$ordinal.wav",
                    displayName = "unknown-$ordinal.wav",
                    durationSec = 65,
                    modifiedMs = 0L,
                    reportedSizeBytes = 0L,
                    backingFileKey = "ten-k-unknown-shared-65s.wav",
                )
            }
        }

        private fun tenKHeavyDocuments(modifiedBaseMs: Long): List<FixtureDocument> =
            List(10_000) { index ->
                val ordinal = index.toString().padStart(5, '0')
                audioDocument(
                    documentId = "root/music/heavy-$ordinal.wav",
                    displayName = "heavy-$ordinal.wav",
                    durationSec = 65,
                    modifiedMs = modifiedBaseMs + index,
                    backingFileKey = "ten-k-heavy-shared-65s.wav",
                )
            }

        private fun audioDocument(
            documentId: String,
            displayName: String,
            durationSec: Int,
            modifiedMs: Long,
            reportedSizeBytes: Long =
                WAV_HEADER_BYTES + durationSec.toLong() * SAMPLE_RATE * BYTES_PER_SAMPLE,
            backingFileKey: String? = null,
        ) = FixtureDocument(
            documentId = documentId,
            displayName = displayName,
            mimeType = "audio/wav",
            reportedSizeBytes = reportedSizeBytes,
            reportedLastModifiedMs = modifiedMs,
            payload = FixturePayload.SilentWav(durationSec),
            backingFileKey = backingFileKey,
        )

        private fun lyricsDocument(
            text: String,
            modifiedMs: Long,
        ) = FixtureDocument(
            documentId = LRC_ID,
            displayName = "contract.lrc",
            mimeType = "text/plain",
            reportedSizeBytes = text.toByteArray(Charsets.UTF_8).size.toLong(),
            reportedLastModifiedMs = modifiedMs,
            payload = FixturePayload.Text(text),
        )

        private fun writeSilentWav(
            file: File,
            durationSec: Int,
        ) {
            val dataSize = SAMPLE_RATE * BYTES_PER_SAMPLE * durationSec
            val header = ByteBuffer.allocate(WAV_HEADER_BYTES.toInt())
                .order(ByteOrder.LITTLE_ENDIAN)
                .apply {
                    put("RIFF".toByteArray())
                    putInt(36 + dataSize)
                    put("WAVE".toByteArray())
                    put("fmt ".toByteArray())
                    putInt(16)
                    putShort(1)
                    putShort(1)
                    putInt(SAMPLE_RATE)
                    putInt(SAMPLE_RATE * BYTES_PER_SAMPLE)
                    putShort(BYTES_PER_SAMPLE.toShort())
                    putShort(16)
                    put("data".toByteArray())
                    putInt(dataSize)
                }
                .array()
            FileOutputStream(file).use { output ->
                output.write(header)
                output.write(ByteArray(dataSize))
            }
        }

        private val ROOT_COLUMNS = arrayOf(
            Root.COLUMN_ROOT_ID,
            Root.COLUMN_DOCUMENT_ID,
            Root.COLUMN_TITLE,
            Root.COLUMN_FLAGS,
            Root.COLUMN_MIME_TYPES,
        )

        private val DOCUMENT_COLUMNS = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED,
            Document.COLUMN_FLAGS,
        )
    }
}
