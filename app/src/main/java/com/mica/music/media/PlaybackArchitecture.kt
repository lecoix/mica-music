package com.mica.music.media

import androidx.media3.common.PlaybackException
import com.mica.music.data.DsdSupport
import com.mica.music.data.Song
import java.io.FileNotFoundException
import java.util.concurrent.atomic.AtomicLong

enum class PlaybackFailureKind {
    SOURCE_PERMISSION,
    SOURCE_MISSING,
    EXTRACTOR_UNSUPPORTED,
    DECODER_UNSUPPORTED,
    DECODE_FAILED,
    OUTPUT_FAILED,
    CANCELLED,
    UNKNOWN,
}

data class PlaybackFailure(
    val kind: PlaybackFailureKind,
    val message: String,
    val cause: Throwable? = null,
)

data class PlaybackRequest(
    val id: Long,
    val songId: String,
    val sourceRevision: String,
    val startPositionMs: Long,
)

sealed interface PlaybackRouteDecision {
    val reason: String

    data class Supported(override val reason: String) : PlaybackRouteDecision

    data class Unsupported(
        override val reason: String,
        val userMessage: String,
    ) : PlaybackRouteDecision
}

object PlaybackSourceRevision {
    fun of(song: Song): String =
        "${song.id}|${song.mediaUri}|${song.sizeBytes}|${song.dateModifiedMs}"
}

object PlaybackRouter {
    fun decide(song: Song): PlaybackRouteDecision {
        val decision = if (DsdSupport.isDsdSong(song)) {
            if (isDsfFile(song)) {
                PlaybackRouteDecision.Supported("dsf-exo-extractor")
            } else {
                PlaybackRouteDecision.Unsupported(
                    reason = "dsd-dff-unsupported",
                    userMessage = "不支持 DFF/DSDIFF 格式，请使用 DSF",
                )
            }
        } else if (AlacPlayback.isAlac(song)) {
            PlaybackRouteDecision.Supported("alac-ffmpeg")
        } else if (isApeFile(song)) {
            PlaybackRouteDecision.Supported("ape-ffmpeg")
        } else {
            PlaybackRouteDecision.Supported("platform-format")
        }
        RendererSupportProbeDiagnostics.logRoute(song, decision)
        return decision
    }

    fun isPlayable(song: Song): Boolean = decide(song) is PlaybackRouteDecision.Supported

    fun unsupportedMessage(song: Song): String? =
        (decide(song) as? PlaybackRouteDecision.Unsupported)?.userMessage

    private fun isDsfFile(song: Song): Boolean {
        val extension = song.fileName.substringAfterLast('.', "")
        val mime = song.metadata.playbackMimeType
        return extension.equals("dsf", ignoreCase = true) ||
            mime.equals("audio/x-dsf", ignoreCase = true) ||
            mime.equals("audio/dsf", ignoreCase = true)
    }

    private fun isApeFile(song: Song): Boolean {
        val extension = song.fileName.substringAfterLast('.', "").lowercase()
        return extension in setOf("ape", "mac") ||
            song.metadata.containerName.equals("APE", ignoreCase = true) ||
            song.metadata.playbackMimeType.contains("ape", ignoreCase = true)
    }
}

object PlaybackFailureClassifier {
    fun classify(error: PlaybackException): PlaybackFailureKind {
        findCause<SecurityException>(error)?.let {
            return PlaybackFailureKind.SOURCE_PERMISSION
        }
        findCause<FileNotFoundException>(error)?.let {
            return PlaybackFailureKind.SOURCE_MISSING
        }
        if (isAudioTrackBufferSizeFailure(error) || isUsbHybridOutputFailure(error)) {
            return PlaybackFailureKind.OUTPUT_FAILED
        }
        return when (error.errorCode) {
        PlaybackException.ERROR_CODE_IO_NO_PERMISSION ->
            PlaybackFailureKind.SOURCE_PERMISSION
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND ->
            PlaybackFailureKind.SOURCE_MISSING
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
        -> PlaybackFailureKind.EXTRACTOR_UNSUPPORTED
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        -> PlaybackFailureKind.DECODER_UNSUPPORTED
        PlaybackException.ERROR_CODE_DECODING_FAILED ->
            PlaybackFailureKind.DECODE_FAILED
        PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
        PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED,
        -> PlaybackFailureKind.OUTPUT_FAILED
        else -> PlaybackFailureKind.UNKNOWN
        }
    }

    fun allowsAutomaticSkip(kind: PlaybackFailureKind): Boolean =
        kind != PlaybackFailureKind.SOURCE_PERMISSION &&
            kind != PlaybackFailureKind.OUTPUT_FAILED &&
            kind != PlaybackFailureKind.CANCELLED

    private fun isUsbHybridOutputFailure(error: Throwable): Boolean {
        val visited = HashSet<Throwable>()
        var current: Throwable? = error
        while (current != null && visited.add(current)) {
            if (current.stackTrace.any { element ->
                    element.className == "com.mica.music.media.usbhybrid.UsbHybridDsdRenderer" ||
                        element.className == "com.mica.music.media.usbhybrid.UsbHybridPcmAudioSink"
                }
            ) {
                return true
            }
            current = current.cause
        }
        return false
    }

    private fun isAudioTrackBufferSizeFailure(error: Throwable): Boolean {
        val visited = HashSet<Throwable>()
        var current: Throwable? = error
        while (current != null && visited.add(current)) {
            for (element in current.stackTrace) {
                if (element.className.endsWith("AudioTrackAudioOutputProvider") &&
                    element.methodName == "getAudioTrackMinBufferSize"
                ) {
                    return true
                }
            }
            current = current.cause
        }
        return false
    }

    private inline fun <reified T : Throwable> findCause(error: Throwable): T? {
        val visited = HashSet<Throwable>()
        var current: Throwable? = error
        while (current != null && visited.add(current)) {
            if (current is T) return current
            current = current.cause
        }
        return null
    }
}

class PlaybackRequestSequencer {
    private val nextId = AtomicLong(0)

    fun next(
        song: Song,
        startPositionMs: Long,
    ): PlaybackRequest {
        val id = nextId.incrementAndGet()
        return PlaybackRequest(
            id = id,
            songId = song.id,
            sourceRevision = PlaybackSourceRevision.of(song),
            startPositionMs = startPositionMs.coerceAtLeast(0),
        )
    }
}
