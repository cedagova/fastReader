package com.cedagova.fastreader.account.library

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Result of turning stored bytes back into an [AccountLibraryDocument]. */
sealed interface AccountLibraryDecoding {

    /** Decoded successfully; [migratedFrom] is set when older bytes were upgraded on the way in. */
    data class Decoded(
        val document: AccountLibraryDocument,
        val migratedFrom: Int? = null,
    ) : AccountLibraryDecoding

    /** The bytes are damaged or not an account document at all; the caller re-bootstraps. */
    data class Damaged(val message: String) : AccountLibraryDecoding

    /**
     * Written by a newer schema than this build understands. The caller must
     * not overwrite it: the queue inside it belongs to the newer build, and a
     * rewrite would drop entries this one cannot even represent.
     */
    data class Newer(val documentVersion: Int, val supportedVersion: Int) : AccountLibraryDecoding
}

/**
 * Encodes and decodes the account-library document, applying [migrations] on
 * the way in — the same shape as `CatalogCodec`, for the same reasons, over a
 * different document.
 *
 * [currentVersion] and [migrations] are injectable so the migration chain can
 * be exercised before a real second version exists.
 */
class AccountLibraryCodec(
    private val json: Json = defaultJson,
    private val currentVersion: Int = AccountLibrarySchema.CURRENT_VERSION,
    private val migrations: Map<Int, AccountLibraryMigration> = AccountLibrarySchema.MIGRATIONS,
) {

    fun encode(document: AccountLibraryDocument): String =
        json.encodeToString(AccountLibraryDocument.serializer(), document.copy(schemaVersion = currentVersion))

    fun decode(text: String): AccountLibraryDecoding {
        val root = try {
            json.parseToJsonElement(text) as? JsonObject
                ?: return AccountLibraryDecoding.Damaged("account document is not a JSON object")
        } catch (error: Exception) {
            return AccountLibraryDecoding.Damaged(error.message ?: "account document is not valid JSON")
        }

        val documentVersion = root["schemaVersion"]?.jsonPrimitive?.intOrNull
            ?: return AccountLibraryDecoding.Damaged("account document has no schemaVersion")
        if (documentVersion > currentVersion) {
            return AccountLibraryDecoding.Newer(documentVersion, currentVersion)
        }
        if (documentVersion < 1) {
            return AccountLibraryDecoding.Damaged("account document has an invalid schemaVersion $documentVersion")
        }

        var working = root
        var version = documentVersion
        while (version < currentVersion) {
            val step = migrations[version]
                ?: return AccountLibraryDecoding.Damaged("no migration from account schema version $version")
            working = step.migrate(working)
            version++
            working = JsonObject(working + ("schemaVersion" to JsonPrimitive(version)))
        }

        return try {
            val document = json.decodeFromJsonElement(AccountLibraryDocument.serializer(), working)
            AccountLibraryDecoding.Decoded(
                document = document.copy(schemaVersion = currentVersion),
                migratedFrom = documentVersion.takeIf { it != currentVersion },
            )
        } catch (error: Exception) {
            AccountLibraryDecoding.Damaged(error.message ?: "account document does not match the schema")
        }
    }

    companion object {
        /**
         * Tolerant on the way in for the catalog codec's reason and one more of
         * this document's own: the values it stores are the *server's* enums, so
         * a status or cover classification reader-api adds later must read back
         * as that enum's `UNKNOWN` member and cost the reader one row's label,
         * never the whole account document — and with it the unsent queue, which
         * is the only thing in here the backend does not already hold.
         */
        val defaultJson: Json = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
            encodeDefaults = true
            prettyPrint = false
        }
    }
}
