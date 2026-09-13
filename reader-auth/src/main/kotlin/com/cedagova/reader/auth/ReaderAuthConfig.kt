package com.cedagova.reader.auth

/**
 * Everything a host hands the module. All three service values are public
 * browser-runtime configuration of the Reader deployment (never a service
 * key), but they are still never committed: the host reads them from its
 * untracked `local.properties` or environment (CONTRACT.md, "Host
 * requirements").
 *
 * @property supabaseUrl the identity provider's project URL.
 * @property publishableKey the identity provider's publishable key; the
 *   bootstrap check refuses to sign in unless the reader-api pre-auth document
 *   names this same key.
 * @property readerApiBaseUrl the reader-api origin, without a trailing slash.
 * @property clientId the `X-Reader-Client` selector; reader-api projects its
 *   contract per client and fails closed on an unknown one.
 * @property clientVersion the `clientVersion` query value on bootstrap and
 *   capabilities.
 */
data class ReaderAuthConfig(
    val supabaseUrl: String,
    val publishableKey: String,
    val readerApiBaseUrl: String,
    val clientId: String = ReaderAuthPolicy.CLIENT_ID,
    val clientVersion: String = ReaderAuthPolicy.CLIENT_VERSION,
) {
    /** False when any service value is blank; a host then shows "not configured" and calls nothing. */
    val isConfigured: Boolean
        get() = supabaseUrl.isNotBlank() && publishableKey.isNotBlank() && readerApiBaseUrl.isNotBlank()

    /** The base URL with any trailing slash removed, so paths can be appended verbatim. */
    val readerApiOrigin: String
        get() = readerApiBaseUrl.trimEnd('/')
}
