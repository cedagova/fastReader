package com.cedagova.fastreader.library.store

import com.cedagova.fastreader.library.Catalog
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Result of turning stored bytes back into a [Catalog]. */
sealed interface CatalogDecoding {

    /** Decoded successfully; [migratedFrom] is set when older bytes were upgraded on the way in. */
    data class Decoded(val catalog: Catalog, val migratedFrom: Int? = null) : CatalogDecoding

    /** The bytes are damaged or not a catalog at all; the caller recovers by starting over. */
    data class Damaged(val message: String) : CatalogDecoding

    /**
     * The document was written by a newer schema than this build understands.
     * The caller must not overwrite it — that would destroy the reader's library.
     */
    data class Newer(val documentVersion: Int, val supportedVersion: Int) : CatalogDecoding
}

/**
 * Encodes and decodes the catalog document, applying [migrations] on the way in.
 *
 * [currentVersion] and [migrations] are injectable so the migration chain itself
 * can be exercised by tests before a real second version exists.
 */
class CatalogCodec(
    private val json: Json = defaultJson,
    private val currentVersion: Int = CatalogSchema.CURRENT_VERSION,
    private val migrations: Map<Int, CatalogMigration> = CatalogSchema.MIGRATIONS,
) {

    fun encode(catalog: Catalog): String =
        json.encodeToString(Catalog.serializer(), catalog.copy(schemaVersion = currentVersion))

    fun decode(text: String): CatalogDecoding {
        val root = try {
            json.parseToJsonElement(text) as? JsonObject
                ?: return CatalogDecoding.Damaged("catalog document is not a JSON object")
        } catch (error: Exception) {
            return CatalogDecoding.Damaged(error.message ?: "catalog document is not valid JSON")
        }

        val documentVersion = root["schemaVersion"]?.jsonPrimitive?.intOrNull
            ?: return CatalogDecoding.Damaged("catalog document has no schemaVersion")
        if (documentVersion > currentVersion) {
            return CatalogDecoding.Newer(documentVersion, currentVersion)
        }
        if (documentVersion < 1) {
            return CatalogDecoding.Damaged("catalog document has an invalid schemaVersion $documentVersion")
        }

        var working = root
        var version = documentVersion
        while (version < currentVersion) {
            val step = migrations[version]
                ?: return CatalogDecoding.Damaged("no migration from catalog schema version $version")
            working = step.migrate(working)
            version++
            working = JsonObject(working + ("schemaVersion" to JsonPrimitive(version)))
        }

        return try {
            val catalog = json.decodeFromJsonElement(Catalog.serializer(), working)
            CatalogDecoding.Decoded(
                catalog = catalog.copy(schemaVersion = currentVersion),
                migratedFrom = documentVersion.takeIf { it != currentVersion },
            )
        } catch (error: Exception) {
            CatalogDecoding.Damaged(error.message ?: "catalog document does not match the schema")
        }
    }

    companion object {
        /**
         * Lenient about unknown keys so a catalog written by a newer *compatible*
         * build (same version, extra field) still loads instead of being discarded.
         *
         * [Json.coerceInputValues] is the same tolerance one level down, and it is
         * what makes the settings schema's "absent or unusable key reads back as
         * its documented default" promise hold for a *present* key too. Without it
         * a single unrecognised enum entry — a pivot colour or theme from a build
         * the reader downgraded from, or a value a partial write truncated — throws
         * during decode, and this codec's only answer to a throw is
         * [CatalogDecoding.Damaged], which sets the whole library aside. One
         * unreadable preference must never cost a reader their books and their
         * places; it costs them that one preference, which returns to its default.
         */
        val defaultJson: Json = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
            encodeDefaults = true
            prettyPrint = false
        }
    }
}
