package com.cedagova.reader.auth.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * The body of `PUT /v1/reader/profile` (`PutReaderProfileRequest`). Every
 * field is optional; the server upserts the actor's profile row, which is why
 * the contract puts the `PUT` before the first `GET`.
 */
@Serializable
data class ReaderProfileUpdate(
    @SerialName("display_name") val displayName: String? = null,
    @SerialName("avatar_url") val avatarUrl: String? = null,
    @SerialName("native_language") val nativeLanguage: String? = null,
    @SerialName("target_language") val targetLanguage: String? = null,
    val metadata: JsonObject = JsonObject(emptyMap()),
)
