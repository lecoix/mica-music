package com.mica.music.data

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.DocumentsContract
import android.util.Log
import kotlinx.coroutines.runBlocking

/**
 * Debug-only user-selected SAF provider gate.
 *
 * It launches the real system DocumentsUI tree picker, persists the returned read grant, then
 * runs the production metadata walker against exactly that user-selected tree.
 */
class LibraryAutoSyncQaPickerActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            val picker = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                        Intent.FLAG_GRANT_PREFIX_URI_PERMISSION,
                )
                putExtra(
                    DocumentsContract.EXTRA_INITIAL_URI,
                    intent.getStringExtra(EXTRA_INITIAL_URI)
                        ?.takeIf { it.isNotBlank() }
                        ?.let(Uri::parse)
                        ?: Uri.parse(
                            "content://com.android.externalstorage.documents/document/primary%3AMusic",
                        ),
                )
            }
            startActivityForResult(picker, REQUEST_TREE)
        }
    }

    // takePersistableUriPermission accepts the READ/WRITE grant bitmask from Intent.flags;
    // lint loses the @IntDef after the runtime mask below.
    @SuppressLint("WrongConstant")
    @Deprecated("Legacy debug-only picker callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_TREE) return
        if (resultCode != RESULT_OK) {
            Log.w(TAG, "picker-cancelled resultCode=" + resultCode)
            finish()
            return
        }
        val treeUri = data?.data
        if (treeUri == null) {
            Log.e(TAG, "picker-failed missing tree URI")
            finish()
            return
        }

        val grantedFlags = data.flags and (
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        runCatching {
            contentResolver.takePersistableUriPermission(treeUri, grantedFlags)
        }.onFailure { error ->
            Log.w(TAG, "persist-grant-failed uri=" + treeUri, error)
        }
        Log.i(TAG, "picker-selected uri=" + treeUri + " flags=" + grantedFlags)

        Thread {
            try {
                val startedMs = SystemClock.elapsedRealtime()
                val snapshot = runBlocking {
                    AndroidLibraryScanner(applicationContext).observeFolderMetadata(treeUri)
                }
                Log.i(
                    TAG,
                    "picker-metadata-complete uri=" + treeUri +
                        " entries=" + snapshot.entries.size +
                        " completeness=" + snapshot.discoveryReport.aggregate +
                        " wallMs=" + snapshot.observationStats.wallTimeMs +
                        " externalWallMs=" + (SystemClock.elapsedRealtime() - startedMs) +
                        " providerQueries=" + snapshot.observationStats.providerQueryCount +
                        " directQueries=" + snapshot.observationStats.directQueryCount +
                        " fallbackListings=" + snapshot.observationStats.fallbackListingCount,
                )
            } catch (error: Throwable) {
                Log.e(TAG, "picker-metadata-failed uri=" + treeUri, error)
            } finally {
                runOnUiThread { finish() }
            }
        }.start()
    }

    private companion object {
        const val TAG = "MICA_S4_VENDOR"
        const val EXTRA_INITIAL_URI = "initialUri"
        const val REQUEST_TREE = 0x5344
    }
}
