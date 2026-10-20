package com.mica.music.data.lx

import android.content.Context
import com.whl.quickjs.android.QuickJSLoader
import com.whl.quickjs.wrapper.JSCallFunction
import com.whl.quickjs.wrapper.JSObject
import com.whl.quickjs.wrapper.QuickJSContext
import java.io.IOException
import java.net.URLDecoder
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/** QuickJS is strictly thread confined. Native callbacks have a separate short side-effect gate. */
internal class LxWorkerUnavailable : IOException("QuickJS worker requires app restart")

internal class QuickJsLxRuntime(context: Context) : LxRuntime {
    private val assets = context.applicationContext.assets
    private val gate = Any()
    @Volatile private var closed = false
    private var js: QuickJSContext? = null // JS thread only, including destruction.
    private val key = UUID.randomUUID().toString()
    private val initialized = CompletableDeferred<Map<String, List<String>>>()
    private var pendingCapabilities: Map<String, List<String>>? = null // JS-thread turn result.
    private val responses = mutableMapOf<String, CompletableDeferred<String>>()
    private val calls = mutableMapOf<String, Call>()
    private val timers = mutableMapOf<Int, ScheduledFuture<*>>()
    private val requestMutex = Mutex()

    override suspend fun initialize(script: LxStoredScript): Map<String, List<String>> {
        post {
            QuickJSLoader.init()
            val engine = QuickJSContext.create()
            js = engine
            engine.setMemoryLimit(32 * 1024 * 1024)
            engine.setMaxStackSize(512 * 1024)
            engine.setConsole(object : QuickJSContext.Console {
                override fun log(info: String?) = Unit
                override fun info(info: String?) = Unit
                override fun warn(info: String?) = Unit
                override fun error(info: String?) = Unit
            })
            fun bind(name: String, body: (Array<out Any?>) -> Any?) {
                engine.globalObject.setProperty(name, JSCallFunction { args ->
                    check(!closed) { "LX runtime closed" }
                    body(args)
                })
            }
            bind("__lx_native_call__") { args ->
                if (args[0] == key) nativeCall(args[1] as String, args[2] as String)
                null
            }
            bind("__lx_native_call__set_timeout") { args ->
                val id = (args[0] as Number).toInt()
                synchronized(gate) {
                    check(!closed && timers.size < 64) { "Too many timers" }
                    timers[id] = scheduler.schedule({
                        synchronized(gate) { timers.remove(id) }
                        post { callJs("__set_timeout__", id.toString()) }
                    }, (args[1] as Number).toLong().coerceIn(0, 60_000), TimeUnit.MILLISECONDS)
                }
                null
            }
            bind("__lx_native_call__utils_str2b64") { Base64.getEncoder().encodeToString((it[0] as String).toByteArray()) }
            bind("__lx_native_call__clear_timeout") { args ->
                synchronized(gate) { timers.remove((args[0] as Number).toInt())?.cancel(false) }
                null
            }
            fun bytes(raw: String): ByteArray {
                val array = JSONArray(raw)
                require(array.length() <= 1024 * 1024)
                return ByteArray(array.length()) { array.getInt(it).toByte() }
            }
            bind("__lx_native_call__utils_buf2b64") { Base64.getEncoder().encodeToString(bytes(it[0] as String)) }
            bind("__lx_native_call__utils_buf2str") { bytes(it[0] as String).toString(Charsets.UTF_8) }
            bind("__lx_native_call__utils_b642buf") { JSONArray(Base64.getDecoder().decode(it[0] as String).map { b -> b.toInt() and 255 }).toString() }
            bind("__lx_native_call__utils_str2md5") {
                MessageDigest.getInstance("MD5").digest(URLDecoder.decode(it[0] as String, "UTF-8").toByteArray()).joinToString("") { b -> "%02x".format(b) }
            }
            bind("__lx_native_call__utils_aes_encrypt") {
                val mode = it[3] as String
                require(mode == "AES/CBC/PKCS7Padding" || mode == "AES")
                val cipher = Cipher.getInstance(mode)
                val secret = SecretKeySpec(Base64.getDecoder().decode(it[1] as String), "AES")
                require(secret.encoded.size == 16)
                val iv = Base64.getDecoder().decode(it[2] as String)
                if (iv.isEmpty()) cipher.init(Cipher.ENCRYPT_MODE, secret)
                else cipher.init(Cipher.ENCRYPT_MODE, secret, IvParameterSpec(iv.copyOf(16)))
                Base64.getEncoder().encodeToString(cipher.doFinal(Base64.getDecoder().decode(it[0] as String)))
            }
            bind("__lx_native_call__utils_rsa_encrypt") {
                require(it[2] == "RSA/ECB/NoPadding")
                val publicKey = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(Base64.getMimeDecoder().decode(it[1] as String)))
                val cipher = Cipher.getInstance("RSA/ECB/NoPadding")
                cipher.init(Cipher.ENCRYPT_MODE, publicKey)
                Base64.getEncoder().encodeToString(cipher.doFinal(Base64.getDecoder().decode(it[0] as String)))
            }
            releaseResult(engine.evaluate(assets.open("lx/user-api-preload.js").bufferedReader().use { it.readText() }))
            val metadata = script.metadata
            engine.globalObject.getJSFunction("lx_setup").let { function ->
                try { releaseResult(function.call(key, script.id, metadata.name, metadata.description, metadata.version, metadata.author, metadata.homepage, script.raw)) }
                finally { function.release() }
            }
            releaseResult(engine.evaluate(script.raw, "lx-user-source.js"))
        }
        return try { initialized.await() } catch (failure: Exception) { close(); throw failure }
    }

    override suspend fun musicUrl(source: String, quality: String, musicInfo: String): String = requestMutex.withLock {
        val id = UUID.randomUUID().toString()
        val response = CompletableDeferred<String>()
        synchronized(gate) { check(!closed); responses[id] = response }
        post {
            callJs("request", JSONObject().put("requestKey", id).put("data", JSONObject()
                .put("source", source).put("action", "musicUrl")
                .put("info", JSONObject().put("type", quality).put("musicInfo", JSONObject(musicInfo)))).toString())
        }
        try { response.await() }
        catch (failure: Exception) { close(); throw failure }
        finally { synchronized(gate) { responses.remove(id) } }
    }

    private fun post(action: () -> Unit) {
        if (closed) return
        if (workerUnhealthy.get()) { fail(LxWorkerUnavailable()); return }
        executor.execute {
            if (closed) return@execute
            if (workerUnhealthy.get()) { fail(LxWorkerUnavailable()); return@execute }
            val finished = AtomicBoolean(false)
            val watchdog = scheduler.schedule({
                if (finished.compareAndSet(false, true)) {
                    workerUnhealthy.set(true)
                    fail(LxWorkerUnavailable())
                }
            }, 5, TimeUnit.SECONDS)
            try {
                action()
                synchronized(gate) { if (!closed) pendingCapabilities?.let { initialized.complete(it) } }
            } catch (failure: Throwable) { fail(failure) }
            finally { finished.set(true); watchdog.cancel(false) }
        }
    }
    private fun releaseResult(result: Any?) { (result as? JSObject)?.release() }
    private fun callJs(action: String, json: String) {
        if (closed) return
        js?.globalObject?.getJSFunction("__lx_native__")?.let { function ->
            try { releaseResult(function.call(key, action, json)) } finally { function.release() }
        }
    }
    private fun nativeCall(action: String, raw: String) {
        require(raw.length <= 2 * 1024 * 1024)
        if (action == "cancelRequest") {
            val id = JSONTokener(raw).nextValue() as String
            synchronized(gate) { calls.remove(id)?.cancel() }
            return
        }
        val data = JSONObject(raw)
        when (action) {
            "init" -> {
                check(data.optBoolean("status")) { "Script initialization rejected" }
                val sources = data.getJSONObject("info").getJSONObject("sources")
                val result = buildMap {
                    for (source in listOf("kw", "kg", "tx", "wy", "mg")) {
                        val entry = sources.optJSONObject(source) ?: continue
                        if (!entry.getJSONArray("actions").strings().contains("musicUrl")) continue
                        val quality = entry.getJSONArray("qualitys").strings().filter { it in listOf("128k", "320k", "flac", "flac24bit") }
                        if (quality.isNotEmpty()) put(source, quality)
                    }
                }
                pendingCapabilities = result
            }
            "response" -> synchronized(gate) {
                if (!closed) responses.remove(data.getString("requestKey"))?.let {
                    if (data.optBoolean("status")) it.complete(data.getJSONObject("result").getJSONObject("data").getString("url"))
                    else it.completeExceptionally(IOException("Script rejected musicUrl"))
                }
            }
            "request" -> requestHttp(data)
            "showUpdateAlert" -> Unit // No update download or navigation is performed by scripts.
            else -> error("Unsupported LX action")
        }
    }

    private fun requestHttp(data: JSONObject) {
        val id = data.getString("requestKey")
        val options = data.getJSONObject("options")
        try {
            val request = buildHttpRequest(data.getString("url"), options)
            val call = http.newCall(request)
            call.timeout().timeout(options.optLong("timeout", 13_000).coerceIn(1, 60_000), TimeUnit.MILLISECONDS)
            synchronized(gate) {
                check(!closed && calls.size < 4 && !calls.containsKey(id)) { "Too many HTTP requests" }
                calls[id] = call
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) = deliverHttp(id, call, null, "HTTP request failed")
                    override fun onResponse(call: Call, response: Response) {
                        try {
                            response.use {
                                val bytes = it.body?.byteStream()?.use { input -> input.readBytesBounded(1024 * 1024) } ?: byteArrayOf()
                                val body: Any = if (options.optBoolean("binary")) JSONObject().put("type", "Buffer").put("data", JSONArray(bytes.map { b -> b.toInt() and 255 }))
                                else bytes.toString(Charsets.UTF_8).let { text -> runCatching { JSONTokener(text).nextValue() }.getOrNull() ?: text }
                                val headers = JSONObject()
                                it.headers.names().forEach { name -> headers.put(name.lowercase(), it.header(name)) }
                                deliverHttp(id, call, JSONObject().put("statusCode", it.code).put("statusMessage", it.message).put("headers", headers).put("body", body), null)
                            }
                        } catch (_: Exception) { deliverHttp(id, call, null, "HTTP response exceeds limit or cannot be decoded") }
                    }
                })
            }
        } catch (_: Exception) {
            post { callJs("response", JSONObject().put("requestKey", id).put("error", "Unsupported or invalid HTTP request").toString()) }
        }
    }

    private fun deliverHttp(id: String, call: Call, response: JSONObject?, error: String?) {
        synchronized(gate) {
            if (closed || calls[id] !== call) return
            // Keep identity until the actual JS delivery, so cancelRequest can fence a queued callback.
            post {
                val current = synchronized(gate) { if (!closed && calls[id] === call) { calls.remove(id); true } else false }
                if (current) callJs("response", JSONObject().put("requestKey", id).put("error", error ?: JSONObject.NULL).put("response", response ?: JSONObject.NULL).toString())
            }
        }
    }

    private fun fail(failure: Throwable) {
        synchronized(gate) {
            initialized.completeExceptionally(failure)
            responses.values.forEach { it.completeExceptionally(failure) }
        }
        close()
    }
    override fun close() {
        synchronized(gate) {
            if (closed) return
            closed = true
            calls.values.forEach { it.cancel() }; calls.clear()
            timers.values.forEach { it.cancel(false) }; timers.clear()
            initialized.completeExceptionally(IOException("LX runtime closed"))
            responses.values.forEach { it.completeExceptionally(IOException("LX runtime closed")) }; responses.clear()
        }
        executor.execute { js?.destroy(); js = null }
    }

    companion object {
        // One process-wide thread: a non-cooperative script cannot cause unbounded thread creation.
        // Wrapper 2.4.0 has no interrupt API. A stuck JS turn requires process restart (experimental gate).
        private val executor = Executors.newSingleThreadExecutor { Thread(it, "Mica-LX-QuickJS").apply { isDaemon = true } }
        private val workerUnhealthy = AtomicBoolean(false)
        private val scheduler = Executors.newSingleThreadScheduledExecutor { Thread(it, "Mica-LX-timers").apply { isDaemon = true } }
        private val http = OkHttpClient.Builder().callTimeout(13, TimeUnit.SECONDS).build()
        private fun JSONArray.strings() = (0 until length()).map { getString(it) }
        internal fun buildHttpRequest(url: String, options: JSONObject): Request {
            val builder = Request.Builder().url(url).header("Accept", "application/json")
            val headers = options.optJSONObject("headers")
            headers?.keys()?.forEach { builder.header(it, headers.getString(it)) }
            val method = options.optString("method", "get").uppercase()
            val contentType = headers?.keys()?.asSequence()?.firstOrNull { it.equals("Content-Type", true) }?.let { headers.getString(it) }
            val form = options.optJSONObject("form")
            val formData = options.optJSONObject("formData")
            if (formData != null) builder.removeHeader("Content-Type") // OkHttp supplies the actual boundary.
            val body = when {
                form != null -> FormBody.Builder().apply { form.keys().forEach { add(it, form.get(it).toString()) } }.build()
                formData != null -> MultipartBody.Builder().setType(MultipartBody.FORM).apply {
                    formData.keys().forEach { key ->
                        val value = formData.get(key)
                        require(value is String || value is Number || value is Boolean) { "File multipart is unsupported" }
                        addFormDataPart(key, value.toString())
                    }
                }.build()
                options.has("body") -> {
                    val value = options.get("body")
                    val json = contentType?.startsWith("application/json") ?: (method == "POST")
                    val text = if (json && value is String) JSONObject.quote(value) else value.toString()
                    text.toRequestBody((contentType ?: if (json) "application/json" else "text/plain").toMediaTypeOrNull())
                }
                method in listOf("POST", "PUT", "PATCH") -> byteArrayOf().toRequestBody(null)
                else -> null
            }
            return builder.method(method, body).build()
        }
    }
}
