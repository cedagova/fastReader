package com.cedagova.reader.library

import java.net.URI

/**
 * The one rule both storage clients apply before a grant's headers go
 * anywhere: they go only to the grant URL's own origin (#142).
 *
 * An origin is scheme, host and effective port, compared the way a browser
 * does — case-insensitively, with an absent port read as the scheme's default.
 * So `https://storage.test/a` and `HTTPS://Storage.test:443/b` are one origin,
 * while a different host, a different port and an https→http downgrade are
 * each another.
 *
 * Anything that does not parse as an absolute http(s) URL is never the same
 * origin: a check that cannot tell must refuse, not wave the headers through.
 */
internal object GrantOrigin {

    /** True when [candidate] is on exactly [grantUrl]'s origin. */
    fun sameAs(grantUrl: String, candidate: String): Boolean {
        val expected = originOf(grantUrl) ?: return false
        val actual = originOf(candidate) ?: return false
        return expected == actual
    }

    private data class Origin(val scheme: String, val host: String, val port: Int)

    private fun originOf(url: String): Origin? {
        val uri = try {
            URI(url)
        } catch (e: java.net.URISyntaxException) {
            return null
        }
        val scheme = uri.scheme?.lowercase() ?: return null
        val host = uri.host?.lowercase() ?: return null
        val port = when {
            uri.port != -1 -> uri.port
            scheme == "https" -> 443
            scheme == "http" -> 80
            else -> return null
        }
        return Origin(scheme, host, port)
    }
}
