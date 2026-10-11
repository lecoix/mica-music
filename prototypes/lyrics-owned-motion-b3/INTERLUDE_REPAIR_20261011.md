# B3 间奏真机反馈续修（2026-10-11）

## Source and safety
- Read-only source QA: `D:\AI\3\mica-lyrics-b3-phoneqa-20261011` commit `861df9f9` and B2 QA `b048c48f`. Both recorded ~47–49 pixels at 720×1600 ≈80–83 device pixels at native 1220×2712.
- Changes ONLY to original B3 development worktree `D:\AI\3\mica-lyrics-b3-20261011`, starting commit `8a45cd14`. No phone operations, no permanent file deletion, no push or main/B2 edits.

## Diagnosed before repair (real production ExpandedLyricsPanel, Robolectric SDK34, xxhdpi/3x density)
- Synthetic QA production lyrics and actual `LyricLineBlock`, real `LazyColumn`, top of list inside a QA-style `Column`.
- At 17s interlude arrival: line-4 Root Y delta = **-84px** (28dp), ordinary follow consumed delta = 0px, owned translation = 0px, stagger lag = 0px at stable endpoints.
- Original code sent unconditional `LazyListState.animateScrollBy(10dp+spacing18dp)` despite the keyed layout having already held the next line. This recreated the same ~28dp upward displacement as the true Android 12 video.
- Initial targeted revision measured Root Y delta **0px** using actual before/after next-key geometry instead of nominal height. However expanded tests exposed 60%-anchor single-frame transient 84px, fast-follow 10.09px, and 29/68 end transitions mixed with legitimate current-line reflow. Those failures were retained and drive the handoff work.
- Follow-distance is positive for content moving upward. Full visual Y in one authority space: `listRootYPx + info.offset + ownedTranslation + staggerLag`. Root geometry, effective item position, actual followConsumed and interludeConsumed are sampled per frame; absence of a row marks recycling.

## Repair ownership
1. On interlude insertion, use the prior same-key measured `offset` receipt and actual cumulative follow to derive **only the additional downshift not already corrected by LazyColumn anchoring**; clamp to the nominal height ceiling.
2. Convert the correction to frame-by-frame `scrollBy` consumption instead of opaque fixed `animateScrollBy`, keep a separate consumed ledger. Do not count the same distance twice.
3. B3 `OwnedLyricsPositionMotion.pin` cancels the conflicting key's old FLIP spring and bridges its previous actual visual Y while that corrective scroll progresses. Only interlude-following keys participate; upper rows and the list retain their own movement authority.
4. Dispose/cancel via existing key disappearance, track change and seek. Never revive Foundation `animateItem` placement or rewrite lyric data/audio systems.
5. No-motion path uses exactly measured correction too; entry/exit visual alpha and word/line styles unchanged.

## Verification / gates
- The original Foundation +463px departing-row probe and previous B3 32/32 tests remain in the suite.
- Real `ExpandedLyricsPanel` integration suite covers first 13→17 entry (25%, 60%, fast overlapping follow), second 49→55 entry, and 28→29 / 67→68 exits under density 3.
- **The entry-specific device-pixel per-frame drift gate is <8px after subtracting actual normal follow**. Exit tests also log the current-line height/anchor/follow contribution; a no-interlude equivalent baseline is needed before attributing all end-transition geometry to duplication.
## Actual post-fix verification

Test command, from only the B3 worktree, Compose BOM remains `2024.10.00`:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests com.mica.music.ui.screens.ExpandedLyricsInterludeIntegrationTest --tests com.mica.music.ui.screens.LyricsOwnedDepartureProbeTest --tests com.mica.music.ui.screens.LyricsOwnedGeometryTest --tests com.mica.music.ui.screens.NowPlayingLyricsExpandedTest --no-daemon --console=plain
```

**BUILD SUCCESSFUL in 1m 12s. 43 / 43 passed, 0 errors/failures** (9 production Panel integration; 3 prior faithful departure/seek; 9 owned geometry; 22 existing panel). Verified XML: `app/build/test-results/testDebugUnitTest/TEST-com.mica.music.ui.screens.*.xml`.

Measured **additional residual after deducting actual follow** (physical pixels at density3):

| Production ExpandedLyricsPanel test | Measured maximum extra drift |
| --- | ---: |
| 13→17s opening, anchor 25% | 0.0px |
| 13→17s opening, anchor 60% | 0.0px |
| rapid overlapping position changes around 17s | 1.035px |
| opening with fixed-follow baseline | 0.0px |
| second opening at 55s | 0.0px |
| during 17→29s gap, from 19s after seek settlement | 0.0px |
| during 55→68s gap | 1.0px |

At 60% the keyed Lazy item really needs ~83.97px list correction, but the synchronous B3 pre-draw pin retains next-line visual Y at 666px; at 25% the same key needs **0** correction, so there is no duplicate scroll.

29s/68s exit tests **do not claim stationary next line**, since it becomes current and the usual follow/reflow is required. They verified follow consumption 352.23px and 279.89px respectively, with expired interlude gone. Normal exit follow, scale/bold line height and configurable anchor remain in production code.

Pure model tests also verify measured key already stationary→0 scroll, actual +84px downshift→84px compensation, and 30px concurrent ordinary follow +20px structural shift→50px measured correction.

The original old-row moving-away +463px negative repro remains prevented by the self-owned B3 layer. The faithful +463px regression test has continued to pass, together with repeated seek/recycling. No Compose itemAnimator or old placement path restored.

**Limits:** Android 12 production component real-device re-acceptance on the newly built B3 fix is pending another session (AgentDock-phone owns the device). The robolectric test uses a controlled Debug production-component synthetic document, not original user audio/song. Test-only frame collection and fixed-follow mode have no effect at default production call sites. The receipt map is scoped to current lyrics and may warrant later memory audit on unusually long documents. No APK installed, no user data touched, no deletion, no merge or push.

## Independent QA build
- Command: `./gradlew.bat :app:assembleDebug '-Pmica.qaSideBySide=true' '-Pmica.qaApplicationId=com.mica.music.b3fixqa' --no-daemon --console=plain` (PowerShell, with `ANDROID_HOME` and `ANDROID_SDK_ROOT` pointing to `C:\Users\leco\AppData\Local\Android\Sdk`).
- **BUILD SUCCESSFUL in 2m27s**, `app/build/outputs/apk/debug/app-debug.apk`.
- Actual `aapt dump badging`: `com.mica.music.b3fixqa`, version 0.4.3-qa, targetSdk34, minSdk26.
- Size: 40,674,394 bytes. SHA-256: `0C2BC0020F419BA4F59DB78760246A4AFA80C830ACC1B4AB5D92A7BE9E1515BB`.
- Published AgentDock QA artifact id `d8ce2d8530e31d577f0ef9c5bc68a3c1`. Only a separate app ID; APK **not installed**.
- Real Android 12 B3 FIX comparison and screenshot/visual gate remain the other AgentDock-phone session's responsibility.
- Local B3-only commit SHA is recorded in the final handoff and in the B3 branch `git log -1`; branch is not pushed/merged.
