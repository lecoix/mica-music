package com.mica.music.media.loudness

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.ffmpeg.OfflineFfmpegPcmDecoder
import com.mica.music.audio.loudness.LoudnessScanPort
import com.mica.music.audio.loudness.LoudnessScanState
import com.mica.music.data.DsdSupport
import com.mica.music.data.LoudnessAnalysis
import com.mica.music.data.MusicLibrary
import com.mica.music.data.Song
import com.mica.music.data.SongSource
import com.mica.music.data.local.MicaDatabase
import com.mica.music.media.DsdDecimationAudioProcessor
import com.mica.music.media.DsdDecimationOutputMode
import com.mica.music.media.ape.ApeExtractor
import com.mica.music.media.dsf.DsfExtractor
import com.mica.music.media.dsf.DsfHeaderReader
import com.mica.music.util.DiagnosticLog
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Process-scoped loudness analysis queue. Leaving Settings does not cancel an active library scan. */
@UnstableApi
internal object LoudnessScanManager {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val decodeMutex = Mutex()
    private val _state = MutableStateFlow(LoudnessScanState())
    val state: StateFlow<LoudnessScanState> = _state.asStateFlow()

    @Volatile
    private var libraryJob: Job? = null

    fun startLibraryScan(
        context: Context,
        library: MusicLibrary,
        missingOnly: Boolean = true,
    ): Boolean {
        if (libraryJob?.isActive == true) return false
        val appContext = context.applicationContext
        val songs = library.songs.filter { it.source == SongSource.LIBRARY }
        libraryJob = scope.launch {
            var succeeded = 0
            var skipped = 0
            var failed = 0
            var completed = 0
            _state.value = LoudnessScanState(running = true, total = songs.size)
            for (song in songs) {
                _state.value = _state.value.copy(
                    currentSongId = song.id,
                    currentTitle = song.title,
                    lastError = null,
                )
                if (missingOnly && song.loudnessAnalysis.matches(song)) {
                    skipped++
                    completed++
                    _state.value = _state.value.copy(completed = completed, skipped = skipped)
                    continue
                }
                val result = analyzeAndPersist(appContext, song)
                result.onSuccess { analysis ->
                    succeeded++
                    withContext(Dispatchers.Main.immediate) {
                        library.applyLoudnessAnalysis(
                            songId = song.id,
                            analysis = analysis,
                            notifyQueueMetadata = false,
                        )
                    }
                }.onFailure { error ->
                    failed++
                    _state.value = _state.value.copy(lastError = error.message ?: error.javaClass.simpleName)
                }
                completed++
                _state.value = _state.value.copy(
                    completed = completed,
                    succeeded = succeeded,
                    skipped = skipped,
                    failed = failed,
                )
            }
            if (succeeded > 0) {
                withContext(Dispatchers.Main.immediate) {
                    library.notifyLoudnessScanCompleted()
                }
            }
            _state.value = _state.value.copy(
                running = false,
                completed = completed,
                succeeded = succeeded,
                skipped = skipped,
                failed = failed,
                currentSongId = null,
                currentTitle = "",
            )
        }
        return true
    }

    suspend fun analyzeSingle(
        context: Context,
        song: Song,
        library: MusicLibrary,
    ): Result<LoudnessAnalysis> = withContext(Dispatchers.IO) {
        analyzeAndPersist(context.applicationContext, song).onSuccess { analysis ->
            withContext(Dispatchers.Main.immediate) {
                library.applyLoudnessAnalysis(song.id, analysis, notifyQueueMetadata = true)
            }
        }
    }

    private suspend fun analyzeAndPersist(
        context: Context,
        song: Song,
    ): Result<LoudnessAnalysis> = decodeMutex.withLock {
        runCatching {
            check(song.source == SongSource.LIBRARY) { "仅曲库歌曲支持响度分析" }
            val startedNanos = System.nanoTime()
            val uri = Uri.parse(song.mediaUri)
            val isDsd = DsdSupport.isDsdSong(song)
            val extension = song.fileName.substringAfterLast('.', "").lowercase()
            val pcmConsumer = LoudnessPcmConsumer(context, dsd = isDsd)
            val decoder = when {
                isDsd -> {
                    check(hasDsfHeader(context, uri)) { "仅 DSF 支持响度分析；DFF/DSDIFF 暂不支持" }
                    OfflineMicaExtractorPcmDecoder.decode(context, uri, DsfExtractor(), pcmConsumer)
                }
                extension == "ape" || song.metadata.playbackMimeType.contains("ape", ignoreCase = true) ->
                    OfflineMicaExtractorPcmDecoder.decode(context, uri, ApeExtractor(), pcmConsumer)
                else -> OfflineFfmpegPcmDecoder.decode(context, uri, pcmConsumer)
            }
            val analysis = pcmConsumer
                .finish(song.sizeBytes, song.dateModifiedMs)
                ?.takeIf(LoudnessAnalysis::isValid)
                ?: error("有效音频不足，无法计算响度")
            MicaDatabase.get(context).songDao().updateLoudnessAnalysis(
                songId = song.id,
                integratedLufs = analysis.integratedLufs,
                samplePeak = analysis.samplePeak,
                trackGainDb = analysis.trackGainDb,
                sourceSizeBytes = analysis.sourceSizeBytes,
                sourceModifiedMs = analysis.sourceModifiedMs,
                analyzerRevision = analysis.analyzerRevision,
            )
            DiagnosticLog.event(
                "LoudnessScan",
                "song=${song.id.takeLast(12)} decoder=${decoder.decoderName} " +
                    "sr=${decoder.sampleRateHz} ch=${decoder.channelCount} samples=${decoder.sampleCount} " +
                    "lufs=${analysis.integratedLufs} peak=${analysis.samplePeak} gainDb=${analysis.trackGainDb} " +
                    "durMs=${(System.nanoTime() - startedNanos) / 1_000_000L}",
            )
            analysis
        }.onFailure { error ->
            DiagnosticLog.event(
                "LoudnessScan",
                "failed song=${song.id.takeLast(12)} type=${error.javaClass.simpleName} message=${error.message}",
            )
        }
    }

    /**
     * DSF support is determined from Mica's own container signature, not Android MIME or the final
     * filename suffix. Real libraries can contain names such as `track.dsf.dsd` while still being
     * a perfectly valid DSF stream.
     */
    internal fun hasDsfHeader(context: Context, uri: Uri): Boolean = runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val header = ByteArray(4)
            var offset = 0
            while (offset < header.size) {
                val read = input.read(header, offset, header.size - offset)
                if (read < 0) break
                offset += read
            }
            offset == header.size && DsfHeaderReader.sniffHeader(header)
        } ?: false
    }.getOrDefault(false)

    /**
     * DSD's FFmpeg decoder output still runs at a very high PCM rate. Feed it through the same
     * decimation processor as playback before R128 so ultrasonic DSD noise is not treated as
     * program loudness. Integer quantization is intentionally avoided for analysis.
     */
    internal class LoudnessPcmConsumer(
        private val context: Context,
        private val dsd: Boolean,
    ) : OfflineFfmpegPcmDecoder.PcmConsumer {
        private val analyzerRef = AtomicReference<R128LoudnessAnalyzer?>()
        private var dsdProcessor: DsdDecimationAudioProcessor? = null
        private var dsdScratch = FloatArray(0)
        private var inputBuffer: ByteBuffer = ByteBuffer.allocateDirect(0).order(ByteOrder.nativeOrder())

        override fun onFormat(sampleRateHz: Int, channelCount: Int) {
            if (!dsd) {
                analyzerRef.set(R128LoudnessAnalyzer(sampleRateHz, channelCount))
                return
            }
            val processor = DsdDecimationAudioProcessor(
                context = context,
                decimationOutputMode = DsdDecimationOutputMode.FloatPcm,
                forcedTargetSampleRateHz = DSD_ANALYSIS_SAMPLE_RATE_HZ,
            )
            val output = processor.configure(
                AudioProcessor.AudioFormat(sampleRateHz, channelCount, C.ENCODING_PCM_FLOAT),
            )
            dsdProcessor = processor.takeIf { it.isActive }
            analyzerRef.set(R128LoudnessAnalyzer(output.sampleRate, output.channelCount))
        }

        override fun onPcm(interleaved: FloatArray, sampleCount: Int): Boolean {
            val analyzer = analyzerRef.get() ?: return true
            val processor = dsdProcessor
            if (processor == null) {
                analyzer.addInterleaved(interleaved, sampleCount)
                return true
            }
            val requiredBytes = sampleCount * Float.SIZE_BYTES
            if (inputBuffer.capacity() < requiredBytes) {
                inputBuffer = ByteBuffer.allocateDirect(requiredBytes).order(ByteOrder.nativeOrder())
            } else {
                inputBuffer.clear()
            }
            repeat(sampleCount) { index -> inputBuffer.putFloat(interleaved[index]) }
            inputBuffer.flip()
            processor.queueInput(inputBuffer)
            drainDsdProcessor(processor, analyzer)
            return true
        }

        fun finish(sourceSizeBytes: Long, sourceModifiedMs: Long): LoudnessAnalysis? {
            val analyzer = analyzerRef.get() ?: return null
            dsdProcessor?.let { processor ->
                processor.queueEndOfStream()
                drainDsdProcessor(processor, analyzer)
            }
            return analyzer.finish(sourceSizeBytes, sourceModifiedMs)
        }

        private fun drainDsdProcessor(
            processor: DsdDecimationAudioProcessor,
            analyzer: R128LoudnessAnalyzer,
        ) {
            while (true) {
                val output = processor.output
                if (!output.hasRemaining()) return
                val count = output.remaining() / Float.SIZE_BYTES
                if (dsdScratch.size < count) dsdScratch = FloatArray(count)
                val floats = output.duplicate().order(ByteOrder.nativeOrder())
                repeat(count) { index -> dsdScratch[index] = floats.float }
                analyzer.addInterleaved(dsdScratch, count)
                output.position(output.limit())
            }
        }
    }

    private const val DSD_ANALYSIS_SAMPLE_RATE_HZ = 176_400
}
internal class MediaLoudnessScanPort(
    private val context: Context,
) : LoudnessScanPort {
    override val state: StateFlow<LoudnessScanState>
        get() = LoudnessScanManager.state

    override fun startLibraryScan(library: MusicLibrary, missingOnly: Boolean): Boolean =
        LoudnessScanManager.startLibraryScan(context, library, missingOnly)

    override suspend fun analyzeSingle(song: Song, library: MusicLibrary): Result<LoudnessAnalysis> =
        LoudnessScanManager.analyzeSingle(context, song, library)
}