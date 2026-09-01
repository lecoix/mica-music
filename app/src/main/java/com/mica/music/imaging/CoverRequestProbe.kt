package com.mica.music.imaging

import android.graphics.drawable.BitmapDrawable
import android.os.SystemClock
import android.os.Build
import android.os.Trace
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.Composer
import androidx.compose.runtime.CompositionTracer
import androidx.compose.runtime.InternalComposeTracingApi
import coil.EventListener
import coil.decode.DecodeResult
import coil.decode.Decoder
import coil.request.ErrorResult
import coil.request.ImageRequest
import coil.request.Options
import coil.request.SuccessResult
import coil.size.Size
import com.mica.music.BuildConfig
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.ConcurrentHashMap

/** Temporary [CRP] probe. Logcat only; never changes requests, caches, or readiness state. */
object CoverRequestProbe {
    @OptIn(InternalComposeTracingApi::class)
    fun installCompositionTracer() {
        if (!BuildConfig.COMPOSITION_PROBE || BuildConfig.BUILD_TYPE != "perf") return
        // Compiler-provided function boundaries. No changes to composition/state or per-frame logs.
        Composer.setTracer(object : CompositionTracer {
            private val names = ConcurrentHashMap<Int, String>()
            override fun isTraceInProgress(): Boolean =
                Build.VERSION.SDK_INT >= 29 && Trace.isEnabled()
            override fun traceEventStart(key: Int, dirty1: Int, dirty2: Int, info: String) {
                Trace.beginSection(names.getOrPut(key) { info.take(127) })
            }
            override fun traceEventEnd() = Trace.endSection()
        })
    }

    private val ids = AtomicInteger()
    private val lines = AtomicInteger()
    private const val MaxLines = 6000
    private val enabled = BuildConfig.COVER_REQUEST_PROBE && BuildConfig.BUILD_TYPE == "perf"

    private fun uriId(uri: Any?): String = uri?.toString()?.hashCode()?.toUInt()?.toString(16) ?: "none"

    fun mark(message: String) {
        if (!enabled || lines.incrementAndGet() > MaxLines) return
        Log.d("MICA_CRP", "[CRP] ns=${SystemClock.elapsedRealtimeNanos()} $message")
    }

    fun factory(loader: String) = EventListener.Factory { request ->
        if (!enabled || request.memoryCacheKey?.key?.startsWith("cover:256x256:") == true) {
            EventListener.NONE
        } else {
            RequestEvents(loader, request)
        }
    }

    private class RequestEvents(loader: String, request: ImageRequest) : EventListener {
        private val id = ids.incrementAndGet()
        private val identity = "req=$id loader=$loader kind=${if (request.target == null) "preload" else "display"} uri=${uriId(request.data)}"
        private val startedNs = SystemClock.elapsedRealtimeNanos()
        private var decodeNs = 0L
        private var decoded = false

        override fun onStart(request: ImageRequest) {
            val key = request.memoryCacheKey?.key
            val keyKind = when {
                key == request.data.toString() -> "uri"
                key?.startsWith("bg:") == true -> "bg"
                key?.startsWith("cover:") == true -> key.substringBeforeLast(':').take(35)
                else -> "other"
            }
            mark("$identity start key=$keyKind hardware=${request.allowHardware}")
        }

        override fun resolveSizeEnd(request: ImageRequest, size: Size) {
            mark("$identity size=$size")
        }

        override fun decodeStart(request: ImageRequest, decoder: Decoder, options: Options) {
            decodeNs = SystemClock.elapsedRealtimeNanos()
            decoded = true
            mark("$identity decode-start size=${options.size}")
        }

        override fun decodeEnd(request: ImageRequest, decoder: Decoder, options: Options, result: DecodeResult?) {
            mark("$identity decode-end us=${(SystemClock.elapsedRealtimeNanos() - decodeNs) / 1000}")
        }

        override fun onSuccess(request: ImageRequest, result: SuccessResult) {
            mark("$identity success source=${result.dataSource} decoded=$decoded ${bitmapInfo(result)} us=${(SystemClock.elapsedRealtimeNanos() - startedNs) / 1000}")
        }

        override fun onCancel(request: ImageRequest) = mark("$identity cancel")
        override fun onError(request: ImageRequest, result: ErrorResult) =
            mark("$identity error=${result.throwable.javaClass.simpleName}")
    }

    private fun bitmapInfo(result: SuccessResult): String {
        val bitmap = (result.drawable as? BitmapDrawable)?.bitmap ?: return "bitmap=none"
        return "bitmap=${System.identityHashCode(bitmap)} pixels=${bitmap.width}x${bitmap.height} config=${bitmap.config}"
    }

    @Composable
    fun layer(role: String?, uri: String?): Int {
        if (!enabled || role == null) return 0
        val instance = remember { ids.incrementAndGet() }
        DisposableEffect(uri, role) {
            mark("layer=$instance role=$role uri=${uriId(uri)} bind")
            onDispose { mark("layer=$instance role=$role uri=${uriId(uri)} unbind") }
        }
        return instance
    }

    fun ready(layer: Int, uri: String?, result: SuccessResult) {
        if (layer == 0) return
        mark("layer=$layer uri=${uriId(uri)} ready source=${result.dataSource} ${bitmapInfo(result)}")
    }
}
