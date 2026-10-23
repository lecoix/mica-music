# Lyrics slash: display-only versus semantic roles

Branch: fix/lyrics-slash-semantic-20261022

## Invariants

- Raw LyricsDocument semantic roles ORIGINAL / TRANSLATION / READING are authoritative.
- Bare slash is ordinary text. Display rules never turn any separator into semantic TRANSLATION.
- Legacy spaced separators and U+2009/U+200A/U+2005 keep inferred secondary display rows, while retaining ORIGINAL role.
- Explicit newline can create multiple ORIGINAL physical rows without hiding any line.
- Known Chinese and English credit prefixes with ':' or '：' protect all slash text on that line.
- External Lyricon translation field is only filled from a genuine structured TRANSLATION part.
- The song-wide heuristic classifier is not enabled in production until independent positive recall is measured.

## Evidence

Read-only experiment: D:\AI\mica-slash-offline-20261022\RESULTS.md, device 172.17.57.17:40875. 88 audited real non-bilingual slash text lines: previous Python mirror splits 52; removing bare slash and conservative prototype split none. No authentic slash bilingual positive song found in local sample.

Independent format-labelled source:
https://github.com/AirLyrics/AirLyrics/blob/main/docs/LYRICS_FORMAT.md
https://github.com/AirLyrics/AirLyrics/blob/main/app/src/test/java/com/andsi/airlyrics/lyrics/parser/LrcParserTest.kt
MIT licence, source includes both inline slash translation and credits using slash, and independent test assertions.

## Verification on this branch (2026-10-22)

- `:app:compileDebugKotlin`: SUCCESS (full production Kotlin compilation; existing deprecation warnings).
- Targeted seven-class Gradle unit suite: first run finished 81 tests, 79 passed and 2 failed; both failures were fixed (three-physical-row predicate and obsolete cloud display fixture).
- Second full targeted Gradle run was attempted but timed out while compiling unit-test Kotlin sources on a memory-constrained machine. It did NOT report a final test pass; do not treat the suite as fully green.
- Direct Java smoke harness loading the **actual compiled Kotlin classes** from app/build/tmp/kotlin-classes/debug passed 21 checks (three explicit physical lines in all display modes, slash credit/title guards, visual-only secondary rows). Harness lives outside the repo at D:\\AI\\mica-slash-offline-20261022\\SlashSmoke.java.
- Phase 2: structured `TTML`/`SYLT` ORIGINAL-only keeps source text intact; LRC/PLAIN ORIGINAL-only stays eligible for visual legacy compatibility. Shared projection applied to cloud, expanded, compact, landscape, Letter, photo stack, external lyrics; structured READING + ORIGINAL no longer duplicates READING in external originals.
- **Final phase-2 directed Gradle JUnit: 7 classes, 86 tests / 0 failures / 0 errors / 0 skips, BUILD SUCCESSFUL** (2026-10-22, 5m28s), including traditional Chinese prefix guard and READING+ORIGINAL tests. Earlier 81-test failure and timeout above are historical phase-1 run diagnostics, not current results.
- Source-level natural corpus running `NaturalSlashCorpusSmoke.java` against actual generated Kotlin classes: 226/226 spaced-slash bilingual candidate rows with exact left/right text slices and ORIGINAL roles; 5/5 naturally sourced lyric-credit slash negatives intact; **231/231 passed**. Samples sourced from three distinct public GitHub repos; tested raw lyric lines only, not UI/device rendering and not independently human-labeled semantic truth for every positive candidate.
- `git diff --check`: clean. This branch remains an isolated fix candidate, not merged/pushed, and UI/device integration acceptance has not been completed.

## Independent natural slash corpus (read-only, no lyric text vendoring)

Five complete public LRC files were inspected from TWO separate repositories. Metadata-only registry: D:\\AI\\mica-slash-offline-20261022\\natural_slash_positive_sources_phase2.json.

- AdingApkgg/YuanShenSFqun: 3 LRC files (majiko, LiSA, Goose house), 128 time-stamped spaced-slash bilingual candidates, all showing a Japanese-kana / Chinese-Han script contrast.
- we1005/music: 2 LRC files (EXO Baby Don't Cry and EXO-CBX Crush U), 98 spaced-slash bilingual candidates. Of these, 78 have the stricter Hangul / Chinese-Han contrast; other English lyric/Chinese-translation pairs are retained as candidates only.
- The EXO Baby Don't Cry file also includes one no-space slash composer credit on a different time-stamped line. This supplies an independently sourced mixed positive/negative **same-song** contract.
- Aggregate candidates: 226 across 5 songs, with 206 higher-confidence cross-script rows; **not** 226 independently manually verified semantic gold labels. The songs are grouped in 2 distinct repository sources.
- Public accessibility is not proof of a copyright licence to reproduce lyrics; only per-file URLs, Git blob IDs and aggregate counts are retained. No complete lyric file is vendored into Mica.
- Independent naturally occurring **negative controls**: a third public repository `sonikkk-526/lrc-lyrics-library` contains four spaced-slash credit rows across three songs, using traditional Chinese abbreviations `詞`, `編`, `監`. These prefixes are explicitly protected in Mica, with short negative examples in unit tests; source file SHA/path references are stored locally in `D:\\AI\\mica-slash-offline-20261022\\natural_slash_negative_sources_phase2.json`.
- Bare-slash TRUE bilingual and same-script bilingual remain data gaps. They should not be assumed to work after the bare-slash fallback removal.

## Still needed

- Verified provenance is now available for five natural spaced-slash bilingual **candidate** files; follow-up requires separately sourced bare-slash TRUE bilingual and same-script bilingual songs, with human labels per boundary.
- Additional complex multi-row ORIGINAL-only TTML render/geometry and visible UI consumer QA, beyond the current per-line contract tests.
- Functional app/device regression beyond Kotlin/unit tests; no production release is implied.

## Phase 3: scope of SPLIT versus genuine semantic tracks (2026-10-23)

- The settings UI has one composite switch: OFF versus ALL/ORIGINAL/TRANSLATION. OFF retains the old preference for later re-enable but its effective *global* mode is ALL while OFF.
- SPLIT governs only legacy visual separator inference on untimed, original-only LRC/PLAIN; it never merges or re-tags authentic TRANSLATION/READING tracks.
- LRC lines with real multiple-timestamp tokens do not enter slash text heuristics: no mid-cue role/style corruption.
- Independent status bar/desktop bilingual mode is not reset by that surface's visual split flag. The global NotificationLyrics options normalize only the global OFF state.
- The prior split-disabled structured merger was an old code/test contract; Letter and PhotoStack tests now preserve original/translation roles.
- Directed JVM regression: 7 test classes, 90/90 PASS (0 failed/0 error/0 skipped). One first-pass test run found two obsolete structured-merge assertions (87/89 PASS), corrected only after checking the new contract.
- This phase is a local fix candidate, not a new release. Android device checks need QA branch integration before final acceptance.

## Phase 4: Split and display-language independent (2026-10-23)

- Combined bilingual 0/1/2/3 UI is now separate automatic line-split boolean and language content ALL / ORIGINAL / TRANSLATION selection.
- SPLIT is a visual heuristic only on ordinary untimed LRC/PLAIN, never on trusted source roles or word timing. Language selection still works with SPLIT off when a real translation is supplied.
- For ordinary LRC without an authoritative translation, SPLIT on may provide left/right inferred visual pieces, without persisting or exporting a semantic translation; SPLIT off keeps the whole source line.
- All previous effectiveBilingualMode(splitEnabled,mode) call sites are removed. Independent desktop/status-bar mode preferences remain separate.
- A one-time shared-preference migration marker lyrics_split_language_decoupled_v1 maps legacy combined OFF plus any hidden old language mode to SPLIT-off + ALL, avoiding unexpected text disappearance. Existing active language selections are retained. After migration the two keys are independent.
- Settings search has two independently addressable items: lyrics.split and lyrics.bilingual.
- Pre-fix reproduction: four failures among five focused tests. After implementation: 8 relevant JVM classes, 93 tests all pass. The independent QA Android regression remains a separate acceptance step.

- Audit extension: small/single-line consumers (mini player, home stats/info row, notification MediaSession title) now pass their LyricsSession document into a provenance-aware display projection. Legacy text-only overload remains for genuinely document-less callers. This ensures independent language filtering works on every displayed surface, not just full lyrics pages.
