package com.cedagova.reader.library

import kotlinx.serialization.json.Json

/**
 * The one JSON configuration every model in this module is encoded and decoded
 * with. Three settings, each load-bearing:
 *
 * - `ignoreUnknownKeys`: a field reader-api adds later is skipped, not fatal.
 * - `coerceInputValues`: an enum member reader-api adds later decodes to the
 *   model's `UNKNOWN` default instead of throwing, so an older client
 *   still reads a newer server's rows (see `UNKNOWN_VALUE`).
 * - `explicitNulls = false`: an absent optional is omitted from a request
 *   rather than sent as an explicit `null`.
 *
 * Forward-compatibility is not permission to drift: `ReaderLibraryContractTest`
 * pins every field, type, enum member and required flag this module actually
 * uses against the committed OpenAPI document.
 */
internal val ReaderLibraryJson: Json = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    explicitNulls = false
    encodeDefaults = true
}
