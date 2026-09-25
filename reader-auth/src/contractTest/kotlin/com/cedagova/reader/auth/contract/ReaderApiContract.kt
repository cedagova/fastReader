package com.cedagova.reader.auth.contract

import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * The one pinned reader-api contract both libraries are gated against (#208).
 *
 * `reader-auth/contracts/reader-api.openapi.json` is the published Reader API
 * document at [PINNED_COMMIT]; `reader-auth/contracts/PINNED.md` records the
 * identity and the one procedure for moving it. This file is test support, not
 * library code: it is compiled into the unit tests of `:reader-auth`
 * (`ReaderAuthContractTest`) and of `:reader-library`
 * (`ReaderLibraryContractTest`) through each module's test source set, so both
 * libraries read the same document through the same checker.
 *
 * The checker compares a model's own kotlinx.serialization descriptor with the
 * document's schema, so the expectations are derived from the models rather
 * than typed out beside them (owner decision P1: hand-written models, contract
 * test, no generator).
 */
@OptIn(ExperimentalSerializationApi::class)
class ReaderApiContract private constructor(val document: JsonObject) {

    val paths: JsonObject = document["paths"]!!.jsonObject
    val schemas: JsonObject = document["components"]!!.jsonObject["schemas"]!!.jsonObject

    /** The named component schema; fails with the name when the document has none. */
    fun schema(name: String): JsonObject =
        checkNotNull(schemas[name]?.jsonObject) { "the pinned document declares no schema $name" }

    /** How a model relates to the schema it is pinned to. */
    enum class Fit {
        /**
         * The model is the whole shape: every field it uses exists with that
         * wire name, type and nullability, and every field the schema requires
         * is present in the model. The library models and every request body.
         */
        WHOLE,

        /**
         * The model reads a lenient subset of a response (unknown fields
         * ignored, every field defaulted): every field it reads exists with that
         * wire name and type, a field the schema allows to be null is nullable in
         * the model, and a field the schema may omit has a default. A field the
         * schema requires may be absent from the model or nullable in it — the
         * client reads less than the server promises, never more.
         */
        READ_SUBSET,
    }

    /** A model pinned to the component schema that declares it. */
    class Pin(val serializer: KSerializer<*>, val schema: String, val fit: Fit = Fit.WHOLE)

    /** A checker over one module's [pins]; a `$ref` must land on a schema one of them is pinned to. */
    fun checker(pins: List<Pin>): Checker = Checker(pins)

    inner class Checker(private val pins: List<Pin>) {

        /** Kotlin descriptor serial name → the schema it must agree with, for `$ref` checks. */
        private val schemaOf: Map<String, String> =
            pins.associate { it.serializer.descriptor.serialName to it.schema }

        /** Every disagreement between the pinned models and the document, each naming the schema and field. */
        fun violations(): List<String> = pins.flatMap { violations(it.serializer.descriptor, it.schema, it.fit) }

        fun violations(descriptor: SerialDescriptor, schemaName: String, fit: Fit = Fit.WHOLE): List<String> {
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
                if (fit == Fit.WHOLE && element.isNullable && !resolved.nullable && field in required) {
                    problems += "$schemaName.$field: the model makes it nullable but the schema requires a value"
                }
                if (!element.isNullable && resolved.nullable) {
                    problems += "$schemaName.$field: the schema allows null but the model does not"
                }
                if (fit == Fit.READ_SUBSET && field !in required && !descriptor.isElementOptional(index)) {
                    problems += "$schemaName.$field: the schema may omit it but the model has no default"
                }
                problems += checkShape(element, resolved, "$schemaName.$field")
            }

            if (fit == Fit.WHOLE) {
                (required - seen).forEach { missing ->
                    problems += "$schemaName.$missing: the schema marks it required but the model has no such field"
                }
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
                    mapped == null ->
                        problems +=
                            "$where: the schema is a \$ref to ${resolved.ref}, but ${element.serialName} is not pinned to any schema"

                    mapped != resolved.ref ->
                        problems +=
                            "$where: the schema refers to ${resolved.ref}, the model to $mapped"
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
                        .filterNot { it == UNKNOWN_ENUM_MEMBER }
                        .toSet()
                    (members - declared).forEach {
                        problems += "$where: the model declares '$it', which the schema does not"
                    }
                    (declared - members).forEach {
                        problems += "$where: the schema declares '$it', which the model does not"
                    }
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

    companion object {
        /** The reader-api commit the document is a byte-for-byte copy of (PINNED.md). */
        const val PINNED_COMMIT: String = "909174aff6a380514da7b81263d69a4e653cfe76"

        /** The document's sha256, as recorded in `reader-api.openapi.json.sha256`. */
        const val PINNED_SHA256: String = "e2c184dbd51d0e3f542d73d69e56a193300615de604486615b254911b67ade90"

        /**
         * The enum member a model may declare as its forward-compatibility
         * fallback; it never goes on the wire, so it is not compared.
         */
        const val UNKNOWN_ENUM_MEMBER: String = "__unknown__"

        /** `reader-auth/contracts/`, found from the test's working directory (a module directory). */
        val contractsDir: File by lazy { File(repositoryRoot(), "reader-auth/contracts") }
        val documentFile: File get() = File(contractsDir, "reader-api.openapi.json")
        val digestFile: File get() = File(contractsDir, "reader-api.openapi.json.sha256")
        val pinnedFile: File get() = File(contractsDir, "PINNED.md")

        /** The pinned document, parsed once per test JVM. */
        val pinned: ReaderApiContract by lazy {
            ReaderApiContract(Json.parseToJsonElement(documentFile.readText()).jsonObject)
        }

        /** The document's actual sha256, recomputed from its bytes. */
        fun actualDigest(): String = MessageDigest.getInstance("SHA-256")
            .digest(documentFile.readBytes())
            .joinToString("") { "%02x".format(it) }

        /** The digest `reader-api.openapi.json.sha256` records. */
        fun recordedDigest(): String = digestFile.readText().trim().substringBefore(' ')

        private fun repositoryRoot(): File {
            var candidate: File? = File("").absoluteFile
            while (candidate != null) {
                if (File(candidate, "settings.gradle.kts").isFile) return candidate
                candidate = candidate.parentFile
            }
            error("no settings.gradle.kts above ${File("").absoluteFile}")
        }
    }
}

/** A JSON scalar's content, or null for anything else. */
fun JsonElement.primitive(): String? = (this as? JsonPrimitive)?.contentOrNull
