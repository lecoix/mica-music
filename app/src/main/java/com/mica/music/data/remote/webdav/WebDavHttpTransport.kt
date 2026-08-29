package com.mica.music.data.remote.webdav

import com.burgstaller.okhttp.DispatchingAuthenticator
import com.burgstaller.okhttp.basic.BasicAuthenticator
import com.burgstaller.okhttp.digest.Credentials as DigestCredentials
import com.burgstaller.okhttp.digest.DigestAuthenticator
import java.io.IOException
import okhttp3.Authenticator
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route

/**
 * Challenge authentication for one WebDAV origin.
 *
 * Digest construction is delegated to okhttp-digest. Mica owns only the security boundary:
 * credentials are scoped to one origin, never sent preemptively, and a failed challenge is not
 * allowed to loop indefinitely. Redirects remain disabled by the caller.
 */
internal class WebDavHttpAuthenticator(
    origin: String,
    username: String,
    password: String,
) : Authenticator {
    private val allowedOrigin = WebDavPathCodec.origin(origin)
    private val delegate: Authenticator = DigestCredentials(username, password).let { credentials ->
        DispatchingAuthenticator.Builder()
            .with("digest", DigestAuthenticator(credentials))
            .with("basic", BasicAuthenticator(credentials))
            .build()
    }

    override fun authenticate(route: Route?, response: Response): Request? {
        if (responseCount(response) >= MAX_RESPONSE_COUNT) return null
        if (WebDavPathCodec.origin(response.request.url.toString()) != allowedOrigin) return null
        if (response.request.header("Authorization") != null) return null
        return delegate.authenticate(route, response)
    }

    private fun responseCount(response: Response): Int {
        var count = 1
        var current = response.priorResponse
        while (current != null) {
            count++
            current = current.priorResponse
        }
        return count
    }

    companion object {
        /** Initial 401/407 may produce one authenticated retry; a second response stops. */
        private const val MAX_RESPONSE_COUNT = 2
    }
}

internal class WebDavStrictRangeInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val rangeStart = request.header("Range")?.let(::parseRangeStart)
        val response = chain.proceed(request)
        if (rangeStart == null) return response

        try {
            when (response.code) {
                206 -> {
                    val actualStart = response.header("Content-Range")?.let(::parseContentRangeStart)
                    if (actualStart != rangeStart) {
                        throw WebDavRangeException(
                            "WebDAV Content-Range start mismatch: requested=$rangeStart actual=${actualStart ?: "missing"}",
                        )
                    }
                }
                200 -> if (rangeStart > 0L) {
                    throw WebDavRangeException("WebDAV server ignored non-zero Range request at $rangeStart")
                }
            }
            return response
        } catch (failure: Throwable) {
            response.close()
            throw failure
        }
    }

    private fun parseRangeStart(header: String): Long? {
        val value = header.trim()
        if (!value.startsWith("bytes=", ignoreCase = true)) return null
        return value.substringAfter('=').substringBefore('-').trim().toLongOrNull()
    }

    private fun parseContentRangeStart(header: String): Long? {
        val value = header.trim()
        if (!value.startsWith("bytes ", ignoreCase = true)) return null
        return value.substringAfter(' ').substringBefore('-').trim().toLongOrNull()
    }
}

internal class WebDavRangeException(message: String) : IOException(message)