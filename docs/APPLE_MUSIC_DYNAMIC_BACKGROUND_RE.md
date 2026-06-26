# Apple Music Dynamic Background Reverse Engineering

> Status: reverse-engineering notes and implementation plan  
> Last updated: 2026-06-27  
> Source APK: `AppleMusic-com.apple.android.music-4.9.3.apk`  
> Local analysis workspace: `.scratch/apple-music-apk/`

---

## 1. Purpose

This document records what was found while statically inspecting the Apple Music
Android APK's Now Playing dynamic background implementation, then translates the
findings into a Mica implementation plan.

The goal is not to copy proprietary source code. The goal is to understand the
rendering model, the important parameters, and the engineering constraints well
enough to build an original Mica implementation with a similar visual language:
slow artwork-derived motion, heavy blur, soft color wash, and track-switch
crossfade.

---

## 2. Honesty And Confidence

### 2.1 What is confirmed

The following points are directly visible in the dexdump/resource dump:

1. The dynamic background is implemented by a custom Android `View`:
   `com.apple.android.music.player.LyricsBackgroundLayerView`.
2. The custom view is placed as a full-screen layer in the main player layout.
3. The background is generated from the album artwork bitmap, not from a video,
   Lottie file, or remote animated image.
4. The core visual recipe is:
   multiple transformed artwork copies, high saturation, black/white scrims,
   blur radius 25, optional 5x5 bitmap mesh warp, then `BitmapShader` drawing.
5. There are four `ValueAnimator`s:
   one 1000 ms transition animator and three infinite slow rotation animators.
6. The slow rotation periods are 120 s, 90 s, and 70 s.
7. The view posts redraws every 42 ms while it is animating.
8. The APK contains `librenderscript-toolkit.so`, and the blur path calls
   `com.google.android.renderscript.Toolkit`.
9. The settings string "Reduce Now Playing Motion" exists and is wired to
   `LyricsBackgroundLayerView.setReducedEffects(boolean)`.

### 2.2 What is not fully guaranteed

1. The inspected APK contains `lib/arm64-v8a/libfrida-gadget.so` and
   `libfrida-gadget.config.so`. That strongly suggests this APK may have been
   modified or repacked. The UI implementation evidence is still useful, but it
   should not be treated as cryptographic proof of the official Play Store APK.
2. The analysis used `aapt`, `aapt2`, `dexdump`, and archive inspection. `jadx`
   and `apktool` were not available in this environment, so class names and
   method shapes were read from low-level DEX output rather than decompiled Java.
3. Some branch semantics are inferred from bytecode control flow. Where a value
   is directly visible, this document says "confirmed"; where visual intent is
   inferred, this document says "likely" or "recommended".
4. Exact mesh vertex arrays exist in the APK, but we should not copy them. Mica
   should generate its own normalized mesh presets or procedural warps.

---

## 3. Evidence Map

Primary analysis files:

| Evidence | Local file |
|---|---|
| Main player layout tree | `.scratch/apple-music-apk/layout-fragment-player-main.xmltree.txt` |
| Resource dump | `.scratch/apple-music-apk/resources.dump.txt` |
| `LyricsBackgroundLayerView` dexdump | `.scratch/apple-music-apk/classes3.dexdump.txt` |
| Mesh enum `com.apple.android.music.player.T` | `.scratch/apple-music-apk/classes3.dexdump.txt` |
| RenderScript Toolkit class | `.scratch/apple-music-apk/classes4.dexdump.txt` |

Useful line anchors from the current dump:

| Topic | Anchor |
|---|---|
| Layout contains `LyricsBackgroundLayerView` | `layout-fragment-player-main.xmltree.txt:19` |
| `LyricsBackgroundLayerView` class descriptor | `classes3.dexdump.txt:187654` |
| Static init and fields | `classes3.dexdump.txt:187658` |
| Constructor and animator setup | `classes3.dexdump.txt:187899` |
| Scrim helper `a(Bitmap, Canvas, int...)` | `classes3.dexdump.txt:188063` |
| Static bitmap preprocessor `b(...)` | `classes3.dexdump.txt:188128` |
| `onDraw(Canvas)` | `classes3.dexdump.txt:189087` |
| `onSizeChanged(...)` | `classes3.dexdump.txt:190299` |
| `onVisibilityChanged(...)` | `classes3.dexdump.txt:190430` |
| `setArtwork(Bitmap)` | `classes3.dexdump.txt:190470` |
| `setReducedEffects(boolean)` | `classes3.dexdump.txt:190648` |
| `setSkipInvalidate(boolean)` | `classes3.dexdump.txt:190752` |
| `setViewLifecycleOwner(...)` | `classes3.dexdump.txt:190780` |
| Mesh enum `T` | `classes3.dexdump.txt:192360` |
| RenderScript Toolkit blur | `classes4.dexdump.txt:478588` |

Native libraries of interest:

```text
lib/arm64-v8a/librenderscript-toolkit.so
lib/arm64-v8a/libfrida-gadget.so
lib/arm64-v8a/libfrida-gadget.config.so
```

---

## 4. High-Level Architecture

Apple Music's Now Playing dynamic background is a custom Android View that owns
its bitmap buffers, shaders, paints, mesh vertices, and animators.

Conceptually:

```text
album artwork bitmap
  -> draw 3 transformed copies into a tiny offscreen bitmap
  -> apply saturation with ColorMatrix
  -> optionally warp with Canvas.drawBitmapMesh
  -> overlay black and white scrims
  -> blur with RenderScript Toolkit, radius 25
  -> wrap result in BitmapShader(TileMode.MIRROR)
  -> draw a rounded/fullscreen Path with that shader
  -> repeat roughly every 42 ms while motion is active
```

The important trick is that the offscreen bitmap is deliberately tiny. For a
1080 x 2400 screen and normal `T = 16`, the working bitmap is roughly:

```text
targetW = round(1080 * 1.3 / 16) = 88 px
targetH = round(2400 * 1.3 / 16) = 195 px
```

That makes a radius-25 blur affordable, then the result is scaled back up as a
shader. This is why the background can move while staying soft and cheap enough.

---

## 5. Layout Entry

The main player layout contains the dynamic background view as a full-screen
child near the bottom of the visual stack:

```text
RelativeLayout
  ConstraintLayout
    com.apple.android.music.player.LyricsBackgroundLayerView
      layout_width = match_parent
      layout_height = match_parent
```

The same layout also contains the player view switchers, lyrics panels, artwork,
and controls above the background layer. The dynamic background is therefore not
an effect attached to the cover image; it is its own background renderer.

For Mica, this maps naturally to:

```text
NowPlayingScreen
  Box(fillMaxSize)
    DynamicArtworkBackground / AndroidView background layer
    current player content
```

---

## 6. Core Class Shape

The custom view is:

```text
com.apple.android.music.player.LyricsBackgroundLayerView : android.view.View
```

Important fields from the DEX:

| Field | Type | Role |
|---|---|---|
| `V` | `float` | normal scale/downsample factor |
| `W` | `float` | reduced-motion scale/downsample factor |
| `a0` | `Bitmap` | 1x1 black fallback bitmap |
| `C` | `HashMap` | buffer cache keyed by target dimensions |
| `D` | `Bitmap` | current working bitmap from cache |
| `E` | `BitmapShader` | current/new shader |
| `F` | `Paint` | current/new paint |
| `G` | `BitmapShader` | previous shader during crossfade |
| `H` | `Paint` | previous paint during crossfade |
| `I` | `Matrix` | final shader scale matrix |
| `J` | `Path` | rounded/fullscreen draw path |
| `K` | `float[]` | current mesh vertices |
| `L` | `ValueAnimator` | track-switch fade animator |
| `M` | `ValueAnimator` | rotation animator A |
| `N` | `ValueAnimator` | rotation animator B |
| `O` | `ValueAnimator` | rotation animator C |
| `P` | `boolean` | marks whether stable/non-mesh path has been reached |
| `Q` | `int` | consecutive slow-frame counter |
| `R` | `boolean` | lifecycle/window state flag, exact source not fully decoded |
| `S` | `boolean` | skip-invalidate flag |
| `T` | `float` | active scale/downsample factor |
| `U` | `float` | active saturation factor |
| `e` | `float` | top corner radius in landscape-like mode |
| `x` | `Bitmap` | current artwork bitmap |
| `y` | `Bitmap` | queued artwork bitmap during transition |

### 6.1 Static initialization

The static initializer reads `densityDpi` and sets two scale constants:

```text
if densityDpi < 420:
    V = 24.0
    W = 72.0
else:
    V = 16.0
    W = 48.0
```

It also creates a 1x1 black fallback bitmap:

```text
a0 = Bitmap.createBitmap(intArrayOf(Color.BLACK), 1, 1, ARGB_8888)
```

Interpretation:

1. Higher-density devices use a less aggressive downsample factor (`16`) because
   the visible screen is denser.
2. Reduced motion uses a much larger factor (`48` or `72`), meaning the source
   texture is smaller and softer.
3. The fallback bitmap allows the renderer to keep a valid shader path even when
   real artwork is missing.

---

## 7. Constructor Behavior

The constructor does the following:

1. Calls `View(context, attrs, defStyle, 0)`.
2. Checks orientation.
3. In landscape-like orientation, loads a dimension resource used as top corner
   radius. In portrait, the radius is effectively `0`.
4. Creates a `HashMap` for cached working bitmaps.
5. Initializes flags:
   `P = false`, `Q = 0`.
6. Sets active scale and saturation:

```text
T = V
U = 2.5
```

7. Creates matrix `I` and sets it to `scale(T, T)`.
8. Creates two antialiased/filtering paints:
   `F` starts with alpha `0`, `H` starts with alpha `255`.
9. Creates the transition animator `L`.
10. Creates the three infinite rotation animators `M`, `N`, and `O`.

### 7.1 Animator table

| Animator | Confirmed value range | Duration | Interpolator | Repeat |
|---|---:|---:|---|---:|
| `L` | `1.0 -> 0.0` | 1000 ms | `PathInterpolator(0, 0, 0.3, 1)` | no repeat |
| `M` | `0 -> -360` | 120000 ms | `LinearInterpolator` | `-1` infinite |
| `N` | `0 -> 360` | 90000 ms | `LinearInterpolator` | `-1` infinite |
| `O` | `0 -> 360` | 70000 ms | `LinearInterpolator` | `-1` infinite |

The mixed directions and different periods are important. If all three layers
used the same direction and duration, the background would look like a simple
spinning disc. The 120/90/70 second periods create a slow phase drift.

For Mica, use exactly these as the starting tuning:

```kotlin
private const val TransitionMs = 1_000L
private const val RotationAMs = 120_000L
private const val RotationBMs = 90_000L
private const val RotationCMs = 70_000L
private const val FrameDelayMs = 42L
```

---

## 8. Static Preprocessor `b(...)`

The static method:

```text
LyricsBackgroundLayerView.b(
    bitmap: Bitmap,
    width: Int,
    height: Int,
    currentPlayTime: Long,
    context: Context
): Bitmap
```

appears to generate a background bitmap for a specific time value. It creates
temporary `ValueAnimator`s with the same 120/90/70 second periods, sets their
current play time, samples their angle values, and renders a frame.

This is likely used when a background frame needs to be prepared without relying
on the live view animator state. The live `onDraw` has a similar rendering path.

### 8.1 Preprocessor pipeline

Confirmed steps:

1. Compute low-resolution target size:

```text
targetW = round(width  * 1.3 / V)
targetH = round(height * 1.3 / V)
```

2. Create `Bitmap.createBitmap(targetW, targetH, ARGB_8888)`.
3. Create a `Canvas` for that bitmap.
4. Compute an oversized square-ish source draw size:

```text
side = round(max(targetW, targetH) * 1.3)
scale = side / artwork.height
```

The DEX divides by `artwork.getHeight()`. This is safe for square album covers
and gives cover-style filling.

5. Build a base matrix:

```text
base.setScale(scale, scale)
base.postRotate(angle, side / 2, side / 2)
base.postTranslate(-(side - targetW) / 2, -(side - targetH) / 2)
```

6. Create a `ColorMatrix`, set saturation to `2.5`, and wrap it in a
   `ColorMatrixColorFilter`.
7. Draw the artwork three times:

```text
layer A:
    centered
    rotate by angleA

layer B:
    centered
    rotate by angleB
    postTranslate(-0.95 * targetW, -0.7 * targetH)

layer C:
    centered
    rotate by angleC
    postTranslate(-1.0 * targetW, +0.7 * targetH)
    extra postRotate(angleC, targetW / 2, targetH / 2)
```

8. Draw black and white scrims over the result.
9. Blur with RenderScript Toolkit radius `25`.
10. Create an output bitmap at the requested full `width x height`.
11. Draw the blurred low-resolution bitmap into the output with scale `V` and
    center-crop style translation.

### 8.2 Scrim helper

The helper:

```text
LyricsBackgroundLayerView.a(bitmap, canvas, int... colors)
```

loops through each color and calls `canvas.drawPaint(paint)` with `Paint.Style.FILL`.
The resource IDs passed by the renderer are:

```text
lyrics_bg_layer_black_scrim = 0x7f0600c8
lyrics_bg_layer_white_scrim = 0x7f0600c9
```

Resource values:

| Resource | Day | Night |
|---|---|---|
| `lyrics_bg_layer_black_scrim` | `black_alpha_30` (`#4d000000`) | `black_alpha_50` (`#80000000`) |
| `lyrics_bg_layer_white_scrim` | `white_alpha_10` | `white_alpha_5` |

The black scrim controls contrast and prevents text from fighting the artwork.
The white scrim lifts saturated colors so the background feels luminous rather
than muddy.

---

## 9. Runtime `onDraw(Canvas)`

`onDraw` is the live rendering path.

### 9.1 Guard conditions

It first checks:

1. Current bitmap `x` exists.
2. Bitmap is not recycled.
3. View width is nonzero.
4. View height is nonzero.

If any check fails, it cancels all four animators:

```text
L.cancel()
M.cancel()
N.cancel()
O.cancel()
```

### 9.2 Shader regeneration condition

The view does not always regenerate the background texture. It reuses the
existing `BitmapShader` when motion is not active.

Confirmed behavior:

1. If shader `E` is missing, it must render a new bitmap and shader.
2. If shader `E` exists but the rotation animator is actively running, it
   regenerates the frame to reflect current angles.
3. If shader `E` exists and motion is not running or is paused, it can draw the
   cached shader directly.

This distinction matters for battery and thermals.

### 9.3 Low-resolution buffer size

At runtime it uses the active scale `T`, not always `V`:

```text
targetW = round(viewWidth  * 1.3 / T)
targetH = round(viewHeight * 1.3 / T)
```

In normal mode:

```text
T = V
U = 2.5
```

In reduced motion:

```text
T = W
U = 3.5
```

### 9.4 Bitmap buffer cache

`C` is a `HashMap` keyed by `(targetW, targetH)`.

Each entry stores a pair of ARGB_8888 bitmaps:

```text
Pair<Bitmap, Bitmap>
```

The view alternates between these two bitmaps to avoid drawing a mesh from and
to the same bitmap in-place. This is a simple ping-pong buffer:

```text
if currentWorkingBitmap == first:
    next = second
else:
    next = first
```

For Mica, keep this design. It avoids repeated allocation and avoids subtle
corruption when applying `drawBitmapMesh`.

### 9.5 Draw three transformed artwork copies

The runtime path repeats the same visual recipe as the static preprocessor, but
it samples the live animator values:

```text
angleA = M.animatedValue or 0
angleB = N.animatedValue or 0
angleC = O.animatedValue or 0
```

Then:

```text
draw artwork A:
    center crop into oversized square
    rotate angleA around center

draw artwork B:
    center crop into oversized square
    rotate angleB around center
    translate -0.95 * targetW, -0.7 * targetH

draw artwork C:
    center crop into oversized square
    rotate angleC around center
    translate -1.0 * targetW, +0.7 * targetH
    rotate angleC around target center
```

The three layers all use:

```text
ColorMatrix.setSaturation(U)
Paint(flags = 7)
```

`Paint(7)` means the standard anti-alias/filter/dither-style flags bundled into
that integer in Android's `Paint` constructor.

### 9.6 Mesh warp

If the view is not yet in its stable state (`P == false`), it builds mesh
vertices and calls:

```text
canvas.drawBitmapMesh(bitmap, 5, 5, verts, 0, null, 0, null)
```

The mesh source is `K`, a normalized vertex array selected by:

```text
K = com.apple.android.music.player.T.h()
```

Runtime conversion:

```text
for row in 0..5:
    for col in 0..5:
        index = row * 12 + col * 2
        out[index]     = normalized[index]     * bitmap.width
        out[index + 1] = normalized[index + 1] * bitmap.height
```

Why 72 floats:

```text
(MESH_COLS + 1) * (MESH_ROWS + 1) * 2
= 6 * 6 * 2
= 72
```

### 9.7 Scrim and blur

After the transformed artwork and optional mesh warp:

```text
draw lyrics_bg_layer_black_scrim
draw lyrics_bg_layer_white_scrim
blur radius = 25
```

The blur method is:

```text
com.google.android.renderscript.Toolkit.a(toolkit, inputBitmap, 25)
```

Under the hood, `Toolkit.a` creates an output bitmap and calls a native
`nativeBlurBitmap` function from `librenderscript-toolkit.so`.

### 9.8 Shader creation

After blur:

```text
shader = BitmapShader(blurredBitmap, TileMode.MIRROR, TileMode.MIRROR)
shader.setLocalMatrix(matrix)
E = shader
F.setShader(E)
```

The final matrix starts from `I`, which is usually `scale(T, T)`, and applies a
pre-translation to crop the 1.3 overscan back into the view bounds.

`TileMode.MIRROR` is a small but important detail. It hides edge seams when the
shader matrix or rounded path samples beyond the blurred bitmap.

### 9.9 Draw path and crossfade

The view draws:

1. Previous shader `G` with previous paint `H`, if `G` exists.
2. Current shader `E` with current paint `F`.

During track switch:

```text
L = ValueAnimator.ofFloat(1f, 0f)
F.alpha = ((1f - L.animatedValue) * 255).toInt()
postInvalidateDelayed(42)
```

At the beginning:

```text
L.animatedValue = 1
F.alpha = 0
```

At the end:

```text
L.animatedValue = 0
F.alpha = 255
```

The old shader remains underneath with alpha 255 until the animation ends. The
new shader fades in over it. When `L` ends, listener `G.onAnimationEnd` clears:

```text
G = null
```

### 9.10 Frame pacing

The live renderer calls:

```text
postInvalidateDelayed(42)
```

42 ms is about 23.8 fps. This is likely deliberate:

1. The motion is very slow, so 60 fps is unnecessary.
2. Low frame rate reduces CPU/GPU load.
3. Slow blur movement reads as ambient motion rather than a spinning animation.

For Mica, start at 42 ms. Only reduce it if visual judder is obvious on real
devices.

---

## 10. Mesh Enum `T`

The mesh enum is:

```text
com.apple.android.music.player.T
```

It contains:

```text
M1
M2
M3
M4
M5
MESH_COLS = 5
MESH_ROWS = 5
mVertices: FloatArray
```

Each enum value stores a 72-float normalized vertex array.

The method `T.h()` randomly picks one of the mesh presets and returns its
vertices. `setArtwork()` calls `T.h()` whenever the artwork changes, so each
track can have a slightly different warp pattern.

### 10.1 Do not copy the exact arrays

The exact arrays are proprietary data. Mica should not copy them. Use one of
these original approaches instead:

1. Procedural mesh generated from seeded sine/noise.
2. Five hand-authored normalized presets created by us.
3. A deterministic random mesh seeded by song ID, album ID, or artwork URI.

Recommended Mica shape:

```kotlin
private const val MeshCols = 5
private const val MeshRows = 5

fun generateMesh(seed: Long, strength: Float): FloatArray {
    val random = Random(seed)
    val out = FloatArray((MeshCols + 1) * (MeshRows + 1) * 2)
    for (row in 0..MeshRows) {
        for (col in 0..MeshCols) {
            val i = row * (MeshCols + 1) * 2 + col * 2
            val baseX = col / MeshCols.toFloat()
            val baseY = row / MeshRows.toFloat()

            val edgeFadeX = sin(Math.PI * baseX).toFloat()
            val edgeFadeY = sin(Math.PI * baseY).toFloat()
            val fade = edgeFadeX * edgeFadeY

            out[i] = (baseX + (random.nextFloat() - 0.5f) * strength * fade)
                .coerceIn(-0.08f, 1.08f)
            out[i + 1] = (baseY + (random.nextFloat() - 0.5f) * strength * fade)
                .coerceIn(-0.08f, 1.08f)
        }
    }
    return out
}
```

Start with:

```text
strength = 0.08 normal
strength = 0.03 reduced motion
```

---

## 11. Track Switch Lifecycle

`setArtwork(Bitmap)` is the key track-change method.

Confirmed behavior:

1. If transition `L` is currently started, compare the new bitmap against queued
   bitmap `y`.
2. If transition is not started, compare the new bitmap against current bitmap
   `x`.
3. It uses object equality and `Bitmap.sameAs(...)` to avoid restarting the
   effect for duplicate artwork.
4. If the same bitmap is already active and rotation is running, it just
   invalidates and returns.
5. For a real change:

```text
L.cancel()
M.cancel()
N.cancel()
O.cancel()

x = newArtwork
y = null

G = E
H.setShader(G)
E = null

K = T.h()

L.start()
F.alpha = 0
Q = 0
P = false
invalidate()
```

6. If `setArtwork` is called during an active transition, it stores the new
   bitmap in `y`. At the end of drawing/transition, if `y` is not null, it calls
   `setArtwork(y)`.

Mica should preserve this queueing behavior. Without it, rapid track skipping
can create flicker, stale backgrounds, or multiple expensive rebuilds in a row.

---

## 12. Size, Path, And Shape

`onSizeChanged(width, height, oldWidth, oldHeight)`:

1. Calls the superclass.
2. Creates a new `Path`.
3. Builds a rounded-rect path covering the view bounds.
4. Uses an 8-float radius array.

The radius array is:

```text
top-left:     e, e
top-right:    e, e
bottom-right: 0, 0
bottom-left:  0, 0
```

In portrait, `e` is generally `0`, so this is effectively full-screen. In a
landscape or constrained player state, the top corners can be rounded.

After size changes, it re-applies queued/current artwork through `setArtwork`.

For Mica:

1. If the background is always full-screen behind the player, use a full rect.
2. If embedding inside a panel, support per-corner radii.
3. Regenerate the shader when size changes.
4. Clear bitmap buffers for the old size if memory becomes a problem.

---

## 13. Visibility And Lifecycle

The view avoids animating while invisible.

### 13.1 Pause helper `d()`

`d()` pauses the three rotation animators:

```text
M.pause()
N.pause()
O.pause()
```

It only does this when the main rotation animator is started.

### 13.2 Resume helper `e()`

`e()` resumes the three rotation animators if they are paused:

```text
M.resume()
N.resume()
O.resume()
```

### 13.3 Visibility callback

`onVisibilityChanged(view, visibility)`:

```text
if visibility == VISIBLE:
    e()
    invalidate()
else:
    d()
```

### 13.4 Detach cleanup

`onDetachedFromWindow()`:

1. Calls superclass.
2. Iterates through all cached bitmap pairs in `C`.
3. Calls `recycle()` on both bitmaps in each pair.
4. Clears the map.

Mica should mirror this. If we implement a custom `View`, buffer cleanup belongs
in `onDetachedFromWindow`. If we implement a renderer thread, cleanup also needs
to stop the thread and release GL/software resources.

---

## 14. Invalidation Control

The view has:

```text
setSkipInvalidate(boolean)
```

It sets field `S`. If skip is later disabled, it invalidates immediately.

The fragment observes a `LiveData<Boolean>` and calls:

```text
setSkipInvalidate(!value)
```

The exact semantic source of that LiveData is not fully decoded, but the pattern
is clear: some external state can suspend or resume redraw requests.

Mica equivalent:

```text
motionEnabled && lifecycleVisible && screenInteractive && backgroundModeActive
```

Only schedule the next frame when all are true.

---

## 15. Reduced Motion

The APK contains strings:

```text
KEY_REDUCE_PLAYER_MOTION = key_reduce_player_motion
reduce_player_motion_setting = Reduce Now Playing Motion
reduce_player_motion_setting_message = Slow down the animated backdrop in Now Playing.
```

`setReducedEffects(boolean reduced)`:

```text
if reduced:
    targetScale = W
    saturation = 3.5
else:
    targetScale = V
    saturation = 2.5

if targetScale != T || saturation != U:
    d() // pause rotation animators
    T = targetScale
    U = saturation
    I.reset()
    I.setScale(T, T)
    setArtwork(queuedOrCurrentArtwork)
```

Confirmed constants:

```text
normal scale:
    V = 24 if densityDpi < 420
    V = 16 if densityDpi >= 420

reduced scale:
    W = 72 if densityDpi < 420
    W = 48 if densityDpi >= 420

normal saturation:
    U = 2.5

reduced saturation:
    U = 3.5
```

Important nuance:

1. The method does not directly change the 120/90/70 second animator durations.
2. It changes the rendering scale and saturation, then forces artwork to be
   reprocessed.
3. The user-facing string says "slow down", but from the DEX alone the directly
   confirmed change is coarser/softer rendering plus a higher saturation value.
4. It is likely that the perceived motion becomes slower because the texture is
   much lower resolution and less detailed, not because the animator periods are
   multiplied.

Mica recommendation:

```text
Normal:
    scaleFactor = 16 or 24
    saturation = 2.5
    meshStrength = 0.08
    frameDelay = 42 ms

Reduced:
    scaleFactor = 48 or 72
    saturation = 3.0 to 3.5
    meshStrength = 0.02 to 0.03
    frameDelay = 84 ms or render static frames only
```

Because Mica already has a global motion policy in `MOTION.md`, align this with
`rememberMicaMotionEnabled()` and Android's animator duration scale.

---

## 16. Artwork Color Metadata

The APK has media metadata constants:

```text
METADATA_KEY_ARTWORK_BACKGROUND_COLOR
METADATA_KEY_ARTWORK_JOE_COLORS
```

It also references:

```text
artwork_joe_colors
StoreMediaItem.artworkJoeColors
```

This means Apple Music has server/media-provided artwork color metadata that can
feed broader player theming. The dynamic background view itself primarily uses
the artwork bitmap and fixed scrims, but the player UI around it can use these
metadata colors.

Mica currently has `coverColor` and `PlayerBackgroundBlend` paths. For a similar
architecture:

1. Keep extracting local palette/accent color from artwork.
2. Use that for text/chrome contrast and ambient overlays.
3. Let the dynamic background bitmap remain artwork-derived.
4. Do not make the dynamic background only a solid palette gradient; that loses
   the Apple Music effect.

---

## 17. Mica Current State

Relevant Mica files today:

| File | Role |
|---|---|
| `app/src/main/java/com/mica/music/ui/theme/NowPlayingBackground.kt` | Chooses player background mode |
| `app/src/main/java/com/mica/music/ui/theme/BlurredCoverBackground.kt` | Current blurred artwork / dynamic light background |
| `app/src/main/java/com/mica/music/data/PlayerLowerBackgroundMode.kt` | User-selectable background modes |
| `app/src/main/java/com/mica/music/ui/screens/NowPlayingScreen.kt` | Places `NowPlayingBackground` |
| `app/src/main/java/com/mica/music/imaging/MicaImageLoaders.kt` | Background artwork preload/decode |
| `app/src/main/java/com/mica/music/ui/motion/MicaMotion.kt` | Motion policy source of truth |

The existing `BlurredCoverBackground` already has:

1. A low-res artwork source size (`384` px).
2. Holdover background during track switch.
3. Strong `BlurEffect` on Android 12+.
4. Optional `DynamicLightOverlay` with very slow rotating artwork copies.

The Apple Music implementation differs in three major ways:

1. It renders into a custom low-resolution bitmap instead of stacking full
   Compose `AsyncImage` layers.
2. It applies `Canvas.drawBitmapMesh`.
3. It blurs the composed low-res texture and turns it into a `BitmapShader`.

Because of `drawBitmapMesh` and buffer pooling, the closest Mica implementation
should be a custom Android `View` hosted from Compose using `AndroidView`.

---

## 18. Recommended Mica Implementation

### 18.1 Add a new background mode

Possible enum addition:

```kotlin
enum class PlayerLowerBackgroundMode {
    THEME,
    ARTWORK_GRADIENT,
    COVER_GLOW,
    DYNAMIC_LIGHT,
    ARTWORK_MOTION, // new
}
```

User-facing label ideas:

```text
Artwork Motion
Liquid Artwork
Dynamic Artwork
```

Use a neutral name in code. Avoid naming it "Apple Music" in product UI.

### 18.2 Add a composable bridge

Create:

```text
app/src/main/java/com/mica/music/ui/theme/DynamicArtworkBackground.kt
```

Shape:

```kotlin
@Composable
fun DynamicArtworkBackground(
    albumArtUri: String?,
    coverColor: Color,
    motionEnabled: Boolean,
    reducedMotion: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { DynamicArtworkBackgroundView(it) },
        update = { view ->
            view.setMotionEnabled(motionEnabled)
            view.setReducedEffects(reducedMotion || !motionEnabled)
            view.setFallbackColor(coverColor.toArgb())
            view.loadArtwork(context, albumArtUri)
        },
    )
}
```

Do not decode large artwork on the UI thread. Use Coil/Mica image loaders to
obtain a software `Bitmap`.

### 18.3 Add a custom View

Create:

```text
app/src/main/java/com/mica/music/ui/theme/DynamicArtworkBackgroundView.kt
```

Core responsibilities:

1. Own low-resolution bitmap buffers.
2. Own `ValueAnimator`s.
3. Accept decoded artwork bitmaps.
4. Render the dynamic texture.
5. Blur the texture.
6. Create and draw `BitmapShader`.
7. Pause/resume on lifecycle and visibility.
8. Recycle internal buffers on detach.

Recommended public API:

```kotlin
class DynamicArtworkBackgroundView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    fun setArtwork(bitmap: Bitmap?)
    fun setReducedEffects(reduced: Boolean)
    fun setMotionEnabled(enabled: Boolean)
    fun setFallbackColor(@ColorInt color: Int)
    fun clearArtwork()
}
```

### 18.4 Constants

Start with:

```kotlin
private const val Overscan = 1.3f
private const val NormalSaturation = 2.5f
private const val ReducedSaturation = 3.5f
private const val BlurRadius = 25
private const val TransitionMs = 1_000L
private const val RotationAMs = 120_000L
private const val RotationBMs = 90_000L
private const val RotationCMs = 70_000L
private const val FrameDelayMs = 42L
private const val MeshCols = 5
private const val MeshRows = 5
```

Scale constants:

```kotlin
private fun normalScaleFactor(context: Context): Float =
    if (context.resources.configuration.densityDpi < 420) 24f else 16f

private fun reducedScaleFactor(context: Context): Float =
    if (context.resources.configuration.densityDpi < 420) 72f else 48f
```

Mica may tune these after measuring real devices, but this should be the first
implementation baseline.

---

## 19. Detailed View Pseudocode

This pseudocode is intentionally original and not a decompilation.

```kotlin
class DynamicArtworkBackgroundView(context: Context) : View(context) {
    private val bufferCache = mutableMapOf<Pair<Int, Int>, BitmapPair>()

    private var currentArtwork: Bitmap? = null
    private var queuedArtwork: Bitmap? = null

    private var currentShader: BitmapShader? = null
    private var previousShader: BitmapShader? = null

    private val currentPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private val previousPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private val shaderMatrix = Matrix()

    private var path = Path()
    private var mesh: FloatArray = generateMesh(seed = 0L, strength = 0.08f)

    private var scaleFactor = normalScaleFactor(context)
    private var saturation = NormalSaturation
    private var stable = false

    private val transition = ValueAnimator.ofFloat(1f, 0f).apply {
        duration = TransitionMs
        interpolator = PathInterpolator(0f, 0f, 0.3f, 1f)
        addUpdateListener { invalidate() }
        addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                previousShader = null
                queuedArtwork?.let {
                    queuedArtwork = null
                    setArtwork(it)
                }
            }
        })
    }

    private val rotateA = ValueAnimator.ofFloat(0f, -360f).loop(RotationAMs)
    private val rotateB = ValueAnimator.ofFloat(0f, 360f).loop(RotationBMs)
    private val rotateC = ValueAnimator.ofFloat(0f, 360f).loop(RotationCMs)

    override fun onDraw(canvas: Canvas) {
        val artwork = currentArtwork ?: return drawFallback(canvas)
        if (width <= 0 || height <= 0 || artwork.isRecycled) return stopAnimators()

        if (currentShader == null || rotateA.isStarted && !rotateA.isPaused) {
            rebuildShader(artwork)
        }

        previousShader?.let {
            previousPaint.shader = it
            previousPaint.alpha = 255
            canvas.drawPath(path, previousPaint)
        }

        currentShader?.let {
            currentPaint.shader = it
            currentPaint.alpha = currentAlphaFromTransition()
            canvas.drawPath(path, currentPaint)
        }

        if (shouldContinueAnimating()) {
            postInvalidateDelayed(FrameDelayMs)
        }
    }
}
```

### 19.1 `rebuildShader`

```kotlin
private fun rebuildShader(artwork: Bitmap) {
    val targetW = round(width * Overscan / scaleFactor).toInt().coerceAtLeast(1)
    val targetH = round(height * Overscan / scaleFactor).toInt().coerceAtLeast(1)

    val pair = bufferCache.getOrPut(targetW to targetH) {
        BitmapPair(
            Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888),
            Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888),
        )
    }

    val source = pair.nextWritable()
    source.eraseColor(Color.TRANSPARENT)
    val sourceCanvas = Canvas(source)

    val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG).apply {
        colorFilter = ColorMatrixColorFilter(ColorMatrix().apply {
            setSaturation(saturation)
        })
    }

    drawArtworkLayers(
        canvas = sourceCanvas,
        artwork = artwork,
        targetW = targetW,
        targetH = targetH,
        paint = paint,
    )

    val warped = if (!stable) {
        val destination = pair.other(source)
        drawMesh(source = source, destination = destination)
        destination
    } else {
        source
    }

    applyScrims(Canvas(warped))
    val blurred = blurLowResBitmap(warped, BlurRadius)

    currentShader = BitmapShader(
        blurred,
        Shader.TileMode.MIRROR,
        Shader.TileMode.MIRROR,
    ).apply {
        val matrix = Matrix(shaderMatrix)
        val cropX = -((blurred.width - blurred.width / Overscan) / 2f)
        val cropY = -((blurred.height - blurred.height / Overscan) / 2f)
        matrix.preTranslate(cropX, cropY)
        setLocalMatrix(matrix)
    }
}
```

### 19.2 `drawArtworkLayers`

```kotlin
private fun drawArtworkLayers(
    canvas: Canvas,
    artwork: Bitmap,
    targetW: Int,
    targetH: Int,
    paint: Paint,
) {
    val side = round(max(targetW, targetH) * Overscan).toInt()
    val scale = side / artwork.height.toFloat()
    val center = side / 2f
    val translateX = -((side - targetW) / 2f)
    val translateY = -((side - targetH) / 2f)

    fun matrixFor(angle: Float): Matrix = Matrix().apply {
        setScale(scale, scale)
        postRotate(angle, center, center)
        postTranslate(translateX, translateY)
    }

    canvas.drawBitmap(artwork, matrixFor(angleA()), paint)

    canvas.drawBitmap(
        artwork,
        matrixFor(angleB()).apply {
            postTranslate(-0.95f * targetW, -0.7f * targetH)
        },
        paint,
    )

    canvas.drawBitmap(
        artwork,
        matrixFor(angleC()).apply {
            postTranslate(-1.0f * targetW, 0.7f * targetH)
            postRotate(angleC(), targetW / 2f, targetH / 2f)
        },
        paint,
    )
}
```

### 19.3 `drawMesh`

```kotlin
private fun drawMesh(source: Bitmap, destination: Bitmap): Bitmap {
    destination.eraseColor(Color.TRANSPARENT)
    val canvas = Canvas(destination)
    val actual = FloatArray((MeshCols + 1) * (MeshRows + 1) * 2)

    for (row in 0..MeshRows) {
        for (col in 0..MeshCols) {
            val i = row * (MeshCols + 1) * 2 + col * 2
            actual[i] = mesh[i] * source.width
            actual[i + 1] = mesh[i + 1] * source.height
        }
    }

    canvas.drawBitmapMesh(source, MeshCols, MeshRows, actual, 0, null, 0, null)
    return destination
}
```

### 19.4 `applyScrims`

Apple uses two resource colors. Mica can define equivalent theme-aware colors:

```kotlin
private fun blackScrim(isDark: Boolean): Int =
    if (isDark) 0x80000000.toInt() else 0x4D000000

private fun whiteScrim(isDark: Boolean): Int =
    if (isDark) 0x0DFFFFFF else 0x1AFFFFFF
```

Then:

```kotlin
private fun Canvas.drawScrim(@ColorInt color: Int) {
    drawPaint(Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        this.color = color
    })
}
```

Order:

```text
black scrim first
white scrim second
```

---

## 20. Blur Strategy For Mica

Apple uses RenderScript Toolkit native blur. Mica has several options.

### 20.1 Option A: Android 12+ `RenderEffect`

Pros:

1. Already used in `BlurredCoverBackground`.
2. GPU-backed and simple for Compose layers.

Cons:

1. The Apple-like pipeline needs a blurred bitmap to use as a `BitmapShader`.
2. `RenderEffect` is not directly a CPU bitmap blur.
3. Harder to combine with `drawBitmapMesh` in a pure View pipeline.

Use this only for a simplified Compose version.

### 20.2 Option B: Software blur on low-res bitmap

Pros:

1. Works on all API levels.
2. Low-res target is tiny, so a CPU blur is realistic.
3. Fits the Apple-like View pipeline.

Cons:

1. Need to implement or adopt a blur algorithm.
2. Must avoid per-frame allocation.

Recommended first implementation: StackBlur or separable box blur on the tiny
working bitmap. Because target bitmaps are around 60-200 px wide/tall, even a
CPU blur should be manageable at 42 ms cadence.

### 20.3 Option C: Native or dependency blur

Pros:

1. Fastest if using a well-maintained library.
2. Closest to Apple's RenderScript Toolkit path.

Cons:

1. Adds dependency and open-source notice work.
2. Needs API and ABI testing.

Use only if Option B cannot meet thermal/performance targets.

### 20.4 Practical recommendation

Implement the first version with:

```text
software blur on low-res ARGB_8888 bitmap
radius = 12 to 25, tune visually
```

Then profile. If the background costs more than expected, reduce the low-res
buffer size first before adding native blur.

---

## 21. Decode And Bitmap Requirements

The View renderer needs a software bitmap:

```text
Bitmap.Config.ARGB_8888
isMutable can be false for source artwork
isHardware must be false
```

If Coil returns a hardware bitmap, draw it into a software bitmap before using
it in the background renderer:

```kotlin
private fun Bitmap.asSoftwareArgb8888(): Bitmap {
    if (config == Bitmap.Config.ARGB_8888 && !isRecycled && config != Bitmap.Config.HARDWARE) {
        return this
    }
    val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    Canvas(out).drawBitmap(this, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
    return out
}
```

For performance, decode a background-sized source, not full album art:

```text
source artwork decode target: 256 to 512 px
current Mica blurred background source: 384 px
recommended first value: reuse 384 px
```

The dynamic renderer will downsample further internally.

---

## 22. Motion Policy

Mica already centralizes motion in `MOTION.md`. The new background should follow
that policy:

1. If global motion is disabled, render a static blurred artwork frame.
2. If system animator duration scale is `0`, do not run infinite animators.
3. If the player is not visible, pause animators and stop invalidation.
4. If the app goes background, pause animators.
5. If battery saver or thermal throttling is detected later, the background can
   switch to reduced mode.

Suggested state:

```kotlin
data class DynamicArtworkMotionState(
    val visible: Boolean,
    val userMotionEnabled: Boolean,
    val systemMotionEnabled: Boolean,
    val reducedMotion: Boolean,
) {
    val shouldAnimate: Boolean =
        visible && userMotionEnabled && systemMotionEnabled && !reducedMotion
}
```

Reduced mode can still show a static or very slow background.

---

## 23. Integration Plan

### 23.1 Minimal visual slice

Implement only:

1. New custom View.
2. Decode current artwork bitmap.
3. Draw three transformed artwork copies.
4. Saturation.
5. Scrims.
6. Blur.
7. Shader draw.

Skip mesh and crossfade initially if needed.

This should already capture most of the visual identity.

### 23.2 Full visual slice

Add:

1. Track-switch shader crossfade.
2. Queued artwork during rapid skips.
3. 5x5 mesh warp.
4. Reduced motion mode.
5. Buffer cleanup.
6. Lifecycle pause/resume.

### 23.3 Product integration

Modify:

```text
PlayerLowerBackgroundMode.kt
NowPlayingBackground.kt
SettingsScreen.kt
NowPlayingScreen.kt
```

Add user setting label and persistence through existing preference path.

### 23.4 Testing integration

Add tests around:

1. Mode persistence.
2. Motion policy -> reduced/static state.
3. View buffer sizing math.
4. Mesh array size and bounds.
5. Duplicate artwork no-op behavior.
6. Rapid queueing behavior.

---

## 24. Visual Tuning Table

Start here:

| Parameter | Apple evidence | Mica initial value | Notes |
|---|---:|---:|---|
| Overscan | `1.3` | `1.3` | Used before downsample and crop |
| Normal scale high dpi | `16` | `16` | `densityDpi >= 420` |
| Normal scale low dpi | `24` | `24` | `densityDpi < 420` |
| Reduced scale high dpi | `48` | `48` | softer/coarser |
| Reduced scale low dpi | `72` | `72` | softer/coarser |
| Normal saturation | `2.5` | `2.3-2.5` | tune against Mica palette |
| Reduced saturation | `3.5` | `3.0-3.5` | can become too neon |
| Blur radius | `25` | `18-25` | depends on blur algorithm |
| Transition | `1000 ms` | `1000 ms` | crossfade new over old |
| Rotation A | `120000 ms`, `0 -> -360` | same | reverse layer |
| Rotation B | `90000 ms`, `0 -> 360` | same | forward layer |
| Rotation C | `70000 ms`, `0 -> 360` | same | forward layer |
| Frame delay | `42 ms` | `42 ms` | about 24 fps |
| Mesh | `5 x 5` | `5 x 5` | original mesh arrays not copied |
| Black scrim day | `#4D000000` | same or theme-adjusted | contrast |
| Black scrim night | `#80000000` | same or theme-adjusted | contrast |
| White scrim day | approx `#1AFFFFFF` | same | lift |
| White scrim night | `#0DFFFFFF` | same | lift |

---

## 25. Simplified Compose Alternative

If we choose not to build a custom View immediately, a Compose-only approximation
can reuse `BlurredCoverBackground` ideas:

```text
Box
  AsyncImage artwork layer A
    scale 1.35
    rotate very slowly -360 / 120s
    saturation not directly easy in Compose without ColorFilter matrix
    blur 96-120 px
  AsyncImage artwork layer B
    scale 1.35
    offset -screenWidth * 0.35, -screenHeight * 0.20
    rotate 360 / 90s
    blur 96-120 px
  AsyncImage artwork layer C
    scale 1.35
    offset -screenWidth * 0.40, screenHeight * 0.20
    rotate 360 / 70s
    blur 96-120 px
  black/white scrims
  Mica gradient overlay
```

Pros:

1. Fast to build.
2. Integrates with current `BlurredCoverBackground`.
3. No custom bitmap blur.

Cons:

1. No `drawBitmapMesh`.
2. More expensive if blurring multiple full-size layers.
3. Less faithful to Apple's low-res shader texture pipeline.
4. Harder to get the exact cloudy liquid texture.

Recommendation: use this only as a prototype. The production version should be
the custom View pipeline.

---

## 26. Performance Notes

### 26.1 Why Apple's approach is efficient

The renderer does expensive-looking operations on a tiny bitmap:

```text
screen: 1080 x 2400
working texture normal high dpi: about 88 x 195
working texture reduced high dpi: about 29 x 65
```

That makes these operations affordable:

1. Three bitmap draws.
2. Optional mesh warp.
3. Scrims.
4. Blur.
5. Shader creation.

The final draw is just a path filled with a shader.

### 26.2 Main risks in Mica

1. Accidentally blurring full-resolution artwork every frame.
2. Allocating new bitmaps every frame.
3. Letting the animation run while the player is off-screen.
4. Failing to recycle buffers on detach.
5. Using hardware bitmaps in software `Canvas` paths.
6. Running at 60 fps when 24 fps is enough.
7. Updating Compose state every frame instead of keeping animation inside a View.

### 26.3 Guardrails

1. Never allocate in `onDraw` after buffers are warmed.
2. Cache two bitmaps per target size.
3. Use `postInvalidateDelayed(42)` rather than `invalidate()` in a tight loop.
4. Pause in `onVisibilityChanged`.
5. Stop in `onDetachedFromWindow`.
6. Keep a debug overlay or logs for buffer dimensions during development.
7. Track frame build time; if more than 8-10 ms frequently, reduce resolution or
   blur radius.

---

## 27. Testing And Verification

### 27.1 Unit tests

Test pure math/helpers:

1. `scaleFactorForDensity(dpi)`.
2. `targetSize(width, height, scaleFactor)`.
3. `meshVertexCount == 72`.
4. Mesh values stay in reasonable bounds.
5. Duplicate artwork update does not reset transition state.
6. Reduced motion updates scale and saturation.

### 27.2 Instrumented/manual tests

Manual QA matrix:

| Scenario | Expected result |
|---|---|
| No artwork | fallback color/black background, no crash |
| First artwork load | background appears without white flash |
| Track switch | old background remains, new background fades in over ~1s |
| Rapid next/previous | no flicker, latest artwork wins |
| Rotate device | path and buffers rebuild correctly |
| Enter background | animators pause |
| Return foreground | animators resume or redraw once |
| Reduced motion on | softer/slower/static result |
| Android 12+ | blur path works |
| Android below 12 | fallback/software blur works |

### 27.3 Performance tests

Measure:

1. Average frame build time.
2. P95 frame build time.
3. Allocations during steady animation.
4. Memory after 30 track switches.
5. Thermal behavior after 10 minutes on mid-range device.
6. Battery usage compared with current `DYNAMIC_LIGHT`.

Targets:

```text
steady-state allocations: zero or near-zero
average onDraw/rebuild: below 6 ms on target devices
P95 rebuild: below 12 ms
frame cadence: stable around 42 ms
no visible jank on player interactions
```

---

## 28. Suggested Build Order

1. Add the document and keep it as the source of truth.
2. Add pure helper functions and tests:
   scale factor, target size, mesh generation.
3. Add `DynamicArtworkBackgroundView` with fallback color only.
4. Add artwork bitmap input and low-res three-layer draw.
5. Add software blur.
6. Add shader draw and path sizing.
7. Add `AndroidView` composable bridge.
8. Add track-switch crossfade and queued artwork.
9. Add mesh warp.
10. Add reduced motion and lifecycle pause.
11. Add settings mode and product integration.
12. Profile on device and tune constants.

This order keeps every step visually testable.

---

## 29. Implementation Checklist

Before shipping:

- [ ] No proprietary mesh arrays copied from Apple Music.
- [ ] Renderer accepts only software ARGB bitmaps.
- [ ] Buffers are reused between frames.
- [ ] Buffers are recycled on detach.
- [ ] `onVisibilityChanged` pauses/resumes animation.
- [ ] Reduced motion is wired to Mica motion policy.
- [ ] No 60 fps redraw loop.
- [ ] Track switch does not flash black/white.
- [ ] Rapid skips do not enqueue stale backgrounds indefinitely.
- [ ] Works without artwork.
- [ ] Works in dark and light themes.
- [ ] Works on API levels supported by Mica.
- [ ] Open-source notice updated if a blur dependency is added.

---

## 30. Summary

Apple Music's Now Playing background is best understood as a tiny animated
artwork texture generator:

```text
3 slow rotating artwork layers
+ saturation
+ optional 5x5 mesh warp
+ black/white scrims
+ radius-25 blur
+ mirrored BitmapShader
+ 1s crossfade on artwork change
```

The most important implementation lesson is not a single animation value. It is
the low-resolution offscreen texture strategy. That lets the app run a visually
rich blur background without continuously blurring a full-screen image.

For Mica, the faithful production path is a custom Android `View` hosted in
Compose. A Compose-only layered `AsyncImage` prototype is possible, but it will
miss the mesh/shader details and may be less efficient.
