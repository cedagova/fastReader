package com.cedagova.reader.auth.host

import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * One HTTPS `GET` through the platform's own client.
 *
 * This is the host's proof that the merged `INTERNET` permission and the
 * device's network actually work, and nothing more: no HTTP stack is chosen
 * here (that decision is #93's), no body is read, redirects are not followed,
 * and there is no retry — one call, one result. Every failure becomes a
 * [ProbeResult.Failure] rather than an exception, so an emulator without a
 * network shows a reason on screen instead of a crash dialog.
 */
object HttpsProbe {

    /**
     * The compiled-in target: the endpoint Android's own connectivity check
     * uses. It answers `204 No Content` with an empty body, so a `204` on screen
     * is the documented success and anything else is worth reading.
     */
    val DEFAULT_URL: URL = URL("https://www.gstatic.com/generate_204")

    private const val TIMEOUT_MS = 10_000

    /** Blocking; call it off the main thread. */
    fun get(url: URL = DEFAULT_URL): ProbeResult {
        require(url.protocol == "https") { "the probe only speaks HTTPS, not ${url.protocol}" }
        return try {
            val connection = url.openConnection() as HttpsURLConnection
            try {
                connection.connectTimeout = TIMEOUT_MS
                connection.readTimeout = TIMEOUT_MS
                connection.requestMethod = "GET"
                connection.instanceFollowRedirects = false
                ProbeResult.Response(connection.responseCode)
            } finally {
                connection.disconnect()
            }
        } catch (e: Exception) {
            ProbeResult.Failure(e.javaClass.simpleName, e.message.orEmpty())
        }
    }
}

sealed interface ProbeResult {
    data class Response(val status: Int) : ProbeResult
    data class Failure(val exceptionType: String, val message: String) : ProbeResult
}

/** The one line shown on screen and written to logcat for a result. */
fun ProbeResult.describe(url: URL): String = when (this) {
    is ProbeResult.Response -> "HTTPS $status from ${url.host}"
    is ProbeResult.Failure -> "Failed: $exceptionType" + if (message.isBlank()) "" else " — $message"
}
