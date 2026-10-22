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
- `git diff --check`: clean. This branch is an isolated fix candidate, not yet merged/pushed, and UI/device integration acceptance has not been completed.

## Still needed

- Real source-owned slash bilingual files, including no-space slash and same-script pairs, tagged by provenance and timestamp.
- A multi-row original-only structured TTML coverage pass and display consumer QA.
- Functional app/device regression beyond Kotlin/unit tests; no production release is implied.
