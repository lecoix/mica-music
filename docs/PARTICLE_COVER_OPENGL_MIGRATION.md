# Particle Cover OpenGL Migration

> Status: planning document
> Last updated: 2026-06-22
> Scope: migrate the current particle cover effect from `WebView + Three.js` to native `TextureView + GLES20` while preserving the shipped visual language.

---

## 1. Goal

This document turns the current discussion into a concrete migration plan.

The target is **not** "make a new particle effect in OpenGL".

The target is:

1. Keep the current particle-cover visual identity.
2. Remove the `WebView`, JS bridge, and base64 texture transfer path.
3. Reuse the existing native GLES renderer scaffold already present in the repo.
4. Preserve current tuning semantics so preview, settings, and the player page continue to speak the same language.

---

## 2. Visual Contract

The migration must preserve the following user-visible behaviors.

### 2.1 Stable state

1. The cover is still readable as a real album cover, not a generic particle field.
2. The cover edge looks like material is gradually eroding away.
3. Edge particles feel attached to the cover material, not like decorative dust floating around it.
4. Stable-state motion stays subtle and low-amplitude.

### 2.2 Transition state

1. Track switch is a staged decomposition, not a plain crossfade.
2. The old cover breaks apart first.
3. Transition particles carry old/new cover color from sampled artwork texels.
4. The new cover gathers and becomes whole again by the end of the 900 ms transition.

### 2.3 Tuning contract

The meaning of these fields must remain stable:

- `erosionScale`
- `featherScale`
- `edgeParticleDensity`
- `edgeParticleAlpha`
- `edgeTravelScale`
- `transitionParticleDensity`

Source of truth today:

- [`app/src/main/java/com/mica/music/data/ParticleCoverTuning.kt`](../app/src/main/java/com/mica/music/data/ParticleCoverTuning.kt)

---

## 3. Current State

### 3.1 Current shipped path

The player page currently renders the particle cover through:

- [`app/src/main/java/com/mica/music/ui/screens/NowPlayingCoverSection.kt`](../app/src/main/java/com/mica/music/ui/screens/NowPlayingCoverSection.kt)
- [`app/src/main/java/com/mica/music/ui/screens/player/view/ThreeParticleCoverHost.kt`](../app/src/main/java/com/mica/music/ui/screens/player/view/ThreeParticleCoverHost.kt)
- [`app/src/main/assets/particle_cover/index.html`](../app/src/main/assets/particle_cover/index.html)
- [`app/src/main/assets/particle_cover/mica-particle-cover.js`](../app/src/main/assets/particle_cover/mica-particle-cover.js)

Important properties of the current shipped path:

1. Host is `AndroidView -> WebView`.
2. Cover art is converted into a data URL and passed into JS.
3. Rendering is continuously driven by `requestAnimationFrame`.
4. The effect logic is implemented in JS/Three.js and shader code embedded in the bundled asset.

### 3.2 Existing native scaffold

The repo already contains a native GLES implementation scaffold:

- [`app/src/main/java/com/mica/music/ui/screens/player/view/ParticleCoverView.kt`](../app/src/main/java/com/mica/music/ui/screens/player/view/ParticleCoverView.kt)
- [`app/src/main/java/com/mica/music/ui/screens/player/view/ParticleCoverRenderer.kt`](../app/src/main/java/com/mica/music/ui/screens/player/view/ParticleCoverRenderer.kt)

Important properties of the native scaffold:

1. Dedicated EGL render thread already exists.
2. Native texture upload path already exists.
3. Stable-state edge particles already exist.
4. Transition particle set already exists.
5. Quad erosion shader already exists.
6. Timed multi-stage transition already exists.

This means the migration is primarily a **visual parity and integration** task, not a greenfield renderer task.

---

## 4. Non-Goals

The first migration pass should **not** try to do the following:

1. Redesign the effect.
2. Introduce a new tuning model.
3. Add music-reactive behavior.
4. Replace GLES20 with Vulkan, Filament, or Compose Canvas.
5. Do aggressive performance optimization before parity is reached.

If we optimize too early, we risk shipping something cheaper but visibly worse.

---

## 5. Architecture Delta

## 5.1 What we are removing

Remove the following runtime responsibilities from the shipped path:

1. `WebView`
2. JS bridge via `evaluateJavascript`
3. `Bitmap -> JPEG/base64` texture transfer
4. Web asset bootstrapping and resize handshakes

## 5.2 What we are keeping

Keep the following product and renderer semantics:

1. Same player-page integration point and sizing rules.
2. Same `ParticleCoverTuning` fields.
3. Same transition duration and staged timing.
4. Same texture-sampled color behavior.
5. Same stable-state vs transition-state separation.

## 5.3 What we are translating

Translate these concepts from JS/Three.js into native GLES:

1. Plane erosion semantics.
2. Stable edge-particle generation distribution.
3. Transition particle-field generation distribution.
4. Transition timing curves.
5. Per-frame uniform-driven motion.

---

## 6. File-by-File Migration Plan

This section is the actual construction map.

## 6.1 [`NowPlayingCoverSection.kt`](../app/src/main/java/com/mica/music/ui/screens/NowPlayingCoverSection.kt)

### Current role

Chooses whether particle cover mode is active and mounts `ThreeParticleCoverHost`.

### Target role

Mount the native particle host instead of the `WebView` host once parity is proven.

### Required changes

1. Introduce a temporary switch between `ThreeParticleCoverHost` and native particle host.
2. Keep sizing, halo, clipping, and `onMotionActiveChanged` behavior unchanged.
3. Keep `coverDecodeTarget` logic unchanged unless parity work proves it wrong.

### Migration note

Do **not** switch this file to native first.

First add a gated A/B host switch so parity can be checked safely.

---

## 6.2 [`ThreeParticleCoverHost.kt`](../app/src/main/java/com/mica/music/ui/screens/player/view/ThreeParticleCoverHost.kt)

### Current role

Current shipped implementation. Also serves as the visual-reference host.

### Target role

Temporary reference implementation during migration.

### Required changes

1. No product behavior changes required in the first migration pass.
2. Optionally keep it available behind a debug toggle for visual comparison.

### Migration note

Do not delete this too early.

This file is the easiest side-by-side parity oracle during development.

---

## 6.3 [`ParticleCoverView.kt`](../app/src/main/java/com/mica/music/ui/screens/player/view/ParticleCoverView.kt)

### Current role

Native `TextureView` host plus EGL render thread lifecycle.

### Target role

The shipped native host for particle cover.

### Required changes

1. Add any parity-oriented debug state output needed during migration.
2. Keep current render-thread ownership model.
3. Keep idle/animating frame pacing split.
4. Ensure cover, tuning, and resize updates stay coalesced and thread-safe.

### Migration note

This file should remain mostly stable.

Most migration work belongs in the renderer rather than the host lifecycle.

---

## 6.4 [`ParticleCoverRenderer.kt`](../app/src/main/java/com/mica/music/ui/screens/player/view/ParticleCoverRenderer.kt)

### Current role

Native GLES renderer with:

1. Quad plane rendering
2. Residue layer
3. Stable edge particles
4. Transition particles
5. Time-based transition staging

### Target role

The native renderer that reproduces the current shipped WebView look closely enough for replacement.

### Required changes by sub-area

#### A. Transition timing parity

Align native stages with shipped JS stages:

1. `0-150 ms`: edge boost
2. `150-450 ms`: breakup / scatter
3. `450-750 ms`: gather
4. `750-900 ms`: reveal / settle

Concrete task:

Map current JS `alpha`, `travel`, `mix`, `breakup`, and `scale` curves onto native uniforms and stage logic.

#### B. Quad erosion parity

Current native quad shader already has:

1. `uErosion`
2. `uNoise`
3. `uFeather`
4. `uResidue`

But the shipped JS path also encodes explicit breakup semantics.

Concrete task:

Add or refine uniforms so native quad rendering can express:

1. Stable edge-only erosion
2. Full breakup during transition
3. Residual speckle/material film
4. Distinct stable-state vs transition-state edge treatment

Expected result:

The viewer should read the effect as the cover boundary being eaten away, not just alpha-masked out.

#### C. Stable edge-particle parity

Current native edge particles already encode:

1. edge side
2. inward/outward depth
3. tangent jitter
4. detach weight
5. size variation

Concrete task:

Compare and align the following against the shipped JS path:

1. edge band thickness
2. distribution falloff toward the interior
3. outward scatter amplitude
4. tangential shear
5. particle size distribution
6. count budget at default tuning
7. count budget at max tuning

Expected result:

Stable particles should feel like a live edge halo made from cover material, not a generic border effect.

#### D. Transition particle-field parity

Current native transition field already encodes:

1. grid-sampled full-cover particles
2. edge bias
3. horizontal vs radial scatter branches
4. random depth and size

Concrete task:

Align:

1. grid density
2. edge bias exponent
3. horizontal branch ratio
4. radial spread range
5. depth spread range
6. size range and scaling
7. detach distribution

Expected result:

The whole cover should appear to decompose and reassemble, but with stronger material continuity near the edges.

#### E. Particle shading parity

Current native particle fragment shader already:

1. samples cover color by UV
2. renders round points
3. uses core + glow composition
4. modulates sparkle with seed

Concrete task:

Refine:

1. stable-state brightness
2. detach-dependent glow
3. transition alpha envelope
4. near-edge chunkiness vs far-particle fineness

Expected result:

Particles should look like fragments of the cover, not abstract lights.

#### F. Uniform-driven motion

Concrete task:

Keep all continuous motion driven through uniforms such as:

1. `uTime`
2. `uTravel`
3. `uAlpha`
4. `uWobble`
5. `uScale`

Do not rebuild particle geometry every frame.

Expected result:

Parity with the shipped "shader-like" motion model and lower CPU cost.

---

## 6.5 [`ParticleCoverPreviewScreen.kt`](../app/src/main/java/com/mica/music/ui/screens/ParticleCoverPreviewScreen.kt)

### Current role

Preview UI for tuning particle cover parameters.

### Target role

Primary parity-validation tool during migration.

### Required changes

1. Add an implementation toggle:
   - `WebView`
   - `Native GL`
2. Reuse the same preview song and same `ParticleCoverTuning` values for both.
3. Keep the current sliders unchanged.

### Migration note

This is the most practical way to keep the migration honest.

Without side-by-side preview, visual drift is too easy.

---

## 6.6 [`ParticleCoverTuning.kt`](../app/src/main/java/com/mica/music/data/ParticleCoverTuning.kt)

### Current role

Business-level tuning model shared by settings and rendering code.

### Target role

Stable tuning interface across old and new implementations.

### Required changes

None in the first pass unless parity work proves a missing semantic.

### Migration note

Do not rename fields during migration.

Changing names here would create avoidable churn across settings, preview, persistence, and renderer logic.

---

## 6.7 [`app/src/main/assets/particle_cover/mica-particle-cover.js`](../app/src/main/assets/particle_cover/mica-particle-cover.js)

### Current role

Current visual truth source for the shipped implementation.

### Target role

Reference implementation during migration, not the long-term shipped path.

### Required changes

No product changes required for the migration itself.

### Migration note

Use this file as the parity spec for:

1. timing constants
2. count scaling
3. distribution ranges
4. breakup semantics
5. stable-state motion envelope

Do not treat it as throwaway until native parity is accepted.

---

## 7. Detailed Concept Mapping

This section translates shipped JS concepts into native renderer responsibilities.

| Shipped concept | Native target | Where it should live |
|---|---|---|
| `setCover(payload)` | `setCover(songId, bitmap, fallbackColor, motionEnabled)` | `ParticleCoverRenderer.setCover()` |
| `setTuning(payload)` | `setTuning(ParticleCoverTuning)` | `ParticleCoverRenderer.setTuning()` |
| `resize()` | `onSurfaceChanged(width, height)` | `ParticleCoverRenderer.onSurfaceChanged()` |
| plane erosion | quad shader uniforms | `QuadFragmentShader` |
| residue film | second quad pass | `drawQuad(... residue = true)` |
| stable edge field | edge particle set | `buildEdgeParticles()` |
| transition field | transition particle set | `buildTransitionParticles()` |
| transition phase curves | native stage logic | `render()` |
| per-frame wobble | uniforms only | `drawParticles()` |
| texture sampled color | UV-sampled particle fragment | `ParticleFragmentShader` |

---

## 8. Recommended Implementation Phases

This is the safest order.

## Phase 0: Add parity tooling

Goal:

Make comparison easy before changing shipped behavior.

Tasks:

1. Add host toggle to preview.
2. Add optional host toggle to player page for local verification.
3. Add minimal native debug state if needed.

Exit criteria:

We can compare WebView and native on the same song and the same tuning values.

## Phase 1: Timing parity

Goal:

Make native transition feel structurally the same.

Tasks:

1. Align phase boundaries.
2. Align scale curves.
3. Align alpha envelopes.
4. Align stable vs transition travel behavior.

Exit criteria:

Even before perfect visual parity, both implementations should "read" as the same staged transition.

## Phase 2: Quad erosion parity

Goal:

Recover the real decomposition feeling.

Tasks:

1. Add any missing breakup-style uniform semantics.
2. Tune stable-state edge treatment.
3. Tune transition-state full breakup.
4. Tune residue speckle/film behavior.

Exit criteria:

The effect no longer looks like "cover fade + particles". It looks like the cover itself is eroding.

## Phase 3: Stable edge-particle parity

Goal:

Match the shipped resting look.

Tasks:

1. Align band thickness.
2. Align density feel.
3. Align detach distribution.
4. Align size and glow feel.

Exit criteria:

Default-tuned stable state looks recognizably like the shipped effect.

## Phase 4: Transition particle-field parity

Goal:

Match the shipped switch drama.

Tasks:

1. Align transition density.
2. Align scatter geometry.
3. Align gather feel.
4. Align near-edge chunkiness and far-particle fineness.

Exit criteria:

Track switch looks materially the same, not just directionally similar.

## Phase 5: Performance shaping

Goal:

Reduce cost without changing the look.

Tasks:

1. Tune idle frame pacing.
2. Tune particle counts conservatively.
3. Prefer preserving plane erosion over preserving excessive point count.
4. Measure stable-state and transition-state behavior separately.

Exit criteria:

Native path is clearly cheaper than WebView path while preserving accepted parity.

## Phase 6: Flip shipped implementation

Goal:

Move product path to native GL.

Tasks:

1. Switch main player page to native host.
2. Keep WebView path behind debug-only switch temporarily.
3. Remove old path only after enough confidence.

Exit criteria:

The native path is the shipped path and the WebView path is no longer needed for safety.

---

## 9. Verification Plan

This migration must be verified visually first, then behaviorally, then performance-wise.

## 9.1 Visual verification

At minimum, verify all of these in preview and on the real player page:

1. Stable state with square cover art.
2. Stable state with fallback-color-only cover.
3. Track switch with motion enabled.
4. Track switch with motion disabled.
5. Repeated rapid track switching.
6. Default tuning.
7. Max tuning.
8. Min tuning.

Visual questions to answer:

1. Does the edge read as erosion or decoration?
2. Are particles clearly sampled from artwork color?
3. Does the old cover visibly decompose before the new cover settles?
4. Is the stable state subtle enough to feel premium rather than noisy?

## 9.2 Behavioral verification

1. `onMotionActiveChanged` timing remains correct.
2. Cover size and halo sizing remain correct in the player page.
3. No player-page clipping regressions in particle mode.
4. No incorrect reuse of previous cover texture after song switches.
5. No crash on missing artwork.

## 9.3 Performance verification

Verify at least these buckets separately:

1. Stable playback with no track switch.
2. Single track switch.
3. Repeated switching.
4. Default tuning.
5. Max tuning.

Measure:

1. stable-state smoothness
2. transition smoothness
3. CPU wakeup pattern
4. obvious memory churn
5. device heat tendency

---

## 10. Risks and Mitigations

## Risk 1: Native version becomes cheaper but loses the look

Mitigation:

Do parity preview first and do not optimize early.

## Risk 2: Too much logic gets pushed into Kotlin instead of shaders

Mitigation:

Keep particle motion uniform-driven and geometry stable.

## Risk 3: Stable edge particles look decorative rather than material

Mitigation:

Treat quad erosion as first-class and tune particle distribution against it.

## Risk 4: Product semantics drift because tuning fields get repurposed

Mitigation:

Keep `ParticleCoverTuning` stable and change only internal mapping.

## Risk 5: We remove the WebView path before we have a trustworthy parity reference

Mitigation:

Keep WebView available behind a debug toggle until migration acceptance is complete.

---

## 11. Recommended Acceptance Standard

The migration should be considered successful only if all of the following are true:

1. Visual parity is good enough that the effect is obviously the same design language.
2. Stable state still feels like eroding cover material, not a generic particle filter.
3. Track switch still reads as decomposition and reassembly.
4. Tuning sliders preserve their meaning.
5. Native implementation is operationally simpler than the WebView path.
6. Native implementation shows a clear performance or cost advantage in practical use.

---

## 12. Suggested Follow-Up Documents

If implementation starts, the following focused docs may be worth adding later:

1. `PARTICLE_COVER_PARITY_CHECKLIST.md`
2. `PARTICLE_COVER_PERF_NOTES.md`
3. small inline renderer notes near shader code once the final mapping stabilizes

---

## 13. Implementation Summary

In one sentence:

Ship the existing native GLES scaffold as the new particle-cover runtime, but only after translating the shipped WebView effect's timing curves, erosion semantics, particle distributions, and tuning meanings closely enough that the visual language survives intact.
