/*
 * Mica candidate R (R1a): diagnostic-only hook.
 *
 * Lets the app module observe the renderer's real supportsFormat() decision without the
 * vendored FFmpeg module depending on app code. When no listener is registered (release
 * builds) report() is a no-op, so playback behaviour is unchanged.
 */
package androidx.media3.decoder.ffmpeg;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.util.UnstableApi;

/** Static seam that surfaces {@code supportsFormatInternal} decisions to a diagnostic listener. */
@UnstableApi
public final class FfmpegRendererSupportProbe {

  /** Receives the outcome of a renderer's supportsFormat evaluation. */
  public interface Listener {
    void onSupportsFormat(String rendererName, Format format, @C.FormatSupport int formatSupport);
  }

  @Nullable private static volatile Listener listener;

  private FfmpegRendererSupportProbe() {}

  /** Registers (or clears with {@code null}) the diagnostic listener. */
  public static void setListener(@Nullable Listener newListener) {
    listener = newListener;
  }

  static void report(String rendererName, Format format, @C.FormatSupport int formatSupport) {
    Listener current = listener;
    if (current == null) {
      return;
    }
    try {
      current.onSupportsFormat(rendererName, format, formatSupport);
    } catch (RuntimeException ignored) {
      // Diagnostics must never affect playback.
    }
  }
}
