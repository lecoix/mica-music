# Mica Lyrics B3 · Implementation Verification (2026-10-11)

## Isolation
- Worktree: `D:\AI\3\mica-lyrics-b3-20261011`
- Branch: `experiment/lyrics-owned-motion-b3-20261011`
- Base: `8cf731da8bf5af150392b2645f3a336272e5a06d`
- B2 (`ab25c1af...`) read-only reference; main worktree uncommitted files untouched.
- Compose BOM unchanged: 2024.10.00 / Foundation 1.7.4. No phone operations and no local-file deletion commands.

## Scope
- Independent keyed FLIP position residual controller; LazyColumn retains measured layout/scroll ownership.
- `animateItem` / `placementSpec` removed from both lyrics lines and interlude.
- Spring cancellation on key disappearance, new document, seek, viewport/freeze changes.
- Normal follow `scrollBy`, stagger lag, highlight/scale and configurable line anchor retained.
- Local 250ms entry fade / fading interlude retained briefly for exit instead of Foundation item animator.

## 2026-10-11 actual test command
```powershell
.\gradlew.bat :app:testDebugUnitTest --tests com.mica.music.ui.screens.LyricsOwnedGeometryTest --tests com.mica.music.ui.screens.LyricsOwnedDepartureProbeTest --tests com.mica.music.ui.screens.NowPlayingLyricsExpandedTest --no-daemon --console=plain
```

**First targeted run: BUILD SUCCESSFUL in 4m 47s**. Follow-up rapid-seek rerun: **BUILD SUCCESSFUL in 1m 18s** using the verified B3 QA application ID. Final Gradle XML: **32/32 pass, 0 failures, 0 errors**:
- `LyricsOwnedDepartureProbeTest`: 3/3 (faithful old-row, interlude anchored-next-row, rapid repeated seek)
- `LyricsOwnedGeometryTest`: 7/7
- `NowPlayingLyricsExpandedTest`: 22/22

Probe measurements:
```text
B3_OWNED_DEPARTURE initial=42.0 max=44.0 drop=+2.0px count=2
B3_INTERLUDE upperMove=-25.401031px lowerMaxDrift=0.0px
```
The `+2px` is far below the historical Foundation moving-away `+463px` under the same controlled padding/row-height/scroll fixture. The old row exits the Lazy viewport quickly, hence only two attached samples; **not** a full original-song device equivalence. Synthetic interlude upper row moved upward and lower row stayed stationary in its measured fixture.

Original first two compile attempts failed only because the new NestedScrollConnection override had a wrong Kotlin return signature; it was corrected to `onPostScroll(...): Offset` returning `Offset.Zero`. The subsequent full targeted run succeeded.

## QA status
- No phone connection / device installation / original-song screenshot or full production visual acceptance performed.
- **Independent QA APK built successfully**: `app/build/outputs/apk/debug/app-debug.apk` (40,642,438 bytes; version `0.4.3-qa`; actual aapt package ID `com.mica.music.lyricsb3qa`). SHA-256 `1AC453F8E2EE97EAE7FFBDB37D66EBF7D6618E2F3A71BE37CCE454D79BE3C497`.
- QA build initially failed because this fresh worktree lacked `third_party/taglib/src/main/cpp/taglib/3rdparty/utfcpp`; `git submodule update --init --recursive` initialized locked commit `c5585ef` only in B3, then `assembleDebug` succeeded (2m 9s). No phone installed; see `QA_COMPARISON.md`.
- Additional rapid seek UI test was added following the initial 31/31 run and **passed on the 32/32 follow-up**: five fast targets (10,3,16,2,19), correct final visible index 19, no stale translation.
- Real-device review remains a gate before merging. Do not auto-push/merge.
