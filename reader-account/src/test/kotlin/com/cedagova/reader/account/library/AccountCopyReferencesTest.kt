package com.cedagova.reader.account.library

import com.cedagova.reader.library.testing.RecordingHostRecords
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The copy references in the account document (schema 3's `copies`), a host
 * record `:reader-library`'s engine stores and never reads (#147). The answered
 * resume offers, the host's other record, are FastReader's and are tested in
 * `:app` (#200).
 */
class AccountCopyReferencesTest {

    private val sha = "b".repeat(64)

    /** A copy reference replaces rather than duplicates, and drop / retain do what they say. */
    @Test
    fun `copy references are keyed by content`() = runTest {
        val records = RecordingHostRecords()
        val references = AccountDocumentCopyReferences(records)
        val copy = AccountCopy(contentSha256 = sha, sizeBytes = 4_096, placedAtEpochMs = 1_700_000_000_000)

        references.putCopyReference(copy)
        assertEquals(listOf(copy), references.copyReferences())

        references.putCopyReference(copy.copy(sizeBytes = 8_192))
        assertEquals("the same content is one reference, not two", 1, references.copyReferences().size)
        assertEquals(8_192L, references.copyReferences().single().sizeBytes)

        references.retainCopyReferences(setOf(sha))
        assertEquals(1, references.copyReferences().size)
        references.retainCopyReferences(emptySet())
        assertEquals(emptyList<AccountCopy>(), references.copyReferences())

        references.putCopyReference(copy)
        references.dropCopyReference(sha)
        assertEquals(emptyList<AccountCopy>(), references.copyReferences())
    }

    /** Asking to change nothing writes nothing — an absent key is not turned into an empty one. */
    @Test
    fun `an unchanged copy list is not written`() = runTest {
        val records = RecordingHostRecords()
        val references = AccountDocumentCopyReferences(records)

        references.retainCopyReferences(emptySet())
        references.dropCopyReference(sha)

        assertTrue(records.writes.isEmpty())
        assertNull(records.document[AccountDocumentCopyReferences.KEY])
    }

    /**
     * #149: one damaged element costs that element and nothing else. Before, the
     * whole list read as empty, and the next put rewrote it with only the new
     * reference — dropping every valid one beside the damaged element.
     */
    @Test
    fun `a malformed copies element keeps the valid references across read and put`() = runTest {
        val records = RecordingHostRecords()
        val first = AccountCopy(contentSha256 = "a".repeat(64), sizeBytes = 4_096, placedAtEpochMs = 1_700_000_000_000)
        val second = AccountCopy(contentSha256 = "c".repeat(64), sizeBytes = 2_048, placedAtEpochMs = 1_700_000_000_001)
        records.document[AccountDocumentCopyReferences.KEY] = buildJsonArray {
            add(copyJson(first))
            add(buildJsonObject { put("sizeBytes", JsonPrimitive(12)) }) // no contentSha256
            add(JsonPrimitive("not a reference"))
            add(copyJson(second))
        }
        val references = AccountDocumentCopyReferences(records)

        assertEquals(listOf(first, second), references.copyReferences())

        val added = AccountCopy(contentSha256 = sha, sizeBytes = 1_024, placedAtEpochMs = 1_700_000_000_002)
        references.putCopyReference(added)

        assertEquals(listOf(first, second, added), references.copyReferences())
        assertEquals(
            "only the malformed elements are gone from what is stored",
            JsonArray(listOf(copyJson(first), copyJson(second), copyJson(added))),
            records.document[AccountDocumentCopyReferences.KEY],
        )
    }

    private fun copyJson(copy: AccountCopy) = buildJsonObject {
        put("contentSha256", JsonPrimitive(copy.contentSha256))
        put("sizeBytes", JsonPrimitive(copy.sizeBytes))
        put("placedAtEpochMs", JsonPrimitive(copy.placedAtEpochMs))
    }

    /** D4: signed out, a reference has no account to belong to, and nothing is stored. */
    @Test
    fun `nothing is referenced while signed out`() = runTest {
        val records = RecordingHostRecords(userId = null)
        val references = AccountDocumentCopyReferences(records)

        references.putCopyReference(AccountCopy(contentSha256 = sha))

        assertNull(references.accountId())
        assertEquals(emptyList<AccountCopy>(), references.copyReferences())
        assertTrue(records.writes.isEmpty())
    }
}
