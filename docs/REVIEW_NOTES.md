# Review Notes

> Last updated: 2026-05-23. These notes capture the project review plus the completed playback page and lyrics page refactor pass.

## High-Priority Risks

- Playback routing is too dependent on the software/FFmpeg path. `PlayerController.playSong()` currently sends playback through the ALAC/PCM engine path, so common formats can fail when the embedded FFmpeg binary is missing or cannot execute. Prefer ExoPlayer/native playback for supported formats, and reserve FFmpeg for ALAC/APE/unsupported codec fallback.
- Compact lyrics rolling can overshoot on large seeks. `PlayerLyricsIndexRoll` uses the full index delta as a vertical offset; dragging across many lyric lines can push the lyric block far off screen. Clamp the roll distance to one visual step in the direction of travel.
- `MusicLibrary` owns an IO `SupervisorJob` but has no lifecycle release hook. Long scans or persistence jobs can continue after `MainViewModel` is cleared. Add a release/cancel method and call it from the ViewModel.
- Playback page geometry is powerful but fragile. The cover-edge progress, expanded lyrics, and immersive lower panel states share several animated progress values and measured snapshots. Keep future refactors small and preserve the "freeze, compute, then animate" rule.

## Refactor Direction

- Keep behavior stable while separating responsibilities:
  - playback screen shell: route state, background, cover area, lower panel composition;
  - compact lyrics: three-line/one-line playback lyrics display and rolling animation;
  - expanded lyrics: full lyrics list, auto-scroll, line seeking;
  - lower panel geometry: spacing, chrome anchoring, immersive/focus transitions.
- Prefer pure data state objects for animation-derived flags such as "lyrics layout active" and "cover edge progress active on play surface".
- Add tests around pure layout and lyric display helpers when the build environment can resolve Android Gradle dependencies.

## Playback/Lyrics Refactor Update

Completed a behavior-preserving split of the playback page and lyrics page:

- `NowPlayingScreen.kt`: now acts as the playback page shell. It wires song state, background, cover section, lower panel, queue sheet, and high-level transitions.
- `NowPlayingLyricsTransition.kt`: owns lyrics focus/chrome transition state and cover letterbox alpha.
- `NowPlayingCoverSection.kt`: owns cover sizing, fit-original morphing, cover-edge progress overlay, and expanded-lyrics header overlay.
- `PlayerLowerPanel.kt`: owns lower-panel composition and the relationship between metadata, compact lyrics, expanded lyrics, and chrome.
- `PlayerLowerPanelAnchor.kt`: owns bottom-anchor snapshots used to avoid jumps when switching between cover-edge progress and standard lyrics chrome.
- `PlayerLowerPanelMetadata.kt`: owns HiFi metadata and title/subtitle display.
- `PlayerLowerPanelChrome.kt`: owns progress bar and playback controls placement.
- `NowPlayingCompactLyrics.kt`: owns compact playback-page lyrics and empty-lyrics fallback.
- `NowPlayingLyricsExpanded.kt`: owns expanded lyrics list, empty-lyrics fallback, timed auto-scroll, and line seeking.

Notable fixes made during the refactor:

- Clamped compact lyric roll distance to one visual step in `PlayerLyricsIndexRoll`, preventing large seeks from pushing lyrics far off screen.
- Restored empty lyric handling for both compact and expanded lyrics by treating blank-only lyric lists as empty.
- Centralized the empty lyric label in `EmptyLyricsText`.
- Preserved the currently playing song by song id when the queue order changes, keeping the mini player, playback page, and actual audio in sync.
- Centered compact lyrics vertically in the playback page metadata area.
- Stabilized immersive title positioning across track changes and cover aspect-ratio changes.
- Kept lower-panel geometry stable while returning from the expanded lyrics page with cover-edge progress enabled.
- Smoothed fit-original cover sizing across the lyrics/playback transition, removing the remaining horizontal-cover and vertical-cover two-step jumps.
- Removed corrupted string/comment remnants from the newly refactored playback and lyrics files where they affected readability or display.

Manual regression already checked by the user:

- Playback page opens, closes, and switches tracks.
- Cover, title, artist, album, and HiFi metadata display.
- Play/pause, previous, next, and queue controls work.
- Progress bar updates and can seek.
- Lyrics page opens from compact lyrics and exits correctly.
- Timed lyrics scroll, highlight, and click-to-seek behavior works.
- Songs without lyrics show the localized empty-lyrics label.
- Immersive mode behavior works.
- Immersive title placement stays centered during track changes.
- Cover-edge progress mode returns from the lyrics page without the compact lyrics/title block jumping.
- Horizontal and vertical fit-original covers no longer cause a two-step lower-panel shift.
- Queue sorting while playing keeps the displayed song aligned with the actual playback item.
- Track-change animation does not visibly flash stale content.

Remaining follow-up candidates:

- Run a full local `assembleDebug` in an environment with Gradle and Android SDK access.
- Consider adding narrow tests for lyric empty-state detection and compact lyric index rolling once the Android build environment is stable.
- Keep future playback/lyrics changes scoped to the new files above instead of re-growing `NowPlayingScreen.kt`.

## Verification Notes

- Initial `:app:assembleDebug` could not complete because Gradle/Android Gradle Plugin dependencies were not available offline.
- After enabling network for this turn, Gradle downloaded successfully, but the build is still blocked by local Android SDK access: `platforms/android-34/package.xml` returns "access denied", and Gradle also reports missing `Sdk/.knownPackages`.
- During the refactor turn, local build attempts in the Codex environment were blocked earlier by Gradle wrapper download permission (`gradle-8.9-bin.zip`). User-side manual testing covered the playback/lyrics regression paths listed above.
