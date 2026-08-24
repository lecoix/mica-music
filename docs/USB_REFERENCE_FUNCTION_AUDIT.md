# USB reference function audit

Snapshot basis: SylvaKru reference snapshot `.codex-tmp/sylvakru-usb-fork-ref-20260812` compared against the current Mica Hybrid USB implementation.

Status vocabulary:

- `EXACT`: normalized reference implementation is identical.
- `EQUIVALENT`: same required behavior, adapted to Mica/Media3 seams.
- `MICA_STRICTER`: deliberately stronger fail-closed behavior; not a missing reference behavior.
- `MEDIA3_REPLACED`: reference owns decoding/player lifecycle itself; Mica delegates that responsibility to Media3 while retaining the USB-side semantics separately.
- `MISSING`: reference behavior is relevant and no equivalent currently exists.
- `NOT_PORTED_FEATURE`: explicit product feature gap, not silently treated as equivalent.
- `NOT_APPLICABLE`: reference helper belongs to a subsystem Mica intentionally does not use.
- `MICA_EXTENSION`: behavior absent from the reference and backed by Mica physical evidence.

## Audited functions

| Reference function / group | Mica implementation | Status | Audit notes |
| --- | --- | --- | --- |
| `UsbDsd.kt` all functions | same vendored file | EXACT | Normalized full-file comparison equal. |
| `UsbStreamTransition.kt` all functions | same vendored file | EXACT | Normalized full-file comparison equal. |
| `UsbDiagnostics.kt` all functions | same vendored file | EXACT | Normalized full-file comparison equal. |
| `UsbExclusiveNative.open` | `UsbExclusiveNative.open` -> `openRaw` JNI | MICA_STRICTER | Reference dup/claim/SETINTERFACE/feedback-arm behavior is retained. Mica additionally publishes/checks an active epoch, returns an owned native `sessionId`, and fails a stale open before it can replace the current session. |
| `UsbExclusiveNative.writePcm` | same JNI with `epoch/sessionId` | MICA_STRICTER | Reference PCM chunking and ISO submission are retained; stale-session writes are rejected before submission. |
| `UsbExclusiveNative.writeIsoPackets` | same JNI with `epoch/sessionId` | MICA_STRICTER | Reference packet-count/length validation and batched ISO submission are retained; every submission is additionally session fenced. |
| `UsbExclusiveNative.setIsoPacketSize` | same JNI with `epoch/sessionId` | MICA_STRICTER | Same `0..maxPacketSize` clamp; Mica returns a stale-session error instead of allowing an obsolete caller to mutate the active session. |
| `UsbExclusiveNative.feedbackFramesPerPacketQ16` | same JNI with `epoch/sessionId` | MICA_STRICTER | Same feedback Q16 value for the owned session; stale callers receive 0 rather than another session's feedback. |
| `UsbExclusiveNative.transportTelemetry` | same JNI with `epoch/sessionId` | MICA_STRICTER | Same 4-value pending/total/pending-URB/error telemetry and completion reap; stale callers receive `[-1,-1,-1,-1]`. |
| `UsbExclusiveNative.setMaxPendingOutputUrbs` | same JNI with `epoch/sessionId` | MICA_STRICTER | Same reference clamp from default minimum to absolute maximum; Mica fences mutation by session ownership. |
| `UsbExclusiveNative.flushOutput` | same JNI with `epoch/sessionId` | MICA_STRICTER | Same native output flush/drain semantics; stale callers cannot flush a successor session. |
| `UsbExclusiveNative.close` | same JNI with `epoch/sessionId` | MICA_STRICTER | Same physical close for the owned session; stale close attempts fail instead of tearing down a successor. This is distinct from the reference nested `MediaDataSource.close` row below. |
| `UsbDacQuirks.forDevice` | `UsbDacQuirks.forDevice` | EXACT | Reference function retained. |
| `UsbDacQuirks.matchDescription` | same | EXACT | Reference function retained. |
| `UsbDacQuirks.loadErrors` | same | EXACT | Reference function retained. |
| `UsbDacQuirks.importOverride` | same | EXACT | Runtime override restored. |
| `UsbDacQuirks.rememberDopSupported` | same | EXACT | Runtime learned DoP override restored. |
| `UsbDacQuirks.ensureLoaded/invalidate/overrideFile/parseEntries/appendEntries/matchQuirk/matchKey/hex/normalizeId/q8_8` | same plus two SK02 cold-entry fields | MICA_EXTENSION | Reference loading/matching preserved; Mica adds physically qualified conditional Native DSD256 cold-entry fields. |
| `PcmIsoPacketizer.beginFadeIn` | `UsbPcmIsoPacketizer.beginFadeIn` | EQUIVALENT | Initially omitted; restored during this audit. |
| `PcmIsoPacketizer.write` fade-in path | `UsbPcmIsoPacketizer.write` | EQUIVALENT | `convertPcmToUsbSlots` then `applyFadeInIfNeeded`, matching reference ordering. |
| `PcmIsoPacketizer.flush` | same | EQUIVALENT | Same packetizer-only drain; does not native-flush queued URBs. |
| `PcmIsoPacketizer.writeTransitionTail` | same | EQUIVALENT | Same fade-to-zero/silence behavior. |
| `PcmIsoPacketizer.writeUsbSilence` | same | EQUIVALENT | Same semantics. |
| `PcmIsoPacketizer.reset` | same plus `UsbPacketCadence.reset` abstraction | EQUIVALENT | Cadence state reset preserved. |
| `PcmIsoPacketizer.drain/flushTransfer/nextPacketBytes/q16ToFrames` | `UsbPcmIsoPacketizer` | EQUIVALENT | Reference feedback validity window and 16 packets/transfer retained; nominal cadence factored into `UsbPacketCadence`. |
| `PcmIsoPacketizer.applyFadeInIfNeeded` | same behavior | EQUIVALENT | Initially omitted; restored during this audit. |
| `PcmIsoPacketizer.convertPcmToUsbSlots` | same behavior via `pcmSampleForUsbTransition` | EQUIVALENT | Restored reference gain clamp/bit-depth alignment and last-frame capture. |
| `PcmIsoPacketizer.hasAudibleSamples/logPcmPreview/readSignedLittleEndian/writeLittleEndian` | same behavior | EQUIVALENT | Diagnostic tag names differ only. |
| `PcmIsoPacketizer.ByteArray.toHexPreview` | `UsbPcmIsoPacketizer.ByteArray.toHexPreview` | EQUIVALENT | Same bounded lowercase hex preview helper; only the enclosing packetizer/tag differs. |
| packetizer `reportFeedback` callback | `recordFeedbackDiagnostics` | EQUIVALENT | Initially omitted; restored during this audit. |
| PCM pause worker tail | `UsbExclusiveAudioTransport.pausePcm` + `UsbHybridPcmAudioSink.pause` | EQUIVALENT | Restored reference 16 ms fade-out + 24 ms zero tail before pausing writes. |
| PCM resume worker fade-in | `resumePcm` + AudioSink `play()` | EQUIVALENT | Restored reference 16 ms fade-in only for a real player pause/resume. |
| PCM seek splice | `preparePcmSeek` + `handleDiscontinuity` | EQUIVALENT | Restored old-position 16 ms fade-out, cadence reset, new-position 16 ms fade-in. Generic Media3 `flush()` remains a cadence reset because it is also used for cross-item changes. |
| `configureUsbAudioClock` | `UsbStreamingTargetResolver.configureUsbAudioClock` | EQUIVALENT | Audit restored reference tolerance: failed claim/SET_CUR count/GET_CUR null-or-zero/runtime exception do not falsely reject a DAC; only valid nonzero mismatching UAC2 readback fails. |
| `readUac2ClockSampleRate` | same | EQUIVALENT | Raw hex diagnostic restored. |
| `collectOutputCandidates` | same | EQUIVALENT | Same UAC isochronous OUT enumeration. |
| `findFeedbackEndpoint` | same | EQUIVALENT | Same ISO IN usage-type 0x10 rule. |
| `parseStreamingFormatInfo` | same | EQUIVALENT | Includes UAC1/UAC2 Type-I distinction and RAW_DATA parsing. |
| `findUac2ClockSourceId` | same | EQUIVALENT | Same terminal-link/clock-source resolution. |
| `requiredIsoPacketBytes` | same | EQUIVALENT | Same ceiling formula. |
| `isoIntervalMicroframes` | restored in resolver | EQUIVALENT | Reference uses it for endpoint/feedback interval diagnostics; divisor remains 1 exactly as reference. |
| `findAudioControlInterface(device, interfaceNumber?)` | resolver helper | EQUIVALENT | Optional interface filter restored. |
| `findOutputTarget` | `resolveTarget` | MICA_STRICTER | Mica exact PCM policy refuses precision-reducing/fallback candidates rather than choosing a looser fitting alt. |
| `shouldFlushOutputOnStop` | no native flush on normal stop/seek/reconfigure | EQUIVALENT | Reference returns false; final hard close still reclaims native URBs. |
| `stopWorkerKeepingSession` | Media3 renderer owns decode worker | MEDIA3_REPLACED | No second decoder worker exists in Mica. USB session stays transport-owned. |
| `stopWorkerForSilentReconfigure` | `prepareSilentReconfigureLocked` | EQUIVALENT | Tail + drain serialized inside synchronized transport. |
| `awaitOldOutputDrain` | `awaitOldOutputDrainLocked` | EQUIVALENT | Same 220 ms timeout and 10 ms polling. |
| `scheduleDeferredClose` | transport generation-fenced deferred close | EQUIVALENT | Missing before audit; restored 4 s hot-reuse window for PCM and DSD. |
| `startDopIdleFiller` | `startDsdIdleFillerLocked` | EQUIVALENT | Handles both DoP and Native; same ~10 ms 0x69 chunks. |
| `stopDopIdleFiller` | `stopDsdIdleFillerLocked` | EQUIVALENT | Reference `join(500)` restored earlier. |
| `hardCloseSession` | `UsbExclusiveAudioTransport.close` + epoch/session ownership | EQUIVALENT | Mica adds stale-session fencing; no weaker close semantics. |
| `applyNativeTargetBuffer` | same formula | EQUIVALENT | 16 ISO packets/URB; pending-URB cap calculated from target buffer. |
| `setTargetBufferMs` | `UsbExclusiveAudioTransport.setTargetBufferMs` | EQUIVALENT | Missing before audit; restored 50..1000 ms clamp and immediate active-session reapply; default remains 200 ms. |
| `capabilities` | `UsbAudioTargetSelector` + owner facts | MICA_STRICTER | Mica fails closed on zero or multiple USB Audio outputs instead of guessing one. |
| `beginSessionDiagnostics` | transport session diagnostic store | EQUIVALENT | Restored session-scoped chronology with input, output selections, feedback, transport and volume sections. |
| `addOutputSelectionDiagnostics` | `addOutputSelectionDiagnostics` / `recordOutputSelection` | EQUIVALENT | Candidate and selected-target attempts are retained in the session snapshot. |
| `updateSessionDiagnostics` | transport generic section store | EQUIVALENT | Section updates are retained with timestamps and exposed through owner facts. |
| `sessionDiagnosticsSnapshot` | `UsbExclusiveAudioTransport.sessionDiagnosticsSnapshot` | EQUIVALENT | Snapshot is exposed through realtime port, owner facts and exported diagnostics. |
| `recordFeedbackDiagnostics` | transport fields restored during audit | EQUIVALENT | Actual/nominal/ignored feedback state is captured in session diagnostics and exposed through the owner diagnostics/report path. |
| `emitTransportTelemetry` | `telemetry` + session diagnostics | EQUIVALENT | Mirrors pending/total ISO, pending URBs, native errors, buffer level, minimum buffer, zero-buffer underrun, submitted bytes, average bytes/s and last underrun. |
| `emitInactiveTelemetry` | close-time inactive telemetry reset | EQUIVALENT | Close resets buffer/minimum/underrun cursors and records an inactive zero-state diagnostic section. |

## Mica-only physically qualified extension

| Mica function/field | Status | Evidence |
| --- | --- | --- |
| `nativeDsdColdEntryPrimeDsd` / `nativeDsdColdEntryThresholdDsd` and `openDsd` cold-entry prime | MICA_EXTENSION | SK02 direct PCM/non-Native -> DSD256 could produce sustained noise; Native DSD128 silence prime followed by normal reference `SILENT_RECONFIGURE` to DSD256 was physically normal on Redmi 22081212C. |

## Remaining `UsbExclusiveAudioEngine.kt` function-by-function classification

| Reference function | Mica implementation | Status | Audit notes |
| --- | --- | --- | --- |
| `acceptVerifiedIbassoTarget` | `UsbIbassoHardwareVolumeController.accept` / `acceptPreservedTarget` | EQUIVALENT | Verified target, device binding, reader health and actual gain are retained. |
| `applyPcmDigitalFallbackImmediately` | immediate-fallback branch in `applyVolumeControlLocked` | EQUIVALENT | Hardware-volume loss while PCM remains active switches to the requested digital gain without a ramp. |
| `applyVolumeControl` | `applyVolumeControlLocked` | EQUIVALENT | Raw/digital/auto/dac selection, UAC/iBasso control, PCM fallback and DSD safety are retained. Audit fixed frozen-state carryover: a frozen trusted iBasso target is no longer cleared before compensation/recovery, and preserved verification recovery forces the reference smooth PCM handoff. |
| `applyVolumeRequest` | `applyVolumeRequest` | MICA_STRICTER | Same generation checks before and inside application. Mica additionally serializes the HID transaction with transport open/close on the transport monitor, so a physical transition cannot overtake an in-flight hardware write; this is more conservative than the reference's split volume/session-write locks. |
| `awaitIbassoReaderForVolumeVerification` | `UsbIbassoHardwareVolumeController.awaitReaderForVerification` | EQUIVALENT | Same bounded reader recovery decision before readback. |
| `bitDepthFromPcmEncoding` | `UsbExactPcmPolicy.bitDepth(Format.pcmEncoding)` | MEDIA3_REPLACED | Media3 supplies decoded PCM format; the app does not preflight files with a private extractor. |
| `bytesPerSampleForBitDepth` | transport `bytesPerSampleForBitDepth` | EQUIVALENT | Same integer PCM slot sizing. |
| `capability` | `UsbAudioTargetSelector` + typed `UsbPlaybackFacts` | MICA_STRICTER | Capability is represented as typed facts and ambiguous multiple DACs fail closed instead of returning a guessed first target. |
| `start` | Media3 renderer/sink configure + `UsbHybridSessionOwner.requestOpen` + transport `openPcm/openDsd` | MEDIA3_REPLACED | Reference owns source/decoder worker startup in one method. Mica delegates decode/player startup to Media3 while preserving target selection, transition action, volume decision, pre-roll and USB session establishment in the owner/transport. |
| `close` (reference growing-file `MediaDataSource.close`) | Media3 data-source lifecycle | MEDIA3_REPLACED | The nested reference streaming data source is not used; Media3 owns source closure. This is distinct from transport/session close, audited as `hardCloseSession`/`release`. |
| `closeIbassoVolumeControl` | `UsbIbassoHardwareVolumeController.closeControl` | EQUIVALENT | Stops reader, fails pending responses, releases interface/connection, resets or preserves trusted state as requested. |
| `collectDiagnostics` | `UsbHybridDiagnosticsReport.build` + session diagnostics | EQUIVALENT | Includes permission, topology, raw descriptors, quirk, negotiated facts, telemetry, session chronology and hardware-volume probes. |
| `collectHardwareVolumeDiagnostics` | `UsbStandardHardwareVolumeController.collectDiagnostics` + transport public diagnostic entry | EQUIVALENT | Restored inactive descriptor Feature Unit parsing, quirk merge, range/current probes and claim failures. |
| `consumePendingSeekMs` | Media3 seek/discontinuity callbacks | MEDIA3_REPLACED | Media3 owns pending seek position; USB side handles the physical discontinuity tail/reset/fade-in. |
| `createPacketizer` | packetizer construction in PCM open | EQUIVALENT | Same input/USB widths, endpoint cadence, feedback callback, digital gain and ISO writer parameters. |
| `decodeAndWrite` | Media3 decoder + `UsbHybridPcmAudioSink` | MEDIA3_REPLACED | Reference-owned MediaExtractor/MediaCodec loop is replaced; USB packetization semantics remain in transport. |
| `description` | `HardwareVolumeFeature.description` | EQUIVALENT | Same Feature Unit diagnostic description. |
| `dopGateError` | DoP checks in `openDsd` | EQUIVALENT | `dopSupported=false` and `dopMaxDsd` gates are retained. |
| `drainVolumeRequests` | transport `drainVolumeRequests` | EQUIVALENT | Async latest-wins queue plus protocol timing restored. |
| `dsdDecodeAndWrite` | `UsbHybridDsdRenderer` + `DsfPlanarBlockConverter` | MEDIA3_REPLACED | Media3 owns source lifecycle; raw DSF conversion and DoP/Native write remain explicit. |
| `emitError` | typed `UsbFailure` / renderer and sink errors | MEDIA3_REPLACED | Errors are published through owner/player contracts instead of a Flutter map callback. |
| `failIbassoPendingResponses` | controller `failPendingResponses` | EQUIVALENT | Pending HID command futures are failed and cleared when reader/control closes. |
| `failStart` | `UsbHybridSessionOwner` failed-open retirement | MICA_STRICTER | Failure publication is epoch/session fenced rather than mutating one untyped current-state map. |
| `findAudioTrack` | Media3 extractor/renderer selection | MEDIA3_REPLACED | No duplicate app-owned MediaExtractor track search. |
| `finishPcmPacketizer` | `finishStream` + silent-reconfigure tail path | EQUIVALENT | Normal EOS flushes packetizer; silent reconfigure emits reference transition tail before drain. |
| `freezeIbassoPcmVolume` | `hardwareVolumeFrozen` + `frozenPcmCompensationGainQ16` | EQUIVALENT | Trusted hardware level is frozen and only safe digital compensation is applied. |
| `getSize` | Media3 data source | MEDIA3_REPLACED | Reference growing-file data source is not used by Mica playback. |
| `handleIbassoReaderFailure` | controller `handleReaderFailure` | EQUIVALENT | Same generation checks, one restart, then write-only/fail-safe degradation. |
| `handleUsbAudioDeviceRemoved` | `UsbHybridSessionOwner.onDetached` / Android topology events | MICA_STRICTER | Detach additionally mints/fences epochs so stale sessions cannot close or revive successors. |
| `hardwareVolumeEventMap` | typed unsolicited-volume callback + session diagnostics | EQUIVALENT | Protocol/raw/gain/DSD state is retained without the reference Flutter event-map shape. |
| `hardwareVolumeGainQ16` | `UsbHardwareVolumePrimitives.hardwareVolumeGainQ16` | EXACT | Reference primitive copied with its tests. |
| `hardwareVolumeQ8_8` | `UsbHardwareVolumePrimitives.hardwareVolumeQ8_8` | EXACT | Reference primitive copied with its tests. |
| `hardwareVolumeRangeOverride` | same primitive | EXACT | Reference quirk range override retained. |
| `hardwareVolumeReadbackMatches` | same primitive | EXACT | Reference readback tolerance retained. |
| `hardwareVolumeRequestType` | same primitive | EXACT | Same UAC class/recipient request construction. |
| `hardwareVolumeRequiresDedicatedConnection` | same primitive | EXACT | Same device-recipient dedicated-connection rule. |
| `hardwareVolumeRequiresInterfaceClaim` | same primitive | EXACT | Same interface claim rule. |
| `hexDump` | `UsbHybridDiagnosticsReport.appendHex` | EQUIVALENT | Raw descriptors are exported as deterministic hex rows. |
| `hexPreview` | resolver `hexPreview` | EQUIVALENT | UAC2 clock raw preview restored. |
| `inactiveState` | typed inactive `UsbPlaybackFacts` states | MEDIA3_REPLACED | Inactive state is strongly typed rather than a Flutter map. |
| `invalidatePendingVolumeRequests` | `invalidatePendingVolumeRequestsLocked` | EQUIVALENT | Session generation increments and both pending latest-wins requests and preserved-PCM verification are invalidated. Audit restored invalidation on logical PCM/DSD `REUSE` as well as physical close/reopen so a previous track's queued volume command cannot land on the reused session. |
| `isCurrentIbassoReader` | controller `isCurrentReader` | EQUIVALENT | Same reader generation/thread/connection/endpoint checks. |
| `isDsdFile` | Media3 MIME/renderer routing | MEDIA3_REPLACED | DSF selection is format-driven rather than filename preflight. |
| `isMediaCodecDecodable` | Media3 renderer capability negotiation | MEDIA3_REPLACED | Decoder support is delegated to Media3. |
| `isSupportedFile` | Media3 source/renderers | MEDIA3_REPLACED | No duplicate extension whitelist in USB transport. |
| `isUsbVolumeControlEngaged` | same primitive | EXACT | Reference engagement rule copied with volume protocol tests. |
| `isVolumeControlEngaged` | `UsbExclusiveAudioTransport.isVolumeControlEngaged` | EQUIVALENT | Public transport wrapper now mirrors reference using active/hardware/sync/digital/bit-depth facts. |
| `keepVerifiedIbassoTarget` | controller `accept` | EQUIVALENT | Reference helper only delegates to verified-target acceptance; controller retains same state. |
| `markIbassoWriteOnly` | controller `markWriteOnly` | EQUIVALENT | Same reader-health degradation and readback trust removal. |
| `parseHardwareVolumeFeatures` | `UsbStandardHardwareVolumeController.parseHardwareVolumeFeatures` | EQUIVALENT | Reference UAC1/UAC2 Feature Unit parser restored. |
| `parseOutputTerminalSources` | same controller helper | EQUIVALENT | Output Terminal source mapping restored. |
| `queueIbassoVolumeEvent` | controller `queueVolumeEvent` | EQUIVALENT | Same debounce/write-confirmation treatment before unsolicited event publication. |
| `readAt` | Media3 data source | MEDIA3_REPLACED | Reference growing-file random read helper is not part of Mica USB transport. |
| `readAttribute` (two reference local declarations) | standard controller `readAttribute` helper | EQUIVALENT | Both scoped reference attribute-reader declarations perform the same class-control attribute read pattern; Mica centralizes that behavior in the standard hardware-volume controller. |
| `readHardwareVolumeCurrent` | same controller function | EQUIVALENT | Same GET_CUR request and signed Q8.8 decoding. |
| `readHardwareVolumeProbe` | same controller function | EQUIVALENT | Same current/range probe now also used by exported inactive diagnostics. |
| `readHardwareVolumeRangeValue` | same controller helper | EQUIVALENT | Same UAC range selector reads. |
| `readHardwareVolumeValues` | controller `readValues` | EQUIVALENT | Same dedicated connection/claim/read/release behavior. |
| `readIbassoCurrentBaseRaw` | controller `readCurrentBaseRaw` | EQUIVALENT | Same HID read command and bounded response path. |
| `readPcmSourceBitDepth` | Media3 `Format.pcmEncoding` + `UsbExactPcmPolicy.bitDepth` | MEDIA3_REPLACED | Source/decode bit depth comes from active Media3 format rather than a duplicate extractor preflight. |
| `readSignedQ8_8` | standard controller `readSignedQ8_8` | EQUIVALENT | Same signed UAC volume decode. |
| `readUac1VolumeRange` | same controller helper | EQUIVALENT | Reference UAC1 min/max/res reads restored. |
| `readUac2VolumeRange` | same controller helper | EQUIVALENT | Reference UAC2 RANGE parsing restored. |
| `recordOutputSelection` | transport `recordOutputSelection` | EQUIVALENT | Selected interface/alt/max packet/format/feedback and fit facts retained in session diagnostics. |
| `release` | Media3 renderer/sink release + owner close | MEDIA3_REPLACED | Media3 owns renderer lifetime; owner/transport still performs the physical USB close. |
| `stop` | Media3 renderer/sink stop/EOS + transport `finishStream/finishDsdStream` and deferred-close/idle-filler paths | MEDIA3_REPLACED | Reference owns an explicit decoder-worker stop command. Mica delegates player stop/EOS sequencing to Media3 while retaining continuous ISO behavior, DSD silence filling and the 4 s hot-reuse close window in the transport. |
| `resolveHardwareVolumeControl` | `UsbStandardHardwareVolumeController.resolve` | EQUIVALENT | Reference Feature Unit selection, quirk injection, range probing and ambiguity handling restored. |
| `rollbackHardwareVolume` | standard controller `rollbackHardwareVolume` | EQUIVALENT | Reference rollback of already-written channels retained. |
| `routeIbassoReaderPacket` | controller `routeReaderPacket` | EQUIVALENT | ACK/event/unknown packet routing retained. |
| `scheduleIbassoReaderRestart` | controller `scheduleReaderRestart` | EQUIVALENT | Same bounded single reader restart policy. |
| `schedulePreservedPcmVerificationAfterPreRoll` | transport function of same name | EQUIVALENT | Frozen trusted-target readback runs only after the new-session PCM silence pre-roll and is generation/device fenced. Matching readback clears freeze and reapplies volume with forced smooth handoff; mismatch/missing readback keeps the trusted hardware level frozen with safe PCM compensation. |
| `selectHardwareVolumeFeatures` | same primitive | EXACT | Reference unique Feature Unit selection helper copied with tests. |
| `setPcmVolumeGain` | transport `setPcmVolumeGain` | EQUIVALENT | Reference bounded ramp is used for normal smooth handoff; failover uses immediate update. |
| `setVolume` | async transport `setVolume` + Media3 PCM/DSD volume seams | EQUIVALENT | Reference latest-wins command worker/coalescing, 150 ms iBasso settle + 300 ms pending quiet window, session-generation fencing, mode, ReplayGain, DSD compensation and smooth-handoff fields are retained. Logical PCM/DSD `REUSE` now invalidates the previous playback generation too. Mica default remains intentionally Raw for unknown/SK02 compatibility. |
| `startIbassoVolumeReader` | controller `startReader` | EQUIVALENT | Dedicated HID IN reader, generation and unsolicited-event mode restored. |
| `targetMap` | transport `recordOutputSelection` candidate map | EQUIVALENT | Equivalent target diagnostics are stored without retaining the nested local helper name. |
| `transferIbassoPacket` | controller `transferPacket` | EQUIVALENT | Same bulk OUT + command future/readback routing. |
| `transferIbassoVolumeTarget` | controller `transferVolumeTarget` | EQUIVALENT | Same reference iBasso packet sequence and ACK/error collection. |
| `uniformHardwareVolumeRange` | same primitive | EXACT | Same common range derivation. |
| `updateState` | typed owner facts publication | MICA_STRICTER | State changes are serialized and epoch/session fenced rather than replacing an untyped map. |
| `writeHardwareVolume` | standard controller `write` | EQUIVALENT | Read-old → write each channel → readback verify → rollback on failure is retained. |
| `writeHardwareVolumeValue` | same controller helper | EQUIVALENT | Same SET_CUR signed Q8.8 transfer. |
| `writeIbassoHidVolume` | controller `apply` + `transferVolumeTarget` | EQUIVALENT | Target mapping, rollback, ACK/readback verification, pending-request yield and reader degradation are retained. Audit completed the frozen-state rules: preserved PCM targets freeze instead of being overwritten, an already-frozen target must recover the trusted base register before new writes, write-only connections skip false initial-readback failure, and unsafe DSD remains fail-closed. |
| `writeOutputBuffer` | `UsbHybridPcmAudioSink` → transport packetizer | MEDIA3_REPLACED | Media3 supplies decoded buffers; USB write/cadence remains reference-derived. |
| `writePreRollIfNeeded` | PCM open pre-roll + `schedulePreservedPcmVerificationAfterPreRoll` | EQUIVALENT | Final ordering is reference-equivalent: establish session -> make/freeze the hardware-volume decision -> write new-session silence -> schedule preserved-target verification. Matching post-pre-roll readback unfreezes through a forced smooth PCM handoff; mismatch/missing readback remains frozen. |
| `writeRawPcm` | `UsbHybridPcmAudioSink.handleBuffer` | MEDIA3_REPLACED | Media3 provides integer PCM buffers directly; transport owns slot conversion and ISO writes. |

## Audit closure — 2026-08-23

### Function accounting

- Full extraction of the reference `UsbExclusiveAudioEngine.kt`, including the top-level `UsbExclusiveNative` JNI surface, found **142 unique function names across 144 function declarations** (`close` and `readAttribute` each occur in two distinct scopes).
- The earlier 134-name scan excluded eight `external fun` JNI names; those eight unique native names plus the separately scoped native `close` are now explicitly audited above.
- Final ledger result: **142 / 142 unique function names accounted for, with all 144 declarations mapped by scope/group**.
- The last Kotlin-engine name-level omissions were `start`, `stop`, the nested growing-file `close`, and packetizer `ByteArray.toHexPreview`; the final native-surface omissions were `open`, `writePcm`, `writeIsoPackets`, `setIsoPacketSize`, `feedbackFramesPerPacketQ16`, `transportTelemetry`, `setMaxPendingOutputUrbs`, `flushOutput`, and the native `close`.
- There is no unexplained `MISSING`, `PARTIAL`, `待审`, or unclassified reference function remaining in this ledger.

### Important runtime conclusions from the final pass

- Async USB volume is reference-style latest-wins with protocol settle/quiet timing and generation fencing.
- Pending volume generation is invalidated not only on physical close/reopen but also on logical PCM/DSD `REUSE`, so a predecessor track cannot land a delayed volume operation on the reused successor session.
- Frozen trusted iBasso PCM state survives the controller->transport mapping, and an already-frozen state must prove the trusted base register again before further hardware writes.
- Preserved iBasso PCM verification runs after the new-session silence pre-roll; success unfreezes through smooth handoff, while failure keeps the trusted hardware level frozen and permits only attenuation-safe PCM compensation.
- Unsafe DSD hardware-volume state is fail-closed at the real DSD payload boundary. This is the Media3/transport equivalent of the reference engine's `paused=true` safety response after an asynchronous volume failure.
- Mica deliberately keeps hardware HID transactions serialized with transport open/close. This is classified `MICA_STRICTER`: it is more conservative than the reference split-lock model and prevents a physical session transition from overtaking an in-flight hardware write.
- Mica Raw/full-scale remains the compatibility default for unknown DACs/SK02; engaging digital volume correctly clears `signalExact` rather than claiming bit-perfect output.

### Final software gates

- `:sylvakru-usb-transport:testDebugUnitTest` -> **BUILD SUCCESSFUL**.
- `:app:testDebugUnitTest` after the final transport changes -> **BUILD SUCCESSFUL in 22s**.
- QA side-by-side `:app:assembleDebug -Pmica.qaSideBySide=true` -> **BUILD SUCCESSFUL in 59s**.
- `git diff --check` -> **exit 0** (line-ending warnings only; no whitespace errors).

### Physical qualification boundary

The **software/reference-function audit is closed**. Physical qualification remains a separate gate:

- Existing SK02 Shared/Exact/DoP/Native, reconnect, pause/resume/seek and DSD64/128 baselines remain authoritative.
- The SK02-only Native DSD256 cold-entry extension remains required: enter through a Native DSD128 silence prime, then use normal `SILENT_RECONFIGURE` to DSD256.
- iBasso/Macaron hardware-volume behavior is software-equivalent but **not physically GREEN** in this audit because matching hardware was unavailable.
