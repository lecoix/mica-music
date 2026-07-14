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

class TestDocumentsProvider : DocumentsProvider() {
    private lateinit var audioFile: File

    override fun onCreate(): Boolean {
        audioFile = createSilentWav(requireNotNull(context).cacheDir)
        return true
    }

    override fun queryRoots(projection: Array<out String>?): Cursor =
        MatrixCursor(projection ?: ROOT_COLUMNS).apply {
            newRow()
                .add(Root.COLUMN_ROOT_ID, ROOT_ID)
                .add(Root.COLUMN_DOCUMENT_ID, ROOT_ID)
                .add(Root.COLUMN_TITLE, "Mica contract provider")
                .add(Root.COLUMN_FLAGS, Root.FLAG_SUPPORTS_IS_CHILD)
                .add(Root.COLUMN_MIME_TYPES, "audio/*")
        }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor =
        MatrixCursor(projection ?: DOCUMENT_COLUMNS).apply { addDocument(documentId) }

    override fun getDocumentType(documentId: String): String = when (documentId) {
        ROOT_ID, MUSIC_ID -> Document.MIME_TYPE_DIR
        AUDIO_ID -> "audio/wav"
        else -> error("Unknown document: $documentId")
    }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?,
    ): Cursor = MatrixCursor(projection ?: DOCUMENT_COLUMNS).apply {
        when (parentDocumentId) {
            ROOT_ID -> addDocument(MUSIC_ID)
            MUSIC_ID -> addDocument(AUDIO_ID)
        }
    }

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?,
    ): ParcelFileDescriptor {
        require(documentId == AUDIO_ID) { "Unknown document: $documentId" }
        return ParcelFileDescriptor.open(audioFile, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean =
        parentDocumentId == ROOT_ID && documentId in setOf(MUSIC_ID, AUDIO_ID) ||
            parentDocumentId == MUSIC_ID && documentId == AUDIO_ID

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
            AUDIO_ID -> newRow()
                .add(Document.COLUMN_DOCUMENT_ID, AUDIO_ID)
                .add(Document.COLUMN_DISPLAY_NAME, audioFile.name)
                .add(Document.COLUMN_MIME_TYPE, "audio/wav")
                .add(Document.COLUMN_SIZE, audioFile.length())
                .add(Document.COLUMN_LAST_MODIFIED, audioFile.lastModified())
                .add(Document.COLUMN_FLAGS, 0)
            else -> error("Unknown document: $documentId")
        }
    }

    private fun createSilentWav(directory: File): File {
        val dataSize = SAMPLE_RATE * BYTES_PER_SAMPLE
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
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
        }.array()
        return File(directory, "mica-contract-saf-provider.wav").also { file ->
            FileOutputStream(file).use { output ->
                output.write(header)
                output.write(ByteArray(dataSize))
            }
        }
    }

    companion object {
        const val AUTHORITY = "com.mica.music.test.documents"
        const val ROOT_ID = "root"
        private const val MUSIC_ID = "root/music"
        private const val AUDIO_ID = "root/music/contract.wav"
        private const val SAMPLE_RATE = 8_000
        private const val BYTES_PER_SAMPLE = 2

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
