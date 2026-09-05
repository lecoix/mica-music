package com.mica.music.ui.theme

import android.content.Context
import android.content.res.AssetManager
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.os.SystemClock
import android.util.AttributeSet
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import com.mica.music.ui.MicaScreenshotGoldenMode
import com.mica.music.util.DiagnosticLog
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Mica-owned GLES renderer over real J2000 star-map data bundled under star_map/.
 *
 * The renderer intentionally treats the playback page as a viewport onto one continuous sky:
 * stars keep their catalogue positions, all nearby constellation lines can remain visible, and
 * only the song-selected primary constellation is emphasized. Track changes move the viewport;
 * individual stars are never rearranged into generated shapes.
 */
@Composable
internal fun StarMapGlBackground(
    sceneKey: String,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    val spec = StarMapSceneSpec(
        key = sceneKey.ifBlank { "mica-star-map" },
        accentR = accent.red,
        accentG = accent.green,
        accentB = accent.blue,
    )
    Box(
        modifier
            .fillMaxSize()
            .background(StarMapBackgroundSurface),
    ) {
        if (!MicaScreenshotGoldenMode.enabled) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    StarMapGlBackgroundView(context).apply { setScene(spec) }
                },
                update = { view -> view.setScene(spec) },
                onRelease = { view -> view.release() },
            )
        }
    }
}

internal val StarMapBackgroundSurface = Color(0xFF050A15)
internal val StarMapForegroundAccent = Color(0xFFB8D8FF)

private data class StarMapSceneSpec(
    val key: String,
    val accentR: Float,
    val accentG: Float,
    val accentB: Float,
)

private class StarMapGlBackgroundView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : TextureView(context, attrs), TextureView.SurfaceTextureListener {

    private var renderThread: StarMapRenderThread? = null
    private var currentSpec = StarMapSceneSpec("mica-star-map", 0.62f, 0.76f, 1.0f)

    init {
        isOpaque = true
        surfaceTextureListener = this
    }

    fun setScene(spec: StarMapSceneSpec) {
        if (currentSpec == spec) return
        currentSpec = spec
        renderThread?.updateScene(spec)
    }

    fun release() {
        renderThread?.requestStopAndJoin()
        renderThread = null
    }

    override fun onSurfaceTextureAvailable(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
        release()
        surfaceTexture.setDefaultBufferSize(width.coerceAtLeast(1), height.coerceAtLeast(1))
        renderThread = StarMapRenderThread(
            assetManager = context.applicationContext.assets,
            surfaceTexture = surfaceTexture,
            initialWidth = width.coerceAtLeast(1),
            initialHeight = height.coerceAtLeast(1),
            initialSpec = currentSpec,
        ).also(Thread::start)
    }

    override fun onSurfaceTextureSizeChanged(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
        surfaceTexture.setDefaultBufferSize(width.coerceAtLeast(1), height.coerceAtLeast(1))
        renderThread?.resize(width.coerceAtLeast(1), height.coerceAtLeast(1))
    }

    override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture): Boolean {
        release()
        return true
    }

    override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) = Unit
}

private class StarMapRenderThread(
    assetManager: AssetManager,
    surfaceTexture: SurfaceTexture,
    initialWidth: Int,
    initialHeight: Int,
    initialSpec: StarMapSceneSpec,
) : Thread("mica-star-map-gl") {

    private val surface = Surface(surfaceTexture)
    private val lock = Object()
    private val renderer = StarMapRenderer(assetManager)

    @Volatile
    private var running = true
    private var width = initialWidth
    private var height = initialHeight
    private var sizeChanged = true
    private var sceneSpec = initialSpec
    private var sceneChanged = true

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    fun resize(newWidth: Int, newHeight: Int) {
        synchronized(lock) {
            width = newWidth.coerceAtLeast(1)
            height = newHeight.coerceAtLeast(1)
            sizeChanged = true
            lock.notifyAll()
        }
    }

    fun updateScene(spec: StarMapSceneSpec) {
        synchronized(lock) {
            if (sceneSpec == spec) return
            sceneSpec = spec
            sceneChanged = true
            lock.notifyAll()
        }
    }

    fun requestStopAndJoin() {
        running = false
        synchronized(lock) { lock.notifyAll() }
        interrupt()
        if (Thread.currentThread() != this) {
            runCatching { join(500) }
        }
    }

    override fun run() {
        try {
            if (!initEgl()) {
                DiagnosticLog.event("StarMapGl", "egl-init-failed")
                return
            }
            renderer.onSurfaceCreated()
            var nextFrameAt = SystemClock.uptimeMillis()
            while (running) {
                val nextWidth: Int
                val nextHeight: Int
                val applySize: Boolean
                val nextSpec: StarMapSceneSpec
                val applyScene: Boolean
                synchronized(lock) {
                    nextWidth = width
                    nextHeight = height
                    applySize = sizeChanged
                    sizeChanged = false
                    nextSpec = sceneSpec
                    applyScene = sceneChanged
                    sceneChanged = false
                }

                if (applySize) {
                    GLES20.glViewport(0, 0, nextWidth, nextHeight)
                    renderer.onSurfaceChanged(nextWidth, nextHeight)
                }
                if (applyScene) {
                    renderer.updateScene(nextSpec)
                }

                renderer.render()
                if (!EGL14.eglSwapBuffers(eglDisplay, eglSurface)) {
                    DiagnosticLog.event(
                        "StarMapGl",
                        "egl-swap-failed error=${EGL14.eglGetError()}",
                    )
                    break
                }

                nextFrameAt += StarMapFrameIntervalMs
                val sleepMs = nextFrameAt - SystemClock.uptimeMillis()
                if (sleepMs > 1L) {
                    runCatching { sleep(sleepMs) }
                } else {
                    nextFrameAt = SystemClock.uptimeMillis()
                }
            }
        } catch (throwable: Throwable) {
            DiagnosticLog.event("StarMapGl", "renderer-stopped", throwable)
        } finally {
            renderer.release()
            releaseEgl()
            surface.release()
        }
    }

    private fun initEgl(): Boolean {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) return false

        val version = IntArray(2)
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) return false

        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        val attribs = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EglOpenGlEs2Bit,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 0,
            EGL14.EGL_DEPTH_SIZE, 0,
            EGL14.EGL_STENCIL_SIZE, 0,
            EGL14.EGL_NONE,
        )
        if (!EGL14.eglChooseConfig(eglDisplay, attribs, 0, configs, 0, 1, numConfigs, 0)) {
            return false
        }
        val config = configs[0] ?: return false

        eglContext = EGL14.eglCreateContext(
            eglDisplay,
            config,
            EGL14.EGL_NO_CONTEXT,
            intArrayOf(EglContextClientVersion, 2, EGL14.EGL_NONE),
            0,
        )
        if (eglContext == EGL14.EGL_NO_CONTEXT) return false

        eglSurface = EGL14.eglCreateWindowSurface(
            eglDisplay,
            config,
            surface,
            intArrayOf(EGL14.EGL_NONE),
            0,
        )
        if (eglSurface == EGL14.EGL_NO_SURFACE) return false

        return EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
    }

    private fun releaseEgl() {
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(
                eglDisplay,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_CONTEXT,
            )
            if (eglSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(eglDisplay, eglSurface)
            }
            if (eglContext != EGL14.EGL_NO_CONTEXT) {
                EGL14.eglDestroyContext(eglDisplay, eglContext)
            }
            EGL14.eglTerminate(eglDisplay)
        }
        eglDisplay = EGL14.EGL_NO_DISPLAY
        eglSurface = EGL14.EGL_NO_SURFACE
        eglContext = EGL14.EGL_NO_CONTEXT
    }
}

private data class Vec3(
    val x: Float,
    val y: Float,
    val z: Float,
) {
    operator fun plus(other: Vec3): Vec3 = Vec3(x + other.x, y + other.y, z + other.z)
    operator fun times(scale: Float): Vec3 = Vec3(x * scale, y * scale, z * scale)

    fun dot(other: Vec3): Float = x * other.x + y * other.y + z * other.z

    fun cross(other: Vec3): Vec3 = Vec3(
        y * other.z - z * other.y,
        z * other.x - x * other.z,
        x * other.y - y * other.x,
    )

    fun normalized(): Vec3 {
        val length = sqrt(x * x + y * y + z * z)
        return if (length <= 1e-6f) Vec3(0f, 0f, 1f) else this * (1f / length)
    }
}

private data class SkyQuaternion(
    val w: Float,
    val x: Float,
    val y: Float,
    val z: Float,
) {
    fun normalized(): SkyQuaternion {
        val length = sqrt(w * w + x * x + y * y + z * z)
        return if (length <= 1e-6f) Identity else SkyQuaternion(
            w / length,
            x / length,
            y / length,
            z / length,
        )
    }

    fun conjugate(): SkyQuaternion = SkyQuaternion(w, -x, -y, -z)

    fun rotate(vector: Vec3): Vec3 {
        val qVector = Vec3(x, y, z)
        val t = qVector.cross(vector) * 2f
        return vector + t * w + qVector.cross(t)
    }

    fun inverseRotate(vector: Vec3): Vec3 = conjugate().rotate(vector)

    companion object {
        val Identity = SkyQuaternion(1f, 0f, 0f, 0f)

        fun lookAt(direction: Vec3): SkyQuaternion {
            val forward = direction.normalized()
            val celestialNorth = Vec3(0f, 1f, 0f)
            var right = forward.cross(celestialNorth)
            if (right.dot(right) < 1e-5f) {
                right = forward.cross(Vec3(0f, 0f, 1f))
            }
            right = right.normalized()
            val up = right.cross(forward).normalized()
            val back = forward * -1f
            return fromBasis(right, up, back)
        }

        fun slerp(from: SkyQuaternion, to: SkyQuaternion, progress: Float): SkyQuaternion {
            val t = progress.coerceIn(0f, 1f)
            var end = to
            var dot = from.w * end.w + from.x * end.x + from.y * end.y + from.z * end.z
            if (dot < 0f) {
                end = SkyQuaternion(-end.w, -end.x, -end.y, -end.z)
                dot = -dot
            }
            if (dot > 0.9995f) {
                return SkyQuaternion(
                    w = lerp(from.w, end.w, t),
                    x = lerp(from.x, end.x, t),
                    y = lerp(from.y, end.y, t),
                    z = lerp(from.z, end.z, t),
                ).normalized()
            }

            val theta = acos(dot.coerceIn(-1f, 1f))
            val sinTheta = sin(theta)
            if (abs(sinTheta) < 1e-5f) return from
            val fromWeight = sin((1f - t) * theta) / sinTheta
            val toWeight = sin(t * theta) / sinTheta
            return SkyQuaternion(
                w = from.w * fromWeight + end.w * toWeight,
                x = from.x * fromWeight + end.x * toWeight,
                y = from.y * fromWeight + end.y * toWeight,
                z = from.z * fromWeight + end.z * toWeight,
            ).normalized()
        }

        private fun fromBasis(right: Vec3, up: Vec3, back: Vec3): SkyQuaternion {
            val m00 = right.x
            val m01 = up.x
            val m02 = back.x
            val m10 = right.y
            val m11 = up.y
            val m12 = back.y
            val m20 = right.z
            val m21 = up.z
            val m22 = back.z
            val trace = m00 + m11 + m22

            val quaternion = if (trace > 0f) {
                val s = sqrt(trace + 1f) * 2f
                SkyQuaternion(
                    w = 0.25f * s,
                    x = (m21 - m12) / s,
                    y = (m02 - m20) / s,
                    z = (m10 - m01) / s,
                )
            } else if (m00 > m11 && m00 > m22) {
                val s = sqrt(1f + m00 - m11 - m22) * 2f
                SkyQuaternion(
                    w = (m21 - m12) / s,
                    x = 0.25f * s,
                    y = (m01 + m10) / s,
                    z = (m02 + m20) / s,
                )
            } else if (m11 > m22) {
                val s = sqrt(1f + m11 - m00 - m22) * 2f
                SkyQuaternion(
                    w = (m02 - m20) / s,
                    x = (m01 + m10) / s,
                    y = 0.25f * s,
                    z = (m12 + m21) / s,
                )
            } else {
                val s = sqrt(1f + m22 - m00 - m11) * 2f
                SkyQuaternion(
                    w = (m10 - m01) / s,
                    x = (m02 + m20) / s,
                    y = (m12 + m21) / s,
                    z = 0.25f * s,
                )
            }
            return quaternion.normalized()
        }
    }
}

private data class CatalogStar(
    val id: Int,
    val direction: Vec3,
    val magnitude: Float,
    val colorIndexBv: Float,
)

private data class CatalogLineSegment(
    val constellationId: String,
    val rank: Int,
    val fromDirection: Vec3,
    val toDirection: Vec3,
)

private data class CatalogConstellation(
    val id: String,
    val rank: Int,
    val centerDirection: Vec3,
)

private data class StarMapCatalog(
    val stars: List<CatalogStar>,
    val lines: List<CatalogLineSegment>,
    val constellations: List<CatalogConstellation>,
)

private object StarMapCatalogCache {
    @Volatile
    private var cached: StarMapCatalog? = null

    fun get(assetManager: AssetManager): StarMapCatalog {
        cached?.let { return it }
        return synchronized(this) {
            cached ?: load(assetManager).also { cached = it }
        }
    }

    private fun load(assetManager: AssetManager): StarMapCatalog {
        val started = SystemClock.elapsedRealtime()
        return runCatching {
            val starRoot = JSONObject(assetManager.readUtf8Text("star_map/stars.6.json"))
            val starFeatures = starRoot.getJSONArray("features")
            val stars = ArrayList<CatalogStar>(starFeatures.length())
            for (index in 0 until starFeatures.length()) {
                val feature = starFeatures.getJSONObject(index)
                val properties = feature.getJSONObject("properties")
                val magnitude = properties.optDouble("mag", 99.0).toFloat()
                if (magnitude > CatalogMagnitudeLimit) continue

                val coordinates = feature
                    .getJSONObject("geometry")
                    .getJSONArray("coordinates")
                stars += CatalogStar(
                    id = feature.optInt("id", index),
                    direction = skyDirection(
                        longitudeDeg = coordinates.getDouble(0).toFloat(),
                        latitudeDeg = coordinates.getDouble(1).toFloat(),
                    ),
                    magnitude = magnitude,
                    colorIndexBv = properties.optString("bv", "0.65")
                        .toFloatOrNull()
                        ?.coerceIn(-0.5f, 2.2f)
                        ?: 0.65f,
                )
            }

            val lineRoot = JSONObject(
                assetManager.readUtf8Text("star_map/constellations.lines.json"),
            )
            val lineFeatures = lineRoot.getJSONArray("features")
            val lines = ArrayList<CatalogLineSegment>(600)
            for (featureIndex in 0 until lineFeatures.length()) {
                val feature = lineFeatures.getJSONObject(featureIndex)
                val constellationId = feature.optString("id", "")
                val rank = feature
                    .optJSONObject("properties")
                    ?.optString("rank", "3")
                    ?.toIntOrNull()
                    ?: 3
                val lineStrings = feature
                    .getJSONObject("geometry")
                    .getJSONArray("coordinates")

                for (lineIndex in 0 until lineStrings.length()) {
                    val lineString = lineStrings.getJSONArray(lineIndex)
                    for (pointIndex in 0 until lineString.length() - 1) {
                        val from = lineString.getJSONArray(pointIndex)
                        val to = lineString.getJSONArray(pointIndex + 1)
                        lines += CatalogLineSegment(
                            constellationId = constellationId,
                            rank = rank,
                            fromDirection = skyDirection(
                                longitudeDeg = from.getDouble(0).toFloat(),
                                latitudeDeg = from.getDouble(1).toFloat(),
                            ),
                            toDirection = skyDirection(
                                longitudeDeg = to.getDouble(0).toFloat(),
                                latitudeDeg = to.getDouble(1).toFloat(),
                            ),
                        )
                    }
                }
            }

            val constellations = lines
                .groupBy { it.constellationId }
                .mapNotNull { (id, segments) ->
                    if (id.isBlank() || segments.isEmpty()) return@mapNotNull null
                    var center = Vec3(0f, 0f, 0f)
                    for (segment in segments) {
                        center += segment.fromDirection
                        center += segment.toDirection
                    }
                    CatalogConstellation(
                        id = id,
                        rank = segments.minOf { it.rank },
                        centerDirection = center.normalized(),
                    )
                }
                .sortedWith(compareBy<CatalogConstellation> { it.rank }.thenBy { it.id })

            StarMapCatalog(
                stars = stars,
                lines = lines,
                constellations = constellations,
            ).also {
                DiagnosticLog.event(
                    "StarMapGl",
                    "catalog-loaded stars=${it.stars.size} lines=${it.lines.size} " +
                        "constellations=${it.constellations.size} " +
                        "ms=${SystemClock.elapsedRealtime() - started}",
                )
            }
        }.getOrElse { throwable ->
            DiagnosticLog.event("StarMapGl", "catalog-load-failed", throwable)
            StarMapCatalog(emptyList(), emptyList(), emptyList())
        }
    }

    private fun AssetManager.readUtf8Text(path: String): String =
        open(path).use { input ->
            BufferedReader(InputStreamReader(input, Charsets.UTF_8)).use { reader ->
                reader.readText()
            }
        }
}

private data class StarMapSceneRequest(
    val key: String,
    val primaryConstellationId: String,
    val targetOrientation: SkyQuaternion,
)

private data class ProjectedStar(
    val x: Float,
    val y: Float,
    val sizePx: Float,
    val brightness: Float,
    val seed: Float,
    val tint: Float,
    val twinkle: Float,
)

private data class ProjectedLine(
    val fromX: Float,
    val fromY: Float,
    val toX: Float,
    val toY: Float,
    val strength: Float,
)

private data class ProjectedStarMapFrame(
    val stars: List<ProjectedStar>,
    val ambientLines: List<ProjectedLine>,
    val fromPrimaryLines: List<ProjectedLine>,
    val toPrimaryLines: List<ProjectedLine>,
)

private object StarMapSceneSelector {
    private val fallbackConstellations = listOf(
        CatalogConstellation("Ori", 1, skyDirection(84f, 13f)),
        CatalogConstellation("Cas", 1, skyDirection(-6f, 55.5f)),
        CatalogConstellation("Cyg", 1, skyDirection(-52.5f, 50f)),
        CatalogConstellation("Sco", 1, skyDirection(-111f, -38f)),
        CatalogConstellation("UMa", 1, skyDirection(165f, 48f)),
        CatalogConstellation("Lyr", 1, skyDirection(-81f, 30f)),
    )

    fun select(key: String, catalog: StarMapCatalog): StarMapSceneRequest {
        val candidates = catalog.constellations.ifEmpty { fallbackConstellations }
        val hash = stableHash(key)
        val index = ((hash ushr 1) and Long.MAX_VALUE).rem(candidates.size).toInt()
        val constellation = candidates[index]
        return StarMapSceneRequest(
            key = key,
            primaryConstellationId = constellation.id,
            targetOrientation = SkyQuaternion.lookAt(constellation.centerDirection),
        )
    }

    private fun stableHash(value: String): Long {
        var hash = 0xCBF29CE484222325UL.toLong()
        val prime = 0x100000001B3UL.toLong()
        for (char in value) {
            hash = hash xor char.code.toLong()
            hash *= prime
        }
        return hash
    }
}

private class SkyProjector(
    cameraOrientation: SkyQuaternion,
    viewportAspect: Float,
) {
    private val right = cameraOrientation.rotate(Vec3(1f, 0f, 0f))
    private val up = cameraOrientation.rotate(Vec3(0f, 1f, 0f))
    private val back = cameraOrientation.rotate(Vec3(0f, 0f, 1f))
    private val verticalScale = tan((VerticalFieldOfViewDeg * 0.5f).toRadians())
    private val horizontalScale = verticalScale * viewportAspect.coerceAtLeast(0.30f)

    fun project(direction: Vec3): Pair<Float, Float>? {
        val frontDepth = -direction.dot(back)
        if (frontDepth <= MinimumProjectionCosine) return null

        val tangentX = direction.dot(right) / frontDepth
        val tangentY = direction.dot(up) / frontDepth
        return (tangentX / horizontalScale) to (tangentY / verticalScale)
    }
}

private object StarMapSceneProjector {
    fun project(
        cameraOrientation: SkyQuaternion,
        fromPrimaryConstellationId: String?,
        toPrimaryConstellationId: String,
        catalog: StarMapCatalog,
        width: Int,
        height: Int,
    ): ProjectedStarMapFrame {
        val projector = SkyProjector(
            cameraOrientation = cameraOrientation,
            viewportAspect = width.toFloat() / height.coerceAtLeast(1).toFloat(),
        )

        val stars = ArrayList<ProjectedStar>(MaxVisibleStars)
        for (star in catalog.stars) {
            if (!shouldShowStar(star)) continue
            val point = projector.project(star.direction) ?: continue
            val x = point.first
            val y = point.second
            if (abs(x) > StarViewportMargin || abs(y) > StarViewportMargin) continue

            val relativeLuminance =
                10f.pow(-0.4f * (star.magnitude - ReferenceMagnitude))
            val minLuminance =
                10f.pow(-0.4f * (CatalogMagnitudeLimit - ReferenceMagnitude))
            val maxLuminance =
                10f.pow(-0.4f * (BrightReferenceMagnitude - ReferenceMagnitude))
            val normalizedLuminance = (
                (relativeLuminance - minLuminance) /
                    (maxLuminance - minLuminance)
                ).coerceIn(0f, 1f)
            val emphasis = normalizedLuminance.pow(0.42f)

            stars += ProjectedStar(
                x = x,
                y = y,
                sizePx = (1.75f + emphasis * 10.3f) * 1.66f,
                brightness = 0.075f + emphasis * 0.925f,
                seed = stableFraction(star.id),
                tint = (0.78f - star.colorIndexBv * 0.34f).coerceIn(0.08f, 0.94f),
                twinkle = if (star.magnitude <= BrightStarTwinkleMagnitude) 1f else 0f,
            )
        }
        stars.sortByDescending { it.brightness }
        if (stars.size > MaxVisibleStars) {
            stars.subList(MaxVisibleStars, stars.size).clear()
        }

        val ambientLines = ArrayList<ProjectedLine>(MaxNeighborLineSegments)
        val fromPrimaryLines = ArrayList<ProjectedLine>(MaxPrimaryLineSegments)
        val toPrimaryLines = ArrayList<ProjectedLine>(MaxPrimaryLineSegments)
        for (segment in catalog.lines) {
            val isFromPrimary =
                fromPrimaryConstellationId != null &&
                    fromPrimaryConstellationId != toPrimaryConstellationId &&
                    segment.constellationId == fromPrimaryConstellationId
            val isToPrimary = segment.constellationId == toPrimaryConstellationId
            if (
                !isFromPrimary &&
                !isToPrimary &&
                segment.rank > NeighborConstellationMaxRank
            ) {
                continue
            }

            val from = projector.project(segment.fromDirection) ?: continue
            val to = projector.project(segment.toDirection) ?: continue
            if (!segmentMayTouchViewport(from.first, from.second, to.first, to.second)) {
                continue
            }

            val projected = ProjectedLine(
                fromX = from.first,
                fromY = from.second,
                toX = to.first,
                toY = to.second,
                strength = when (segment.rank) {
                    1 -> 1f
                    2 -> 0.78f
                    else -> 0.62f
                },
            )

            if (segment.rank <= NeighborConstellationMaxRank &&
                ambientLines.size < MaxNeighborLineSegments
            ) {
                ambientLines += projected
            }
            if (isFromPrimary && fromPrimaryLines.size < MaxPrimaryLineSegments) {
                fromPrimaryLines += projected
            }
            if (isToPrimary && toPrimaryLines.size < MaxPrimaryLineSegments) {
                toPrimaryLines += projected
            }
        }

        return ProjectedStarMapFrame(
            stars = stars,
            ambientLines = ambientLines,
            fromPrimaryLines = fromPrimaryLines,
            toPrimaryLines = toPrimaryLines,
        )
    }

    private fun shouldShowStar(star: CatalogStar): Boolean {
        if (star.magnitude <= AlwaysVisibleMagnitude) return true
        val fraction = stableFraction(star.id)
        return when {
            star.magnitude <= MidStarMagnitude -> fraction <= MidStarKeepFraction
            else -> fraction <= FaintStarKeepFraction
        }
    }

    private fun stableFraction(id: Int): Float {
        var value = id * 0x45D9F3B
        value = value xor (value ushr 16)
        value *= 0x45D9F3B
        value = value xor (value ushr 16)
        return (value and 0xFFFF).toFloat() / 65535f
    }

    private fun segmentMayTouchViewport(
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
    ): Boolean {
        val margin = LineViewportMargin
        if (
            x1 in -margin..margin &&
            y1 in -margin..margin
        ) {
            return true
        }
        if (
            x2 in -margin..margin &&
            y2 in -margin..margin
        ) {
            return true
        }

        val minX = minOf(x1, x2)
        val maxX = maxOf(x1, x2)
        val minY = minOf(y1, y2)
        val maxY = maxOf(y1, y2)
        return maxX >= -1f && minX <= 1f && maxY >= -1f && minY <= 1f
    }
}

private class StarMapRenderer(
    assetManager: AssetManager,
) {
    private val catalog = StarMapCatalogCache.get(assetManager)

    private var width = 1
    private var height = 1
    private var startMs = SystemClock.uptimeMillis()

    private var backgroundProgram = 0
    private var backgroundPositionLocation = -1
    private var backgroundResolutionLocation = -1
    private var backgroundAccentLocation = -1

    private var lineProgram = 0
    private var lineCenterLocation = -1
    private var lineNormalLocation = -1
    private var lineAlongLocation = -1
    private var lineSideLocation = -1
    private var lineStrengthLocation = -1
    private var lineViewportLocation = -1
    private var lineHalfWidthLocation = -1
    private var lineOffsetLocation = -1
    private var lineAccentLocation = -1
    private var lineAlphaLocation = -1

    private var pointProgram = 0
    private var pointPositionLocation = -1
    private var pointSeedLocation = -1
    private var pointSizeLocation = -1
    private var pointBrightnessLocation = -1
    private var pointTintLocation = -1
    private var pointTwinkleLocation = -1
    private var pointTimeLocation = -1
    private var pointOffsetLocation = -1
    private var pointAccentLocation = -1
    private var pointAlphaLocation = -1

    private var currentRequest: StarMapSceneRequest? = null
    private var fromPrimaryConstellationId: String? = null
    private var fromOrientation = SkyQuaternion.Identity
    private var toOrientation = SkyQuaternion.Identity
    private var transitionStartMs = 0L
    private var previousAccent = floatArrayOf(0.62f, 0.76f, 1.0f)
    private var currentAccent = previousAccent.copyOf()

    private val quadBuffer = floatArrayOf(
        -1f, -1f,
        1f, -1f,
        -1f, 1f,
        1f, 1f,
    ).toFloatBuffer()
    private val pointBuffer = allocateFloatBuffer(MaxVisibleStars * PointFloatsPerVertex)
    private val lineBuffer = allocateFloatBuffer(MaxLineSegments * 6 * LineFloatsPerVertex)

    fun onSurfaceCreated() {
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDisable(GLES20.GL_CULL_FACE)
        GLES20.glClearColor(0.016f, 0.031f, 0.07f, 1f)

        backgroundProgram = createProgram(StarMapFullscreenVertexShader, StarMapBackgroundFragmentShader)
        backgroundPositionLocation = GLES20.glGetAttribLocation(backgroundProgram, "aPosition")
        backgroundResolutionLocation = GLES20.glGetUniformLocation(backgroundProgram, "uResolution")
        backgroundAccentLocation = GLES20.glGetUniformLocation(backgroundProgram, "uAccent")

        lineProgram = createProgram(StarMapLineVertexShader, StarMapLineFragmentShader)
        lineCenterLocation = GLES20.glGetAttribLocation(lineProgram, "aCenter")
        lineNormalLocation = GLES20.glGetAttribLocation(lineProgram, "aNormal")
        lineAlongLocation = GLES20.glGetAttribLocation(lineProgram, "aAlong")
        lineSideLocation = GLES20.glGetAttribLocation(lineProgram, "aSide")
        lineStrengthLocation = GLES20.glGetAttribLocation(lineProgram, "aStrength")
        lineViewportLocation = GLES20.glGetUniformLocation(lineProgram, "uViewport")
        lineHalfWidthLocation = GLES20.glGetUniformLocation(lineProgram, "uHalfWidthPx")
        lineOffsetLocation = GLES20.glGetUniformLocation(lineProgram, "uOffset")
        lineAccentLocation = GLES20.glGetUniformLocation(lineProgram, "uAccent")
        lineAlphaLocation = GLES20.glGetUniformLocation(lineProgram, "uGlobalAlpha")

        pointProgram = createProgram(StarMapPointVertexShader, StarMapPointFragmentShader)
        pointPositionLocation = GLES20.glGetAttribLocation(pointProgram, "aPosition")
        pointSeedLocation = GLES20.glGetAttribLocation(pointProgram, "aSeed")
        pointSizeLocation = GLES20.glGetAttribLocation(pointProgram, "aSizePx")
        pointBrightnessLocation = GLES20.glGetAttribLocation(pointProgram, "aBrightness")
        pointTintLocation = GLES20.glGetAttribLocation(pointProgram, "aTint")
        pointTwinkleLocation = GLES20.glGetAttribLocation(pointProgram, "aTwinkle")
        pointTimeLocation = GLES20.glGetUniformLocation(pointProgram, "uTime")
        pointOffsetLocation = GLES20.glGetUniformLocation(pointProgram, "uOffset")
        pointAccentLocation = GLES20.glGetUniformLocation(pointProgram, "uAccent")
        pointAlphaLocation = GLES20.glGetUniformLocation(pointProgram, "uGlobalAlpha")

        startMs = SystemClock.uptimeMillis()
        DiagnosticLog.event(
            "StarMapGl",
            "surface-created vendor=${GLES20.glGetString(GLES20.GL_VENDOR)} " +
                "renderer=${GLES20.glGetString(GLES20.GL_RENDERER)}",
        )
    }

    fun onSurfaceChanged(newWidth: Int, newHeight: Int) {
        width = newWidth.coerceAtLeast(1)
        height = newHeight.coerceAtLeast(1)
    }

    fun updateScene(spec: StarMapSceneSpec) {
        val nextAccent = floatArrayOf(
            spec.accentR.coerceIn(0f, 1f),
            spec.accentG.coerceIn(0f, 1f),
            spec.accentB.coerceIn(0f, 1f),
        )

        if (currentRequest?.key == spec.key) {
            currentAccent = nextAccent
            return
        }

        val nowMs = SystemClock.uptimeMillis()
        val request = StarMapSceneSelector.select(spec.key, catalog)
        val previousRequest = currentRequest

        if (previousRequest == null) {
            currentRequest = request
            fromPrimaryConstellationId = request.primaryConstellationId
            fromOrientation = request.targetOrientation
            toOrientation = request.targetOrientation
            previousAccent = nextAccent.copyOf()
            currentAccent = nextAccent
            transitionStartMs = nowMs - StarMapTransitionMs
            DiagnosticLog.event(
                "StarMapGl",
                "scene key=${request.key.takeLast(12)} primary=${request.primaryConstellationId} " +
                    "mode=spherical-camera",
            )
            return
        }

        fromOrientation = currentOrientationAt(nowMs)
        toOrientation = request.targetOrientation
        fromPrimaryConstellationId = previousRequest.primaryConstellationId
        currentRequest = request
        previousAccent = currentAccent
        currentAccent = nextAccent
        transitionStartMs = nowMs

        DiagnosticLog.event(
            "StarMapGl",
            "camera-move key=${request.key.takeLast(12)} " +
                "from=${fromPrimaryConstellationId} to=${request.primaryConstellationId} " +
                "mode=quaternion-slerp",
        )
    }

    fun render() {
        val request = currentRequest ?: return
        val nowMs = SystemClock.uptimeMillis()
        val timeSeconds = (nowMs - startMs) / 1000f
        val rawProgress =
            ((nowMs - transitionStartMs).toFloat() / StarMapTransitionMs).coerceIn(0f, 1f)
        val progress = smoothStep(rawProgress)
        val orientation = SkyQuaternion.slerp(fromOrientation, toOrientation, progress)

        val frame = StarMapSceneProjector.project(
            cameraOrientation = orientation,
            fromPrimaryConstellationId = fromPrimaryConstellationId,
            toPrimaryConstellationId = request.primaryConstellationId,
            catalog = catalog,
            width = width,
            height = height,
        )

        val accent = floatArrayOf(
            lerp(previousAccent[0], currentAccent[0], progress),
            lerp(previousAccent[1], currentAccent[1], progress),
            lerp(previousAccent[2], currentAccent[2], progress),
        )

        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glUseProgram(backgroundProgram)
        GLES20.glUniform2f(backgroundResolutionLocation, width.toFloat(), height.toFloat())
        GLES20.glUniform3f(backgroundAccentLocation, accent[0], accent[1], accent[2])
        drawQuad(backgroundPositionLocation)

        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)

        drawLines(
            lines = frame.ambientLines,
            offsetX = 0f,
            alpha = 1f,
            accent = NeighborLineColor,
            haloAlpha = NeighborLineHaloAlpha,
            coreAlpha = NeighborLineCoreAlpha,
            haloWidthPx = 2.2f,
            coreWidthPx = 0.62f,
        )

        val samePrimary = fromPrimaryConstellationId == request.primaryConstellationId
        val oldHighlightAlpha = if (rawProgress >= 1f || samePrimary) {
            0f
        } else {
            1f - smoothStepRange(rawProgress, 0f, 0.42f)
        }
        val newHighlightAlpha = if (rawProgress >= 1f || samePrimary) {
            1f
        } else {
            smoothStepRange(rawProgress, 0.58f, 1f)
        }

        if (oldHighlightAlpha > 0.005f) {
            drawLines(
                lines = frame.fromPrimaryLines,
                offsetX = 0f,
                alpha = oldHighlightAlpha,
                accent = previousAccent,
                haloAlpha = PrimaryLineHaloAlpha,
                coreAlpha = PrimaryLineCoreAlpha,
                haloWidthPx = 3.0f,
                coreWidthPx = 0.86f,
            )
        }
        if (newHighlightAlpha > 0.005f) {
            drawLines(
                lines = frame.toPrimaryLines,
                offsetX = 0f,
                alpha = newHighlightAlpha,
                accent = currentAccent,
                haloAlpha = PrimaryLineHaloAlpha,
                coreAlpha = PrimaryLineCoreAlpha,
                haloWidthPx = 3.0f,
                coreWidthPx = 0.86f,
            )
        }

        drawPoints(
            stars = frame.stars,
            offsetX = 0f,
            alpha = 1f,
            timeSeconds = timeSeconds,
            accent = accent,
        )

        GLES20.glDisable(GLES20.GL_BLEND)
    }

    private fun currentOrientationAt(nowMs: Long): SkyQuaternion {
        val rawProgress =
            ((nowMs - transitionStartMs).toFloat() / StarMapTransitionMs).coerceIn(0f, 1f)
        return SkyQuaternion.slerp(fromOrientation, toOrientation, smoothStep(rawProgress))
    }

    fun release() {
        if (backgroundProgram != 0) GLES20.glDeleteProgram(backgroundProgram)
        if (lineProgram != 0) GLES20.glDeleteProgram(lineProgram)
        if (pointProgram != 0) GLES20.glDeleteProgram(pointProgram)
        backgroundProgram = 0
        lineProgram = 0
        pointProgram = 0
    }

    private fun drawLines(
        lines: List<ProjectedLine>,
        offsetX: Float,
        alpha: Float,
        accent: FloatArray,
        haloAlpha: Float,
        coreAlpha: Float,
        haloWidthPx: Float,
        coreWidthPx: Float,
    ) {
        val vertexCount = buildLineBuffer(lines)
        if (vertexCount <= 0) return

        GLES20.glUseProgram(lineProgram)
        GLES20.glUniform2f(lineViewportLocation, width.toFloat(), height.toFloat())
        GLES20.glUniform2f(lineOffsetLocation, offsetX, 0f)
        GLES20.glUniform3f(lineAccentLocation, accent[0], accent[1], accent[2])
        bindLineAttributes()

        GLES20.glUniform1f(lineHalfWidthLocation, haloWidthPx)
        GLES20.glUniform1f(lineAlphaLocation, alpha * haloAlpha)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, vertexCount)

        GLES20.glUniform1f(lineHalfWidthLocation, coreWidthPx)
        GLES20.glUniform1f(lineAlphaLocation, alpha * coreAlpha)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, vertexCount)

        disableLineAttributes()
    }

    private fun buildLineBuffer(lines: List<ProjectedLine>): Int {
        lineBuffer.clear()
        var vertexCount = 0
        for (line in lines.take(MaxLineSegments)) {
            val dxPx = (line.toX - line.fromX) * width * 0.5f
            val dyPx = (line.toY - line.fromY) * height * 0.5f
            val lengthPx = sqrt(dxPx * dxPx + dyPx * dyPx)
            if (lengthPx < 0.5f) continue

            val normalX = -dyPx / lengthPx
            val normalY = dxPx / lengthPx

            putLineVertex(
                line.fromX,
                line.fromY,
                normalX,
                normalY,
                0f,
                -1f,
                line.strength,
            )
            putLineVertex(
                line.fromX,
                line.fromY,
                normalX,
                normalY,
                0f,
                1f,
                line.strength,
            )
            putLineVertex(
                line.toX,
                line.toY,
                normalX,
                normalY,
                1f,
                1f,
                line.strength,
            )

            putLineVertex(
                line.fromX,
                line.fromY,
                normalX,
                normalY,
                0f,
                -1f,
                line.strength,
            )
            putLineVertex(
                line.toX,
                line.toY,
                normalX,
                normalY,
                1f,
                1f,
                line.strength,
            )
            putLineVertex(
                line.toX,
                line.toY,
                normalX,
                normalY,
                1f,
                -1f,
                line.strength,
            )
            vertexCount += 6
        }
        lineBuffer.position(0)
        return vertexCount
    }

    private fun putLineVertex(
        centerX: Float,
        centerY: Float,
        normalX: Float,
        normalY: Float,
        along: Float,
        side: Float,
        strength: Float,
    ) {
        lineBuffer.put(centerX)
        lineBuffer.put(centerY)
        lineBuffer.put(normalX)
        lineBuffer.put(normalY)
        lineBuffer.put(along)
        lineBuffer.put(side)
        lineBuffer.put(strength)
    }

    private fun bindLineAttributes() {
        val stride = LineFloatsPerVertex * FloatBytes

        lineBuffer.position(0)
        GLES20.glEnableVertexAttribArray(lineCenterLocation)
        GLES20.glVertexAttribPointer(
            lineCenterLocation,
            2,
            GLES20.GL_FLOAT,
            false,
            stride,
            lineBuffer,
        )

        lineBuffer.position(2)
        GLES20.glEnableVertexAttribArray(lineNormalLocation)
        GLES20.glVertexAttribPointer(
            lineNormalLocation,
            2,
            GLES20.GL_FLOAT,
            false,
            stride,
            lineBuffer,
        )

        lineBuffer.position(4)
        GLES20.glEnableVertexAttribArray(lineAlongLocation)
        GLES20.glVertexAttribPointer(
            lineAlongLocation,
            1,
            GLES20.GL_FLOAT,
            false,
            stride,
            lineBuffer,
        )

        lineBuffer.position(5)
        GLES20.glEnableVertexAttribArray(lineSideLocation)
        GLES20.glVertexAttribPointer(
            lineSideLocation,
            1,
            GLES20.GL_FLOAT,
            false,
            stride,
            lineBuffer,
        )

        lineBuffer.position(6)
        GLES20.glEnableVertexAttribArray(lineStrengthLocation)
        GLES20.glVertexAttribPointer(
            lineStrengthLocation,
            1,
            GLES20.GL_FLOAT,
            false,
            stride,
            lineBuffer,
        )
    }

    private fun disableLineAttributes() {
        GLES20.glDisableVertexAttribArray(lineCenterLocation)
        GLES20.glDisableVertexAttribArray(lineNormalLocation)
        GLES20.glDisableVertexAttribArray(lineAlongLocation)
        GLES20.glDisableVertexAttribArray(lineSideLocation)
        GLES20.glDisableVertexAttribArray(lineStrengthLocation)
    }

    private fun drawPoints(
        stars: List<ProjectedStar>,
        offsetX: Float,
        alpha: Float,
        timeSeconds: Float,
        accent: FloatArray,
    ) {
        pointBuffer.clear()
        for (star in stars.take(MaxVisibleStars)) {
            pointBuffer.put(star.x)
            pointBuffer.put(star.y)
            pointBuffer.put(star.seed)
            pointBuffer.put(star.sizePx)
            pointBuffer.put(star.brightness)
            pointBuffer.put(star.tint)
            pointBuffer.put(star.twinkle)
        }
        pointBuffer.position(0)

        GLES20.glUseProgram(pointProgram)
        GLES20.glUniform1f(pointTimeLocation, timeSeconds)
        GLES20.glUniform2f(pointOffsetLocation, offsetX, 0f)
        GLES20.glUniform3f(pointAccentLocation, accent[0], accent[1], accent[2])
        GLES20.glUniform1f(pointAlphaLocation, alpha)

        val stride = PointFloatsPerVertex * FloatBytes

        pointBuffer.position(0)
        GLES20.glEnableVertexAttribArray(pointPositionLocation)
        GLES20.glVertexAttribPointer(
            pointPositionLocation,
            2,
            GLES20.GL_FLOAT,
            false,
            stride,
            pointBuffer,
        )

        pointBuffer.position(2)
        GLES20.glEnableVertexAttribArray(pointSeedLocation)
        GLES20.glVertexAttribPointer(
            pointSeedLocation,
            1,
            GLES20.GL_FLOAT,
            false,
            stride,
            pointBuffer,
        )

        pointBuffer.position(3)
        GLES20.glEnableVertexAttribArray(pointSizeLocation)
        GLES20.glVertexAttribPointer(
            pointSizeLocation,
            1,
            GLES20.GL_FLOAT,
            false,
            stride,
            pointBuffer,
        )

        pointBuffer.position(4)
        GLES20.glEnableVertexAttribArray(pointBrightnessLocation)
        GLES20.glVertexAttribPointer(
            pointBrightnessLocation,
            1,
            GLES20.GL_FLOAT,
            false,
            stride,
            pointBuffer,
        )

        pointBuffer.position(5)
        GLES20.glEnableVertexAttribArray(pointTintLocation)
        GLES20.glVertexAttribPointer(
            pointTintLocation,
            1,
            GLES20.GL_FLOAT,
            false,
            stride,
            pointBuffer,
        )

        pointBuffer.position(6)
        GLES20.glEnableVertexAttribArray(pointTwinkleLocation)
        GLES20.glVertexAttribPointer(
            pointTwinkleLocation,
            1,
            GLES20.GL_FLOAT,
            false,
            stride,
            pointBuffer,
        )

        GLES20.glDrawArrays(GLES20.GL_POINTS, 0, stars.size.coerceAtMost(MaxVisibleStars))

        GLES20.glDisableVertexAttribArray(pointPositionLocation)
        GLES20.glDisableVertexAttribArray(pointSeedLocation)
        GLES20.glDisableVertexAttribArray(pointSizeLocation)
        GLES20.glDisableVertexAttribArray(pointBrightnessLocation)
        GLES20.glDisableVertexAttribArray(pointTintLocation)
        GLES20.glDisableVertexAttribArray(pointTwinkleLocation)
    }

    private fun drawQuad(positionLocation: Int) {
        quadBuffer.position(0)
        GLES20.glEnableVertexAttribArray(positionLocation)
        GLES20.glVertexAttribPointer(
            positionLocation,
            2,
            GLES20.GL_FLOAT,
            false,
            2 * FloatBytes,
            quadBuffer,
        )
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(positionLocation)
    }

    private fun createProgram(vertexSource: String, fragmentSource: String): Int {
        val vertex = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource)
        val fragment = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        val nextProgram = GLES20.glCreateProgram()
        GLES20.glAttachShader(nextProgram, vertex)
        GLES20.glAttachShader(nextProgram, fragment)
        GLES20.glLinkProgram(nextProgram)

        val status = IntArray(1)
        GLES20.glGetProgramiv(nextProgram, GLES20.GL_LINK_STATUS, status, 0)
        val log = GLES20.glGetProgramInfoLog(nextProgram)
        GLES20.glDeleteShader(vertex)
        GLES20.glDeleteShader(fragment)
        check(status[0] == GLES20.GL_TRUE) { "Star-map GL program link failed: $log" }
        return nextProgram
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        val log = GLES20.glGetShaderInfoLog(shader)
        check(status[0] == GLES20.GL_TRUE) { "Star-map GL shader compile failed: $log" }
        return shader
    }

    private fun FloatArray.toFloatBuffer(): FloatBuffer =
        ByteBuffer.allocateDirect(size * FloatBytes)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(this@toFloatBuffer)
                position(0)
            }
}

private fun allocateFloatBuffer(floatCount: Int): FloatBuffer =
    ByteBuffer.allocateDirect(floatCount * FloatBytes)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()

private fun Float.toRadians(): Float = this * (PI.toFloat() / 180f)

private fun skyDirection(longitudeDeg: Float, latitudeDeg: Float): Vec3 {
    val longitude = longitudeDeg.toRadians()
    val latitude = latitudeDeg.toRadians()
    val cosLatitude = cos(latitude)
    return Vec3(
        x = cosLatitude * cos(longitude),
        y = sin(latitude),
        z = cosLatitude * sin(longitude),
    )
}

private fun lerp(start: Float, end: Float, amount: Float): Float =
    start + (end - start) * amount

private fun smoothStep(value: Float): Float {
    val t = value.coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

private fun smoothStepRange(value: Float, start: Float, end: Float): Float {
    if (end <= start) return if (value >= end) 1f else 0f
    return smoothStep((value - start) / (end - start))
}

private val NeighborLineColor = floatArrayOf(0.58f, 0.68f, 0.80f)

private const val StarMapFrameIntervalMs = 50L
private const val StarMapTransitionMs = 1300L

private const val VerticalFieldOfViewDeg = 76f
private const val MinimumProjectionCosine = 0.15f
private const val StarViewportMargin = 1.06f
private const val LineViewportMargin = 1.16f

private const val CatalogMagnitudeLimit = 5.65f
private const val AlwaysVisibleMagnitude = 3.90f
private const val MidStarMagnitude = 4.80f
private const val MidStarKeepFraction = 0.52f
private const val FaintStarKeepFraction = 0.18f
private const val ReferenceMagnitude = 1.0f
private const val BrightReferenceMagnitude = -1.0f
private const val BrightStarTwinkleMagnitude = 1.0f

private const val NeighborConstellationMaxRank = 2
private const val PrimaryLineHaloAlpha = 0.075f
private const val PrimaryLineCoreAlpha = 0.38f
private const val NeighborLineHaloAlpha = 0.014f
private const val NeighborLineCoreAlpha = 0.082f

private const val MaxVisibleStars = 118
private const val MaxPrimaryLineSegments = 80
private const val MaxNeighborLineSegments = 180
private const val MaxLineSegments = 180
private const val PointFloatsPerVertex = 7
private const val LineFloatsPerVertex = 7
private const val FloatBytes = 4
private const val EglOpenGlEs2Bit = 4
private const val EglContextClientVersion = 0x3098

private const val StarMapFullscreenVertexShader = """
attribute vec2 aPosition;

void main() {
    gl_Position = vec4(aPosition, 0.0, 1.0);
}
"""

private const val StarMapBackgroundFragmentShader = """
precision mediump float;

uniform vec2 uResolution;
uniform vec3 uAccent;

void main() {
    vec2 uv = gl_FragCoord.xy / uResolution.xy;
    vec2 centered = uv - 0.5;
    centered.x *= uResolution.x / uResolution.y;

    vec3 bottom = vec3(0.014, 0.027, 0.052);
    vec3 top = vec3(0.002, 0.005, 0.014);
    vec3 col = mix(bottom, top, smoothstep(0.0, 1.0, uv.y));

    // The album colour is atmosphere only; the sky itself stays recognisably neutral.
    vec3 restrainedAccent = mix(vec3(0.17, 0.28, 0.48), uAccent, 0.14);
    vec2 glowCenter = vec2(-0.16, 0.12);
    float glow = exp(-dot(centered - glowCenter, centered - glowCenter) * 5.8);
    col += restrainedAccent * glow * 0.022;

    float edge = smoothstep(0.38, 0.98, length(centered * vec2(1.18, 0.90)));
    col *= mix(1.0, 0.72, edge);

    gl_FragColor = vec4(col, 1.0);
}
"""

private const val StarMapLineVertexShader = """
attribute vec2 aCenter;
attribute vec2 aNormal;
attribute float aAlong;
attribute float aSide;
attribute float aStrength;

uniform vec2 uViewport;
uniform float uHalfWidthPx;
uniform vec2 uOffset;

varying float vAlong;
varying float vSide;
varying float vStrength;

void main() {
    vec2 pixelToNdc = vec2(2.0 / uViewport.x, 2.0 / uViewport.y);
    vec2 offset = aNormal * pixelToNdc * uHalfWidthPx * aSide;
    gl_Position = vec4(aCenter + uOffset + offset, 0.0, 1.0);
    vAlong = aAlong;
    vSide = aSide;
    vStrength = aStrength;
}
"""

private const val StarMapLineFragmentShader = """
precision mediump float;

uniform vec3 uAccent;
uniform float uGlobalAlpha;

varying float vAlong;
varying float vSide;
varying float vStrength;

void main() {
    float endFade =
        smoothstep(0.00, 0.055, vAlong) *
        (1.0 - smoothstep(0.945, 1.0, vAlong));
    float sideFade = 1.0 - smoothstep(0.24, 1.0, abs(vSide));

    vec3 neutral = vec3(0.72, 0.80, 0.91);
    vec3 restrainedAccent = mix(neutral, uAccent, 0.24);
    float alpha = endFade * sideFade * mix(0.82, 1.0, vStrength) * uGlobalAlpha;

    gl_FragColor = vec4(restrainedAccent, alpha);
}
"""

private const val StarMapPointVertexShader = """
attribute vec2 aPosition;
attribute float aSeed;
attribute float aSizePx;
attribute float aBrightness;
attribute float aTint;
attribute float aTwinkle;

uniform float uTime;
uniform vec2 uOffset;

varying float vBrightness;
varying float vTint;
varying float vTwinkleAmount;

void main() {
    float phase = aSeed * 21.71;
    float waveA = sin(uTime * (1.10 + aSeed * 0.80) + phase);
    float waveB = sin(uTime * (1.82 + aSeed * 0.64) + phase * 1.63);
    float shimmer = (waveA * 0.65 + waveB * 0.35) * 0.5 + 0.5;

    float twinkleScale = mix(1.0, mix(0.96, 1.055, shimmer), aTwinkle);
    vBrightness = aBrightness * twinkleScale;
    vTint = aTint;
    vTwinkleAmount = aTwinkle * shimmer;

    gl_Position = vec4(aPosition + uOffset, 0.0, 1.0);
    gl_PointSize = aSizePx * mix(1.0, 1.055, aTwinkle * shimmer);
}
"""

private const val StarMapPointFragmentShader = """
precision mediump float;

uniform vec3 uAccent;
uniform float uGlobalAlpha;

varying float vBrightness;
varying float vTint;
varying float vTwinkleAmount;

void main() {
    vec2 p = gl_PointCoord - 0.5;
    float d = length(p);
    if (d >= 0.5) discard;

    float core = 1.0 - smoothstep(0.02, 0.17, d);
    float halo = 1.0 - smoothstep(0.10, 0.50, d);

    vec3 warm = vec3(1.00, 0.88, 0.72);
    vec3 cool = vec3(0.75, 0.88, 1.00);
    vec3 starColor = mix(warm, cool, vTint);
    vec3 color = mix(starColor, uAccent, 0.025);

    float sparkle = exp(-abs(p.x) * 32.0) + exp(-abs(p.y) * 32.0);
    sparkle *= vTwinkleAmount * 0.035;

    // Do not multiply RGB by vBrightness again. With standard alpha blending that
    // effectively squares the perceived brightness and makes faint catalogue stars vanish.
    color *= (0.68 + core * 0.48);
    color += vec3(0.84, 0.92, 1.0) * sparkle;

    float visibility = clamp((0.20 + vBrightness * 0.80) * 1.66, 0.0, 1.0);
    float alpha = (core * 0.96 + halo * 0.40 + sparkle) * visibility * uGlobalAlpha;
    gl_FragColor = vec4(color, clamp(alpha, 0.0, 1.0));
}
"""
