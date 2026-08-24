# SK02 USB-exclusive prototype findings

Question: can Mica take a Fosi Audio SK02 from Android's shared USB audio path and drive its
isochronous endpoints directly, and what is the smallest viable transport shape?

Verified on Xiaomi `22081212C`, Android 12 / API 31, with Fosi Audio SK02 `262a:0001`, device
revision `0.04`:

- Android enumerates the phone as USB Host/DFP and the DAC as an output-only UAC2 device.
- Interface 0 is HID. Interface 1 is UAC2 AudioControl. Interface 2 is UAC2 AudioStreaming.
- Interface 2 alt 1 is stereo PCM16 in 2-byte subslots; alt 2 is stereo PCM24 in 3-byte
  subslots; alt 3 is stereo PCM32 in 4-byte subslots.
- Alt 4 has UAC2 Type-I `bmFormats=0x80000000` (raw data), 4-byte subslots and 32-bit
  resolution. Treat it as a native/raw candidate, not PCM; DSD framing still requires a
  separate transport decision.
- All active alts use asynchronous isochronous OUT endpoint `0x03` plus explicit feedback IN
  endpoint `0x84`. Maximum packet sizes are 200/300/400/400 bytes respectively.
- Non-forced Java `claimInterface(..., false)` fails for both AudioControl and AudioStreaming.
- A debug-only JNI `USBDEVFS_GETDRIVER` probe succeeds and reports `snd-usb-audio` for both
  interfaces. The Java-authorized `UsbDeviceConnection.fileDescriptor` is therefore a viable
  seam into native USBFS.
- Force-claim succeeds for both interfaces. While claimed, `USBDEVFS_GETDRIVER` reports `usbfs`;
  after release it immediately reports `snd-usb-audio` again. Android's USB headset route and
  ALSA card survive the cycle without requiring a physical reconnect.
- AOSP Android 12 `libusbhost` supports queued USB requests only for bulk and interrupt
  endpoints. Isochronous transport requires native USBFS URBs or a library such as libusb.
- A native isochronous IN URB on feedback endpoint `0x84` completes successfully. At the
  original 384 kHz clock it reports exactly 48 frames/microframe. At 44.1 kHz it reports
  5.512497 frames/microframe (`44099.975586 Hz`).
- A first isochronous OUT URB on endpoint `0x03` completed 80/80 packets: 10 ms of stereo
  PCM16 silence, 1764/1764 bytes, with zero URB errors and zero packet errors. The probe then
  restored alt 0 and the original 384 kHz clock before release/rebind.
- A feedback-controlled queue with eight data URBs (eight packets each) and four feedback URBs
  sustained stereo PCM16 silence at nominal 44.1 kHz. A 5-second run completed 5,000 data URBs,
  40,000 packets and 881,996 bytes; a 30-second run completed 30,000 data URBs, 240,000 packets
  and 5,292,004 bytes. Both runs had zero submit, transport and packet errors, drained to zero
  pending URBs, restored alt 0 and the original 384 kHz clock, and released both interfaces.
- The 30-second run observed feedback from 44099.975586 to 44320.434570 Hz. The queue used the
  live 16.16 feedback value for fractional packet sizing; the four-byte difference from the
  ideal 30-second byte count is consistent with that feedback-driven sizing.
- After the sustained run, `USBDEVFS_GETDRIVER` again reported `snd-usb-audio` for both audio
  interfaces, Android still enumerated the SK02 USB audio route, and no player was active.
- A bounded non-silent source also completed successfully. The debug receiver generated a
  four-second stereo PCM16 buffer at 44.1 kHz (997 Hz left, 1499 Hz right, approximately
  -36 dBFS, with fades) and sent it for three seconds without looping. The run completed 3,000
  data URBs and 24,000 packets, accounting for 529,204 completed bytes and 477,361 completed
  non-zero bytes. Submit, transport and packet errors were all zero; the source did not wrap,
  pending URBs drained to zero, and the original interface/clock state was restored.
- A read-only MediaStore format probe inspected 64 FLAC files without logging titles and found
  25 exact 44.1 kHz, stereo, 16-bit candidates. Candidate MediaStore ID 4729 was decoded offline
  by `c2.android.flac.decoder`; the decoder reported 44.1 kHz, two channels and Android PCM16
  encoding. Four seconds produced 705,600 bytes / 176,400 frames, 626,802 non-zero bytes, peak
  absolute sample 32,394 and CRC32 2275827798. No USB interface was claimed during this step.
- With the SK02 hardware volume manually lowered, that exact decoded buffer (CRC32 2275827798)
  was handed to the USB queue at unity gain: no digital volume, DSP, resampling, channel mixing
  or bit-depth conversion. A three-second run completed 3,000 data URBs / 24,000 packets and
  529,196 bytes, including 452,137 completed non-zero bytes. The source did not wrap; submit,
  transport and packet errors were zero; pending URBs drained to zero. Alt 0 and the original
  48 kHz device clock were restored and both interfaces were released.
- The platform `c2.android.flac.decoder` was rejected for a 96 kHz/24-bit FLAC because it exposed
  PCM16 (`pcmEncoding=2`), which would discard source precision. Mica's bundled FFmpeg decoder
  instead produced float PCM whose 384,000 samples all mapped exactly back to signed 24-bit
  integers (`float * 2^23` residual 0 for every sample). The resulting 1,152,000-byte packed
  PCM24 buffer had CRC32 405717052.
- That exact 96/24 buffer was sent at unity gain through SK02 AudioStreaming alt 2. A 1.9-second
  run completed 1,899 data URBs / 15,192 packets and 1,093,824 bytes, including 485,950 completed
  non-zero bytes. Explicit feedback stayed exactly at 96,000 Hz; the source did not wrap; submit,
  transport and packet errors were zero; pending URBs drained to zero. Alt 0 and the original
  384 kHz clock were restored and both interfaces were released.
- A physical hot-unplug was performed during a 30-second silent PCM16 queue. At approximately
  22.45 seconds the device disappeared; the queue exited with one resubmit error and 57 packet
  errors, drained its bookkeeping to zero pending URBs, and completed without hanging the app.
  Interface/clock restoration and release reported false/-1 because the USB device no longer
  existed, which is expected for this prototype path.
- After reconnect, Android re-enumerated both the SK02 and its `USB-Audio` route within about six
  seconds. A fresh permission grant and driver probe reported `snd-usb-audio` on both audio
  interfaces. A subsequent five-second exclusive silent run completed 4,999 data URBs / 39,992
  packets with zero submit, transport or packet errors, restored alt 0 and the original 384 kHz
  clock, and released both interfaces. This proves the single-DAC prototype can be reacquired
  after a physical detach/attach cycle.
- The prototype now has one process-wide generation owner
  (`UsbPrototypeGenerationOwner.gate`, backed by `AtomicLong`) and one serialized USB side-effect
  seam (`UsbPrototypeGenerationGate.withTransport`, backed by a `ReentrantLock`). Native USBFS
  checks the published generation after each blocking reap and again before every resubmit.
- A deterministic JVM interleaving test pauses the old request at the USB side-effect boundary,
  publishes a newer request, then releases the old request and asserts that only the newer request
  can submit. The test is
  `UsbPrototypeGenerationGateTest.oldRequestPausedAtUsbSideEffectCannotWriteAfterNewRequestWins`.
- The same interleaving was verified on the phone and real SK02. A nominal 30-second generation 1
  run was superseded after about 1.8 seconds. It exited with `cancelled=true`, zero submit,
  transport and packet errors, and zero pending URBs. It then restored alt 0 and the original
  384 kHz clock and released both interfaces while reporting `current=false`. Only after that
  cleanup completed did generation 2 enter the serialized transport seam; its three-second run
  completed 3,000 data URBs / 24,000 packets with zero errors and zero pending URBs and performed
  the same complete restore/release sequence. A post-run native driver probe reported
  `snd-usb-audio` on both interfaces.

## Media3 playback integration (2026-08-08)

- The debug/QA build now selects an SK02-only `AudioOutputProvider` at service construction time.
  Release builds cannot load or select the provider, and the QA application id is
  `com.mica.music.qa`, so the installed production package and its data were not modified.
- Media3's `DefaultAudioSink` still owns timestamps, buffering, flush and renderer lifecycle. The
  provider replaces only the final `AudioTrack` output with the bounded native USBFS queue (eight
  OUT URBs and four feedback URBs).
- FFmpeg float output from the tested 24-bit ALAC files is not always an exact `S24 / 2^23` value,
  so packing it to PCM24 would require rounding. The prototype does not round. It instead requires
  every float to be an exact signed `S32 / 2^31` value and packs that integer losslessly to SK02
  alt 3. Any non-integral, non-finite or out-of-range sample fails closed with error 10003 rather
  than falling back to PCM16 or silently quantizing.
- Real Mica playback completed for 24-bit/48 kHz and 24-bit/96 kHz ALAC. Diagnostics reported
  `AudioOutputPath=UsbDirectPcm`, Media3 `PCM_FLOAT`, and SK02 `alt=3`; the media-session clock
  advanced normally. While playing, `USBDEVFS_GETDRIVER` reported `usbfs` for both audio
  interfaces, proving the samples were not going through the Android shared USB AudioTrack path.
- Pause froze the Media3 position and resume advanced it again. A seek near the end of the 96 kHz
  track followed by automatic transition to a 48 kHz track reconfigured 96 -> 48 kHz and continued
  with no `WriteException` or `PlaybackException`.
- Normal output release restores alt 0 and the original clock, releases both interfaces and asks
  usbfs to reconnect `snd-usb-audio`. A deliberately forced process stop exposed an Android-kernel
  edge case where the detached interfaces remained unbound. The debug recovery action now tunnels
  `USBDEVFS_CONNECT` through `USBDEVFS_IOCTL` per interface; on the real phone it restored
  `snd-usb-audio` on both interfaces without touching another app or physically reconnecting the
  DAC. The QA process was finally stopped with the prototype disabled and both drivers bound.

These results prove the SK02-specific USBFS seam, generation-safe cancellation, bounded
hot-unplug recovery and Media3 lifecycle integration for this single DAC. They do not yet prove
multi-hour stability, audible-underrun recovery, ungraceful process-death recovery without a later
reconnect action, DSD/native framing, or behavior across other DAC implementations. The debug
receiver and SK02-specific USBFS code remain prototype-only; production absorption still requires
a durable device owner and lifecycle policy at the real playback ownership boundary.

## Automated Media3 soak runner (2026-08-09)

`scripts/run-usb-sk02-media3-soak.ps1` automates the repeatable single-DAC checks against only the
side-by-side `com.mica.music.qa` package. A typical run is:

```powershell
.\scripts\run-usb-sk02-media3-soak.ps1 -Serial 172.17.57.9:42883 -DurationMinutes 10
```

Each cycle selects the configured 48 kHz and 96 kHz queue items, checks that the Media3 clock
advances, pauses/resumes, seeks near the end to cross a track boundary, checks both USB audio
interfaces are owned by `usbfs`, and scans diagnostics/logcat for playback failures. Configurable
cycles deliberately force-stop QA while it owns the DAC, restore and verify `snd-usb-audio` while
QA remains stopped, then restart playback and verify progress. A `finally` block disables the
prototype, stops QA and reconnects the kernel drivers even after an assertion failure. Per-cycle
logs, diagnostics, media-session dumps and a JSON summary are written below
`.scratch/usb-sk02-soak/`.

The first complete real-device smoke run used one cycle with a forced process stop. It passed in
100 seconds, observed both 48,000 and 96,000 Hz, resumed playback after driver recovery, and ended
with `cleanupDriversBound=true`. Its artifacts are under
`.scratch/usb-sk02-soak/20260809-013752/`. This is automation evidence, not an audible assessment
or a multi-hour stability result; physical detach/attach and listening checks remain manual.

## Generation protocol review

- Owner: the process-wide `UsbPrototypeGenerationOwner.gate`; `beginRequest()` is the only
  request-generation minting point used by this probe, and the same value is published to native
  USBFS through `publishGeneration()`.
- Blocking/cancellation boundaries: `openDevice`, each forced interface claim, clock read/write,
  alternate-setting selection, native `poll`/`USBDEVFS_REAPURBNDELAY`, drain, restoration and
  release. Kotlin rechecks the lease after open/claim/control operations and before the next USB
  mutation; native checks generation before initial submits, after each completed reap and before
  resubmit. Cleanup deliberately continues for a stale lease because it removes that lease's own
  USB state while still holding the serialized seam.
- Actual USB side effects: detach/claim AudioControl and AudioStreaming, write the UAC2 clock,
  select the streaming alternate setting, submit/discard/drain isochronous URBs, restore alt 0 and
  the original clock, release both interfaces and close the connection.
- Serialization seam: every side effect above is inside
  `UsbPrototypeGenerationGate.withTransport`; callers can publish a newer generation without the
  mutex so native transport observes cancellation, but the newer request cannot claim or mutate
  USB state until stale cleanup releases the mutex.
- Interleaving coverage: `UsbPrototypeGenerationGateTest` deterministically controls the stale
  side-effect boundary, and the on-device `supersedeAfterMs` probe covers real native cancellation,
  URB drain, restoration/release ordering and subsequent acquisition by the newer request.
