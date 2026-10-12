# Android 16 / targetSdk 36 migration

Date: 2026-10-12. Branch: `codex/target-sdk-36`, created from the current
`codex/enable-r8` checkout. Existing uncommitted R8, Vivo diagnostics and player UI
changes were retained; they are not changes introduced by this migration.
Native dependency follow-up: 2026-10-13.

Proceeding decision (2026-10-13): the user approved continuing the migration
with the available Android 12/16 4KB evidence and no further physical device
available. Keep 16KB runtime explicitly unverified; do not treat the decision
as test evidence or full release acceptance. Close the migration as a local
branch commit without publishing. Existing R8 build/proguard/Sardine edits
are outside that commit; recorded Release builds used the whole working tree,
including those separate edits, and do not prove a clean migration-only
checkout's Release build.

## Main-branch integration (2026-10-13)

Integrate only source commit `828d36a1` into the local default branch
`exoplayer-only`, whose pre-integration tip is `2d246d39`. The complete SDK
branch also contains earlier docs/lyrics/spectrum commits and conflicts with
the main branch's newer lyrics rewrite, so those other commits are excluded.
Production Kotlin/C++ sources already on main are retained unchanged.
The documentation-index conflict retains main's entries and adds the SDK
migration link. Retain main's existing test inventory and add only the new native device
test; omit source-branch-only lyrics/layout entries. Main already has 92
unclassified methods and 3 stale rows before this integration; an exact
before/after key-set check confirms this existing gap is unchanged. Its existing taxonomy
gap is not repaired by the SDK merge; the source-branch inventory PASS must
not be presented as a main-branch PASS.
The 2507-case JVM and Android 12/16 receipts above belong to the source SDK
branch; do not advertise them as a complete rerun of main's newer lyrics
implementation. No remote push or release is performed by this integration.

## Scope

- Raise app `targetSdk` from 34 to 36; retain `compileSdk 36`, `minSdk 26`, AGP
  8.9.1 and Java/Kotlin bytecode target 17.
- Upgrade Robolectric 4.13 to 4.16.1. API 36 JVM tests need JDK 21, so update
  JVM-test, nightly and release-test CI environments. SDK 34 screenshot and
  compile-only jobs retain JDK 17.
  Test-running CI jobs install both JDK 17 and 21, default to 21, and expose the
  version-specific installation variables to Gradle. TagLib retains its explicit
  JDK 17 compilation toolchain.
- Allow a 2GB JVM-test heap after the full-suite attempt exhausted memory.
  This is a test-process limit, not a change to Android application memory.
- Enable NDK flexible page sizes for TagLib, SylvaKru/libusb and the debug-only
  SK02 prototype. Inspection of the first target-36 Debug APK found these
  arm64 libraries had 4KB ELF LOAD alignment. FFmpeg and the packaged C++
  runtime already had 16KB LOAD alignment. Retain the explicit
  `common-page-size=16384` option for self-built libraries.
  Recheck the rebuilt APKs, not just the build flags.
- Pin NDK r28c (`28.2.13676358`) for the app and all three native modules so
  the packaged C++ shared runtime and self-built libraries use the same NDK.
  Update the transitive Graphics Path 1.0.1 dependency to stable 1.1.0 explicitly,
  retaining the existing Compose BOM. Its published AAR declares minSdk 23,
  minCompileSdk 34 and minimum AGP 8.1.1; app minSdk remains 26.
  The local r28c install is under `.local/android-sdk/ndk/28.2.13676358`;
  ignored `local.properties` points to it via `ndk.dir` for this verification.
  The committed configuration pins the version and has no absolute local path.
- Run the existing noisy-broadcast service lifecycle flow and the queued USB
  dispatch/destroy contract on both SDK 34 and SDK 36.
- Pin the default JVM platform to SDK 34 in `robolectric.properties`, retaining
  the pre-migration platform baseline instead of implicitly selecting the new
  manifest target for every unannotated test. Explicit multi-API annotations,
  including the new SDK 36 contracts, continue to override that default.
  This is a test-platform baseline, not a rollback of the app target.
- Seed an actual MediaStore item in the tag-editor intent test, because the
  upgraded Robolectric provider rejects MIME queries for nonexistent rows.
  Verify read-only URI grants and ClipData on SDK 34 and 36.
- No changes to decoding, DSP, resampling, audio output modes, database schema,
  lyric loading or library publication ownership.

## Behavior review

| Change | Existing implementation and remaining validation |
| --- | --- |
| API 35 audio-focus restrictions | `MicaMediaService` is a `MediaSessionService`; manifest declares the mediaPlayback service type and permission. `ExoPlaybackStack` uses Media3 audio-focus handling. Widget cold commands use a temporary mediaPlayback FGS. Actual background resume/focus acquisition still needs device testing. |
| API 35 FGS boot/overlay restrictions | App manifest has no BOOT_COMPLETED playback receiver. Desktop lyrics uses an ordinary overlay service. Do not assume overlay permission alone permits a background playback FGS start. |
| Edge-to-edge and display cutouts | Main Activity already calls `enableEdgeToEdge`; Compose consumes navigation/safe-drawing insets. No edge-to-edge opt-out is added. Verify portrait, landscape, cutouts, IME and three-button navigation. |
| Predictive back | Manifest enables the callback; AppNavigation uses `PredictiveBackHandler` and screens use Compose `BackHandler`. No legacy `onBackPressed` or KEYCODE_BACK interception was found in production sources. Verify gesture completion/cancellation and overlay priority on a device. |
| Large screens | Manifest does not lock orientation or resizability. Existing landscape layout policies remain in place. Verify tablets, split screen and window resizing; layout unit tests are not visual acceptance. |
| Fixed-rate scheduling | Spectrum facade schedules engine ticks at 60Hz. Missing executions are no longer replayed in a burst at target 36. Verify background/foreground spectrum recovery; do not change analysis or audio quality to compensate without evidence. |
| MediaStore version | `AndroidDeviceMediaStoreGenerationApi` treats providerVersion as an opaque nonblank string. The cursor establishes a new baseline when it changes; it does not parse the version format. |
| Local network | API 36 local-network restrictions are opt-in. Do not add the API 37 ACCESS_LOCAL_NETWORK permission or enable the restriction in this migration. LAN playback still needs smoke coverage. |

## Validation

| Check | Current result |
| --- | --- |
| Debug APK | Updated native dependencies passed. minSdk 26 / targetSdk 36, arm64-v8a + armeabi-v7a. APK 40,536,954 bytes. |
| Release APK | Updated native dependencies passed, including R8. `app-release-unsigned.apk`, 17,490,093 bytes; minSdk 26 / targetSdk 36; both ABIs. Not signed or published. |
| androidTest Kotlin compilation | Passed, including the new native compatibility device test. Android 12/16 QA device results are recorded below. |
| Debug lint | Earlier migration run passed in 1m 43s; not rerun after the native-only dependency update. |
| Release vital lint | Passed again after the native dependency update. |
| Focused service/open/widget tests | Earlier focused run: 32 tests, zero failures/errors. Included again in the final full JVM run. |
| Corrected tag-editor fixture | Final full run: all 10 `SongActionsTest` cases passed, including MediaStore URI grants/ClipData on SDK 34 and 36. |
| Last full JVM suite | Passed after the native update and stable default-SDK configuration: 2507 tests, zero failures/errors, 9 skipped; BUILD SUCCESSFUL in 8m 5s. This is the default SDK 34 suite plus explicit API configurations, not a full API 36 matrix. |
| Native 16KB structure | Updated Debug/Release APKs pass 4KB checks for both ABIs, 16KB ARM64 LOAD alignment/offset checks and APK zip alignment. No page-rounded RELRO overlap with writable data. Dependency identity checks passed for both ABIs in both APKs. Runtime testing remains open. |
| CI | JDK/runner configuration updated locally; remote GitHub jobs have not been executed by this task. |
| Android 16 device / capacity acceptance | Android 12 and Android 16 / 4KB directed QA has evidence below. Android 16 queue automation is blocked in setup. Android 15, 16KB, signed R8 runtime and 10,000-song capacity gates remain open. |

The common-page option aligned TagLib, SylvaKru/libusb and prototype RELRO ends;
FFmpeg also passes. Debug has seven arm64 libraries and Release has six; all
have 16KB LOAD alignment and both APKs pass `zipalign -c -P 16 4`.
The initial prebuilt checks reported RELRO-end remainders `0x2000` for
`libandroidx.graphics.path.so` and `0x3000` for `libc++_shared.so`.
Correction: these remainders alone do not establish an actual runtime defect.
AOSP's linker rounds the protection range to memory pages; failure requires
that protection to cover data that still needs to be writable. Inspect the
LOAD layout as well as the RELRO header. In the old Graphics Path artifact,
the RELRO end is `0x6000`, protection ends at `0x8000`, and writable BSS begins
at `0x9fd0`. In the old C++ runtime, the end is `0x143000`, protection ends at
`0x144000`, and the next writable LOAD begins at `0x146e40`. Neither crosses
into that writable data. Graphics Path 1.1.0 still has a `0x2000` remainder,
but its writable BSS begins at `0x9ed8`, also outside the protection range.
The initial wording that these two libraries had confirmed compatibility
failures was too strong. No 16KB-device crash has been reproduced.
The scratch check `.scratch/target-sdk-36/check-native-pages.py` checks LOAD
alignment/offset congruence and page-rounded RELRO overlap with writable LOAD
bytes outside RELRO. It is a structural check, not a full Android linker or a
substitute for runtime testing. Do not claim complete 16KB acceptance.

The initial Robolectric 4.13 run had 80 cases and two unsupported-API-36
initialization failures. A later combined run exhausted memory in the full
JVM suite; the isolated 2GB-test-heap run completed without reported OOM.
Its only assertion-run failure came from querying nonexistent MediaStore row
42 while creating ClipData, rather than a URI-grant assertion.

Command infrastructure also failed before process creation and lost a focused
session during configuration; no exit marker or Java process remained for that
interrupted attempt. The build-only run reached its 15-minute limit during lint,
and the first isolated Release run reached its 10-minute limit during R8.
These interrupted runs are not PASS evidence. Cache-reusing Release and lint
retries passed with a temporary 3GB Gradle heap and one worker; the repository's
default Gradle heap remains 2GB. R8 CPU samples showed ongoing computation,
with roughly 2.7GB heap in use, but do not isolate the cause of its runtime.

Evidence logs:

- `.scratch-target36-focused-isolated.log`: 32 focused tests.
- `.scratch-target36-full-jvm.log`: last full-suite result.
- `.scratch-target36-song-actions.log`: corrected fixture, 10 tests.
- `.scratch-target36-build-only.log`: Debug / androidTest compilation; lint timeout.
- `.scratch-target36-release-only.log`: first Release timeout.
- `.scratch-target36-release-resume.log`: successful Release build, 5m 8s.
- `.scratch-target36-lint-only.log`: successful Debug lint.
- `.scratch-target36-ndk-install.log`: workspace-local NDK r28c installation.
- `.scratch-target36-native-debug.log`: first updated build, timed out after
  15 minutes at dexBuilderDebug, after app Kotlin compilation completed.
  A host sample reported 621MB free physical memory out of 16,116MB; memory
  pressure is a possible contributor, not a proven exclusive cause.
- `.scratch-target36-native-debug-resume.log`: cache-reusing retry with a
  temporary 2GB Gradle heap and one worker. Passed, `BUILD SUCCESSFUL in
  13m 21s`, including Debug APK and androidTest Kotlin compilation.
- `.scratch-target36-native-apk-check.log`: updated Debug/Release APK structural
  checks. The r28c C++ runtime has RELRO-end remainder `0x2000`, and Graphics
  Path 1.1.0 also has `0x2000`; neither protects writable data. All self-built
  ARM64 libraries have zero remainder.
  Separate SHA-256 comparison confirmed both ABIs' packaged Graphics Path
  binaries match the official 1.1.0 AAR and C++ runtimes match the r28c sysroot
  binaries after the same `--strip-unneeded` operation used by packaging.
  Release has the same four verified dependency binaries as Debug. Both APKs
  pass `zipalign -c -P 16 4`; final badging confirms minSdk 26 / targetSdk 36.
- `.scratch-target36-native-release.log`: Release build with a temporary
  3GB heap, one worker and in-process Kotlin compilation to avoid a separate
  compiler heap during this memory-constrained local verification. Passed,
  `BUILD SUCCESSFUL in 21m 52s`, including R8 and Release vital lint.

## Open device gates

### Local commit preparation / JVM follow-up (2026-10-13)

The first final whole-suite retry completed in 6m 44s with 2507 tests,
35 failures and three skips. The first failure was
`DefaultNativeRuntimeLoader.maybeCopyFonts` opening an already-registered ZIP
filesystem; the remaining 34 failures were consequent native RenderNode
link errors across six UI/rendering classes. Failure XML and counts are
archived under `.scratch/target-sdk-36/final-jvm-first/`; the original log is
`.scratch-target36-final-jvm.log`. The same MainAppSurface screenshot class
passed all three cases in a fresh isolated run (1m 58s), without changes to
production UI, assertions or golden images.

Robolectric's upstream [font extraction fix](https://github.com/robolectric/robolectric/pull/11496)
describes this JVM-global ZIP filesystem collision and failed-copy leak.
The local mitigation retains the default test-platform baseline at API 34
through `app/src/test/resources/robolectric.properties`; manifest targetSdk
remains 36. Explicit `@Config` selections still override the default. This
keeps target changes from implicitly changing every unannotated test's
platform and native initialization path; it does not backport the upstream
loader fix or guarantee the absence of all future native-runtime collisions.

With that single configuration change, the complete suite passed in 8m 5s:
2507 tests, zero failures/errors, nine skips. XML confirms both API variants
of the noisy receiver, queued USB dispatch/destroy, and MediaStore ClipData
tests passed; the three MainAppSurface renders also passed in the full run.
Evidence: `.scratch-target36-final-jvm-stable-sdk.log` / `.exit`, and
`.scratch/target-sdk-36/final-jvm-stable-sdk/summary.json`. The default baseline
is API 34; do not advertise this as all 2507 tests running on API 36. The nine
skips remain outside passing evidence. Full screenshot-golden/nightly gates
were not run during this final retry.

The test inventory is regenerated and its check passes with 2642 tracked
test methods. It adds this migration's native device test plus five tests
already committed by the preceding lyrics/layout work. Selective staging of
`app/build.gradle.kts` retains the R8/minification and Sardine changes in the
working tree; root R8 buildscript and ProGuard changes are also excluded.
No production async publication/owner protocol or audio-quality algorithm is
changed by the migration. Peak capacity on the 10,000-song/8GB baseline
remains unmeasured. A local commit does not close release acceptance gates.

### Android 12 / 4KB device follow-up (2026-10-13)

The supplied `10:42539` was resolved using the current WLAN subnet to
`172.17.57.10:42539`. The connected device reports model `22081212C`, Android
12 / API 31 and 4,096-byte memory pages. This can provide older-platform / 4KB
evidence; it cannot close Android 15/16 or 16KB runtime gates.

Device tests use dedicated package `com.mica.music.target36.qa` (targetSdk 36,
minSdk 26), separate from the existing ordinary app and other QA packages.
The original verified normal Debug APK is archived at
`.scratch/target-sdk-36/normal-debug/app-debug.apk`; standard `app/build`
Debug outputs now belong to the dedicated QA build.

`NativeLibraryCompatibilityDeviceTest` directly loads the six release native
libraries and checks PathIterator coordinates and conic conversion. It is
classified as Implementation Verification; the tracked test manifest is
regenerated during local commit preparation, including five already-committed
lyrics/layout tests omitted by the previous inventory. Existing decode, TagLib, external-open and UI
flow tests supply additional evidence. Results:

| Device check | Result |
| --- | --- |
| Installed QA package | versionCode 58, `0.4.3-qa`, minSdk 26 / targetSdk 36, ARM64. |
| Six native libraries + PathIterator geometry/conics | Passed. Includes the r28c C++ runtime and Graphics Path 1.1.0; API 31 exercises JNI iteration. |
| TagLib FLAC/ALAC embedded lyrics and DSF ID3 pointer | Two tests passed. Fixtures only use QA cache files. |
| ALAC / DSF actual FFmpeg decode and AudioTrack delivery, DSF seek | Passed. Tests mute their local player; this is delivery evidence, not subjective audio-quality or USB acceptance. |
| FLAC notification metadata first-play stress | Ten first-play iterations passed without decoder reinitialization. |
| External DocumentsProvider audio resolution | Passed. |
| Queue UI drag/remove -> service order -> natural successor -> visible song | Passed. |
| Settings search/tutorial flow | Failed at line 131's return-to-`浏览设置` assertion, reproduced in an isolated retry. Do not label the full UI group PASS. |

The native/metadata/decode/provider group reports `OK (7 tests)` in 10.084s.
The UI group reports one pass and one failure in 13.481s; the isolated settings
retry fails at the same assertion in 6.176s. SettingsScreen's existing
`navigateBack()` search-entry branch clears the selected entry/category but
retains the search query, while SettingsCategoryList only displays `浏览设置`
for a blank query. These settings source files have no migration diff. This
explains the expectation mismatch; it does not establish a new SDK/native
regression or a full before/after runtime comparison. Neither the production
navigation nor the existing test expectation was changed to obtain PASS.

Device logs: `.scratch-target36-device-native-tests.log`,
`.scratch-target36-device-ui-tests.log`, and
`.scratch-target36-device-settings-retry.log`.
Final shell cold launch returned `Status: ok`, `LaunchState: COLD`, total time
1,588ms; `.scratch/target-sdk-36/device-home.png` was visually inspected and
shows the QA home/empty-library screen. Post-test package inspection still
shows the ordinary app at versionCode 57 / 0.4.2 / targetSdk 34, and the new
QA app at versionCode 58 / 0.4.3-qa / targetSdk 36. No ordinary-app update or
data reset was performed. Unique directed result: eight passed, one settings
assertion failed; that failure repeated once in isolation. This is Debug QA
evidence, not signed/minified Release runtime acceptance.

The first QA attempt used the existing `mica.alternateBuildDir` option and
failed because `R.jar` was absent from that alternate directory while a
resource jar was present in standard `app/build`. The harness retry uses the
standard build directory. This was a build-harness failure before device
execution, not evidence of native incompatibility. Logs:
`.scratch-target36-device-qa-build.log` and
`.scratch-target36-device-qa-standard-build.log`. The standard build passed
in 9m 58s, including the main QA APK and fresh instrumentation APK.

### Android 16 / 4KB device follow-up (2026-10-13)

ADB mDNS confirms the supplied `17:44531` as `172.17.57.17:44531`.
The connected realme RMX8899 reports Android 16 / API 36, ARM64 and
4,096-byte memory pages. The same dedicated Debug QA package and fresh
instrumentation APK from the Android 12 follow-up were installed successfully;
no rebuild was necessary. Installed QA badging is versionCode 58 / 0.4.3-qa,
minSdk 26 / targetSdk 36. The ordinary package remains versionCode 58 / 0.4.3,
targetSdk 34, matching its pre-test inspection; it was not updated or cleared.

| Device check | Result |
| --- | --- |
| Six native libraries, PathIterator geometry and conic conversion | Passed. API 36 exercises the platform iterator path together with the packaged native dependencies. |
| TagLib FLAC/ALAC lyrics and DSF ID3 pointer | Two tests passed. |
| Actual ALAC / DSF FFmpeg decode and audio-sink delivery, DSF seek | Passed. Muted fixture players do not establish audible quality or USB transport acceptance. |
| FLAC notification metadata first-play stress | Passed, including ten first-play iterations. |
| External DocumentsProvider audio resolution | Passed. |
| Media service recreation | Passed: current song, position and repeat mode restored without automatic playback. This is service recreation, not process-death restoration. |
| Automated queue drag/remove flow | Blocked in setup at PlaybackQueueUiFlowTest.kt:72: UiAutomation notification grant throws SecurityException because shell uid 2000 lacks GRANT_RUNTIME_PERMISSIONS on this device. The queue assertions did not execute. No device security setting or test expectation was changed. |
| Shell cold launch | Status ok, COLD, total time 912ms. The immediate home capture is still the splash screen; it does not prove a settled home screen. |
| ACTION_VIEW to visible player | Passed smoke: the QA-owned DocumentsProvider URI for contract.wav opens the actual player, shows contract / WAV 16bit/8kHz and 0:03 / 1:05 with a pause control. MediaSession reports PLAYING, one queue item and no error. This exercises the real intent/resolver/player path with a same-package provider, not a third-party URI permission grant. |
| Short background playback | After HOME and a three-second wait, MediaSession remains PLAYING at 50,941ms, with contract metadata and a foreground media notification record (id 1001). This is bounded background evidence, not lock-screen, notification-action or OEM long-duration acceptance. |

The completed native/decode/provider/service group reports `OK (8 tests)` in
7.064s in `.scratch-target36-an16-native-tests-retry.log`. An earlier run's
ADB output ended during DSF with no JUnit summary, so that run is incomplete,
not PASS. DSF separately passed in 0.723s before the complete group retry.
The first installation attempt also lost its ADB connection; reconnect/install
then succeeded. A prior host command timed out around the interrupted run;
host ADB cleanup is a plausible cause, not proven. Captured application exit
info records FORCE STOP due to finished instrumentation, not a crash.

Queue setup failure is captured in `.scratch-target36-an16-queue-ui.log`;
the directed total is eight completed passing tests and one setup error.
The previously known settings return assertion was not rerun on this device.
Additional smoke evidence: `.scratch-target36-an16-external-open.log`,
`.scratch-target36-an16-external-session.log`,
`.scratch-target36-an16-background-session.log` and
`.scratch-target36-an16-notifications.log`. The inspected
`.scratch/target-sdk-36/an16-external.png` shows the visible playing fixture.
`an16-queue.png` shows the lyrics view after a coordinate tap; it does not prove
queue interaction. `an16-resume.png` captures a system return animation and
does not establish the settled layout. No production code changes were made
during this follow-up, and no signed/minified Release APK was installed.
The final inspected `an16-final.png` shows the settled player at 1:05 / 1:05
with a play control after the silent fixture ends; a wireless-debugging system
banner overlays the upper cover. The app is left installed on that player.

### Remaining acceptance work

1. Android 16: ordinary media permission UX and third-party external content
   URI grants through the final visible player/queue result; automated queue
   drag/remove remains blocked by this device's shell permission restriction.
2. Playback: foreground start -> background/lock screen -> notification,
   headset and widget pause/resume; include cold process restoration and
   transient buffering/focus loss.
3. UI: predictive back completion/cancellation, drawer/player/lyrics priority,
   gesture and three-button navigation, portrait/landscape, split screen.
4. Native: on a 16KB ARM64 device, load C++ / Graphics Path / TagLib / FFmpeg /
   USB libraries and perform real decoding and USB permission/output/recovery.
   Also exercise library loading and actual playback on Android 8, 13 and 15;
   older-platform Graphics Path JNI fallback needs coverage as well as the
   newer platform path. ELF alignment alone does not establish runtime,
   transport or audio-quality correctness.
5. Capacity: 10,000 songs with complete word-timed lyrics on an 8GB phone.
   Migration adds no per-song state or lyric materialization, but total process
   peak/stability after the target bump remains unmeasured.

Do not label this migration a release PASS until the relevant device gates
have evidence. SDK 36 Robolectric models do not implement every OEM lifecycle,
audio-focus, USB or window-management restriction.

## Sources

- [Robolectric font/ZIP filesystem collision fix](https://github.com/robolectric/robolectric/pull/11496)
- [Android 15 target changes](https://developer.android.com/about/versions/15/behavior-changes-15)
- [Android 16 target changes](https://developer.android.com/about/versions/16/behavior-changes-16)
- [16KB native page compatibility](https://developer.android.com/guide/practices/page-sizes)
- [AOSP RELRO page protection implementation](https://android.googlesource.com/platform/bionic/+/refs/heads/main/linker/linker_phdr.cpp)
- [NDK r28 release notes](https://github.com/android/ndk/wiki/Changelog-r28)
- [Graphics Path release notes](https://developer.android.com/jetpack/androidx/releases/graphics#graphics-path)
- [Robolectric SDK/JDK compatibility](https://robolectric.org/compatibility_table/)
