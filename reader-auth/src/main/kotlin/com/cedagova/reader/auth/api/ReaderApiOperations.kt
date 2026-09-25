package com.cedagova.reader.auth.api

import kotlinx.serialization.json.JsonObject

/**
 * The reader-api calls a module layered on this one makes (#199, A197-F002):
 * the three routes the contract names and the generic verbs every further
 * route is built on, all under one call policy.
 *
 * [ReaderApiClient] is the one production implementation; the policy it
 * enforces (bearer, single-flight refresh, the 401/403/429/502 branches) is
 * stated there. A layered module such as `:reader-library` takes this
 * interface, and its tests drive the real client over a mock server from this
 * module's test fixtures (`com.cedagova.reader.auth.testing.ReaderAuthHarness`)
 * rather than a double, because the policy is the behaviour under test.
 */
public interface ReaderApiOperations {

    /** `GET /v1/reader/pre-auth?clientVersion=…`: public, called before any sign-in. */
    public suspend fun preAuth(): PreAuthDocument

    /** `GET /v1/reader/capabilities?clientVersion=…`: the first authenticated call after sign-in. */
    public suspend fun capabilities(): JsonObject

    /** [capabilities], with the `X-Request-ID` the successful attempt carried beside the document. */
    public suspend fun capabilitiesResponse(): ReaderApiResponse

    /** `PUT /v1/reader/profile`: the upsert that precedes any profile `GET`. */
    public suspend fun upsertProfile(update: ReaderProfileUpdate): JsonObject

    /** Any further protected `GET` a host needs, under the same policy. */
    public suspend fun get(path: String): JsonObject

    /** Any further protected `PUT` a host needs, under the same policy. */
    public suspend fun put(path: String, body: JsonObject): JsonObject

    /** Any further protected `POST` a host needs, under the same policy. */
    public suspend fun post(path: String, body: JsonObject): JsonObject
}
