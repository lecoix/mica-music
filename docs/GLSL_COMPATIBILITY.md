# GLSL Compatibility Guide

> Status: **project-wide compatibility contract**
> Scope: Android OpenGL ES / GLSL shaders used by Mica UI and visual effects
> Last updated: 2026-09-26

This document records GLSL compatibility rules learned from real-device failures, especially strict ARM Mali drivers on HyperOS 3 / Android 16. These rules apply to all current and future Mica shader code, not only Particle Cover or Rain Glass.

The goal is simple: **do not rely on permissive GPU drivers accepting technically invalid, undefined, or cross-stage-inconsistent GLSL.**

---

## 1. Why this document exists

Mica has had two separate HyperOS 3 / Mali failures where the rendering surface and EGL stack were healthy, but GLSL that passed elsewhere was rejected by the stricter driver.

### Incident A — Particle Cover program link failure

Affected device:

- Xiaomi 2407FRK8EC
- Android 16 / SDK 36
- ARM Mali-G720-Immortalis MC12
- OpenGL ES 3.2

Failure stage:

```text
surface -> EGL -> shader compile -> program link FAILED
```

Driver diagnosis:

```text
The fragment floating-point variable uSizeVariance does not match the vertex variable uSizeVariance.
The precision does not match.

The fragment floating-point variable uLyrics does not match the vertex variable uLyrics.
The precision does not match.
```

Root cause:

The same floating-point uniforms were visible in both shader stages but did not have identical effective precision.

Fix:

```glsl
uniform mediump float uLyrics;
uniform mediump float uSizeVariance;
```

The declarations were made explicit and identical in both stages.

Reference commit:

```text
ebef6ab0 Fix particle cover shader precision on Mali
```

### Incident B — Rain Glass shader compile failure

Affected device:

- Xiaomi 2407FRK8EC
- Android 16 / SDK 36
- HyperOS 3
- ARM Mali family

Failure stage:

```text
surface -> EGL -> vertex shader compile FAILED
```

Driver diagnosis:

```text
P0005: #version must be on the first line in a program and only whitespace are allowed in the declaration
```

The Kotlin raw string was written as:

```kotlin
internal const val RainGlassVertexShader = """
#version 300 es
...
"""
```

That creates a leading newline, so the actual GLSL source begins with an empty line and `#version` is on line 2.

Fix:

```kotlin
internal const val RainGlassVertexShader = """#version 300 es
...
"""
```

The same fix is required for every shader stage containing a `#version` directive.

During the same audit, another portability hazard was found:

```glsl
smoothstep(1.0, y, st.y)
```

With `y < 1.0`, this uses reversed edges. GLSL does not define the result when `edge0 >= edge1`.

It was replaced with the explicit equivalent:

```glsl
1.0 - smoothstep(y, 1.0, st.y)
```

---

## 2. Compatibility rules

These are project rules, not suggestions.

### 2.1 `#version` must be the first shader source token

For GLSL ES 3.x shaders:

```glsl
#version 300 es
precision highp float;
```

The source string must literally begin with `#version`.

Good:

```kotlin
const val Shader = """#version 300 es
precision highp float;
...
"""
```

Avoid:

```kotlin
const val Shader = """
#version 300 es
...
"""
```

Also avoid adding comments, BOM characters, generated prefixes, or runtime text before `#version`.

If shader source is assembled dynamically, preserve `#version` as the first directive and append generated definitions after it.

### 2.2 Cross-stage declarations must match explicitly

For any variable intentionally shared between vertex and fragment stages, keep the following identical:

- type;
- precision;
- array dimensions;
- interpolation qualifier where applicable;
- interface direction and compatible naming / location strategy.

Do not depend on a stage's default precision when the same floating-point value exists in multiple stages.

Prefer:

```glsl
// vertex
uniform mediump float uAmount;

// fragment
uniform mediump float uAmount;
```

over:

```glsl
// vertex
uniform float uAmount;

// fragment
uniform mediump float uAmount;
```

Even if a permissive driver links the second form, Mica treats it as a compatibility defect.

### 2.3 Do not rely on undefined GLSL behavior

Avoid constructs whose result is explicitly undefined or implementation-dependent when an equivalent defined form exists.

Known Mica example:

```glsl
smoothstep(highEdge, lowEdge, x)
```

when `highEdge > lowEdge`.

Use:

```glsl
1.0 - smoothstep(lowEdge, highEdge, x)
```

Other review targets include:

- division by values that may reach zero;
- `sqrt(x)` where `x` may become negative through floating-point error;
- `normalize(vec)` where the vector may be zero;
- out-of-range indexing;
- undefined derivatives in non-uniform control flow;
- assumptions about NaN / infinity propagation;
- texture LOD use that is not valid for the declared GLSL ES version.

Where the visual intent needs reversed or unusual behavior, write the math explicitly instead of depending on undefined intrinsic semantics.

### 2.4 Match GLSL features to the requested GLES context

Before using a GLSL feature, verify both sides:

1. EGL requests a context that supports it.
2. The shader declares the matching GLSL ES version.

Rain Glass currently requests an OpenGL ES 3 context and uses:

```glsl
#version 300 es
textureLod(...)
```

That pairing is intentional.

Do not silently copy ES 3.x shader syntax into an ES 2.0 renderer, or vice versa.

### 2.5 Treat `-1` locations as evidence, not normal success

`glGetUniformLocation()` and `glGetAttribLocation()` may return `-1` because:

- the declaration does not exist;
- the compiler optimized it away;
- the program did not expose the expected interface.

OpenGL commonly ignores `glUniform*` calls to location `-1`, so stale renderer code can appear to work while no longer matching the shader.

For actively required inputs:

- validate their locations after program link;
- log unexpected `-1` values in detailed rendering diagnostics;
- remove obsolete CPU-side state instead of relying on silent ignore behavior.

Optional / optimized-away values should be documented as such.

### 2.6 Check GL errors at phase boundaries, not every frame

Per-frame `glGetError()` polling is unnecessarily expensive and noisy.

Prefer checks at meaningful boundaries:

- after context initialization;
- after shader/program setup;
- after texture creation/upload/mipmap generation;
- after FBO setup;
- after the first draw;
- after operations already suspected to be failing.

This gives useful evidence without turning diagnostics into a rendering workload.

---

## 3. Required diagnostic chain

For any native GL visual effect that can fail as "blank / black / invisible", diagnostics should make the following stages distinguishable:

```text
View/Surface
  -> EGL display
  -> EGL initialize
  -> EGL config
  -> EGL context
  -> EGL window surface
  -> eglMakeCurrent
  -> GL vendor / renderer / version / GLSL version
  -> vertex shader compile
  -> fragment shader compile
  -> program link
  -> interface locations
  -> texture upload / mipmap
  -> first render
  -> first draw
  -> first swap
```

The diagnostic goal is to answer **where the pipeline stopped**, not merely "GL failed".

### Detailed diagnostic events

Successful breadcrumbs belong under:

```text
Detailed diagnostics -> UI & rendering
```

Examples:

```text
surface-available
egl-ready
shader-compiled
program-linked
gl-ready
texture-ready
first-render
first-draw
first-swap
```

### Failure events

Failures must remain available even when detailed diagnostics are off.

Examples:

```text
egl-*-failed
shader-compile-failed
program-link-failed
texture-upload-failed
gl-error
egl-swap-failed
renderer-stopped
```

Shader compiler and linker info logs are part of the failure evidence and must not be discarded.

---

## 4. What local compilation can and cannot prove

Android/Kotlin compilation proves that the host-side renderer code builds.

It does **not** prove that a device GPU driver accepts the GLSL.

A shader string embedded in Kotlin may compile through Gradle while still failing immediately at runtime on a strict GPU driver.

Therefore:

- JVM tests should protect source-level invariants that can be checked statically;
- device execution remains required for actual shader compile/link compatibility;
- a permissive desktop browser, emulator, Adreno device, or older MIUI device is not sufficient evidence for Mali compatibility.

For known compatibility-sensitive effects, a strict-driver device is part of the verification matrix.

---

## 5. Test classification

### Behavior Contract

These must remain stable across renderer refactors:

- selecting a visual theme must never corrupt user data;
- renderer failure must not block playback or persistence;
- diagnostics must not expose private media contents beyond existing diagnostic policy;
- rendering failure must remain recoverable by leaving/re-entering the view or falling back appropriately.

### Architecture Contract

These require an explicit architecture decision to change:

- the GL render thread owns EGL/GL lifecycle and GL objects;
- Compose/View code publishes scene/configuration state but does not become a second GL authority;
- shader diagnostics flow through the shared diagnostic policy;
- verbose success breadcrumbs are owned by the UI/rendering detailed-diagnostics domain;
- high-value failure evidence remains available independently of the verbose gate.

### Implementation Verification

These may evolve with the renderer:

- exact shader helpers and math;
- EGL config details;
- texture formats and mipmap strategy;
- program/uniform names;
- first-frame instrumentation;
- frame cadence;
- GPU-specific regression tests.

---

## 6. Review checklist for new or modified shaders

Before merging a shader change, check:

- [ ] If `#version` is present, it is literally the first source directive / first non-BOM content.
- [ ] Vertex and fragment interface types match.
- [ ] Shared floating-point declarations use explicit, identical precision where compatibility could depend on it.
- [ ] No reversed-edge `smoothstep`.
- [ ] No possible negative `sqrt` without clamping/proof.
- [ ] No possible divide-by-zero without clamping/proof.
- [ ] No zero-vector `normalize` without a guard/proof.
- [ ] Texture functions are legal for the declared GLSL ES version.
- [ ] EGL client version matches shader requirements.
- [ ] Required uniforms/attributes are present and their locations are checked.
- [ ] Shader compile failures include the full useful info log.
- [ ] Program link failures include the full useful info log.
- [ ] Texture upload/mipmap errors are distinguishable from shader errors.
- [ ] First draw and first swap can be observed in detailed diagnostics.
- [ ] The change has been run on at least one real device.
- [ ] Compatibility-sensitive changes are checked on a strict-driver device when available.

---

## 7. Source-level regression tests

Where a compatibility rule is easy to violate accidentally, add a cheap source-level test.

Rain Glass currently protects:

1. both shader strings start with `#version 300 es\n`;
2. the known reversed `smoothstep(1.0, y, st.y)` form does not return.

These tests do not replace real GPU execution. They prevent known mistakes from re-entering the source tree.

Similar tests are appropriate for:

- generated shader prefixes;
- required explicit precision declarations;
- required version directives;
- banned undefined constructs that have previously caused device-specific failures.

---

## 8. Triage order for "effect is blank on device X"

Do not begin by assuming a ROM permission issue.

Use this order:

1. Confirm the Surface / TextureView was created.
2. Confirm EGL display/config/context/surface/makeCurrent.
3. Read GL vendor, renderer, GL version and GLSL version.
4. Check vertex compile log.
5. Check fragment compile log.
6. Check program link log.
7. Check required interface locations.
8. Check texture upload and mipmap errors.
9. Check first draw GL errors.
10. Check first successful swap.
11. Only after the GL pipeline is proven healthy investigate composition, opacity, z-order, lifecycle, motion settings, or ROM-specific window behavior.

This ordering prevented both the Particle Cover and Rain Glass incidents from turning into speculative ROM workarounds once adequate diagnostics were present.

---

## 9. Known-device lesson

The Xiaomi 2407FRK8EC / Android 16 / Mali-G720-class path has repeatedly exposed GLSL assumptions that other devices tolerated.

Mica should treat this as useful compatibility coverage, not as justification for device-specific shader forks.

Preferred response:

```text
strict driver exposes invalid/undefined GLSL
    -> make GLSL specification-compliant
    -> keep one renderer path
```

Avoid:

```text
strict driver fails
    -> add Xiaomi/HyperOS conditional shader
```

unless a confirmed driver defect remains after the shader itself is specification-compliant.

---

## 10. Related documents

- `docs/PARTICLE_COVER_OPENGL_MIGRATION.md`
- `docs/RAIN_GLASS_BACKGROUND.md`
- `docs/MOTION.md`
- `docs/TESTING.md`
