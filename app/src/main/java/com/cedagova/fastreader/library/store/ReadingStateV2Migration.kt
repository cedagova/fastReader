package com.cedagova.fastreader.library.store

import com.cedagova.fastreader.content.ContentPipelineVersion
import com.cedagova.fastreader.timing.RsvpTiming
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Schema 1 → 2: re-express every stored reading position on the token stream.
 *
 * Version 1 addressed a position as `spineIndex` + `wordIndex`, a slot increment
 * 001 defined but nothing ever wrote — no build that persisted a v1 document
 * could stream words. So the honest conversion is a position at the start of the
 * book carrying the progress and timestamp that were recorded, never a fabricated
 * index into a stream those two numbers never described.
 *
 * The one value the old document *does* carry is identity: entries are keyed by
 * the content digest (AD-2), so `bookDigest` is filled from the key rather than
 * left blank. `pipelineVersion` is pinned to
 * [ContentPipelineVersion.CURRENT] as it stood when this migration was written —
 * a literal, because a later pipeline change must move *new* positions, not
 * silently reinterpret ones this step already converted.
 */
internal object ReadingStateV2Migration : CatalogMigration {

    /** The tokenization rules in force when schema 2 was introduced. */
    private const val PIPELINE_VERSION_AT_V2 = 1

    override fun migrate(document: JsonObject): JsonObject {
        val states = document["readingStates"] as? JsonObject ?: return document
        val upgraded = states.mapValues { (bookId, value) ->
            val old = value.jsonObject
            JsonObject(
                mapOf(
                    "bookDigest" to JsonPrimitive(bookId),
                    "tokenIndex" to JsonPrimitive(0),
                    "pipelineVersion" to JsonPrimitive(PIPELINE_VERSION_AT_V2),
                    "progressFraction" to JsonPrimitive(old.float("progressFraction")),
                    "wpm" to JsonPrimitive(RsvpTiming.DEFAULT_WPM),
                    "updatedAtEpochMs" to JsonPrimitive(old.long("updatedAtEpochMs")),
                ),
            )
        }
        return JsonObject(document + ("readingStates" to JsonObject(upgraded)))
    }

    private fun JsonObject.float(name: String): Float =
        this[name]?.jsonPrimitive?.floatOrNull ?: 0f

    private fun JsonObject.long(name: String): Long =
        this[name]?.jsonPrimitive?.longOrNull ?: 0L
}
