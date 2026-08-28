package androidx.media3.decoder.ffmpeg;

import android.content.Context;
import android.media.AudioFormat;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.decoder.DecoderInputBuffer;
import androidx.media3.decoder.SimpleDecoderOutputBuffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * Offline, non-realtime PCM bridge for analysis jobs.
 *
 * <p>Compressed samples are demuxed with {@link MediaExtractor} and decoded through the same Media3
 * FFmpeg decoder used by Mica playback. Decoded PCM is delivered immediately and never written to
 * disk. This class deliberately lives in the FFmpeg package so it can reuse the package-private
 * decoder without exposing decoder internals to the app module.
 */
public final class OfflineFfmpegPcmDecoder {
  private static final int DEFAULT_MAX_INPUT_SIZE = 256 * 1024;

  private OfflineFfmpegPcmDecoder() {}

  public interface PcmConsumer {
    /** Called exactly once before PCM delivery. */
    void onFormat(int sampleRateHz, int channelCount);

    /** Return false to stop decoding early. */
    boolean onPcm(float[] interleaved, int sampleCount);
  }

  /**
   * Stateful packet decoder for containers that Mica demuxes with its own Media3 extractors.
   * Compressed packet bytes are still decoded by the exact same FFmpeg decoder as playback.
   */
  public static final class PacketDecoder implements AutoCloseable {
    private final FfmpegAudioDecoder decoder;
    private final PcmConsumer consumer;
    private float[] scratch = new float[0];
    private long sampleCount;
    private boolean inputEnded;
    private boolean outputEnded;
    private boolean closed;

    private PacketDecoder(Format format, PcmConsumer consumer) throws Exception {
      String mime = format.sampleMimeType;
      if (mime == null || !FfmpegLibrary.supportsFormat(mime)) {
        throw new UnsupportedOperationException("FFmpeg decoder unavailable for " + mime);
      }
      int maxInputSize =
          format.maxInputSize == Format.NO_VALUE
              ? DEFAULT_MAX_INPUT_SIZE
              : Math.max(DEFAULT_MAX_INPUT_SIZE, format.maxInputSize);
      decoder =
          new FfmpegAudioDecoder(
              format,
              /* numInputBuffers= */ 8,
              /* numOutputBuffers= */ 8,
              maxInputSize,
              C.ENCODING_PCM_FLOAT);
      this.consumer = consumer;
      consumer.onFormat(format.sampleRate, format.channelCount);
    }

    public void queue(byte[] packet, int offset, int length, long timeUs) throws Exception {
      if (closed || inputEnded || outputEnded) return;
      if (offset < 0 || length < 0 || offset + length > packet.length) {
        throw new IndexOutOfBoundsException("Invalid packet range");
      }
      while (!outputEnded) {
        drainAvailable();
        DecoderInputBuffer input = decoder.dequeueInputBuffer();
        if (input == null) {
          Thread.yield();
          continue;
        }
        input.ensureSpaceForWrite(length);
        ByteBuffer data = input.data;
        if (data == null) throw new IllegalStateException("Missing decoder input buffer");
        data.clear();
        data.put(packet, offset, length);
        data.flip();
        input.timeUs = timeUs;
        decoder.queueInputBuffer(input);
        drainAvailable();
        return;
      }
    }

    public Result finish() throws Exception {
      if (closed) throw new IllegalStateException("PacketDecoder already closed");
      if (!inputEnded && !outputEnded) {
        while (true) {
          drainAvailable();
          DecoderInputBuffer input = decoder.dequeueInputBuffer();
          if (input == null) {
            Thread.yield();
            continue;
          }
          input.addFlag(C.BUFFER_FLAG_END_OF_STREAM);
          decoder.queueInputBuffer(input);
          inputEnded = true;
          break;
        }
      }
      while (!outputEnded) {
        if (!drainAvailable()) Thread.yield();
      }
      int sampleRate = decoder.getSampleRate();
      int channelCount = decoder.getChannelCount();
      return new Result(
          decoder.getName(),
          sampleRate > 0 ? sampleRate : 0,
          channelCount > 0 ? channelCount : 0,
          sampleCount);
    }

    private boolean drainAvailable() throws Exception {
      boolean progressed = false;
      while (true) {
        SimpleDecoderOutputBuffer output = decoder.dequeueOutputBuffer();
        if (output == null) return progressed;
        progressed = true;
        try {
          if (output.isEndOfStream()) {
            outputEnded = true;
          } else if (!output.shouldBeSkipped && output.data != null) {
            ByteBuffer floats = output.data.duplicate().order(ByteOrder.nativeOrder());
            int count = floats.remaining() / Float.BYTES;
            if (scratch.length < count) scratch = new float[count];
            for (int index = 0; index < count; index++) scratch[index] = floats.getFloat();
            sampleCount += count;
            if (count > 0 && !consumer.onPcm(scratch, count)) {
              outputEnded = true;
            }
          }
        } finally {
          output.release();
        }
        if (outputEnded) return true;
      }
    }

    @Override
    public void close() {
      if (closed) return;
      closed = true;
      decoder.release();
    }
  }

  public static PacketDecoder createPacketDecoder(Format format, PcmConsumer consumer)
      throws Exception {
    return new PacketDecoder(format, consumer);
  }

  public static Result decode(Context context, Uri uri, PcmConsumer consumer) throws Exception {
    MediaExtractor extractor = new MediaExtractor();
    FfmpegAudioDecoder decoder = null;
    try {
      extractor.setDataSource(context, uri, null);
      Track track = selectAudioTrack(extractor);
      extractor.selectTrack(track.index);
      consumer.onFormat(track.sampleRateHz, track.channelCount);

      if (MimeTypes.AUDIO_RAW.equals(track.mime)) {
        long samples = decodeRaw(extractor, track, consumer);
        return new Result("pcm", track.sampleRateHz, track.channelCount, samples);
      }
      if (!FfmpegLibrary.supportsFormat(track.mime)) {
        throw new UnsupportedOperationException("FFmpeg decoder unavailable for " + track.mime);
      }

      Format format =
          new Format.Builder()
              .setSampleMimeType(track.mime)
              .setSampleRate(track.sampleRateHz)
              .setChannelCount(track.channelCount)
              .setMaxInputSize(track.maxInputSize)
              .setInitializationData(track.initializationData)
              .build();
      decoder =
          new FfmpegAudioDecoder(
              format,
              /* numInputBuffers= */ 8,
              /* numOutputBuffers= */ 8,
              track.maxInputSize,
              C.ENCODING_PCM_FLOAT);

      boolean inputEnded = false;
      boolean outputEnded = false;
      long sampleCount = 0L;
      float[] scratch = new float[0];
      while (!outputEnded) {
        boolean progressed = false;
        if (!inputEnded) {
          DecoderInputBuffer input = decoder.dequeueInputBuffer();
          if (input != null) {
            long sampleSize = extractor.getSampleSize();
            if (sampleSize < 0) {
              input.addFlag(C.BUFFER_FLAG_END_OF_STREAM);
              inputEnded = true;
            } else {
              if (sampleSize > Integer.MAX_VALUE) {
                throw new IllegalStateException("Input packet too large: " + sampleSize);
              }
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
          try {
            if (output.isEndOfStream()) {
              outputEnded = true;
            } else if (!output.shouldBeSkipped && output.data != null) {
              ByteBuffer floats = output.data.duplicate().order(ByteOrder.nativeOrder());
              int count = floats.remaining() / 4;
              if (scratch.length < count) scratch = new float[count];
              for (int index = 0; index < count; index++) scratch[index] = floats.getFloat();
              sampleCount += count;
              if (count > 0 && !consumer.onPcm(scratch, count)) {
                outputEnded = true;
              }
            }
          } finally {
            output.release();
          }
          progressed = true;
        }
        if (!progressed) Thread.yield();
      }
      return new Result(decoder.getName(), track.sampleRateHz, track.channelCount, sampleCount);
    } finally {
      if (decoder != null) decoder.release();
      extractor.release();
    }
  }

  private static long decodeRaw(MediaExtractor extractor, Track track, PcmConsumer consumer)
      throws Exception {
    ByteBuffer packet = ByteBuffer.allocateDirect(track.maxInputSize).order(ByteOrder.LITTLE_ENDIAN);
    float[] scratch = new float[Math.max(1, track.maxInputSize / Math.max(1, track.bytesPerSample))];
    long sampleCount = 0L;
    while (true) {
      packet.clear();
      int size = extractor.readSampleData(packet, 0);
      if (size < 0) break;
      packet.position(0);
      packet.limit(size);
      int count = 0;
      while (packet.remaining() >= track.bytesPerSample) {
        float value;
        switch (track.pcmEncoding) {
          case AudioFormat.ENCODING_PCM_FLOAT:
            value = packet.getFloat();
            break;
          case AudioFormat.ENCODING_PCM_8BIT:
            value = ((packet.get() & 0xff) - 128) / 128f;
            break;
          case AudioFormat.ENCODING_PCM_24BIT_PACKED:
            int lo = packet.get() & 0xff;
            int mid = packet.get() & 0xff;
            int hi = packet.get();
            int pcm24 = lo | (mid << 8) | (hi << 16);
            value = pcm24 / 8_388_608f;
            break;
          case AudioFormat.ENCODING_PCM_32BIT:
            value = packet.getInt() / 2_147_483_648f;
            break;
          case AudioFormat.ENCODING_PCM_16BIT:
          default:
            value = packet.getShort() / 32_768f;
            break;
        }
        if (count == scratch.length) {
          float[] grown = new float[scratch.length * 2];
          System.arraycopy(scratch, 0, grown, 0, scratch.length);
          scratch = grown;
        }
        scratch[count++] = value;
      }
      sampleCount += count;
      if (count > 0 && !consumer.onPcm(scratch, count)) break;
      extractor.advance();
    }
    return sampleCount;
  }

  private static Track selectAudioTrack(MediaExtractor extractor) {
    for (int index = 0; index < extractor.getTrackCount(); index++) {
      MediaFormat mediaFormat = extractor.getTrackFormat(index);
      String mime = mediaFormat.getString(MediaFormat.KEY_MIME);
      if (mime == null || !mime.startsWith("audio/")) continue;
      int sampleRate = mediaFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE);
      int channelCount = mediaFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
      int maxInputSize =
          mediaFormat.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)
              ? Math.max(DEFAULT_MAX_INPUT_SIZE, mediaFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE))
              : DEFAULT_MAX_INPUT_SIZE;
      List<byte[]> initializationData = new ArrayList<>();
      for (int csdIndex = 0; ; csdIndex++) {
        ByteBuffer csd = mediaFormat.getByteBuffer("csd-" + csdIndex);
        if (csd == null) break;
        ByteBuffer copy = csd.duplicate();
        byte[] bytes = new byte[copy.remaining()];
        copy.get(bytes);
        initializationData.add(bytes);
      }
      int pcmEncoding =
          mediaFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)
              ? mediaFormat.getInteger(MediaFormat.KEY_PCM_ENCODING)
              : AudioFormat.ENCODING_PCM_16BIT;
      int bytesPerSample = bytesPerSample(pcmEncoding, mediaFormat);
      return new Track(
          index,
          mime,
          sampleRate,
          channelCount,
          maxInputSize,
          initializationData,
          pcmEncoding,
          bytesPerSample);
    }
    throw new IllegalArgumentException("No audio track");
  }

  private static int bytesPerSample(int encoding, MediaFormat mediaFormat) {
    switch (encoding) {
      case AudioFormat.ENCODING_PCM_8BIT:
        return 1;
      case AudioFormat.ENCODING_PCM_24BIT_PACKED:
        return 3;
      case AudioFormat.ENCODING_PCM_FLOAT:
      case AudioFormat.ENCODING_PCM_32BIT:
        return 4;
      case AudioFormat.ENCODING_PCM_16BIT:
        return 2;
      default:
        int bits = mediaFormat.containsKey("bits-per-sample") ? mediaFormat.getInteger("bits-per-sample") : 16;
        return Math.max(1, (bits + 7) / 8);
    }
  }

  private static final class Track {
    final int index;
    final String mime;
    final int sampleRateHz;
    final int channelCount;
    final int maxInputSize;
    final List<byte[]> initializationData;
    final int pcmEncoding;
    final int bytesPerSample;

    Track(
        int index,
        String mime,
        int sampleRateHz,
        int channelCount,
        int maxInputSize,
        List<byte[]> initializationData,
        int pcmEncoding,
        int bytesPerSample) {
      this.index = index;
      this.mime = mime;
      this.sampleRateHz = sampleRateHz;
      this.channelCount = channelCount;
      this.maxInputSize = maxInputSize;
      this.initializationData = initializationData;
      this.pcmEncoding = pcmEncoding;
      this.bytesPerSample = bytesPerSample;
    }
  }

  public static final class Result {
    public final String decoderName;
    public final int sampleRateHz;
    public final int channelCount;
    public final long sampleCount;

    Result(String decoderName, int sampleRateHz, int channelCount, long sampleCount) {
      this.decoderName = decoderName;
      this.sampleRateHz = sampleRateHz;
      this.channelCount = channelCount;
      this.sampleCount = sampleCount;
    }
  }
}
