# USB Exclusive P4-A1: deterministic scheduler / feedback host gate

> Status: implemented on `codex/usb-exclusive-p4-a1` from the frozen P1/P2 baseline (`b50f3497`).
> Scope: pure scheduling/feedback math and host-test infrastructure. This does not bind the still-evolving P3 capability/transport API and does not change the P2 owner/recovery lifecycle.

## What P4-A1 adds

The Media3 USB worker and host tests now share payload/device-agnostic C++ seams for:

- isochronous packet scheduling (`usb_iso_scheduler.h`);
- fixed-point feedback decoding (`usb_feedback_decoder.h`);
- feedback median/slew diagnostics (`usb_feedback_rate_filter.h`);
- ahead-window / required-depth math (`usb_iso_ahead_window.h`).

The existing SK02 headers remain compatibility wrappers for the frozen P2 path. SK02 production constants are still supplied by the existing SK02 session; P3 can later replace those inputs with immutable generic transport configuration without replacing the math or the tests.

## Host gate

Run from the repository root:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\run-usb-native-host-tests.ps1
```

The Windows host lane intentionally contains only deterministic, platform-independent tests. The existing `usb_underrun_accounting_interleaving_test` remains in the Android/NDK CMake gate because the workstation's MinGW.org GCC 6.3 runtime does not provide the `std::thread` support needed by that test.

Current host gate:

1. existing SK02 feedback-filter regression;
2. existing packet/PCM telemetry regression;
3. existing ahead-window regression;
4. generic fixed-point feedback decoder regression;
5. 72-hour constant-time scheduler projection;
6. fixed-seed scheduler/feedback stress.

Failures in fixed-seed stress print the seed, scenario and iteration so the exact event stream can be reproduced.

## 72-hour projection matrix

The rational scheduler projection verifies exact frame conservation without wall-clock playback for:

- high speed 44.1 kHz PCM16;
- high speed 48 kHz PCM16;
- high speed 96 kHz packed PCM24;
- high speed 192 kHz PCM32;
- high speed 384 kHz PCM32;
- full speed 44.1 kHz PCM16;
- full speed 96 kHz packed PCM24.

It also proves that an intentionally undersized packet capacity is rejected by the projection gate.

### Important finding: do not use a floored Q16 constant as a no-feedback clock

For 44.1 kHz at 8,000 service intervals/second, `floor(sampleRate * 65536 / 8000)` is `361267`. Projecting that fixed value for 72 hours produces `11,430,713,671` frames instead of the ideal `11,430,720,000`: a deficit of **6,329 frames**.

This is not a current SK02 playback defect: SK02's asynchronous stream receives live explicit feedback, and the frozen P2 worker continues to let raw device feedback steer packet sizing. It is a design constraint for P3 no-feedback adaptive/synchronous modes: their nominal scheduler must preserve the exact rational `sampleRate / serviceIntervals` phase (or an equivalent exact representation), not silently substitute a floored fixed 16.16 value.

## Fixed-seed stress

The current stress executable runs 100,000 packet iterations for each of:

- HS 44.1 kHz PCM16;
- HS 48 kHz PCM16;
- HS 96 kHz PCM24;
- HS 192 kHz PCM32;
- HS 384 kHz PCM32;
- FS 96 kHz PCM24;

and another 100,000 iterations through the generic feedback filter. Each scheduler iteration checks cumulative frame conservation, fractional phase, packet capacity and byte bounds against an independent accumulator.

## Android/NDK gate

The new host-test sources are also CMake targets. `:usb-sk02-native-prototype:assembleDebug` compiles the production shared library and the projection/stress/decoder executables with the project's C++17 `-Wall -Wextra -Werror` policy. This catches divergence between MinGW host compilation and the Android NDK/Bionic toolchain.

## SK02 short real-device smoke

On 2026-08-13, the P4-A1 QA build passed a one-minute `Lifecycle` smoke on the connected Fosi Audio SK02 (`262a:0001`). Evidence: `.scratch/usb-sk02-soak/20260813-002013/`.

The run covered the production path that now shares the generic scheduler/feedback seams:

- SharedPcm baseline -> UsbDirectPcm -> SharedPcm -> UsbDirectPcm;
- two full lifecycle cycles;
- pause/resume twice;
- near-end seek and track-boundary crossing twice;
- explicit 48 kHz / 24-bit and 96 kHz / 24-bit selections (44.1 kHz was also observed while crossing the persisted queue);
- final USB release and kernel-driver rebound.

The runner summary reports `passed=true`, two completed cycles, two metric samples, FD count fixed at 186, and `cleanupDriversBound=true`. Across 263 captured `UsbPcmQueueHealth` samples, the maxima for `underrunBytes`, `transportErrorCode`, `invalidFeedbackPacketCount`, `dataPacketErrorCount`, `totalPollTimeouts`, `maximumConsecutivePollTimeouts`, and `outOfNominalRequests` were all zero. The 96 kHz steady-state samples also reported `scheduleDeviationFrames=0`.

Two test-infrastructure defects were found before the clean pass and are retained as evidence rather than being treated as transport failures:

1. the full-mode SharedPcm setup used a global media-key PLAY event, which this device routed to another app's active MediaSession; the runner now sends PLAY to the Mica QA control receiver explicitly;
2. `Get-ControlOutcome` rejected a temporarily empty logcat poll at PowerShell parameter binding time; empty logs now map to the existing `Pending` state and continue within the unchanged timeout/retry contract.

A previous lifecycle attempt also exercised the production USB path successfully for two cycles but ended on the runner's fixed 48/96 kHz matrix assertion because the then-selected persisted queue indices were 44.1/48 kHz. The final passing run resolved the actual persisted queue IDs against the QA library database and used a verified 48/96 kHz pair.

## Boundary with P3 and later P4 work

P4-A1 deliberately does not define P3's final `UsbTransportConfig`, feedback topology enum, bus-speed mapping or candidate policy. When P3.4 freezes those contracts, a thin adapter should populate the generic scheduler/feedback inputs and the same host gate should become a production transport contract gate.

Completion-stream simulation, delayed/missing completion injection, cancel/drain stress, sanitizer/ABI automation and the full state-space runner remain later P4 work; they should build on these generic seams rather than reintroduce SK02 constants.

## SK02 real-device short smoke

A side-by-side QA build from this P4-A1 worktree was exercised on the connected Fosi Audio SK02 after the generic scheduler/decoder extraction. The final successful run is stored under:

```text
.scratch/usb-sk02-soak/20260813-002013
```

The one-minute `Lifecycle` smoke completed two cycles with crash injection disabled and covered:

- SharedPcm -> UsbDirectPcm -> SharedPcm -> UsbDirectPcm cutover;
- active USB playback progress;
- pause/resume;
- seek near end and track-boundary crossing;
- explicit selection of 48 kHz and 96 kHz material (44.1 kHz was also observed while crossing the queue);
- final prototype disable / QA stop / kernel-driver reconnect.

Final summary: `passed=true`, `completedCycles=2`, observed rates `48000, 44100, 96000`, FD count `186 -> 186`, and `cleanupDriversBound=true`. The successful diagnostics contain no non-zero `transportErrorCode`, `invalidFeedbackPacketCount`, `dataPacketErrorCount`, `underrunBytes`, or poll-timeout counter. This is a short hardware smoke only; the two PSS samples (`187974 -> 210538 KiB`) are not sufficient evidence for a resource-leak conclusion.

The smoke also exposed two runner defects unrelated to production USB transport: a global media-key PLAY could be consumed by another app's active MediaSession, and an empty logcat poll immediately after clearing logcat was rejected by PowerShell mandatory-string binding. P4 now targets the QA Media3 control receiver explicitly and treats an empty control log as `Pending` until the existing timeout expires.
