# Rain Glass Background

> Status: **static backdrop is the current implementation path** (2026-09-21).  
> Procedural city scenes are retained as research/prototype material only; they are not part of the active Android rendering path.

## Current implementation

Mica's active rain-glass path is now **fully Mica-owned**:

1. static backdrop texture: `app/src/main/res/drawable-nodpi/rain_glass_landscape.jpg`;
2. `MicaStaticDropsV2` — static micro-droplets;
3. `MicaMovingDropMaskV2` — moving-drop body / trailing bead mask;
4. `MicaTrailV5` — wet trail shape and decay;
5. `MicaRefractionNormalV2` — refraction normal estimation;
6. `MicaBlurLodV1` — wet-glass blur / mip LOD mapping;
7. `MicaWetGlassPostV1` — tint, startup fade and vignette.

The previous Heartfelt-era implementation is no longer part of the active Android shader. Its final integrated snapshot is retained under `.scratch/RainGlassBackground.heartfelt-final-20260921.kt`, together with per-stage migration snapshots, for history and visual regression reference only.

The failed all-at-once Mica Rain Glass V1 experiment is also retained as scratch/history. It diverged too far visually, which is why the replacement was redone component-by-component with a Shadertoy gate before every Android install.

### Stage 1 visual gate — static micro-droplets

- V1 was rejected: mean mask intensity was about 2.2× the baseline and high-intensity coverage was about 3–4× too large.
- V2 restored low/zero random-strength droplets and gradual lifecycle decay.
- Across four fixed lifecycle phases, mean intensity, visible-area coverage and connected-component size distributions were brought within a few percent of the baseline.
- Only then was `MicaStaticDropsV2` installed to Spectrum QA.

### Stage 2 visual gate — moving-drop mask

- The moving-drop candidate was compared side-by-side at four fixed phases, then rechecked after composition with the accepted Stage-1 static layer.
- `MicaMovingDropMaskV2` uses Mica-owned hashing, lifetime bell, vertical travel, lateral wobble, main-drop ellipse and trailing bead.
- Average mask intensity / visible coverage stayed within roughly 2% of the baseline, with closely aligned connected-component counts and size quantiles.
- The original trail remained authoritative during this stage; only the moving body mask changed.

### Stage 3 visual gate — wet trail

- Early versions were rejected for being too strong or too narrow.
- `MicaTrailV5` uses explicit forward-edge envelope math and a calibrated 0.93 trail gain.
- Across four fixed phases, pooled mean trail intensity was `1.0015×` the baseline; median width matched and trail-height distributions remained visually close.
- A two-layer integrated trail check was also completed before Android installation.

### Stage 4 visual gate — refraction normal

- A diagonal-gradient V1 was rejected in Shadertoy because the warped-scene comparison was visibly different (about 0.82 SSIM).
- `MicaRefractionNormalV2` uses an explicit two-axis forward-gradient helper over the already-migrated rain field.
- Across four fixed phases the warped-scene comparison averaged **0.999681 SSIM** with about **0.000034 mean absolute image difference**.
- Blur and post-processing were unchanged during this stage.

### Stage 5 visual gate — blur / focus

- `MicaBlurLodV1` rewrites the blur mapping as explicit clear-drop and wet-trail LOD terms.
- Across four fixed phases / rain levels, the rendered LOD field was pixel-equivalent at visible precision to the baseline: no pixel differed by more than 1/255.
- Post-processing stayed unchanged until this gate passed.

### Stage 6 visual gate — post-processing

- `MicaWetGlassPostV1` independently expresses the active tint oscillation, startup fade and vignette.
- Shadertoy comparisons at T=2 / 10 / 25 / 50 seconds were effectively pixel-identical to the baseline (SSIM ~1.0, only sub-quantization rounding).
- Rain geometry, refraction and blur were unchanged during this stage.

### Stage 7 cleanup gate — remove inactive legacy shader code

After all active visual components were Mica-owned, the remaining inactive Heartfelt-era helpers / disabled branches were removed from `RainGlassBackground.kt`.

A full synthetic wet-glass composite was rendered in Shadertoy before and after cleanup at four fixed phases:

- MAE: **0.0**
- maximum pixel difference: **0.0**
- non-zero pixels: **0**
- SSIM: **1.0**

Source verification after cleanup confirms the active Android shader no longer contains `Heartfelt`, `DropLayer2`, `N13`, `N14`, `N`, `Saw`, `HAS_HEART`, `HAS_LIGHTNING`, `CHEAP_NORMALS`, or the old mouse-control compatibility path.

## Procedural-city experiment — 2026-09-19 to 2026-09-21

The experiment rendered a city scene into a low-resolution FBO (Android prototype: **0.35x scene scale**) and fed that mipmapped texture into the full-resolution Heartfelt rain pass.

### Visual findings

- The early hand-written city/light/reflection prototype was rejected: moving lights/reflections read as floating silhouettes rather than a convincing city.
- `wdfGW4` / **Descent 3D** produced the strongest city-light structure of the tested candidates and was integrated into the Android prototype.
- The original `wdfGW4` camera was too top-down for a vertical rain-covered window. A near-level camera experiment reduced the downward angle to about 13 degrees and improved perspective consistency, but did not solve the performance budget.
- `XsBSRG` / **Morning city** was materially lighter, but after compositing behind the same Heartfelt rain layer its visual language still did not fit the intended rain-window background well enough.
- `XtsSWs`, `MdXGW2`, and especially `lstGzS` did not provide enough benefit to justify their higher cost.

### Desktop proxy benchmark

Measured in the same Edge/Shadertoy session at a **630x354 render canvas**. These numbers are a relative proxy for shader cost, not an Android/Adreno performance guarantee.

| Candidate | Shadertoy title | Stable measured FPS | Relative scene cost vs XsBSRG | Result |
|---|---|---:|---:|---|
| `XsBSRG` | Morning city | ~106.3 | 1.00x | Lightest, visual fit rejected |
| `wdfGW4` | Descent 3D | ~89.0 | ~1.19x | Best of city attempts visually, too costly for the target path |
| `MdXGW2` | Venice | ~60.1 | ~1.77x | Too costly |
| `XtsSWs` | Skyline | ~54.4 | ~1.95x | Too costly |
| `lstGzS` | Bay Bridge | ~14.6 | ~7.3x | Not viable |

Top-two repeat measurements:

- `XsBSRG`: 105.15 / 107.43 / 106.22 FPS, mean 106.27.
- `wdfGW4`: 89.16 / 90.20 / 87.65 FPS, mean 89.00.

### wdfGW4 tuning that was explored

The prototype also tested:

- fixed window viewpoint;
- near-level camera instead of the original strong top-down view;
- higher building/window emission;
- slower moving traffic;
- restored lower moving-headlight intensity;
- stronger neon/sign emission.

These changes were useful for visual exploration but did not remove the structural cost of running the city pass underneath Heartfelt.

## Decision

**Shelve the real-time procedural city path and return the shipping/active implementation to a static background image.**

Reasons:

- The wet-glass foreground remains the important visual layer, so its sustained fragment cost must stay bounded on phone GPUs.
- The city pass adds continuous fragment work even when its details are heavily blurred by the glass.
- The most visually promising candidate (`wdfGW4`) was already too expensive in practice.
- The lightest candidate (`XsBSRG`) did not meet the desired visual direction after compositing.
- A static mipmapped image preserves the desired wet-glass/refraction appearance while removing the extra procedural scene pass.

The Shadertoy working files and experiment helpers under `.scratch/shadertoy-rain-glass/` are retained for future research. Do not treat them as the production rendering authority.

## Revisit criteria

Only reopen the procedural-background path if at least one of these changes materially alters the budget:

- the foreground rain pass becomes substantially cheaper;
- the scene can be rendered at a much lower cadence/resolution without visible degradation;
- a pre-rendered/looped background achieves the same visual goal at lower sustained GPU cost;
- target-device profiling shows enough GPU headroom for a second animated pass.
