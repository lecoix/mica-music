# Phase 1 (P0) · B3 staged acceptance checkpoint

Date: 2026-10-12. Source under test: `30fe28558a41bf2156fa103fa1241c153cf85c4d`.
All new tests and evidence are confined to `D:\AI\3\mica-lyrics-b3-staged-20261012` on `experiment/lyrics-b3-staged-tests-20261012`. Formal B3 development worktree, B2 and main user worktree remain untouched. No package reinstall, music library edits, git clean/reset or destructive file cleanup.

## Gate and results (status ≠ progress percentage)

The primary 172-scenario ledger defines 75 P0 scenarios (stage 1), 80 P1 common/interaction cases (stage 2), 17 long-running/resource cases (stage 3). Stage 4 is a *repeat of selected critical scenarios through real playback and original user music*, not additional fake unit-test credit.

**Stage 1 explicitly remains OPEN/FAILED.** Of the 75 P0 ledger entries: **1 verified pass, 4 verified fail, 70 not executed**. The failure was already established by previous premerge phone review; this round reproduced it with a new production-component RED test and extra device controls. It would be incorrect to call stage1 finished.

### New automated RED with production ExpandedLyricsPanel (Behavior Contract)

- File: `app/src/test/java/com/mica/music/ui/screens/ExpandedLyricsStage1GestureTest.kt`.
- Exact test: `pausedInterludeDrag_movesRowsOnBothSidesOfInterludeTogether`, with Android34 Robolectric native Compose at 400dp×900dp xxhdpi, 154dp QA top chrome, 60% current line anchor, 13s→18s incremental timeline, pause, 550ms real UI-touch swipe.
- Build: Gradle test source compiled successfully; test ran, **1 FAILED** for the intended user behavior, not compilation, click targeting or instrumentation setup.
- Probe result: `beforeVisible=3:85 afterVisible=6:66 above=-625.7565 below=0.0 mismatch=625.7565`; guard that the list actually moved succeeded, but upper/lower visual travel differed by 625.8 device px. RED evidence XML `P0_C03_ROBOLECTRIC_RED.xml`. There was no intervening production code modification.
- This is a *failing regression test by design*. Keep it in the isolated stage-test branch and **do not cherry-pick an unaddressed failing test into main** until a corresponding B3 fix makes it GREEN.

### Existing B3 regression battery (Implementation Verification)

Separately reran 5 existing test classes in the *same new isolated worktree* against the unchanged `30fe2855` implementation:
- `ExpandedLyricsInterludeIntegrationTest` **9/9 PASS**;
- `LyricsOwnedDepartureProbeTest` **3/3 PASS**;
- `LyricsOwnedGeometryTest` **9/9 PASS**;
- `NowPlayingLyricsExpandedTest` **22/22 PASS**;
- `NowPlayingCompactLyricsTest` **2/2 PASS**.

**45/45 baseline cases PASS / BUILD SUCCESSFUL in 2m17s**. These are **45 implementation tests**, not proof that 45 distinct entries from the 75-scenario P0 ledger have been verified. Their coverage misses real manual drag during interlude.

### Android12 real-device controls

Same B3 fix QA app `com.mica.music.b3fixqa`, model `22081212C`, actual production `ExpandedLyricsPanel` debug controlled song fixture. No audio file modification.

1. C03/X01 **FAIL** — after automatic 13s→18s, 60% anchor, pause, swipe up: upper lyrics move hundreds of px; following lyrics remain pinned at old Y. Swipe-down reversal likewise affects upper rows but not lower (C04 **FAIL**). Photo `PHONE_P0_PAUSED_UP_DOWN_20261012.jpg` (left=before, center=after up, right=after down).
2. C05 **FAIL** — 25% anchor, keep playing during interlude, swipe up: upper lyrics move offscreen, lower lines remain pinned and transiently overlap. Photo `PHONE_P0_PLAYING_DRAG_20261012.jpg`.
3. E04 **PASS** (restricted scenario) — after a failed interlude swipe, seek to 35s, swipe again: rows now scroll together. The stale pin is cleared *on exit*, but remains broken during an active interlude. Photo `PHONE_P0_SEEK_EXIT_PIN_20261012.jpg`.
4. Existing prior fix-release proof: 17s insertion at 25/60% and 55s insertion at 25% showed 0px extra down-line travel across 914 sampled video frames; this still only proves automatic insertion, not user-scrolling behavior.

### Architecture Contract root-cause boundary

`NowPlayingLyricsExpanded.kt:649–670` pins all lines after active interlude on every `onPlaced` using stored pre-interlude visual Y and subtracts *only* auto-follow from that receipt (`debugLedger.followPx`), ignoring user drag consumed scroll. `LyricsOwnedPositionMotion.kt:93–102` keeps a pinned row until `releasePins`; current effect calls `releasePins` only when `interludeKey == null` (roughly at `NowPlayingLyricsExpanded.kt:364–370`). This violates the Behavior Contract that hand dragging moves the same list as one unit and the Architecture Contract that true LazyColumn scrolling remains authority. Pin must be a finite insertion handoff rather than a long-lived immovable coordinate lock.

Stage3 additional lifecycle concern is retained as OPEN: `preInterludeGeometry` `remember(lyrics) mutableMap` grows with visited row keys; unboundedness not yet stress-proven.

## Why stage 2 is not started

The planned stage gate explicitly disallows proceeding past an open P0. Do not attribute the separate 45 green baseline cases to the newly defined 75 P0 scenarios. The *next actionable loop* is B3 test-driven fix of C03/C04/C05/X01 using the new RED real-component test and subsequent phone confirmation, while maintaining:
- 17s and 55s insertion residual < 8 device px;
- list rows responding equally to user gesture during paused/playing interlude;
- normal Seek exit, 29/68s natural exit, fast Seek, old-row departure.

After RED→GREEN and multi-input phone acceptance, continue remaining stage1 P0 cases before phase2/3/4.

## Deterministic commands

```powershell
cd D:\AI\3\mica-lyrics-b3-staged-20261012
.\gradlew.bat :app:testDebugUnitTest --tests com.mica.music.ui.screens.ExpandedLyricsStage1GestureTest.pausedInterludeDrag_movesRowsOnBothSidesOfInterludeTogether --offline --no-daemon --console=plain
.\gradlew.bat :app:testDebugUnitTest --tests com.mica.music.ui.screens.ExpandedLyricsInterludeIntegrationTest --tests com.mica.music.ui.screens.LyricsOwnedDepartureProbeTest --tests com.mica.music.ui.screens.LyricsOwnedGeometryTest --tests com.mica.music.ui.screens.NowPlayingLyricsExpandedTest --tests com.mica.music.ui.screens.NowPlayingCompactLyricsTest --offline --no-daemon --console=plain
```

Set `ANDROID_HOME` and `ANDROID_SDK_ROOT` to the installed SDK `C:\Users\leco\AppData\Local\Android\Sdk` if not already configured. Import `SCENARIO_LEDGER.csv` to inspect/continue statuses. Preserve RED result XML because subsequent Gradle test runs replace test-result directories.
