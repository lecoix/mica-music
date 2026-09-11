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
import kotlin.math.ln
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
                DiagnosticLog.important("StarMapGl", "egl-init-failed")
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

                val transitionActive = renderer.render()
                if (!EGL14.eglSwapBuffers(eglDisplay, eglSurface)) {
                    DiagnosticLog.important(
                        "StarMapGl",
                        "egl-swap-failed error=${EGL14.eglGetError()}",
                    )
                    break
                }

                nextFrameAt += if (transitionActive) {
                    StarMapMovingFrameIntervalMs
                } else {
                    StarMapIdleFrameIntervalMs
                }
                val sleepMs = nextFrameAt - SystemClock.uptimeMillis()
                if (sleepMs > 1L) {
                    runCatching { sleep(sleepMs) }
                } else {
                    nextFrameAt = SystemClock.uptimeMillis()
                }
            }
        } catch (throwable: Throwable) {
            DiagnosticLog.important("StarMapGl", "renderer-stopped", throwable)
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
            }
        }.getOrElse { throwable ->
            DiagnosticLog.important("StarMapGl", "catalog-load-failed", throwable)
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
    val seasonalDistanceDeg: Float,
    val featuredSolarBodyId: SolarSystemBodyId?,
)

private data class ProjectedStar(
    val x: Float,
    val y: Float,
    val sizePx: Float,
    val brightness: Float,
    val seed: Float,
    val tint: Float,
    val twinkle: Float,
    val isPrimarySkeleton: Boolean,
)

private data class ProjectedLine(
    val fromX: Float,
    val fromY: Float,
    val toX: Float,
    val toY: Float,
    val strength: Float,
)

private data class ProjectedSolarBody(
    val id: SolarSystemBodyId,
    val x: Float,
    val y: Float,
    val sizePx: Float,
    val red: Float,
    val green: Float,
    val blue: Float,
    val haloStrength: Float,
    val moonFlag: Float,
    val moonLightX: Float,
    val moonLightY: Float,
    val moonLightZ: Float,
)

private data class ProjectedStarMapFrame(
    val stars: List<ProjectedStar>,
    val ambientLines: List<ProjectedLine>,
    val fromPrimaryLines: List<ProjectedLine>,
    val toPrimaryLines: List<ProjectedLine>,
)

private data class StarMapProjectionCandidates(
    val stars: List<CatalogStar>,
    val lines: List<CatalogLineSegment>,
    val primarySkeletonDirections: Set<Vec3>,
    val neighborSkeletonDirections: Set<Vec3>,
)

private object StarMapSceneSelector {
    private data class WeightedCandidate(
        val constellation: CatalogConstellation,
        val seasonalDistanceDeg: Float,
        val rendezvousScore: Double,
    )

    private data class WeightedSolarCandidate(
        val body: SolarSystemBodyState,
        val direction: Vec3,
        val seasonalDistanceDeg: Float,
        val rendezvousScore: Double,
    )

    private val fallbackConstellations = listOf(
        CatalogConstellation("Ori", 1, skyDirection(84f, 13f)),
        CatalogConstellation("Cas", 1, skyDirection(-6f, 55.5f)),
        CatalogConstellation("Cyg", 1, skyDirection(-52.5f, 50f)),
        CatalogConstellation("Sco", 1, skyDirection(-111f, -38f)),
        CatalogConstellation("UMa", 1, skyDirection(165f, 48f)),
        CatalogConstellation("Lyr", 1, skyDirection(-81f, 30f)),
    )

    fun select(
        key: String,
        catalog: StarMapCatalog,
        epochMillis: Long,
        solarBodies: List<SolarSystemBodyState>,
        previousFeaturedSolarBodyId: SolarSystemBodyId?,
    ): StarMapSceneRequest {
        val candidates = catalog.constellations.ifEmpty { fallbackConstellations }
        val seasonalNight = SeasonalSkyModel.nightDirection(epochMillis)
        val nightDirection = Vec3(
            seasonalNight.x,
            seasonalNight.y,
            seasonalNight.z,
        ).normalized()

        val constellationScene = selectConstellationScene(
            key = key,
            candidates = candidates,
            nightDirection = nightDirection,
        )

        val shouldUseSolarScene =
            solarBodies.isNotEmpty() &&
                stableUnit("$key|scene-kind-v1") < SolarSceneChance
        if (!shouldUseSolarScene) return constellationScene

        val rankedSolar = solarBodies
            .map { body ->
                val direction = solarDirection(body)
                val seasonalDistanceDeg = angularDistanceDeg(direction, nightDirection)
                val nightAffinity = ((direction.dot(nightDirection) + 1f) * 0.5f)
                    .coerceIn(0f, 1f)
                val seasonalFactor = lerp(
                    SolarDaySideMinFactor,
                    1f,
                    nightAffinity,
                )
                val weight = (
                    solarBodyWeight(body.id) * seasonalFactor
                    ).coerceAtLeast(0.0001f)
                WeightedSolarCandidate(
                    body = body,
                    direction = direction,
                    seasonalDistanceDeg = seasonalDistanceDeg,
                    rendezvousScore = -ln(
                        stableUnit("$key|${body.id.name}|solar-v1"),
                    ) / weight,
                )
            }
            .sortedBy { it.rendezvousScore }

        var selected = rankedSolar.firstOrNull() ?: return constellationScene
        if (
            selected.body.id == previousFeaturedSolarBodyId &&
            rankedSolar.size > 1
        ) {
            val alternative = rankedSolar[1]
            if (
                alternative.rendezvousScore <=
                selected.rendezvousScore * SolarRepeatAlternativeRatio
            ) {
                selected = alternative
            }
        }

        val contextConstellation = candidates.minByOrNull {
            angularDistanceDeg(it.centerDirection, selected.direction)
        } ?: candidates.first()
        val targetDirection = compositionDirection(
            center = selected.direction,
            hash = stableHash("$key|${selected.body.id.name}|solar-composition-v1"),
            eastLimitDeg = SolarCompositionEastOffsetDeg,
            northLimitDeg = SolarCompositionNorthOffsetDeg,
        )

        return StarMapSceneRequest(
            key = key,
            primaryConstellationId = contextConstellation.id,
            targetOrientation = SkyQuaternion.lookAt(targetDirection),
            seasonalDistanceDeg = selected.seasonalDistanceDeg,
            featuredSolarBodyId = selected.body.id,
        )
    }

    private fun selectConstellationScene(
        key: String,
        candidates: List<CatalogConstellation>,
        nightDirection: Vec3,
    ): StarMapSceneRequest {
        val selected = candidates
            .mapNotNull { constellation ->
                val cosine = constellation.centerDirection
                    .dot(nightDirection)
                    .coerceIn(-1f, 1f)
                if (cosine <= 0f) return@mapNotNull null

                val distanceDeg = (
                    acos(cosine) * 180f / PI.toFloat()
                    ).coerceIn(0f, SeasonalCandidateLimitDeg)
                val seasonalWeight = seasonalWeight(distanceDeg)
                if (seasonalWeight <= 0.0001f) return@mapNotNull null

                val rankWeight = when (constellation.rank) {
                    1 -> 1.00f
                    2 -> 0.88f
                    else -> 0.72f
                }
                val weight = (seasonalWeight * rankWeight).coerceAtLeast(0.0001f)
                val randomUnit = stableUnit("$key|${constellation.id}|seasonal-v1")
                WeightedCandidate(
                    constellation = constellation,
                    seasonalDistanceDeg = distanceDeg,
                    rendezvousScore = -ln(randomUnit) / weight,
                )
            }
            .minByOrNull { it.rendezvousScore }
            ?: WeightedCandidate(
                constellation = candidates.first(),
                seasonalDistanceDeg = 0f,
                rendezvousScore = 0.0,
            )

        val targetDirection = compositionDirection(
            center = selected.constellation.centerDirection,
            hash = stableHash("$key|${selected.constellation.id}|composition-v1"),
        )
        return StarMapSceneRequest(
            key = key,
            primaryConstellationId = selected.constellation.id,
            targetOrientation = SkyQuaternion.lookAt(targetDirection),
            seasonalDistanceDeg = selected.seasonalDistanceDeg,
            featuredSolarBodyId = null,
        )
    }

    private fun solarBodyWeight(id: SolarSystemBodyId): Float = when (id) {
        SolarSystemBodyId.MOON -> 1.00f
        SolarSystemBodyId.VENUS -> 0.95f
        SolarSystemBodyId.JUPITER -> 0.90f
        SolarSystemBodyId.MARS -> 0.72f
        SolarSystemBodyId.SATURN -> 0.68f
        SolarSystemBodyId.MERCURY -> 0.45f
        SolarSystemBodyId.URANUS -> 0.25f
        SolarSystemBodyId.NEPTUNE -> 0.22f
        SolarSystemBodyId.SUN -> 0.18f
    }

    private fun solarDirection(body: SolarSystemBodyState): Vec3 = Vec3(
        body.direction.x,
        body.direction.y,
        body.direction.z,
    ).normalized()

    private fun angularDistanceDeg(first: Vec3, second: Vec3): Float =
        acos(first.dot(second).coerceIn(-1f, 1f)) * 180f / PI.toFloat()

    private fun seasonalWeight(distanceDeg: Float): Float = when {
        distanceDeg <= SeasonalStrongRegionDeg -> {
            lerp(1f, 0.72f, distanceDeg / SeasonalStrongRegionDeg)
        }
        distanceDeg < SeasonalCandidateLimitDeg -> {
            0.72f * (1f - smoothStepRange(
                distanceDeg,
                SeasonalStrongRegionDeg,
                SeasonalCandidateLimitDeg,
            ))
        }
        else -> 0f
    }

    private fun compositionDirection(
        center: Vec3,
        hash: Long,
        eastLimitDeg: Float = CompositionEastOffsetDeg,
        northLimitDeg: Float = CompositionNorthOffsetDeg,
    ): Vec3 {
        val celestialNorth = Vec3(0f, 1f, 0f)
        var east = celestialNorth.cross(center)
        if (east.dot(east) < 1e-5f) {
            east = Vec3(1f, 0f, 0f).cross(center)
        }
        east = east.normalized()
        val north = center.cross(east).normalized()

        val eastOffset = signedUnit(hash xor 0x4F1BBCDCL) * eastLimitDeg
        val northOffset = signedUnit(hash xor 0x2A9D7E13L) * northLimitDeg
        return (
            center +
                east * tan(eastOffset.toRadians()) +
                north * tan(northOffset.toRadians())
            ).normalized()
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

    private fun stableUnit(value: String): Double {
        val bits = stableHash(value).ushr(11)
        return (bits.toDouble() + 1.0) / (StableUnitDenominator + 1.0)
    }

    private fun signedUnit(value: Long): Float {
        val bits = (value ushr 40) and 0xFFFFFFL
        return bits.toFloat() / 0x7FFFFF - 1f
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

    fun localLightVector(
        surfaceDirection: Vec3,
        lightDirection: Vec3,
    ): Vec3 {
        val viewDirection = surfaceDirection.normalized() * -1f
        var localRight =
            right + viewDirection * (-right.dot(viewDirection))
        if (localRight.dot(localRight) < 1e-5f) {
            localRight = up + viewDirection * (-up.dot(viewDirection))
        }
        localRight = localRight.normalized()
        val localUp = viewDirection.cross(localRight).normalized()
        val normalizedLight = lightDirection.normalized()
        return Vec3(
            x = normalizedLight.dot(localRight),
            y = normalizedLight.dot(localUp),
            z = normalizedLight.dot(viewDirection),
        ).normalized()
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
        candidates: StarMapProjectionCandidates? = null,
    ): ProjectedStarMapFrame {
        val projector = SkyProjector(
            cameraOrientation = cameraOrientation,
            viewportAspect = width.toFloat() / height.coerceAtLeast(1).toFloat(),
        )

        val sourceStars = candidates?.stars ?: catalog.stars
        val sourceLines = candidates?.lines ?: catalog.lines
        val primarySkeletonDirections: Set<Vec3>
        val neighborSkeletonDirections: Set<Vec3>
        if (candidates != null) {
            primarySkeletonDirections = candidates.primarySkeletonDirections
            neighborSkeletonDirections = candidates.neighborSkeletonDirections
        } else {
            val primary = HashSet<Vec3>()
            val neighbor = HashSet<Vec3>()
            collectSkeletonDirections(
                lines = sourceLines,
                fromPrimaryConstellationId = fromPrimaryConstellationId,
                toPrimaryConstellationId = toPrimaryConstellationId,
                primary = primary,
                neighbor = neighbor,
            )
            primarySkeletonDirections = primary
            neighborSkeletonDirections = neighbor
        }

        val stars = ArrayList<ProjectedStar>(MaxVisibleStars)
        for (star in sourceStars) {
            val isPrimarySkeleton = star.direction in primarySkeletonDirections
            val isNeighborSkeleton = star.direction in neighborSkeletonDirections
            if (!shouldShowStar(star, isPrimarySkeleton, isNeighborSkeleton)) continue
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
                sizePx = (1.75f + emphasis * 10.3f) * 2.7556f,
                brightness = 0.075f + emphasis * 0.925f,
                seed = stableFraction(star.id),
                tint = (0.78f - star.colorIndexBv * 0.34f).coerceIn(0.08f, 0.94f),
                twinkle = if (star.magnitude <= BrightStarTwinkleMagnitude) 1f else 0f,
                isPrimarySkeleton = isPrimarySkeleton,
            )
        }
        stars.sortWith(
            compareByDescending<ProjectedStar> { it.isPrimarySkeleton }
                .thenByDescending { it.brightness },
        )
        if (stars.size > MaxVisibleStars) {
            stars.subList(MaxVisibleStars, stars.size).clear()
        }

        val ambientLines = ArrayList<ProjectedLine>(MaxNeighborLineSegments)
        val fromPrimaryLines = ArrayList<ProjectedLine>(MaxPrimaryLineSegments)
        val toPrimaryLines = ArrayList<ProjectedLine>(MaxPrimaryLineSegments)
        for (segment in sourceLines) {
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

    fun buildTransitionCandidates(
        fromOrientation: SkyQuaternion,
        toOrientation: SkyQuaternion,
        fromPrimaryConstellationId: String?,
        toPrimaryConstellationId: String,
        catalog: StarMapCatalog,
        width: Int,
        height: Int,
    ): StarMapProjectionCandidates {
        val aspect = (width.toFloat() / height.coerceAtLeast(1).toFloat())
            .coerceAtLeast(0.30f)
        val verticalTangent =
            tan((VerticalFieldOfViewDeg * 0.5f).toRadians()) *
                TransitionCandidateViewportMargin
        val horizontalTangent = verticalTangent * aspect
        val minimumCandidateDot =
            1f / sqrt(
                1f +
                    verticalTangent * verticalTangent +
                    horizontalTangent * horizontalTangent,
            )
        val sampledForwardDirections = ArrayList<Vec3>(TransitionCandidateSamples)
        for (index in 0 until TransitionCandidateSamples) {
            val progress = if (TransitionCandidateSamples <= 1) {
                1f
            } else {
                index.toFloat() / (TransitionCandidateSamples - 1).toFloat()
            }
            val orientation = SkyQuaternion.slerp(
                fromOrientation,
                toOrientation,
                progress,
            )
            sampledForwardDirections += orientation
                .rotate(Vec3(0f, 0f, -1f))
                .normalized()
        }

        fun touchesSweptView(direction: Vec3): Boolean =
            sampledForwardDirections.any { forward ->
                direction.dot(forward) >= minimumCandidateDot
            }

        val relevantLines = ArrayList<CatalogLineSegment>()
        for (segment in catalog.lines) {
            val isPrimary =
                segment.constellationId == fromPrimaryConstellationId ||
                    segment.constellationId == toPrimaryConstellationId
            if (!isPrimary && segment.rank > NeighborConstellationMaxRank) continue

            if (
                isPrimary ||
                touchesSweptView(segment.fromDirection) ||
                touchesSweptView(segment.toDirection)
            ) {
                relevantLines += segment
            }
        }

        val primarySkeletonDirections = HashSet<Vec3>()
        val neighborSkeletonDirections = HashSet<Vec3>()
        collectSkeletonDirections(
            lines = relevantLines,
            fromPrimaryConstellationId = fromPrimaryConstellationId,
            toPrimaryConstellationId = toPrimaryConstellationId,
            primary = primarySkeletonDirections,
            neighbor = neighborSkeletonDirections,
        )

        val relevantStars = ArrayList<CatalogStar>()
        for (star in catalog.stars) {
            if (star.direction in primarySkeletonDirections) {
                relevantStars += star
                continue
            }
            if (touchesSweptView(star.direction)) {
                relevantStars += star
            }
        }

        return StarMapProjectionCandidates(
            stars = relevantStars,
            lines = relevantLines,
            primarySkeletonDirections = primarySkeletonDirections,
            neighborSkeletonDirections = neighborSkeletonDirections,
        ).also {
        }
    }

    private fun collectSkeletonDirections(
        lines: List<CatalogLineSegment>,
        fromPrimaryConstellationId: String?,
        toPrimaryConstellationId: String,
        primary: MutableSet<Vec3>,
        neighbor: MutableSet<Vec3>,
    ) {
        for (segment in lines) {
            val isFromPrimary =
                fromPrimaryConstellationId != null &&
                    segment.constellationId == fromPrimaryConstellationId
            val isToPrimary = segment.constellationId == toPrimaryConstellationId
            when {
                isFromPrimary || isToPrimary -> {
                    primary += segment.fromDirection
                    primary += segment.toDirection
                }
                segment.rank <= NeighborConstellationMaxRank -> {
                    neighbor += segment.fromDirection
                    neighbor += segment.toDirection
                }
            }
        }
    }

    fun projectSolarBodies(
        cameraOrientation: SkyQuaternion,
        bodies: List<SolarSystemBodyState>,
        featuredSolarBodyId: SolarSystemBodyId?,
        width: Int,
        height: Int,
    ): List<ProjectedSolarBody> {
        if (bodies.isEmpty()) return emptyList()
        val projector = SkyProjector(
            cameraOrientation = cameraOrientation,
            viewportAspect = width.toFloat() / height.coerceAtLeast(1).toFloat(),
        )
        val sunDirection = bodies
            .firstOrNull { it.id == SolarSystemBodyId.SUN }
            ?.let { Vec3(it.direction.x, it.direction.y, it.direction.z).normalized() }
        return bodies.mapNotNull { body ->
            val direction = Vec3(
                x = body.direction.x,
                y = body.direction.y,
                z = body.direction.z,
            )
            val point = projector.project(direction) ?: return@mapNotNull null
            if (
                abs(point.first) > SolarBodyViewportMargin ||
                abs(point.second) > SolarBodyViewportMargin
            ) {
                return@mapNotNull null
            }
            val isFeatured = body.id == featuredSolarBodyId
            val isMoon = body.id == SolarSystemBodyId.MOON
            val moonLight = if (isMoon && sunDirection != null) {
                projector.localLightVector(
                    surfaceDirection = direction,
                    lightDirection = sunDirection,
                )
            } else {
                Vec3(0f, 0f, 1f)
            }
            ProjectedSolarBody(
                id = body.id,
                x = point.first,
                y = point.second,
                sizePx = body.sizePx,
                red = body.red,
                green = body.green,
                blue = body.blue,
                haloStrength = if (isMoon) {
                    MoonHaloStrength
                } else {
                    (
                        body.haloStrength *
                            if (isFeatured) FeaturedSolarBodyHaloScale else 1f
                        ).coerceAtMost(1.35f)
                },
                moonFlag = if (isMoon) 1f else 0f,
                moonLightX = moonLight.x,
                moonLightY = moonLight.y,
                moonLightZ = moonLight.z,
            )
        }
    }

    private fun shouldShowStar(
        star: CatalogStar,
        isPrimarySkeleton: Boolean,
        isNeighborSkeleton: Boolean,
    ): Boolean {
        if (isPrimarySkeleton || star.magnitude <= AlwaysVisibleMagnitude) return true
        val fraction = stableFraction(star.id)
        if (isNeighborSkeleton) {
            return fraction <= NeighborSkeletonKeepFraction
        }
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

    private var solarProgram = 0
    private var solarPositionLocation = -1
    private var solarSizeLocation = -1
    private var solarColorLocation = -1
    private var solarHaloLocation = -1
    private var solarMoonFlagLocation = -1
    private var solarMoonLightLocation = -1
    private var solarAlphaLocation = -1

    private var currentRequest: StarMapSceneRequest? = null
    private var fromPrimaryConstellationId: String? = null
    private var fromOrientation = SkyQuaternion.Identity
    private var toOrientation = SkyQuaternion.Identity
    private var transitionStartMs = 0L
    private var previousAccent = floatArrayOf(0.62f, 0.76f, 1.0f)
    private var currentAccent = previousAccent.copyOf()
    private var cachedStaticFrame: ProjectedStarMapFrame? = null
    private var transitionCandidates: StarMapProjectionCandidates? = null
    private var solarSystemBucket = Long.MIN_VALUE
    private var solarSystemStates: List<SolarSystemBodyState> = emptyList()
    private var cachedStaticSolarBodies: List<ProjectedSolarBody>? = null
    private var lastVisibleSolarBodies: String? = null

    private val quadBuffer = floatArrayOf(
        -1f, -1f,
        1f, -1f,
        -1f, 1f,
        1f, 1f,
    ).toFloatBuffer()
    private val pointBuffer = allocateFloatBuffer(MaxVisibleStars * PointFloatsPerVertex)
    private val solarBuffer = allocateFloatBuffer(MaxSolarBodies * SolarBodyFloatsPerVertex)
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

        solarProgram = createProgram(SolarBodyVertexShader, SolarBodyFragmentShader)
        solarPositionLocation = GLES20.glGetAttribLocation(solarProgram, "aPosition")
        solarSizeLocation = GLES20.glGetAttribLocation(solarProgram, "aSizePx")
        solarColorLocation = GLES20.glGetAttribLocation(solarProgram, "aColor")
        solarHaloLocation = GLES20.glGetAttribLocation(solarProgram, "aHaloStrength")
        solarMoonFlagLocation = GLES20.glGetAttribLocation(solarProgram, "aMoonFlag")
        solarMoonLightLocation = GLES20.glGetAttribLocation(solarProgram, "aMoonLight")
        solarAlphaLocation = GLES20.glGetUniformLocation(solarProgram, "uGlobalAlpha")

        startMs = SystemClock.uptimeMillis()
    }

    fun onSurfaceChanged(newWidth: Int, newHeight: Int) {
        val nextWidth = newWidth.coerceAtLeast(1)
        val nextHeight = newHeight.coerceAtLeast(1)
        if (width != nextWidth || height != nextHeight) {
            cachedStaticFrame = null
            transitionCandidates = null
            cachedStaticSolarBodies = null
        }
        width = nextWidth
        height = nextHeight
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
        val epochMillis = System.currentTimeMillis()
        val previousRequest = currentRequest
        val request = StarMapSceneSelector.select(
            key = spec.key,
            catalog = catalog,
            epochMillis = epochMillis,
            solarBodies = currentSolarSystemStates(epochMillis),
            previousFeaturedSolarBodyId = previousRequest?.featuredSolarBodyId,
        )

        if (previousRequest == null) {
            cachedStaticFrame = null
            transitionCandidates = null
            cachedStaticSolarBodies = null
            currentRequest = request
            fromPrimaryConstellationId = request.primaryConstellationId
            fromOrientation = request.targetOrientation
            toOrientation = request.targetOrientation
            previousAccent = nextAccent.copyOf()
            currentAccent = nextAccent
            transitionStartMs = nowMs - StarMapTransitionMs
            return
        }

        cachedStaticFrame = null
        transitionCandidates = null
        cachedStaticSolarBodies = null
        fromOrientation = currentOrientationAt(nowMs)
        toOrientation = request.targetOrientation
        fromPrimaryConstellationId = previousRequest.primaryConstellationId
        currentRequest = request
        previousAccent = currentAccent
        currentAccent = nextAccent
        transitionStartMs = nowMs

    }

    fun render(): Boolean {
        val request = currentRequest ?: return false
        val nowMs = SystemClock.uptimeMillis()
        val epochMillis = System.currentTimeMillis()
        val timeSeconds = (nowMs - startMs) / 1000f
        val rawProgress =
            ((nowMs - transitionStartMs).toFloat() / StarMapTransitionMs).coerceIn(0f, 1f)
        val progress = smoothStep(rawProgress)
        val orientation = SkyQuaternion.slerp(fromOrientation, toOrientation, progress)
        val transitionActive = rawProgress < 1f

        val activeTransitionCandidates = if (transitionActive) {
            transitionCandidates ?: StarMapSceneProjector.buildTransitionCandidates(
                fromOrientation = fromOrientation,
                toOrientation = toOrientation,
                fromPrimaryConstellationId = fromPrimaryConstellationId,
                toPrimaryConstellationId = request.primaryConstellationId,
                catalog = catalog,
                width = width,
                height = height,
            ).also { transitionCandidates = it }
        } else {
            transitionCandidates = null
            null
        }

        val frame = if (transitionActive) {
            StarMapSceneProjector.project(
                cameraOrientation = orientation,
                fromPrimaryConstellationId = fromPrimaryConstellationId,
                toPrimaryConstellationId = request.primaryConstellationId,
                catalog = catalog,
                width = width,
                height = height,
                candidates = activeTransitionCandidates,
            )
        } else {
            cachedStaticFrame ?: StarMapSceneProjector.project(
                cameraOrientation = toOrientation,
                fromPrimaryConstellationId = fromPrimaryConstellationId,
                toPrimaryConstellationId = request.primaryConstellationId,
                catalog = catalog,
                width = width,
                height = height,
            ).also { cachedStaticFrame = it }
        }

        val solarStates = currentSolarSystemStates(epochMillis)
        val solarBodies = if (transitionActive) {
            StarMapSceneProjector.projectSolarBodies(
                cameraOrientation = orientation,
                bodies = solarStates,
                featuredSolarBodyId = request.featuredSolarBodyId,
                width = width,
                height = height,
            )
        } else {
            cachedStaticSolarBodies ?: StarMapSceneProjector.projectSolarBodies(
                cameraOrientation = toOrientation,
                bodies = solarStates,
                featuredSolarBodyId = request.featuredSolarBodyId,
                width = width,
                height = height,
            ).also { cachedStaticSolarBodies = it }
        }
        if (!transitionActive) {
            val visibleKey = solarBodies.joinToString(",") { it.id.name }
            if (visibleKey != lastVisibleSolarBodies) {
                lastVisibleSolarBodies = visibleKey
            }
        }

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
        drawSolarBodies(
            bodies = solarBodies,
            alpha = 1f,
        )

        GLES20.glDisable(GLES20.GL_BLEND)
        return transitionActive
    }

    private fun currentSolarSystemStates(epochMillis: Long): List<SolarSystemBodyState> {
        val bucket = epochMillis / SolarSystemRefreshIntervalMs
        if (bucket != solarSystemBucket) {
            solarSystemBucket = bucket
            solarSystemStates = SolarSystemEphemeris.compute(epochMillis)
            cachedStaticSolarBodies = null
        }
        return solarSystemStates
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
        if (solarProgram != 0) GLES20.glDeleteProgram(solarProgram)
        backgroundProgram = 0
        lineProgram = 0
        pointProgram = 0
        solarProgram = 0
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

    private fun drawSolarBodies(
        bodies: List<ProjectedSolarBody>,
        alpha: Float,
    ) {
        if (bodies.isEmpty()) return

        solarBuffer.clear()
        for (body in bodies.take(MaxSolarBodies)) {
            solarBuffer.put(body.x)
            solarBuffer.put(body.y)
            solarBuffer.put(body.sizePx)
            solarBuffer.put(body.red)
            solarBuffer.put(body.green)
            solarBuffer.put(body.blue)
            solarBuffer.put(body.haloStrength)
            solarBuffer.put(body.moonFlag)
            solarBuffer.put(body.moonLightX)
            solarBuffer.put(body.moonLightY)
            solarBuffer.put(body.moonLightZ)
        }
        solarBuffer.position(0)

        GLES20.glUseProgram(solarProgram)
        GLES20.glUniform1f(solarAlphaLocation, alpha)

        val stride = SolarBodyFloatsPerVertex * FloatBytes

        solarBuffer.position(0)
        GLES20.glEnableVertexAttribArray(solarPositionLocation)
        GLES20.glVertexAttribPointer(
            solarPositionLocation,
            2,
            GLES20.GL_FLOAT,
            false,
            stride,
            solarBuffer,
        )

        solarBuffer.position(2)
        GLES20.glEnableVertexAttribArray(solarSizeLocation)
        GLES20.glVertexAttribPointer(
            solarSizeLocation,
            1,
            GLES20.GL_FLOAT,
            false,
            stride,
            solarBuffer,
        )

        solarBuffer.position(3)
        GLES20.glEnableVertexAttribArray(solarColorLocation)
        GLES20.glVertexAttribPointer(
            solarColorLocation,
            3,
            GLES20.GL_FLOAT,
            false,
            stride,
            solarBuffer,
        )

        solarBuffer.position(6)
        GLES20.glEnableVertexAttribArray(solarHaloLocation)
        GLES20.glVertexAttribPointer(
            solarHaloLocation,
            1,
            GLES20.GL_FLOAT,
            false,
            stride,
            solarBuffer,
        )

        solarBuffer.position(7)
        GLES20.glEnableVertexAttribArray(solarMoonFlagLocation)
        GLES20.glVertexAttribPointer(
            solarMoonFlagLocation,
            1,
            GLES20.GL_FLOAT,
            false,
            stride,
            solarBuffer,
        )

        solarBuffer.position(8)
        GLES20.glEnableVertexAttribArray(solarMoonLightLocation)
        GLES20.glVertexAttribPointer(
            solarMoonLightLocation,
            3,
            GLES20.GL_FLOAT,
            false,
            stride,
            solarBuffer,
        )

        GLES20.glDrawArrays(
            GLES20.GL_POINTS,
            0,
            bodies.size.coerceAtMost(MaxSolarBodies),
        )

        GLES20.glDisableVertexAttribArray(solarPositionLocation)
        GLES20.glDisableVertexAttribArray(solarSizeLocation)
        GLES20.glDisableVertexAttribArray(solarColorLocation)
        GLES20.glDisableVertexAttribArray(solarHaloLocation)
        GLES20.glDisableVertexAttribArray(solarMoonFlagLocation)
        GLES20.glDisableVertexAttribArray(solarMoonLightLocation)
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

private const val StarMapMovingFrameIntervalMs = 17L
private const val StarMapIdleFrameIntervalMs = 50L
private const val StarMapTransitionMs = 1300L
private const val SolarSystemRefreshIntervalMs = 5L * 60L * 1000L

private const val SeasonalStrongRegionDeg = 55f
private const val SeasonalCandidateLimitDeg = 90f
private const val CompositionEastOffsetDeg = 5.0f
private const val CompositionNorthOffsetDeg = 3.5f
private const val SolarSceneChance = 0.28
private const val SolarDaySideMinFactor = 0.48f
private const val SolarRepeatAlternativeRatio = 1.18
private const val SolarCompositionEastOffsetDeg = 2.6f
private const val SolarCompositionNorthOffsetDeg = 1.8f
private const val FeaturedSolarBodyHaloScale = 1.20f
private const val MoonHaloStrength = 0.12f
private const val StableUnitDenominator = 9_007_199_254_740_992.0

private const val VerticalFieldOfViewDeg = 76f
private const val MinimumProjectionCosine = 0.15f
private const val StarViewportMargin = 1.06f
private const val LineViewportMargin = 1.16f
private const val SolarBodyViewportMargin = 1.08f
private const val TransitionCandidateSamples = 7
private const val TransitionCandidateViewportMargin = 1.55f

private const val CatalogMagnitudeLimit = 5.65f
private const val AlwaysVisibleMagnitude = 3.90f
private const val MidStarMagnitude = 4.80f
private const val MidStarKeepFraction = 0.52f
private const val FaintStarKeepFraction = 0.18f
private const val NeighborSkeletonKeepFraction = 0.80f
private const val ReferenceMagnitude = 1.0f
private const val BrightReferenceMagnitude = -1.0f
private const val BrightStarTwinkleMagnitude = 1.0f

private const val NeighborConstellationMaxRank = 2
private const val PrimaryLineHaloAlpha = 0.075f
private const val PrimaryLineCoreAlpha = 0.38f
private const val NeighborLineHaloAlpha = 0.014f
private const val NeighborLineCoreAlpha = 0.082f

private const val MaxVisibleStars = 118
private const val MaxSolarBodies = 9
private const val MaxPrimaryLineSegments = 80
private const val MaxNeighborLineSegments = 180
private const val MaxLineSegments = 180
private const val PointFloatsPerVertex = 7
private const val SolarBodyFloatsPerVertex = 11
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

    float visibility = clamp((0.20 + vBrightness * 0.80) * 2.7556, 0.0, 1.0);
    float alpha = (core * 0.96 + halo * 0.40 + sparkle) * visibility * uGlobalAlpha;
    gl_FragColor = vec4(color, clamp(alpha, 0.0, 1.0));
}
"""

private const val SolarBodyVertexShader = """
attribute vec2 aPosition;
attribute float aSizePx;
attribute vec3 aColor;
attribute float aHaloStrength;
attribute float aMoonFlag;
attribute vec3 aMoonLight;

varying vec3 vColor;
varying float vHaloStrength;
varying float vMoonFlag;
varying vec3 vMoonLight;

void main() {
    gl_Position = vec4(aPosition, 0.0, 1.0);
    gl_PointSize = aSizePx;
    vColor = aColor;
    vHaloStrength = aHaloStrength;
    vMoonFlag = aMoonFlag;
    vMoonLight = aMoonLight;
}
"""

private const val SolarBodyFragmentShader = """
precision mediump float;

uniform float uGlobalAlpha;

varying vec3 vColor;
varying float vHaloStrength;
varying float vMoonFlag;
varying vec3 vMoonLight;

float moonTexture(vec2 uv) {
    float largeMaria =
        exp(-18.0 * dot(uv - vec2(-0.26, 0.10), uv - vec2(-0.26, 0.10))) +
        0.72 * exp(-24.0 * dot(uv - vec2(0.16, -0.18), uv - vec2(0.16, -0.18))) +
        0.54 * exp(-30.0 * dot(uv - vec2(0.28, 0.20), uv - vec2(0.28, 0.20)));
    float fine =
        sin((uv.x * 16.0 + uv.y * 9.0) * 1.7) *
        sin((uv.y * 14.0 - uv.x * 6.0) * 1.3);
    return clamp(1.0 - largeMaria * 0.16 + fine * 0.025, 0.72, 1.04);
}

void main() {
    vec2 p = gl_PointCoord - 0.5;
    float d = length(p);
    if (d >= 0.5) discard;

    if (vMoonFlag > 0.5) {
        vec2 moonUv = vec2(p.x, -p.y) / 0.44;
        float r2 = dot(moonUv, moonUv);
        float outerHalo = 1.0 - smoothstep(0.44, 0.50, d);

        if (r2 > 1.0) {
            float haloOnly =
                (1.0 - smoothstep(0.44, 0.50, d)) *
                vHaloStrength *
                0.12;
            gl_FragColor = vec4(
                vec3(0.72, 0.78, 0.86),
                haloOnly * uGlobalAlpha
            );
            return;
        }

        float z = sqrt(max(0.0, 1.0 - r2));
        vec3 normal = normalize(vec3(moonUv.x, moonUv.y, z));
        vec3 lightDirection = normalize(vMoonLight);
        float lambert = max(dot(normal, lightDirection), 0.0);
        float earthshine = 0.055;
        float illumination = earthshine + lambert * 0.945;

        float limb = smoothstep(0.0, 0.16, z);
        float textureValue = moonTexture(moonUv);
        vec3 moonBase = vec3(0.73, 0.76, 0.80) * textureValue;
        vec3 litColor = moonBase * illumination;
        litColor += vec3(0.03, 0.035, 0.045) * earthshine;

        float diskAlpha = (1.0 - smoothstep(0.94, 1.0, r2)) * limb;
        float haloAlpha = outerHalo * vHaloStrength * 0.06;
        gl_FragColor = vec4(
            litColor,
            clamp((diskAlpha + haloAlpha) * uGlobalAlpha, 0.0, 1.0)
        );
        return;
    }

    float core = 1.0 - smoothstep(0.08, 0.24, d);
    float disk = 1.0 - smoothstep(0.22, 0.34, d);
    float halo = 1.0 - smoothstep(0.24, 0.50, d);

    vec3 color = mix(vColor * 0.78, vec3(1.0), core * 0.34);
    float alpha =
        core * 0.98 +
        disk * 0.50 +
        halo * (0.16 + vHaloStrength * 0.30);

    gl_FragColor = vec4(color, clamp(alpha * uGlobalAlpha, 0.0, 1.0));
}
"""
