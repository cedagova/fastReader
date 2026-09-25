package com.cedagova.reader.auth

import com.cedagova.reader.auth.api.PreAuthDocument
import com.cedagova.reader.auth.api.ReaderApiClient
import com.cedagova.reader.auth.api.ReaderProfileUpdate
import com.cedagova.reader.auth.api.SignInMethod
import com.cedagova.reader.auth.contract.ReaderApiContract
import com.cedagova.reader.auth.contract.ReaderApiContract.Fit
import com.cedagova.reader.auth.contract.ReaderApiContract.Pin
import com.cedagova.reader.auth.contract.primitive
import com.cedagova.reader.auth.testing.json
import com.cedagova.reader.auth.testing.recorded
import com.cedagova.reader.auth.testing.session
import java.io.File
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reader-api drift gate for `:reader-auth` (#208, A197-F011).
 *
 * The same gate `:reader-library` has had since #112, over the same pinned
 * document (`reader-auth/contracts/`) and the same checker
 * (`ReaderApiContract`): every shape this module sends or reads is compared,
 * field by field, with the schema that declares it, from the model's own
 * serializer descriptor.
 *
 * - `ReaderProfileUpdate` is a request body, so it is a whole shape
 *   ([Fit.WHOLE]): every field it sends exists with that wire name, type and
 *   nullability, and every field the schema requires is sent.
 * - `PreAuthDocument` and its sections deliberately read a lenient subset of
 *   `ReaderPreAuthResponse` (unknown fields ignored, every field defaulted, so a
 *   server addition never breaks sign-in), so they are [Fit.READ_SUBSET]: every
 *   field read exists with that wire name and type, a field the schema lets be
 *   null is nullable, and a field the schema may omit has a default.
 *
 * The capabilities document and the profile answer are returned to the host
 * as JSON, not decoded here; `:reader-library` models the capability entries.
 * The error body is read field by field without a model, so its fields are
 * checked by name in [the error body the client reads is the document's own].
 *
 * When reader-api genuinely changes, re-pin the document
 * (`reader-auth/contracts/PINNED.md`) and move the models — never relax this
 * test.
 */
class ReaderAuthContractTest {

    private val contract = ReaderApiContract.pinned

    /**
     * Every shape this module decodes or encodes with a serializer, against the
     * schema that declares it. A model added under `api/` without a row here is
     * caught by [every model the module sends or reads is pinned to a schema].
     */
    private val pins: List<Pin> = listOf(
        Pin(ReaderProfileUpdate.serializer(), "PutReaderProfileRequest", Fit.WHOLE),
        Pin(PreAuthDocument.serializer(), "ReaderPreAuthResponse", Fit.READ_SUBSET),
        Pin(PreAuthDocument.Compatibility.serializer(), "ReaderPreAuthCompatibility", Fit.READ_SUBSET),
        Pin(PreAuthDocument.AccountEntry.serializer(), "ReaderPreAuthAccountEntry", Fit.READ_SUBSET),
        Pin(PreAuthDocument.Configuration.serializer(), "ReaderPreAuthConfiguration", Fit.READ_SUBSET),
        Pin(PreAuthDocument.Client.serializer(), "ReaderPreAuthClient", Fit.READ_SUBSET),
        Pin(PreAuthDocument.Authentication.serializer(), "ReaderPreAuthProviderConfiguration", Fit.READ_SUBSET),
        Pin(PreAuthDocument.PostAuth.serializer(), "ReaderPreAuthPostAuthContract", Fit.READ_SUBSET),
    )

    private val checker = contract.checker(pins)

    /** How the client decodes the pre-auth document: unknown fields ignored. */
    private val lenient = Json { ignoreUnknownKeys = true }

    @Test
    fun `the committed document is the pinned identity, byte for byte`() {
        val contractFile = ReaderApiContract.documentFile
        assertTrue("missing ${contractFile.path}", contractFile.isFile)
        val actual = ReaderApiContract.actualDigest()
        assertEquals(
            "reader-auth/contracts/reader-api.openapi.json does not match its recorded sha256; " +
                "re-pin it deliberately (reader-auth/contracts/PINNED.md) rather than editing the digest",
            ReaderApiContract.recordedDigest(),
            actual,
        )
        assertEquals(ReaderApiContract.PINNED_SHA256, actual)
        assertTrue(
            "PINNED.md must record the pinned commit",
            ReaderApiContract.pinnedFile.readText().contains(ReaderApiContract.PINNED_COMMIT),
        )
    }

    @Test
    fun `every shape the module sends or reads agrees with the pinned schema`() {
        val problems = checker.violations()
        assertEquals(
            "the models and the pinned contract disagree:\n" + problems.joinToString("\n"),
            emptyList<String>(),
            problems,
        )
    }

    /**
     * The three routes the module names, with the method it uses, and the body
     * each one answers or takes being the very schema the model is pinned to.
     */
    @Test
    fun `every route the module calls is declared by the pinned document`() {
        val preAuth = operation(ReaderApiClient.PRE_AUTH_PATH, "get")
        assertEquals("ReaderPreAuthResponse", preAuth.bodySchema("responses", "200"))

        val capabilities = operation(ReaderApiClient.CAPABILITIES_PATH, "get")
        assertEquals("ReaderCapabilitiesResponse", capabilities.bodySchema("responses", "200"))

        val profile = operation(ReaderApiClient.PROFILE_PATH, "put")
        assertEquals("PutReaderProfileRequest", profile.bodySchema("requestBody"))

        // The client version query and the client header the bootstrap and
        // capabilities calls carry.
        listOf(preAuth, capabilities).forEach { route ->
            val parameters = (route["parameters"] as JsonArray).map { it.jsonObject }
            fun declared(name: String, place: String) =
                parameters.any { it["name"]?.primitive() == name && it["in"]?.primitive() == place }
            assertTrue(
                "the document declares no ${ReaderApiClient.QUERY_CLIENT_VERSION} query",
                declared(ReaderApiClient.QUERY_CLIENT_VERSION, "query"),
            )
            assertTrue(
                "the document declares no ${ReaderApiClient.HEADER_CLIENT} header",
                declared(ReaderApiClient.HEADER_CLIENT, "header"),
            )
        }
    }

    /**
     * The values the bootstrap check compares against, against the document's
     * own declarations. The shape checker compares fields and types; these are
     * *values*, and a wrong one silently turns sign-in off.
     */
    @Test
    fun `the pre-auth values the module compares are the document's own`() {
        fun enumOf(schema: String, field: String): List<String> {
            val property = contract.schema(schema)["properties"]!!.jsonObject[field]!!.jsonObject
            val values = (property["enum"] ?: property["items"]!!.jsonObject["enum"]) as JsonArray
            return values.mapNotNull { it.primitive() }
        }
        assertTrue(
            "compatibility.status must still declare '${PreAuthDocument.COMPATIBLE}'",
            PreAuthDocument.COMPATIBLE in enumOf("ReaderPreAuthCompatibility", "status"),
        )
        assertTrue(
            "accountEntry.availability must still declare '${PreAuthDocument.AVAILABLE}'",
            PreAuthDocument.AVAILABLE in enumOf("ReaderPreAuthAccountEntry", "availability"),
        )
        val providers = enumOf("ReaderPreAuthProviderConfiguration", "enabledProviders")
        assertTrue("enabledProviders must still declare 'password'", "password" in providers)

        // And that wire value really does turn on the password method, through
        // the module's own model rather than by inspection.
        val document = lenient.decodeFromString(
            PreAuthDocument.serializer(),
            """{"configuration":{"authentication":{"enabledProviders":["password"],"emailOtp":true}}}""",
        )
        assertEquals(setOf(SignInMethod.EMAIL_CODE, SignInMethod.PASSWORD), document.enabledMethods)

        // The route the pre-auth document names for after sign-in is the one the
        // module calls after sign-in.
        val postAuthPath = contract.schema("ReaderPreAuthPostAuthContract")["properties"]!!
            .jsonObject["path"]!!.jsonObject["const"]!!.primitive()
        assertEquals(ReaderApiClient.CAPABILITIES_PATH, postAuthPath)
    }

    /**
     * reader-api's `ErrorResponse`, which `ReaderApiClient` reads field by field
     * (`code`, `message`, `category`, `retryable`, `request_id`) because an
     * error body must never fail to decode. The names and types it reads them
     * as must be the document's.
     */
    @Test
    fun `the error body the client reads is the document's own`() {
        val properties = contract.schema("ErrorResponse")["properties"]!!.jsonObject
        mapOf(
            "code" to "string",
            "message" to "string",
            "category" to "string",
            "retryable" to "boolean",
            "request_id" to "string",
        ).forEach { (field, type) ->
            val property = properties[field]?.jsonObject
            assertTrue("ErrorResponse.$field: not declared by the pinned schema", property != null)
            val types = listOfNotNull(property!!["type"]?.primitive()) +
                ((property["anyOf"] as? JsonArray)?.mapNotNull { it.jsonObject["type"]?.primitive() } ?: emptyList())
            assertTrue("ErrorResponse.$field: the schema's type is $types, the client reads a $type", type in types)
        }
    }

    /**
     * `CONTRACT.md` cites reader-api only at the pinned commit (#208), so a
     * re-pin cannot leave the prose describing a different reader-api.
     */
    @Test
    fun `CONTRACT md cites reader-api only at the pinned commit`() {
        val text = File(repositoryRoot(), "reader-auth/CONTRACT.md").readText()
        val cited = Regex("""reader-api@([0-9a-f]{7,40})""").findAll(text).map { it.groupValues[1] }.toSet()
        assertTrue("CONTRACT.md cites no reader-api commit", cited.isNotEmpty())
        assertEquals(
            "CONTRACT.md cites a reader-api commit other than the pinned one",
            setOf(ReaderApiContract.PINNED_COMMIT),
            cited,
        )
    }

    /**
     * The negative case: the same checker, run against deliberately broken
     * copies of the module's shapes, must fail — so the gate is known to bite.
     */
    @Test
    fun `a renamed, retyped or undecodable field is caught`() {
        val renamed = checker.violations(
            RenamedProfileUpdate.serializer().descriptor,
            "PutReaderProfileRequest",
            Fit.WHOLE,
        )
        assertTrue(
            "a renamed profile field went unnoticed: $renamed",
            renamed.any { it.contains("displayName") && it.contains("not declared") },
        )

        val retyped = checker.violations(
            RetypedCompatibility.serializer().descriptor,
            "ReaderPreAuthCompatibility",
            Fit.READ_SUBSET,
        )
        assertTrue(
            "a retyped pre-auth field went unnoticed: $retyped",
            retyped.any { it.contains("supportedMajor") && it.contains("type") },
        )

        val renamedRead = checker.violations(
            RenamedPreAuth.serializer().descriptor,
            "ReaderPreAuthResponse",
            Fit.READ_SUBSET,
        )
        assertTrue(
            "a renamed pre-auth field went unnoticed: $renamedRead",
            renamedRead.any { it.contains("freshUntill") && it.contains("not declared") },
        )

        val undecodable = checker.violations(
            UndecodablePreAuth.serializer().descriptor,
            "ReaderPreAuthResponse",
            Fit.READ_SUBSET,
        )
        assertTrue(
            "a field that cannot read the schema's null went unnoticed: $undecodable",
            undecodable.any { it.contains("configuration") && it.contains("allows null") },
        )
        assertTrue(
            "a field without a default the schema may omit went unnoticed: $undecodable",
            undecodable.any { it.contains("postAuth") && it.contains("no default") },
        )

        // And the checker is not simply always angry: the real models are clean.
        assertEquals(
            emptyList<String>(),
            checker.violations(PreAuthDocument.serializer().descriptor, "ReaderPreAuthResponse", Fit.READ_SUBSET),
        )
    }

    /**
     * Every `@Serializable` class under `…auth.api` is pinned. Without this, a
     * shape added later would simply never be checked. (`session/`'s envelope
     * is a local file format, not a reader-api shape.)
     */
    @Test
    fun `every model the module sends or reads is pinned to a schema`() {
        val sourceDir = File(repositoryRoot(), "reader-auth/src/main/kotlin/com/cedagova/reader/auth/api")
        val declared = sourceDir.listFiles()!!
            .filter { it.extension == "kt" }
            .flatMap { file ->
                Regex("""@Serializable\s+(?:\w+\s+)*class (\w+)""").findAll(file.readText()).map { it.groupValues[1] }
            }
            .toSet()
        val pinned = pins.map { it.serializer.descriptor.serialName.substringAfterLast('.') }.toSet()
        assertTrue("found no @Serializable class under ${sourceDir.path}", declared.isNotEmpty())
        assertEquals(
            "a shape exists that the contract test never compares with the document",
            emptySet<String>(),
            declared - pinned,
        )
    }

    private fun operation(path: String, method: String): JsonObject {
        val declared = contract.paths[path]?.jsonObject
        assertTrue("the document declares no $path", declared != null)
        val operation = declared!![method]?.jsonObject
        assertTrue("the document declares no $method on $path", operation != null)
        return operation!!
    }

    /** The component schema a request body (`requestBody`) or a response (`responses`, status) refers to. */
    private fun JsonObject.bodySchema(vararg at: String): String? {
        var node: JsonObject = this
        at.forEach { node = node[it]!!.jsonObject }
        return node["content"]!!.jsonObject["application/json"]!!.jsonObject["schema"]!!.jsonObject["\$ref"]
            ?.primitive()
            ?.substringAfterLast('/')
    }

    // ------------------------------------------------- deliberately broken copies

    /** `display_name` sent under its Kotlin name instead of the wire name. */
    @Serializable
    private data class RenamedProfileUpdate(
        val displayName: String? = null,
        @SerialName("avatar_url") val avatarUrl: String? = null,
        @SerialName("native_language") val nativeLanguage: String? = null,
        @SerialName("target_language") val targetLanguage: String? = null,
        val metadata: JsonObject = JsonObject(emptyMap()),
    )

    /** `supportedMajor` read as a string where the schema declares an integer. */
    @Serializable
    private data class RetypedCompatibility(val status: String? = null, val supportedMajor: String? = null)

    /** `freshUntil` misspelt: the document never sends it, so the cache never gets a bound. */
    @Serializable
    private data class RenamedPreAuth(val generatedAt: String? = null, val freshUntill: String? = null)

    /**
     * `configuration` non-null where the schema allows null, and `postAuth`
     * without a default where the schema may omit it: both fail to decode a
     * document the server is allowed to send.
     */
    @Serializable
    private data class UndecodablePreAuth(
        val configuration: PreAuthDocument.Configuration = PreAuthDocument.Configuration(),
        val postAuth: PreAuthDocument.PostAuth?,
    )
}
