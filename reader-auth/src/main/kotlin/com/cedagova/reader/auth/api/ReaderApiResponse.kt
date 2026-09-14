package com.cedagova.reader.auth.api

import kotlinx.serialization.json.JsonObject

/**
 * A successful reader-api answer together with the `X-Request-ID` the call
 * carried (#100). reader-api echoes the id it was sent, so a host can show it
 * beside the document and a server log can be read against the screen. A
 * failed call reports its id on the [com.cedagova.reader.auth.ReaderAuthException]
 * branch instead, as it always has.
 */
data class ReaderApiResponse(
    /** The response body, as returned. */
    val document: JsonObject,
    /** The lowercase-UUID `X-Request-ID` header the successful attempt sent. */
    val requestId: String,
)
