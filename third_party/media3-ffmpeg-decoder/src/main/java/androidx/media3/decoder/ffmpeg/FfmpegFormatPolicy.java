/*
 * Mica candidate R (R1b): renderer support allowlist.
 *
 * Lets the app module gate which formats an FFmpeg renderer instance accepts, so a single
 * ExoPlayer can host mutually-exclusive DsdOnly / PcmOnly renderers without the vendored module
 * depending on app code.
 */
package androidx.media3.decoder.ffmpeg;

import androidx.media3.common.Format;
import androidx.media3.common.util.UnstableApi;

/** Decides whether a role-scoped {@link FfmpegAudioRenderer} should accept a format. */
@UnstableApi
public interface FfmpegFormatPolicy {
  boolean acceptsFormat(Format format);
}
