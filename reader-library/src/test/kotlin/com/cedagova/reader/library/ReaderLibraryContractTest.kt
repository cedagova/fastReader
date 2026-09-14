package com.cedagova.reader.library

import com.cedagova.reader.library.model.ReaderBook
import com.cedagova.reader.library.model.ReaderBookAsset
import com.cedagova.reader.library.model.ReaderBookAssetKind
import com.cedagova.reader.library.model.ReaderCapabilityEntry
import com.cedagova.reader.library.model.ReaderCapabilityQuota
import com.cedagova.reader.library.model.ReaderLibraryItem
import com.cedagova.reader.library.model.ReaderLibraryResponse
import com.cedagova.reader.library.model.ReaderProgress
import com.cedagova.reader.library.model.ReaderProgressListResponse
import com.cedagova.reader.library.model.ReaderPublicationMembershipOutcome
import com.cedagova.reader.library.model.ReaderResourceType
import com.cedagova.reader.library.model.ReaderSyncChange
import com.cedagova.reader.library.model.ReaderSyncConflict
import com.cedagova.reader.library.model.ReaderSyncDeltaResponse
import com.cedagova.reader.library.model.ReaderSyncMutationBatchRequest
import com.cedagova.reader.library.model.ReaderSyncMutationBatchResponse
import com.cedagova.reader.library.model.ReaderSyncMutationEnvelope
import com.cedagova.reader.library.model.ReaderSyncMutationResult
import com.cedagova.reader.library.model.ReaderSyncRejection
import com.cedagova.reader.library.model.UNKNOWN_VALUE
import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The drift gate (owner decision P1, 2026-09-14).
 *
 * `:reader-library` writes its models by hand rather than generating them, so
 * something has to fail the build when a model and the published Reader API
 * contract stop agreeing. This is that something. It reads the OpenAPI document
 * committed under `reader-library/contracts/`, confirms it is byte-for-byte the
 * pinned identity by sha256, and then — for every model the module sends or
 * reads — compares the model's own kotlinx.serialization descriptor with the
 * document's schema:
 *
 * - every field the model uses exists in the schema, under that exact wire name;
 * - its JSON type agrees (string / integer / number / boolean / array / object);
 * - its nullability agrees with the schema's `anyOf … null`;
 * - every enum member the model declares is a value the schema declares, and
 *   every value the schema declares has a member (the `__unknown__` sentinel,
 *   which is this module's forward-compatibility fallback and never goes on the
 *   wire, is excluded); and
 * - every field the schema marks required is present in the model.
 *
 * The expectations are *derived from the models*, not typed out beside them, so
 * renaming a field, changing its type or dropping a required one fails here
 * rather than at runtime against stage. `a renamed, retyped or dropped field is
 * caught` proves that claim on deliberately broken copies of three models: a
 * gate nobody has watched fail is not a gate.
 *
 * When reader-api genuinely changes, the fix is to re-pin the document and move
 * the models — never to relax this test. Drift is a proposal to Chunipers
 * (reader-api #511 / #512), never a local workaround.
 */
@OptIn(ExperimentalSerializationApi::class)
class ReaderLibraryContractTest {

    private val moduleDir = File(repositoryRoot(), "reader-library")
    private val contractFile = File(moduleDir, "contracts/reader-api.openapi.json")
    private val digestFile = File(moduleDir, "contracts/reader-api.openapi.json.sha256")

    private val document: JsonObject by lazy {
        Json.parseToJsonElement(contractFile.readText()).jsonObject
    }
    private val schemas: JsonObject by lazy {
        document["components"]!!.jsonObject["schemas"]!!.jsonObject
    }

    /**
     * Every model this module puts on the wire or reads off it, against the
     * schema that declares it. A model added to the module without a row here
     * is caught by [every model in the module is pinned to a schema].
     */
    private val pinned: Map<KSerializer<*>, String> = mapOf(
        ReaderLibraryResponse.serializer() to "ReaderLibraryResponse",
        ReaderLibraryItem.serializer() to "ReaderLibraryItem",
        ReaderBook.serializer() to "ReaderBook",
        ReaderBookAsset.serializer() to "ReaderBookAsset",
        ReaderProgressListResponse.serializer() to "ReaderProgressListResponse",
        ReaderProgress.serializer() to "ReaderProgress",
        ReaderSyncMutationBatchRequest.serializer() to "ReaderSyncMutationBatchRequest",
        ReaderSyncMutationEnvelope.serializer() to "ReaderSyncMutationEnvelope",
        ReaderSyncMutationBatchResponse.serializer() to "ReaderSyncMutationBatchResponse",
        ReaderSyncMutationResult.serializer() to "ReaderSyncMutationResult",
        ReaderSyncConflict.serializer() to "ReaderSyncConflict",
        ReaderSyncRejection.serializer() to "ReaderSyncRejection",
        ReaderPublicationMembershipOutcome.serializer() to "ReaderPublicationMembershipOutcome",
        ReaderSyncDeltaResponse.serializer() to "ReaderSyncDeltaResponse",
        ReaderSyncChange.serializer() to "ReaderSyncChange",
        ReaderCapabilityEntry.serializer() to "ReaderCapabilityEntry",
        ReaderCapabilityQuota.serializer() to "ReaderCapabilityQuota",
    )

    /** Kotlin descriptor serial name → the schema it must agree with, for `$ref` checks. */
    private val schemaOf: Map<String, String> by lazy {
        pinned.entries.associate { (serializer, schema) -> serializer.descriptor.serialName to schema }
    }

    @Test
    fun `the committed document is the pinned identity, byte for byte`() {
        assertTrue("missing ${contractFile.path}", contractFile.isFile)
        val recorded = digestFile.readText().trim().substringBefore(' ')
        val actual = MessageDigest.getInstance("SHA-256")
            .digest(contractFile.readBytes())
            .joinToString("") { "%02x".format(it) }
        assertEquals(
            "reader-library/contracts/reader-api.openapi.json does not match its recorded sha256; " +
                "re-pin it deliberately (contracts/PINNED.md) rather than editing the digest",
            recorded,
            actual,
        )
        assertEquals("a550abfd7046368681d02aec50e80e80e372b152416c744669dd72ee534c6f9e", actual)
    }

    @Test
    fun `every model the module sends or reads agrees with the pinned schema`() {
        val problems = pinned.entries.flatMap { (serializer, schema) ->
            violations(serializer.descriptor, schema)
        }
        assertEquals("the models and the pinned contract disagree:\n" + problems.joinToString("\n"), emptyList<String>(), problems)
    }

    /** A route this module calls that the document does not declare would be a request nobody promised. */
    @Test
    fun `every route the module calls is declared by the pinned document`() {
        val paths = document["paths"]!!.jsonObject
        listOf(
            ReaderLibraryClient.LIBRARY_PATH to "get",
            ReaderLibraryClient.PROGRESS_PATH to "get",
            ReaderLibraryClient.MUTATIONS_PATH to "post",
            ReaderLibraryClient.DELTAS_PATH to "get",
            "/v1/reader/capabilities" to "get",
        ).forEach { (path, method) ->
            val declared = paths[path]?.jsonObject
            assertTrue("the document declares no $path", declared != null)
            assertTrue("the document declares no $method on $path", declared!!.containsKey(method))
        }
        // The delta query parameters, with the bounds the client enforces locally.
        val parameters = paths[ReaderLibraryClient.DELTAS_PATH]!!.jsonObject["get"]!!.jsonObject["parameters"] as JsonArray
        val byName = parameters.associate { it.jsonObject["name"]!!.primitive()!! to it.jsonObject["schema"]!!.jsonObject }
        assertEquals("^[0-9]+$", byName["after_cursor"]!!["pattern"]!!.primitive())
        assertEquals(ReaderLibraryClient.MIN_DELTA_LIMIT.toString(), byName["limit"]!!["minimum"].toString())
        assertEquals(ReaderLibraryClient.MAX_DELTA_LIMIT.toString(), byName["limit"]!!["maximum"].toString())

        val mutations = schemas["ReaderSyncMutationBatchRequest"]!!.jsonObject["properties"]!!
            .jsonObject["mutations"]!!.jsonObject
        assertEquals(ReaderSyncMutationBatchRequest.MIN_MUTATIONS.toString(), mutations["minItems"].toString())
        assertEquals(ReaderSyncMutationBatchRequest.MAX_MUTATIONS.toString(), mutations["maxItems"].toString())

        assertTrue(
            "the sync capability key must be one the document declares",
            schemas["ReaderCapabilityEntry"]!!.jsonObject["properties"]!!.jsonObject["key"]!!
                .jsonObject["enum"]!!.let { it as JsonArray }
                .mapNotNull { it.primitive() }
                .contains(ReaderLibraryClient.SYNC_CAPABILITY_KEY),
        )
    }

    /**
     * The negative case the leaf asks for: the same checker, run against
     * deliberately broken copies of three models, must fail — once for a
     * renamed field, once for a retyped one, once for a dropped required one.
     */
    @Test
    fun `a renamed, retyped or dropped field is caught`() {
        val renamed = violations(RenamedLibraryItem.serializer().descriptor, "ReaderLibraryItem")
        assertTrue("a renamed field went unnoticed: $renamed", renamed.any { it.contains("bookk") && it.contains("not declared") })

        val retyped = violations(RetypedProgress.serializer().descriptor, "ReaderProgress")
        assertTrue("a retyped field went unnoticed: $retyped", retyped.any { it.contains("progress_percent") && it.contains("type") })

        val dropped = violations(DroppedRequiredChange.serializer().descriptor, "ReaderSyncChange")
        assertTrue("a dropped required field went unnoticed: $dropped", dropped.any { it.contains("cursor") && it.contains("required") })

        val shrunkEnum = violations(ShrunkEnumRejection.serializer().descriptor, "ReaderSyncRejection")
        assertTrue("a dropped enum member went unnoticed: $shrunkEnum", shrunkEnum.any { it.contains("unsupported_mutation") })

        // And the checker is not simply always angry: the real models are clean.
        assertEquals(emptyList<String>(), violations(ReaderSyncChange.serializer().descriptor, "ReaderSyncChange"))
    }

    // ------------------------------------------------------------- the checker

    private fun violations(descriptor: SerialDescriptor, schemaName: String): List<String> {
        val schema = schemas[schemaName]?.jsonObject
            ?: return listOf("$schemaName: the pinned document declares no such schema")
        val properties = schema["properties"]?.jsonObject ?: JsonObject(emptyMap())
        val required = (schema["required"] as? JsonArray)?.mapNotNull { it.primitive() }?.toSet() ?: emptySet()
        val problems = mutableListOf<String>()
        val seen = mutableSetOf<String>()

        for (index in 0 until descriptor.elementsCount) {
            val field = descriptor.getElementName(index)
            seen += field
            val element = descriptor.getElementDescriptor(index)
            val property = properties[field]?.jsonObject
            if (property == null) {
                problems += "$schemaName.$field: not declared by the pinned schema"
                continue
            }
            val resolved = resolve(property)
            if (element.isNullable && !resolved.nullable && field in required) {
                problems += "$schemaName.$field: the model makes it nullable but the schema requires a value"
            }
            if (!element.isNullable && resolved.nullable) {
                problems += "$schemaName.$field: the schema allows null but the model does not"
            }
            problems += checkShape(element, resolved, "$schemaName.$field")
        }

        (required - seen).forEach { missing ->
            problems += "$schemaName.$missing: the schema marks it required but the model has no such field"
        }
        return problems
    }

    /** One element's JSON type, enum members and `$ref` target against the resolved schema. */
    private fun checkShape(element: SerialDescriptor, resolved: Resolved, where: String): List<String> {
        val problems = mutableListOf<String>()
        val expected = jsonType(element)
        if (expected == null) {
            return listOf("$where: the model uses ${element.kind}, which this checker cannot compare")
        }
        if (resolved.ref != null) {
            // A nullable class descriptor's serial name carries a trailing '?'.
            val mapped = schemaOf[element.serialName.removeSuffix("?")]
            when {
                mapped == null -> problems += "$where: the schema is a \$ref to ${resolved.ref}, but ${element.serialName} is not pinned to any schema"
                mapped != resolved.ref -> problems += "$where: the schema refers to ${resolved.ref}, the model to $mapped"
            }
            if (expected != "object") problems += "$where: the schema is an object reference, the model a $expected"
            return problems
        }
        if (resolved.type != null && resolved.type != expected) {
            problems += "$where: the schema's type is ${resolved.type}, the model's is $expected"
        }
        if (element.kind == SerialKind.ENUM) {
            val declared = (resolved.schema["enum"] as? JsonArray)?.mapNotNull { it.primitive() }?.toSet()
            if (declared == null) {
                problems += "$where: the model is an enum but the schema declares no enum values"
            } else {
                val members = (0 until element.elementsCount)
                    .map { element.getElementName(it) }
                    .filterNot { it == UNKNOWN_VALUE }
                    .toSet()
                (members - declared).forEach { problems += "$where: the model declares '$it', which the schema does not" }
                (declared - members).forEach { problems += "$where: the schema declares '$it', which the model does not" }
            }
        }
        if (element.kind == StructureKind.LIST) {
            val items = resolved.schema["items"]?.jsonObject
            if (items == null) {
                problems += "$where: the model is an array but the schema declares no items"
            } else {
                problems += checkShape(element.getElementDescriptor(0), resolve(items), "$where[]")
            }
        }
        return problems
    }

    private data class Resolved(val schema: JsonObject, val type: String?, val nullable: Boolean, val ref: String?)

    /** Unwraps `anyOf [X, null]` and `$ref`, which is how the document spells "optional" and "another schema". */
    private fun resolve(property: JsonObject): Resolved {
        property["\$ref"]?.primitive()?.let { return Resolved(property, "object", false, it.substringAfterLast('/')) }
        val anyOf = property["anyOf"] as? JsonArray
        if (anyOf != null) {
            val branches = anyOf.map { it.jsonObject }
            val nullable = branches.any { it["type"]?.primitive() == "null" }
            val concrete = branches.firstOrNull { it["type"]?.primitive() != "null" } ?: JsonObject(emptyMap())
            val inner = resolve(concrete)
            return Resolved(inner.schema, inner.type, nullable || inner.nullable, inner.ref)
        }
        return Resolved(property, property["type"]?.primitive(), false, null)
    }

    /** The document's JSON type for a Kotlin element, or null when this checker has no opinion. */
    private fun jsonType(element: SerialDescriptor): String? = when (element.kind) {
        PrimitiveKind.STRING, PrimitiveKind.CHAR -> "string"
        PrimitiveKind.BYTE, PrimitiveKind.SHORT, PrimitiveKind.INT, PrimitiveKind.LONG -> "integer"
        PrimitiveKind.FLOAT, PrimitiveKind.DOUBLE -> "number"
        PrimitiveKind.BOOLEAN -> "boolean"
        SerialKind.ENUM -> "string"
        StructureKind.LIST -> "array"
        StructureKind.MAP, StructureKind.CLASS, StructureKind.OBJECT -> "object"
        PolymorphicKind.OPEN, PolymorphicKind.SEALED -> null
        else -> null
    }

    private fun kotlinx.serialization.json.JsonElement.primitive(): String? =
        (this as? JsonPrimitive)?.contentOrNull

    // ------------------------------------------------- deliberately broken copies

    /** `book` renamed: the wire name no longer exists in the schema. */
    @Serializable
    private data class RenamedLibraryItem(
        @SerialName("bookk") val book: ReaderBook,
        @SerialName("status") val status: String,
        @SerialName("created_at") val createdAt: String,
        @SerialName("updated_at") val updatedAt: String,
    )

    /** `progress_percent` retyped from the schema's `number` to a string. */
    @Serializable
    private data class RetypedProgress(
        @SerialName("book_id") val bookId: String,
        @SerialName("progress_percent") val progressPercent: String,
        @SerialName("updated_at") val updatedAt: String,
    )

    /** The required `cursor` dropped from a change row. */
    @Serializable
    private data class DroppedRequiredChange(
        @SerialName("resource_type") val resourceType: ReaderResourceType,
        @SerialName("resource_id") val resourceId: String,
        @SerialName("revision") val revision: Long,
        @SerialName("kind") val kind: String,
        @SerialName("server_admitted_at") val serverAdmittedAt: String,
    )

    /** A rejection whose code enum has quietly lost one of the schema's members. */
    @Serializable
    private data class ShrunkEnumRejection(
        @SerialName("code") val code: ShrunkRejectionCode,
        @SerialName("detail") val detail: String,
    )

    @Serializable
    private enum class ShrunkRejectionCode {
        @SerialName("invalid_payload") INVALID_PAYLOAD,
        @SerialName("invalid_resource_id") INVALID_RESOURCE_ID,
        @SerialName("idempotency_mismatch") IDEMPOTENCY_MISMATCH,
        @SerialName("related_resource_missing") RELATED_RESOURCE_MISSING,
    }

    /**
     * Every `@Serializable` model class in `…library.model` is in [pinned].
     * Without this, a model added later would simply never be checked.
     */
    @Test
    fun `every model in the module is pinned to a schema`() {
        val sourceDir = File(moduleDir, "src/main/kotlin/com/cedagova/reader/library/model")
        val declared = sourceDir.listFiles()!!
            .filter { it.extension == "kt" }
            .flatMap { file ->
                Regex("""@Serializable\s+data class (\w+)""").findAll(file.readText()).map { it.groupValues[1] }
            }
            .toSet()
        val pinnedNames = pinned.values.toSet()
        assertEquals(
            "a model exists that the contract test never compares with the document",
            emptySet<String>(),
            declared - pinnedNames,
        )
    }
}
