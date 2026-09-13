package com.cedagova.reader.auth.api

import com.cedagova.reader.auth.ReaderAuthConfig
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * The subset of reader-api's `GET /v1/reader/pre-auth` document
 * (`reader.pre-auth.v1`) the bootstrap check reads. Unknown fields are
 * ignored so a server addition never breaks a client.
 */
@Serializable
data class PreAuthDocument(
    val schemaVersion: String? = null,
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
    data class AccountEntry(val availability: String? = null, val reason: String? = null, val retryable: Boolean = false)

    @Serializable
    data class Configuration(
        val revision: String? = null,
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

        private val json = Json { ignoreUnknownKeys = true }

        fun parse(body: JsonObject): PreAuthDocument = json.decodeFromJsonElement(serializer(), body)
    }
}
