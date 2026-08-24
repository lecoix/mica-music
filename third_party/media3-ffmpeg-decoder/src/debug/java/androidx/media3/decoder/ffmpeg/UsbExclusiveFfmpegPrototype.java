package androidx.media3.decoder.ffmpeg;

// THROWAWAY PROTOTYPE: expose the package-private Media3 FFmpeg decoder to the app debug variant.

import android.content.Context;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.decoder.DecoderInputBuffer;
import androidx.media3.decoder.SimpleDecoderOutputBuffer;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

public final class UsbExclusiveFfmpegPrototype {
  private UsbExclusiveFfmpegPrototype() {}

  public static Result decodeFlac24(
      Context context, Uri uri, int expectedSampleRate, int maxFrames) throws Exception {
    MediaExtractor extractor = new MediaExtractor();
    FfmpegAudioDecoder decoder = null;
    try {
      extractor.setDataSource(context, uri, null);
      int trackIndex = -1;
      MediaFormat mediaFormat = null;
      for (int index = 0; index < extractor.getTrackCount(); index++) {
        MediaFormat candidate = extractor.getTrackFormat(index);
        String candidateMime = candidate.getString(MediaFormat.KEY_MIME);
        if (candidateMime != null && candidateMime.startsWith("audio/")) {
          trackIndex = index;
          mediaFormat = candidate;
          break;
        }
      }
      if (trackIndex < 0 || mediaFormat == null) {
        throw new IllegalArgumentException("No audio track");
      }
      String mime = mediaFormat.getString(MediaFormat.KEY_MIME);
      int sampleRate = mediaFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE);
      int channelCount = mediaFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
      int bits = mediaFormat.getInteger("bits-per-sample");
      if (!MimeTypes.AUDIO_FLAC.equals(mime)
          || sampleRate != expectedSampleRate
          || channelCount != 2
          || bits != 24) {
        throw new IllegalArgumentException(
            "Unexpected input " + mime + " " + sampleRate + "Hz ch=" + channelCount + " bits=" + bits);
      }

      List<byte[]> initializationData = new ArrayList<>();
      for (int index = 0; ; index++) {
        ByteBuffer csd = mediaFormat.getByteBuffer("csd-" + index);
        if (csd == null) break;
        ByteBuffer copy = csd.duplicate();
        byte[] bytes = new byte[copy.remaining()];
        copy.get(bytes);
        initializationData.add(bytes);
      }
      int maxInputSize = mediaFormat.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)
          ? mediaFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
          : 131_072;
      Format format =
          new Format.Builder()
              .setSampleMimeType(MimeTypes.AUDIO_FLAC)
              .setSampleRate(sampleRate)
              .setChannelCount(channelCount)
              .setPcmEncoding(C.ENCODING_PCM_24BIT)
              .setMaxInputSize(maxInputSize)
              .setInitializationData(initializationData)
              .build();
      decoder = new FfmpegAudioDecoder(format, 8, 8, maxInputSize, C.ENCODING_PCM_FLOAT);
      extractor.selectTrack(trackIndex);

      ByteArrayOutputStream packed = new ByteArrayOutputStream(maxFrames * channelCount * 3);
      long sampleCount = 0;
      long nonIntegralSamples = 0;
      double maxResidual = 0.0;
      boolean inputEnded = false;
      long deadlineNanos = System.nanoTime() + 10_000_000_000L;
      while (sampleCount / channelCount < maxFrames && System.nanoTime() < deadlineNanos) {
        boolean progressed = false;
        if (!inputEnded) {
          DecoderInputBuffer input = decoder.dequeueInputBuffer();
          if (input != null) {
            long sampleSize = extractor.getSampleSize();
            if (sampleSize < 0) {
              input.addFlag(C.BUFFER_FLAG_END_OF_STREAM);
              inputEnded = true;
            } else {
              if (sampleSize > Integer.MAX_VALUE) throw new IllegalStateException("Input packet too large");
              input.ensureSpaceForWrite((int) sampleSize);
              ByteBuffer data = input.data;
              if (data == null) throw new IllegalStateException("Missing decoder input buffer");
              data.clear();
              int size = extractor.readSampleData(data, 0);
              if (size < 0) {
                input.addFlag(C.BUFFER_FLAG_END_OF_STREAM);
                inputEnded = true;
              } else {
                data.position(0);
                data.limit(size);
                input.timeUs = extractor.getSampleTime();
                extractor.advance();
              }
            }
            decoder.queueInputBuffer(input);
            progressed = true;
          }
        }

        SimpleDecoderOutputBuffer output = decoder.dequeueOutputBuffer();
        if (output != null) {
          ByteBuffer data = output.data;
          if (data != null && !output.shouldBeSkipped) {
            ByteBuffer floats = data.duplicate().order(ByteOrder.nativeOrder());
            while (floats.remaining() >= 4 && sampleCount / channelCount < maxFrames) {
              float value = floats.getFloat();
              double scaled = value * 8_388_608.0;
              long rounded = Math.round(scaled);
              double residual = Math.abs(scaled - rounded);
              maxResidual = Math.max(maxResidual, residual);
              if (residual > 0.000_001) nonIntegralSamples++;
              int pcm = (int) Math.max(-8_388_608L, Math.min(8_388_607L, rounded));
              packed.write(pcm & 0xff);
              packed.write((pcm >>> 8) & 0xff);
              packed.write((pcm >>> 16) & 0xff);
              sampleCount++;
            }
          }
          output.release();
          progressed = true;
        }
        if (!progressed) Thread.sleep(1);
      }
      return new Result(
          decoder.getName(),
          decoder.getSampleRate(),
          decoder.getChannelCount(),
          decoder.getEncoding(),
          sampleCount,
          nonIntegralSamples,
          maxResidual,
          packed.toByteArray());
    } finally {
      if (decoder != null) decoder.release();
      extractor.release();
    }
  }

  public static final class Result {
    public final String decoderName;
    public final int sampleRate;
    public final int channelCount;
    public final int encoding;
    public final long sampleCount;
    public final long nonIntegralSamples;
    public final double maxResidual;
    public final byte[] packedPcm24;

    Result(
        String decoderName,
        int sampleRate,
        int channelCount,
        int encoding,
        long sampleCount,
        long nonIntegralSamples,
        double maxResidual,
        byte[] packedPcm24) {
      this.decoderName = decoderName;
      this.sampleRate = sampleRate;
      this.channelCount = channelCount;
      this.encoding = encoding;
      this.sampleCount = sampleCount;
      this.nonIntegralSamples = nonIntegralSamples;
      this.maxResidual = maxResidual;
      this.packedPcm24 = packedPcm24;
    }
  }
}
