package com.cedagova.reader.auth.api

import com.cedagova.reader.auth.ReaderAuthConfig
import kotlin.time.Duration
import kotlin.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** A sign-in method the pre-auth document can turn on or off (#160). */
enum class SignInMethod {
    /** The emailed six-digit code (`authentication.emailOtp`). */
    EMAIL_CODE,

    /** Email and password, and the code-based password recovery that sets one (`"password"` in `authentication.enabledProviders`). */
    PASSWORD,
}

/**
 * The subset of reader-api's `GET /v1/reader/pre-auth` document
 * (`reader.pre-auth.v1`) the bootstrap check reads. Unknown fields are
 * ignored so a server addition never breaks a client. The timestamps are
 * kept as the server's strings and read leniently, so an unreadable one
 * makes the document uncacheable instead of unparseable.
 */
@Serializable
data class PreAuthDocument(
    val schemaVersion: String? = null,
    val generatedAt: String? = null,
    val freshUntil: String? = null,
    val staleUntil: String? = null,
    val compatibility: Compatibility? = null,
    val accountEntry: AccountEntry? = null,
    val configuration: Configuration? = null,
    val postAuth: PostAuth? = null,
) {
    @Serializable
    data class Compatibility(
        val status: String? = null,
        val requestedVersion: String? = null,
        val minimumVersion: String? = null,
        val supportedMajor: Int? = null,
    )

    @Serializable
    data class AccountEntry(
        val availability: String? = null,
        val reason: String? = null,
        val retryable: Boolean = false,
    )

    @Serializable
    data class Configuration(
        val revision: String? = null,
        val validUntil: String? = null,
        val client: Client? = null,
        val authentication: Authentication? = null,
    )

    @Serializable
    data class Client(val applicationId: String? = null)

    @Serializable
    data class Authentication(
        val kind: String? = null,
        val authorityOrigin: String? = null,
        val publicClientId: String? = null,
        val enabledProviders: List<String> = emptyList(),
        val emailOtp: Boolean = false,
    )

    @Serializable
    data class PostAuth(val schemaVersion: String? = null, val path: String? = null)

    /**
     * The sign-in methods the server has turned on for this client; a host
     * offers only these. Empty when the document carries no authentication
     * section.
     */
    val enabledMethods: Set<SignInMethod>
        get() {
            val authentication = configuration?.authentication ?: return emptySet()
            return buildSet {
                if (authentication.emailOtp) add(SignInMethod.EMAIL_CODE)
                if (PASSWORD_PROVIDER in authentication.enabledProviders) add(SignInMethod.PASSWORD)
            }
        }

    /** Whether the server allows account entry at all (`accountEntry.availability` is `available`). */
    val accountEntryAvailable: Boolean
        get() = accountEntry?.availability == AVAILABLE

    /**
     * How long this document may be used without asking again, and how long
     * it may still stand in when asking again fails, as instants on the
     * device clock (CONTRACT.md, "Bootstrap and first calls").
     *
     * The server's bounds are moved by the difference between [receivedAt]
     * and `generatedAt`, so a device clock that is off does not stretch or
     * shrink them. `configuration.validUntil` caps both. A bound that is
     * missing or unreadable is [receivedAt] itself: the document is used for
     * the call that fetched it and never again.
     */
    internal fun lifetime(receivedAt: Instant): Lifetime {
        val offset = instant(generatedAt)?.let { receivedAt - it } ?: Duration.ZERO
        val validUntil = instant(configuration?.validUntil)
        fun local(bound: String?): Instant {
            val server = instant(bound) ?: return receivedAt
            val capped = if (validUntil != null && validUntil < server) validUntil else server
            return capped + offset
        }
        return Lifetime(freshUntil = local(freshUntil), staleUntil = local(staleUntil))
    }

    internal data class Lifetime(val freshUntil: Instant, val staleUntil: Instant)

    /**
     * Why this document does not describe [config], or `null` when it does
     * (CONTRACT.md, "Bootstrap and first calls"). The publishable key is never
     * echoed into the reason.
     */
    fun mismatch(config: ReaderAuthConfig): String? {
        val status = compatibility?.status
        if (status != COMPATIBLE) return "compatibility status is ${status ?: "missing"}, not $COMPATIBLE"
        val configuration = configuration
            ?: return "no configuration for client ${config.clientId}" +
                (accountEntry?.reason?.let { " ($it)" } ?: "")
        val applicationId = configuration.client?.applicationId
        if (applicationId != config.clientId) {
            return "client.applicationId is ${applicationId ?: "missing"}, expected ${config.clientId}"
        }
        val authentication = configuration.authentication ?: return "no authentication section"
        if (authentication.publicClientId != config.publishableKey) {
            return "authentication.publicClientId differs from the configured publishable key"
        }
        val authority = authentication.authorityOrigin?.trimEnd('/')
        val expected = config.supabaseUrl.trimEnd('/')
        if (authority != null && !authority.equals(expected, ignoreCase = true)) {
            return "authentication.authorityOrigin is $authority, expected $expected"
        }
        return null
    }

    companion object {
        const val COMPATIBLE: String = "compatible"
        const val AVAILABLE: String = "available"
        private const val PASSWORD_PROVIDER = "password"

        private fun instant(value: String?): Instant? = value?.let { runCatching { Instant.parse(it) }.getOrNull() }

        private val json = Json { ignoreUnknownKeys = true }

        fun parse(body: JsonObject): PreAuthDocument = json.decodeFromJsonElement(serializer(), body)
    }
}
