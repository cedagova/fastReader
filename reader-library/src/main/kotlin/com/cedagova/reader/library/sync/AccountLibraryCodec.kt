package com.cedagova.reader.library.sync

import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
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
 *
 * It is also where host records cross the boundary (#147): on the way in, every
 * key of the document or of a book row that the schema does not declare is
 * gathered into that level's `host` object; on the way out, those entries are
 * written back as keys of the same level. A declared key always wins — a host
 * record can add to a level, never shadow what the schema says there.
 */
class AccountLibraryCodec(
    private val json: Json = defaultJson,
    private val currentVersion: Int = AccountLibrarySchema.CURRENT_VERSION,
    private val migrations: Map<Int, AccountLibraryMigration> = AccountLibrarySchema.MIGRATIONS,
) {

    fun encode(document: AccountLibraryDocument): String {
        val encoded = json.encodeToJsonElement(
            AccountLibraryDocument.serializer(),
            document.copy(schemaVersion = currentVersion),
        ).jsonObject
        val books = (encoded["books"] as? JsonArray)?.let { rows ->
            JsonArray(rows.map { row -> flatten(row.jsonObject, BOOK_KEYS) })
        }
        val flat = flatten(encoded, DOCUMENT_KEYS)
        return json.encodeToString(
            JsonObject.serializer(),
            if (books == null) flat else JsonObject(flat + ("books" to books)),
        )
    }

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
            val document = json.decodeFromJsonElement(AccountLibraryDocument.serializer(), gatherHostRecords(working))
            AccountLibraryDecoding.Decoded(
                document = document.copy(schemaVersion = currentVersion),
                migratedFrom = documentVersion.takeIf { it != currentVersion },
            )
        } catch (error: Exception) {
            AccountLibraryDecoding.Damaged(error.message ?: "account document does not match the schema")
        }
    }

    /** Undeclared keys of the document and of every book row, gathered into each level's `host`. */
    private fun gatherHostRecords(root: JsonObject): JsonObject {
        val books = (root["books"] as? JsonArray)?.let { rows ->
            JsonArray(rows.map { row -> (row as? JsonObject)?.let { gather(it, BOOK_KEYS) } ?: row })
        }
        val gathered = gather(root, DOCUMENT_KEYS)
        return if (books == null) gathered else JsonObject(gathered + ("books" to books))
    }

    companion object {

        /**
         * The keys a document-level host record may not be named (#149): every key
         * the schema declares at that level, which would win over it on the way
         * out, and `host` itself, which the way in drops. A record under either
         * would be written and then silently lost, so [AccountHostRecords] refuses
         * both at write time.
         */
        val RESERVED_DOCUMENT_KEYS: Set<String> by lazy { DOCUMENT_KEYS + HOST_RECORDS_KEY }

        /** The keys a book row's host record may not be named, for the same reason. */
        val RESERVED_BOOK_KEYS: Set<String> by lazy { BOOK_KEYS + HOST_RECORDS_KEY }

        /** The keys the schema declares for a document, read from its serializer. */
        private val DOCUMENT_KEYS: Set<String> = declared(AccountLibraryDocument.serializer().descriptor)

        /** The keys the schema declares for a book row, read from its serializer. */
        private val BOOK_KEYS: Set<String> = declared(AccountBook.serializer().descriptor)

        private fun declared(descriptor: SerialDescriptor): Set<String> =
            (0 until descriptor.elementsCount).map(descriptor::getElementName).toSet() - HOST_RECORDS_KEY

        /** [level] with its undeclared keys moved into its `host` object; a stray `host` key is dropped. */
        private fun gather(level: JsonObject, declared: Set<String>): JsonObject {
            val (own, host) = level.entries
                .filter { it.key != HOST_RECORDS_KEY }
                .partition { it.key in declared }
            val ownMap: Map<String, JsonElement> = own.associate { it.key to it.value }
            if (host.isEmpty()) return JsonObject(ownMap)
            return JsonObject(ownMap + (HOST_RECORDS_KEY to JsonObject(host.associate { it.key to it.value })))
        }

        /** [level] with its `host` entries written back as keys of the same level; declared keys win. */
        private fun flatten(level: JsonObject, declared: Set<String>): JsonObject {
            val host = level[HOST_RECORDS_KEY] as? JsonObject
            val own = level - HOST_RECORDS_KEY
            if (host.isNullOrEmpty()) return JsonObject(own)
            return JsonObject(own + host.filterKeys { it !in declared })
        }

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
