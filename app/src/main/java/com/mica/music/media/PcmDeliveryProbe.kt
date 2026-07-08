package com.mica.music.media

import android.os.Build
import com.mica.music.data.DsdSupport
import com.mica.music.data.Song

internal data class PcmDeliveryLadderStep(
    val format: PcmDeliveryFormat,
    val supported: Boolean,
    val directSupport: Int? = null,
)

internal data class PcmDeliveryProbeResult(
    val route: AudioRouteSnapshot,
    val songId: String,
    val sourceFormat: AlacPcmFormat,
    val isDsd: Boolean,
    val dspPathActive: Boolean,
    val noDspLadder: List<PcmDeliveryLadderStep>,
    val dspLadder: List<PcmDeliveryLadderStep>,
    val selectedNoDsp: PcmDeliveryFormat?,
    val selectedDsp: PcmDeliveryFormat?,
    val dsdIntCandidates: List<AlacPcmFormat>,
)

/** Gate 3-0: probe-only delivery ladders; does not change playback. */
internal object PcmDeliveryProbe {

    fun probe(
        context: android.content.Context,
        song: Song,
        dspPathActive: Boolean,
    ): PcmDeliveryProbeResult {
        val route = AudioOutputCapabilities.route(context)
        val sourceFormat = AlacPcmFormat.fromSong(song)
        val isDsd = isDsdSong(song)
        val noDspLadder = if (isDsd) {
            emptyList()
        } else {
            probeLadder(buildNoDspLadder(sourceFormat)) { format ->
                AudioOutputCapabilities.queryIntSupport(
                    context,
                    (format as PcmDeliveryFormat.IntPcm).toAlacPcmFormat(),
                )
            }
        }
        val dspLadder = if (isDsd) {
            emptyList()
        } else {
            probeLadder(buildDspLadder(sourceFormat)) { format ->
                when (format) {
                    is PcmDeliveryFormat.FloatPcm ->
                        AudioOutputCapabilities.queryFloatSupport(
                            context,
                            format.sampleRateHz,
                            format.channelCount,
                        )
                    is PcmDeliveryFormat.IntPcm ->
                        AudioOutputCapabilities.queryIntSupport(context, format.toAlacPcmFormat())
                }
            }
        }
        val dsdIntCandidates = if (isDsd) {
            DsdOutputPolicy.candidates(context, sourceFormat.channelCount)
        } else {
            emptyList()
        }
        return PcmDeliveryProbeResult(
            route = route,
            songId = song.id,
            sourceFormat = sourceFormat,
            isDsd = isDsd,
            dspPathActive = dspPathActive,
            noDspLadder = noDspLadder,
            dspLadder = dspLadder,
            selectedNoDsp = noDspLadder.firstSupported(),
            selectedDsp = dspLadder.firstSupported(),
            dsdIntCandidates = dsdIntCandidates,
        )
    }

    internal fun buildNoDspLadder(source: AlacPcmFormat): List<PcmDeliveryFormat> =
        buildList {
            add(
                PcmDeliveryFormat.IntPcm(
                    sampleRateHz = source.sampleRateHz,
                    channelCount = source.channelCount,
                    bitsPerSample = source.bitsPerSample,
                ),
            )
            if (source.bitsPerSample > 16) {
                add(
                    PcmDeliveryFormat.IntPcm(
                        sampleRateHz = source.sampleRateHz,
                        channelCount = source.channelCount,
                        bitsPerSample = 16,
                    ),
                )
            }
        }

    internal fun buildDspLadder(source: AlacPcmFormat): List<PcmDeliveryFormat> =
        buildList {
            add(
                PcmDeliveryFormat.FloatPcm(
                    sampleRateHz = source.sampleRateHz,
                    channelCount = source.channelCount,
                ),
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(
                    PcmDeliveryFormat.IntPcm(
                        sampleRateHz = source.sampleRateHz,
                        channelCount = source.channelCount,
                        bitsPerSample = 24,
                    ),
                )
            }
            add(
                PcmDeliveryFormat.IntPcm(
                    sampleRateHz = source.sampleRateHz,
                    channelCount = source.channelCount,
                    bitsPerSample = 16,
                ),
            )
        }

    private fun probeLadder(
        formats: List<PcmDeliveryFormat>,
        supported: (PcmDeliveryFormat) -> Boolean,
    ): List<PcmDeliveryLadderStep> =
        formats.map { format ->
            PcmDeliveryLadderStep(
                format = format,
                supported = supported(format),
                directSupport = (format as? PcmDeliveryFormat.IntPcm)
                    ?.toAlacPcmFormat()
                    ?.let { AudioOutputCapabilities.queryDirectSupportLevel(it) },
            )
        }

    private fun List<PcmDeliveryLadderStep>.firstSupported(): PcmDeliveryFormat? =
        firstOrNull { it.supported }?.format

    private fun isDsdSong(song: Song): Boolean = DsdSupport.isDsdSong(song)
}
