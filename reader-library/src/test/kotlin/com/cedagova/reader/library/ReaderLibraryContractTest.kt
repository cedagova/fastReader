package com.cedagova.reader.library

import com.cedagova.reader.library.model.CancelPublicationImportRequest
import com.cedagova.reader.library.model.CreatePublicationImportRequest
import com.cedagova.reader.library.model.PublicationArchivePolicy
import com.cedagova.reader.library.model.PublicationFormatPolicy
import com.cedagova.reader.library.model.PublicationImport
import com.cedagova.reader.library.model.PublicationImportAdmissionResponse
import com.cedagova.reader.library.model.PublicationImportFailure
import com.cedagova.reader.library.model.PublicationImportPolicyResponse
import com.cedagova.reader.library.model.PublicationImportResponse
import com.cedagova.reader.library.model.PublicationOwnershipPolicy
import com.cedagova.reader.library.model.PublicationPromotion
import com.cedagova.reader.library.model.PublicationTransferGrant
import com.cedagova.reader.library.model.ReaderAssetDirection
import com.cedagova.reader.library.model.ReaderAssetGrant
import com.cedagova.reader.library.model.ReaderAssetGrantResponse
import com.cedagova.reader.library.model.ReaderAssetMethod
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
import org.junit.Assert.assertFalse
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
        // The publication-import lifecycle (#116).
        CreatePublicationImportRequest.serializer() to "CreatePublicationImportRequest",
        CancelPublicationImportRequest.serializer() to "CancelPublicationImportRequest",
        PublicationImportPolicyResponse.serializer() to "PublicationImportPolicyResponse",
        PublicationFormatPolicy.serializer() to "PublicationFormatPolicy",
        PublicationOwnershipPolicy.serializer() to "PublicationOwnershipPolicy",
        PublicationArchivePolicy.serializer() to "PublicationArchivePolicy",
        PublicationTransferGrant.serializer() to "PublicationTransferGrant",
        PublicationImport.serializer() to "PublicationImport",
        PublicationPromotion.serializer() to "PublicationPromotion",
        PublicationImportFailure.serializer() to "PublicationImportFailure",
        PublicationImportAdmissionResponse.serializer() to "PublicationImportAdmissionResponse",
        PublicationImportResponse.serializer() to "PublicationImportResponse",
        // The asset download grant (#118).
        ReaderAssetGrant.serializer() to "ReaderAssetGrant",
        ReaderAssetGrantResponse.serializer() to "ReaderAssetGrantResponse",
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
            // The import lifecycle. Note the other prefix: `/reader/v1/...`.
            ReaderLibraryClient.IMPORT_POLICY_PATH to "get",
            ReaderLibraryClient.IMPORTS_PATH to "post",
            "${ReaderLibraryClient.IMPORTS_PATH}/${ReaderLibraryClient.IMPORT_ID_TEMPLATE}" to "get",
            "${ReaderLibraryClient.IMPORTS_PATH}/${ReaderLibraryClient.IMPORT_ID_TEMPLATE}${ReaderLibraryClient.COMPLETE_SUFFIX}" to "post",
            "${ReaderLibraryClient.IMPORTS_PATH}/${ReaderLibraryClient.IMPORT_ID_TEMPLATE}${ReaderLibraryClient.CANCEL_SUFFIX}" to "post",
            // The asset download grant (#118): the one route book bytes arrive by.
            "${ReaderLibraryClient.ASSETS_PATH}/${ReaderLibraryClient.ASSET_ID_TEMPLATE}${ReaderLibraryClient.DOWNLOAD_GRANT_SUFFIX}" to "post",
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
     * The import constants this module puts on the wire, against the document's
     * own `const` declarations, plus the two client-side bounds.
     *
     * The shape checker above compares fields and types; these are *values* —
     * `promotion_source`, `ownership_intent`, the cancel reason's default and
     * the `client_import_id` length — and a wrong value is a 422 against stage
     * that no type check would have caught.
     */
    @Test
    fun `the import constants the module sends are the document's own`() {
        val request = schemas["CreatePublicationImportRequest"]!!.jsonObject["properties"]!!.jsonObject
        assertEquals(
            CreatePublicationImportRequest.PROMOTION_SOURCE_DEVICE_ONLY,
            request["promotion_source"]!!.constValue(),
        )
        assertEquals(
            CreatePublicationImportRequest.OWNERSHIP_INTENT_ACCOUNT_LIBRARY,
            request["ownership_intent"]!!.constValue(),
        )
        assertEquals(
            CreatePublicationImportRequest.MAX_CLIENT_IMPORT_ID_LENGTH.toString(),
            request["client_import_id"]!!.jsonObject["maxLength"].toString(),
        )
        assertEquals(
            "the module's sha256 guard must be the schema's own pattern",
            "^(?:sha256:)?[0-9A-Fa-f]{64}$",
            request["sha256"]!!.jsonObject["pattern"]!!.primitive(),
        )

        val cancel = schemas["CancelPublicationImportRequest"]!!.jsonObject["properties"]!!.jsonObject["reason"]!!.jsonObject
        assertEquals(CancelPublicationImportRequest.DEFAULT_REASON, cancel["default"]!!.primitive())
        assertEquals(CancelPublicationImportRequest.MAX_REASON_LENGTH.toString(), cancel["maxLength"].toString())

        val grant = schemas["PublicationTransferGrant"]!!.jsonObject["properties"]!!.jsonObject
        assertEquals(PublicationTransferGrant.PROTOCOL_TUS, grant["protocol"]!!.constValue())
        assertEquals(PublicationTransferGrant.METHOD_POST, grant["method"]!!.constValue())
        assertTrue(
            "the document must still promise a signed creation endpoint",
            grant["endpoint"]!!.jsonObject["description"]!!.primitive()!!
                .contains(PublicationTransferGrant.SIGNED_ENDPOINT_SUFFIX),
        )

        val promotion = schemas["PublicationPromotion"]!!.jsonObject["properties"]!!.jsonObject
        assertEquals(PublicationPromotion.SOURCE_DEVICE_ONLY, promotion["source"]!!.constValue())
        assertEquals(PublicationPromotion.DESTINATION_ACCOUNT_LIBRARY, promotion["destination"]!!.constValue())

        val failure = schemas["PublicationImportFailure"]!!.jsonObject["properties"]!!.jsonObject
        assertEquals(
            PublicationImportFailure.LOCAL_STATE_RETAINED,
            failure["local_state_disposition"]!!.constValue(),
        )
        assertEquals(
            PublicationImportFailure.EXTERNAL_SOURCE_UNTOUCHED,
            failure["external_source_disposition"]!!.constValue(),
        )
        // REQ-507's promise, in the contract itself: a failed import never
        // leaves a broken account entry behind.
        assertEquals("false", failure["broken_account_entry_created"]!!.jsonObject["const"].toString())
    }

    /**
     * The download grant's own shape, against the document (#118).
     *
     * The shape checker above already compares every field and enum member.
     * What this adds is the two facts the copy store *acts* on and would
     * otherwise be trusting from memory: that a grant's checksum and size come
     * from the document as required fields (nothing in FastReader may supply
     * either), and that the direction and method values the client gates on —
     * `download` and `GET` — are values the schema actually declares.
     */
    @Test
    fun `the download grant the module reads is the document's own`() {
        val grant = schemas["ReaderAssetGrant"]!!.jsonObject
        val required = (grant["required"] as JsonArray).mapNotNull { it.primitive() }.toSet()
        listOf("url", "checksum", "size_bytes", "expires_at", "direction", "method").forEach { field ->
            assertTrue(
                "$field must be required: the copy store reads it rather than assuming one",
                field in required,
            )
        }

        val properties = grant["properties"]!!.jsonObject
        val directions = (properties["direction"]!!.jsonObject["enum"] as JsonArray).mapNotNull { it.primitive() }
        val methods = (properties["method"]!!.jsonObject["enum"] as JsonArray).mapNotNull { it.primitive() }
        assertTrue(
            "the schema must still declare the download direction the client gates on",
            "download" in directions,
        )
        assertTrue("the schema must still declare the GET method the client gates on", "GET" in methods)

        // And those two wire values really do reach the members the client
        // gates on, through the module's own JSON rather than by inspection.
        val decoded = ReaderLibraryJson.decodeFromJsonElement(ReaderAssetGrant.serializer(), grantDocument())
        assertEquals(ReaderAssetDirection.DOWNLOAD, decoded.direction)
        assertEquals(ReaderAssetMethod.GET, decoded.method)
        assertTrue("a download GET grant is what the client spends", decoded.isDownload)
        assertEquals("0".repeat(64), decoded.checksumHex)

        // The grant has no chunk size of its own: a download is one GET the
        // provider streams. If the document ever grows one, the client must
        // read it rather than keep streaming whole.
        assertFalse(
            "the download grant gained a chunk size the client ignores",
            properties.containsKey("chunk_size_bytes"),
        )
    }

    /** A minimal grant document, as the provider's answer carries one. */
    private fun grantDocument(): JsonObject = JsonObject(
        mapOf(
            "asset_id" to JsonPrimitive("11111111-1111-1111-1111-111111111111"),
            "book_id" to JsonPrimitive("22222222-2222-2222-2222-222222222222"),
            "direction" to JsonPrimitive("download"),
            "method" to JsonPrimitive("GET"),
            "url" to JsonPrimitive("https://storage.test/object"),
            "expires_at" to JsonPrimitive("2026-09-21T12:00:00Z"),
            "checksum" to JsonPrimitive("sha256:" + "0".repeat(64)),
            "size_bytes" to JsonPrimitive(1),
            "upload_status" to JsonPrimitive("ready"),
        ),
    )

    /** A `const` property's single accepted value, through an `anyOf … null` wrapper if there is one. */
    private fun kotlinx.serialization.json.JsonElement.constValue(): String? {
        val property = jsonObject
        property["const"]?.primitive()?.let { return it }
        val anyOf = property["anyOf"] as? JsonArray ?: return null
        return anyOf.mapNotNull { it.jsonObject["const"]?.primitive() }.firstOrNull()
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
