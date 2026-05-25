# Open Source Notices

Mica Music uses the following major open source components. This file is a
release checklist seed; before a public release, include the full license text
and copyright notice required by each dependency.

## Runtime Dependencies

| Component | License |
|---|---|
| AndroidX Core / Activity / Lifecycle / Navigation / Room / DocumentFile / Palette | Apache License 2.0 |
| Jetpack Compose UI / Material 3 / Material Icons | Apache License 2.0 |
| AndroidX Media3 | Apache License 2.0 |
| Kotlin / Kotlinx Coroutines | Apache License 2.0 |
| Coil | Apache License 2.0 |
| Calvin Reorderable | Apache License 2.0 |
| FFmpeg | LGPL 2.1+ by default; current build script does not enable GPL or nonfree components |

## Release Notes

- Apache 2.0 dependencies require preserving copyright notices and a copy of
  the Apache License 2.0 text.
- FFmpeg binary distribution requires preserving FFmpeg / LGPL notices and
  providing a way to obtain the corresponding source or build scripts.
- If FFmpeg build flags change to include GPL or nonfree components, update this
  notice and the app distribution terms before release.
