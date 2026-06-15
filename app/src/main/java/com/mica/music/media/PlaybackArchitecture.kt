package com.mica.music.media

import androidx.media3.common.PlaybackException
import com.mica.music.data.DsdSupport
import com.mica.music.data.Song
import java.io.FileNotFoundException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

enum class PlaybackBackendKind { MEDIA3, SOFTWARE }

enum class AudioQualityMode { HIFI, DSP }

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
    val generation: Long,
    val songId: String,
    val sourceRevision: String,
    val backend: PlaybackBackendKind,
    val startPositionMs: Long,
    val userPlayIntent: Boolean,
    val qualityMode: AudioQualityMode,
)

data class PlaybackRouteDecision(
    val primary: PlaybackBackendKind,
    val fallback: PlaybackBackendKind?,
    val reason: String,
)

sealed interface PlaybackEngineState {
    data object Idle : PlaybackEngineState
    data class Preparing(val request: PlaybackRequest) : PlaybackEngineState
    data class Playing(val request: PlaybackRequest, val positionMs: Long) : PlaybackEngineState
    data class Paused(val request: PlaybackRequest, val positionMs: Long) : PlaybackEngineState
    data class Switching(
        val fromRequestId: Long,
        val toRequest: PlaybackRequest,
    ) : PlaybackEngineState
    data class Failed(
        val request: PlaybackRequest,
        val failure: PlaybackFailure,
    ) : PlaybackEngineState
}

object PlaybackSourceRevision {
    fun of(song: Song): String =
        "${song.id}|${song.mediaUri}|${song.sizeBytes}|${song.dateModifiedMs}"
}

object PlaybackRouter {
    fun decide(song: Song): PlaybackRouteDecision {
        if (DsdSupport.isDsdMetadata(song.metadata) ||
            DsdSupport.isDsdExtension(song.fileName.substringAfterLast('.', ""))
        ) {
            return PlaybackRouteDecision(
                primary = PlaybackBackendKind.SOFTWARE,
                fallback = null,
                reason = "dsd-high-quality-pcm",
            )
        }
        if (AlacPlayback.isAlac(song)) {
            return PlaybackRouteDecision(
                primary = PlaybackBackendKind.MEDIA3,
                fallback = PlaybackBackendKind.SOFTWARE,
                reason = "alac-media3-first",
            )
        }
        return PlaybackRouteDecision(
            primary = PlaybackBackendKind.MEDIA3,
            fallback = null,
            reason = "platform-format",
        )
    }
}

object PlaybackFailureClassifier {
    private val decoderNamePattern = Regex("""Decoder failed:\s*([^\s,]+)""")

    fun classify(error: PlaybackException): PlaybackFailureKind {
        findCause<SecurityException>(error)?.let {
            return PlaybackFailureKind.SOURCE_PERMISSION
        }
        findCause<FileNotFoundException>(error)?.let {
            return PlaybackFailureKind.SOURCE_MISSING
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

    fun allowsSoftwareFallback(kind: PlaybackFailureKind): Boolean =
        kind == PlaybackFailureKind.EXTRACTOR_UNSUPPORTED ||
            kind == PlaybackFailureKind.DECODER_UNSUPPORTED ||
            kind == PlaybackFailureKind.DECODE_FAILED

    fun allowsAutomaticSkip(kind: PlaybackFailureKind): Boolean =
        kind != PlaybackFailureKind.SOURCE_PERMISSION &&
            kind != PlaybackFailureKind.CANCELLED

    /**
     * Returns a decoder identity only when the failure chain names an ALAC decoder.
     * This is intentionally narrower than DECODE_FAILED: a damaged file must not
     * disable Media3 ALAC playback for the rest of the process.
     */
    fun stableAlacDecoderIdentity(error: Throwable): String? {
        val visited = HashSet<Throwable>()
        var current: Throwable? = error
        while (current != null && visited.add(current)) {
            val decoder = current.message
                ?.let(decoderNamePattern::find)
                ?.groupValues
                ?.getOrNull(1)
            if (decoder?.contains("alac", ignoreCase = true) == true) return decoder
            current = current.cause
        }
        return null
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
        backend: PlaybackBackendKind,
        startPositionMs: Long,
        playWhenReady: Boolean,
        qualityMode: AudioQualityMode,
    ): PlaybackRequest {
        val id = nextId.incrementAndGet()
        return PlaybackRequest(
            id = id,
            generation = id,
            songId = song.id,
            sourceRevision = PlaybackSourceRevision.of(song),
            backend = backend,
            startPositionMs = startPositionMs.coerceAtLeast(0),
            userPlayIntent = playWhenReady,
            qualityMode = qualityMode,
        )
    }
}

class PlaybackFallbackLedger {
    private val softwareAttempts = ConcurrentHashMap.newKeySet<String>()

    fun claimSoftwareFallback(sourceRevision: String): Boolean =
        softwareAttempts.add(sourceRevision)

    fun hasAttemptedSoftware(sourceRevision: String): Boolean =
        sourceRevision in softwareAttempts

    fun forget(sourceRevision: String) {
        softwareAttempts.remove(sourceRevision)
    }

    fun clear() {
        softwareAttempts.clear()
    }
}
