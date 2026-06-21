package com.mica.music.ui.screens.player.view

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.os.Build
import android.util.Base64
import android.webkit.WebView
import android.webkit.WebViewClient
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import com.mica.music.util.DiagnosticLog
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.mica.music.data.ParticleCoverTuning
import com.mica.music.data.Song
import com.mica.music.imaging.CoverDecodeTarget
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream

internal const val ThreeParticleCoverTransitionDurationMs = 900L
internal const val ThreeParticleCoverHaloFraction = 0.04f

@Composable
internal fun ThreeParticleCoverHost(
    song: Song,
    coverDecodeTarget: CoverDecodeTarget,
    motionEnabled: Boolean,
    coverColor: Color,
    modifier: Modifier = Modifier,
    tuning: ParticleCoverTuning = ParticleCoverTuning(),
    onAspectRatioChanged: (Float) -> Unit = {},
    onMotionActiveChanged: (Boolean) -> Unit = {},
) {
    val context = LocalContext.current
    val coverUri = song.albumArtUri.orEmpty()
    var webView by remember { mutableStateOf<WebView?>(null) }
    var pageReady by remember { mutableStateOf(false) }
    var payload by remember(song.id, coverUri, coverDecodeTarget, coverColor) {
        mutableStateOf<JSONObject?>(null)
    }

    LaunchedEffect(Unit) {
        DiagnosticLog.event(
            "ThreeParticleCover",
            "host-enter song=${song.id.takeLast(12)} uriBlank=${coverUri.isBlank()} target=${coverDecodeTarget.widthPx}x${coverDecodeTarget.heightPx}",
        )
    }

    LaunchedEffect(song.id) {
        onAspectRatioChanged(1f)
    }

    LaunchedEffect(song.id, motionEnabled) {
        if (!motionEnabled) {
            onMotionActiveChanged(false)
            return@LaunchedEffect
        }
        onMotionActiveChanged(true)
        delay(ThreeParticleCoverTransitionDurationMs)
        onMotionActiveChanged(false)
    }

    LaunchedEffect(song.id, coverUri, coverDecodeTarget, coverColor, motionEnabled) {
        DiagnosticLog.event(
            "ThreeParticleCover",
            "build-payload-start song=${song.id.takeLast(12)} uriBlank=${coverUri.isBlank()} motion=$motionEnabled",
        )
        val bitmap = if (coverUri.isBlank()) {
            null
        } else {
            CoverFlowBitmaps.memoryBitmap(coverUri, coverDecodeTarget)
                ?: CoverFlowBitmaps.ensureLoaded(context, coverUri, coverDecodeTarget)
        }
        val dataUrl = bitmap?.toDataUrl()
        DiagnosticLog.event(
            "ThreeParticleCover",
            "build-payload-end song=${song.id.takeLast(12)} bitmap=${bitmap != null} bitmapSize=${bitmap?.width}x${bitmap?.height} dataUrlChars=${dataUrl?.length ?: 0}",
        )
        payload = JSONObject()
            .put("id", song.id)
            .put("src", dataUrl)
            .put("color", coverColor.toArgb().toCssColor())
            .put("motionEnabled", motionEnabled)
    }

    LaunchedEffect(webView, pageReady, payload) {
        val view = webView
        val data = payload
        DiagnosticLog.event(
            "ThreeParticleCover",
            "set-cover-check webView=${view != null} pageReady=$pageReady payload=${data != null}",
        )
        if (view == null || data == null || !pageReady) return@LaunchedEffect
        DiagnosticLog.event(
            "ThreeParticleCover",
            "set-cover-evaluate chars=${data.toString().length}",
        )
        view.evaluateJavascript(
            "window.MicaParticleCover && window.MicaParticleCover.setCover(${data});",
        ) { result ->
            DiagnosticLog.event("ThreeParticleCover", "set-cover-result=$result")
            view.notifyParticleResize("after-set-cover")
        }
    }

    LaunchedEffect(webView, pageReady, tuning) {
        val view = webView
        DiagnosticLog.event(
            "ThreeParticleCover",
            "set-tuning-check webView=${view != null} pageReady=$pageReady",
        )
        if (view == null || !pageReady) return@LaunchedEffect
        val data = JSONObject()
            .put("erosionScale", tuning.erosionScale)
            .put("featherScale", tuning.featherScale)
            .put("edgeParticleDensity", tuning.edgeParticleDensity)
            .put("edgeParticleAlpha", tuning.edgeParticleAlpha)
            .put("edgeTravelScale", tuning.edgeTravelScale)
            .put("transitionParticleDensity", tuning.transitionParticleDensity)
        DiagnosticLog.event("ThreeParticleCover", "set-tuning-evaluate $data")
        view.evaluateJavascript(
            "window.MicaParticleCover && window.MicaParticleCover.setTuning(${data});",
        ) { result ->
            DiagnosticLog.event("ThreeParticleCover", "set-tuning-result=$result")
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            DiagnosticLog.event("ThreeParticleCover", "webview-factory")
            ThreeParticleWebView(ctx).apply {
                configureForThreeParticleCover()
                webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                        DiagnosticLog.event(
                            "ThreeParticleCover",
                            "${consoleMessage.messageLevel()}: ${consoleMessage.message()}",
                        )
                        return true
                    }
                }
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String) {
                        DiagnosticLog.event("ThreeParticleCover", "page-finished url=$url")
                        pageReady = true
                        view.notifyParticleResize("page-finished")
                    }
                }
                DiagnosticLog.event("ThreeParticleCover", "load-url $ThreeParticleAssetUrl")
                loadUrl(ThreeParticleAssetUrl)
                webView = this
            }
        },
        update = { view ->
            DiagnosticLog.event(
                "ThreeParticleCover",
                "webview-update width=${view.width} height=${view.height}",
            )
            view.notifyParticleResize("compose-update")
            webView = view
        },
        onRelease = { view ->
            DiagnosticLog.event("ThreeParticleCover", "webview-release")
            pageReady = false
            if (webView === view) webView = null
            view.destroy()
        },
    )
}

private class ThreeParticleWebView(context: Context) : WebView(context) {
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        DiagnosticLog.event(
            "ThreeParticleCover",
            "webview-size w=$w h=$h old=${oldw}x$oldh",
        )
        notifyParticleResize("on-size-changed")
    }
}

private fun WebView.notifyParticleResize(reason: String) {
    if (width <= 0 || height <= 0) return
    evaluateJavascript(
        """
        (function(){
          if (!window.MicaParticleCover) return "missing-api";
          window.MicaParticleCover.resize();
          return JSON.stringify(window.MicaParticleCover.debugState && window.MicaParticleCover.debugState());
        })();
        """.trimIndent(),
    ) { result ->
        DiagnosticLog.event("ThreeParticleCover", "resize-$reason result=$result")
    }
}

@SuppressLint("SetJavaScriptEnabled")
private fun WebView.configureForThreeParticleCover() {
    setBackgroundColor(AndroidColor.TRANSPARENT)
    setLayerType(View.LAYER_TYPE_HARDWARE, null)
    isVerticalScrollBarEnabled = false
    isHorizontalScrollBarEnabled = false
    settings.javaScriptEnabled = true
    settings.domStorageEnabled = false
    settings.allowFileAccess = true
    settings.allowContentAccess = false
    settings.mediaPlaybackRequiresUserGesture = false
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        settings.safeBrowsingEnabled = false
    }
}

private suspend fun Bitmap.toDataUrl(): String = withContext(Dispatchers.Default) {
    val uploadBitmap = if (config == Bitmap.Config.HARDWARE) {
        copy(Bitmap.Config.ARGB_8888, false)
    } else {
        this@toDataUrl
    }
    val stream = ByteArrayOutputStream()
    uploadBitmap.compress(Bitmap.CompressFormat.JPEG, 96, stream)
    if (uploadBitmap !== this@toDataUrl) uploadBitmap.recycle()
    "data:image/jpeg;base64," + Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
}

private fun Int.toCssColor(): String =
    "#%02x%02x%02x".format(
        AndroidColor.red(this),
        AndroidColor.green(this),
        AndroidColor.blue(this),
    )

private const val ThreeParticleAssetUrl = "file:///android_asset/particle_cover/index.html"
