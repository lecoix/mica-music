/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package androidx.media3.decoder.ffmpeg;

import static androidx.media3.exoplayer.audio.AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY;
import static androidx.media3.exoplayer.audio.AudioSink.SINK_FORMAT_SUPPORTED_WITH_TRANSCODING;
import static androidx.media3.exoplayer.audio.AudioSink.SINK_FORMAT_UNSUPPORTED;
import static com.google.common.base.Preconditions.checkNotNull;

import android.os.Handler;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.util.TraceUtil;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.decoder.CryptoConfig;
import androidx.media3.exoplayer.audio.AudioRendererEventListener;
import androidx.media3.exoplayer.audio.AudioSink;
import androidx.media3.exoplayer.audio.AudioSink.SinkFormatSupport;
import androidx.media3.exoplayer.audio.DecoderAudioRenderer;
import androidx.media3.exoplayer.audio.DefaultAudioSink;

/** Decodes and renders audio using FFmpeg. */
@UnstableApi
public final class FfmpegAudioRenderer extends DecoderAudioRenderer<FfmpegAudioDecoder> {

  private static final String TAG = "FfmpegAudioRenderer";

  /** The number of input and output buffers. */
  private static final int NUM_BUFFERS = 16;

  /** The default input buffer size. */
  private static final int DEFAULT_INPUT_BUFFER_SIZE = 960 * 6;

  // Mica R1b: optional role label + support allowlist for renderer-split sinks. Null keeps the
  // stock behaviour used by R1a and release builds.
  @Nullable private final String micaRole;
  @Nullable private final FfmpegFormatPolicy micaPolicy;
  private final boolean preferPcm32ForHighResolution;

  public FfmpegAudioRenderer() {
    this(/* eventHandler= */ null, /* eventListener= */ null);
  }

  /**
   * Creates a new instance.
   *
   * @param eventHandler A handler to use when delivering events to {@code eventListener}. May be
   *     null if delivery of events is not required.
   * @param eventListener A listener of events. May be null if delivery of events is not required.
   * @param audioProcessors Optional {@link AudioProcessor}s that will process audio before output.
   */
  public FfmpegAudioRenderer(
      @Nullable Handler eventHandler,
      @Nullable AudioRendererEventListener eventListener,
      AudioProcessor... audioProcessors) {
    this(
        eventHandler,
        eventListener,
        new DefaultAudioSink.Builder().setAudioProcessors(audioProcessors).build());
  }

  /**
   * Creates a new instance.
   *
   * @param eventHandler A handler to use when delivering events to {@code eventListener}. May be
   *     null if delivery of events is not required.
   * @param eventListener A listener of events. May be null if delivery of events is not required.
   * @param audioSink The sink to which audio will be output.
   */
  public FfmpegAudioRenderer(
      @Nullable Handler eventHandler,
      @Nullable AudioRendererEventListener eventListener,
      AudioSink audioSink) {
    this(
        eventHandler,
        eventListener,
        audioSink,
        /* micaRole= */ null,
        /* micaPolicy= */ null,
        /* preferPcm32ForHighResolution= */ false);
  }

  /**
   * Mica R1b: creates a role-scoped renderer whose {@code supportsFormat} is gated by {@code
   * micaPolicy}, so several renderers can share one ExoPlayer with mutually-exclusive sinks.
   *
   * @param micaRole Diagnostic role label (e.g. {@code DsdOnly}). May be null for stock behaviour.
   * @param micaPolicy Allowlist consulted before the stock format check. Null accepts everything the
   *     stock renderer supports.
   */
  public FfmpegAudioRenderer(
      @Nullable Handler eventHandler,
      @Nullable AudioRendererEventListener eventListener,
      AudioSink audioSink,
      @Nullable String micaRole,
      @Nullable FfmpegFormatPolicy micaPolicy) {
    this(
        eventHandler,
        eventListener,
        audioSink,
        micaRole,
        micaPolicy,
        /* preferPcm32ForHighResolution= */ false);
  }

  /** Mica USB prototype constructor: keeps high-resolution integer PCM out of float conversion. */
  public FfmpegAudioRenderer(
      @Nullable Handler eventHandler,
      @Nullable AudioRendererEventListener eventListener,
      AudioSink audioSink,
      @Nullable String micaRole,
      @Nullable FfmpegFormatPolicy micaPolicy,
      boolean preferPcm32ForHighResolution) {
    super(eventHandler, eventListener, audioSink);
    this.micaRole = micaRole;
    this.micaPolicy = micaPolicy;
    this.preferPcm32ForHighResolution = preferPcm32ForHighResolution;
  }

  @Override
  public String getName() {
    return micaRole == null ? TAG : TAG + "[" + micaRole + "]";
  }

  @Override
  protected @C.FormatSupport int supportsFormatInternal(Format format) {
    @C.FormatSupport int formatSupport;
    if (micaPolicy != null && !micaPolicy.acceptsFormat(format)) {
      // Mica R1b: role allowlist rejects this format so another renderer/sink can claim it.
      formatSupport = C.FORMAT_UNSUPPORTED_SUBTYPE;
    } else {
      formatSupport = computeSupportsFormatInternal(format);
    }
    // Mica R1a: diagnostic-only report of the real supportsFormat decision. No-op in release.
    FfmpegRendererSupportProbe.report(getName(), format, formatSupport);
    return formatSupport;
  }

  private @C.FormatSupport int computeSupportsFormatInternal(Format format) {
    String mimeType = checkNotNull(format.sampleMimeType);
    if (!FfmpegLibrary.isAvailable() || !MimeTypes.isAudio(mimeType)) {
      return C.FORMAT_UNSUPPORTED_TYPE;
    } else if (!FfmpegLibrary.supportsFormat(mimeType)
        || (!sinkSupportsFormat(format, C.ENCODING_PCM_16BIT)
            && !sinkSupportsFormat(format, C.ENCODING_PCM_FLOAT)
            && !(preferPcm32ForHighResolution
                && sinkSupportsFormat(format, C.ENCODING_PCM_32BIT)))) {
      return C.FORMAT_UNSUPPORTED_SUBTYPE;
    } else if (format.cryptoType != C.CRYPTO_TYPE_NONE) {
      return C.FORMAT_UNSUPPORTED_DRM;
    } else {
      return C.FORMAT_HANDLED;
    }
  }

  @Override
  public @AdaptiveSupport int supportsMixedMimeTypeAdaptation() {
    return ADAPTIVE_NOT_SEAMLESS;
  }

  @Override
  protected FfmpegAudioDecoder createDecoder(Format format, @Nullable CryptoConfig cryptoConfig)
      throws FfmpegDecoderException {
    TraceUtil.beginSection("createFfmpegAudioDecoder");
    int initialInputBufferSize =
        format.maxInputSize != Format.NO_VALUE ? format.maxInputSize : DEFAULT_INPUT_BUFFER_SIZE;
    FfmpegAudioDecoder decoder =
        new FfmpegAudioDecoder(
            format, NUM_BUFFERS, NUM_BUFFERS, initialInputBufferSize, chooseOutputEncoding(format));
    TraceUtil.endSection();
    return decoder;
  }

  @Override
  protected Format getOutputFormat(FfmpegAudioDecoder decoder) {
    checkNotNull(decoder);
    return new Format.Builder()
        .setSampleMimeType(MimeTypes.AUDIO_RAW)
        .setChannelCount(decoder.getChannelCount())
        .setSampleRate(decoder.getSampleRate())
        .setPcmEncoding(decoder.getEncoding())
        .build();
  }

  /**
   * Returns whether the renderer's {@link AudioSink} supports the PCM format that will be output
   * from the decoder for the given input format and requested output encoding.
   */
  private boolean sinkSupportsFormat(Format inputFormat, @C.PcmEncoding int pcmEncoding) {
    return sinkSupportsFormat(
        Util.getPcmFormat(pcmEncoding, inputFormat.channelCount, inputFormat.sampleRate));
  }

  private boolean shouldOutputFloat(Format inputFormat) {
    if ("audio/dsd".equals(inputFormat.sampleMimeType)) {
      // DSD decodes to planar float. Keep float output so Mica's AudioProcessor can
      // decimate the decoder-rate PCM before AudioTrack configuration.
      return true;
    }
    if (!sinkSupportsFormat(inputFormat, C.ENCODING_PCM_16BIT)) {
      // We have no choice because the sink doesn't support 16-bit integer PCM.
      return true;
    }

    @SinkFormatSupport
    int formatSupport =
        getSinkFormatSupport(
            Util.getPcmFormat(
                C.ENCODING_PCM_FLOAT, inputFormat.channelCount, inputFormat.sampleRate));
    switch (formatSupport) {
      case SINK_FORMAT_SUPPORTED_DIRECTLY:
        // AC-3 is always 16-bit, so there's no point using floating point. Assume that it's worth
        // using for all other formats.
        return !MimeTypes.AUDIO_AC3.equals(inputFormat.sampleMimeType);
      case SINK_FORMAT_UNSUPPORTED:
      case SINK_FORMAT_SUPPORTED_WITH_TRANSCODING:
      default:
        // Always prefer 16-bit PCM if the sink does not provide direct support for floating point.
        return false;
    }
  }

  private @C.PcmEncoding int chooseOutputEncoding(Format inputFormat) {
    if (preferPcm32ForHighResolution
        && getSinkFormatSupport(
                Util.getPcmFormat(
                    C.ENCODING_PCM_32BIT, inputFormat.channelCount, inputFormat.sampleRate))
            == SINK_FORMAT_SUPPORTED_DIRECTLY) {
      return C.ENCODING_PCM_32BIT;
    }
    return shouldOutputFloat(inputFormat) ? C.ENCODING_PCM_FLOAT : C.ENCODING_PCM_16BIT;
  }
}
