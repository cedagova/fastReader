package com.cedagova.fastreader.account.library

import com.cedagova.reader.library.sync.AccountHostRecords
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull

/**
 * One account book whose bytes are on this device (REQ-510, D2).
 *
 * [contentSha256] is the whole of the identity — the same digest the shelf
 * merges rows on (AD-23) and the same one `AccountCopyStore` names its file
 * after. There is deliberately no path here: a reference that recorded where
 * the bytes were would be a second answer to a question the catalog's
 * `ACCOUNT_COPY` source already answers, and two answers drift.
 *
 * [sizeBytes] and [placedAtEpochMs] are what a host shows about a copy — how
 * much freeing it would recover, and when it arrived. Neither is load-bearing:
 * a reference with both at zero still means "this account's copy is here".
 */
@Serializable
data class AccountCopy(
    @SerialName("contentSha256") val contentSha256: String,
    @SerialName("sizeBytes") val sizeBytes: Long = 0,
    @SerialName("placedAtEpochMs") val placedAtEpochMs: Long = 0,
)

/**
 * The account document's copy references, as the download flow uses them
 * (LEAF811, REQ-510, D2).
 *
 * These say which account books this device holds bytes for; the bytes are
 * [AccountCopyStore]'s and the readable source is the device catalog's.
 */
interface AccountCopyReferences {

    /** The signed-in account's user id, or null when nobody is signed in. */
    fun accountId(): String?

    /** The content identities the signed-in account has copies of on this device. */
    suspend fun copyReferences(): List<AccountCopy>

    /** Records [copy], replacing any earlier reference to the same content. */
    suspend fun putCopyReference(copy: AccountCopy)

    /** Forgets the reference to [contentSha256]. The bytes are not dropped here. */
    suspend fun dropCopyReference(contentSha256: String)

    /**
     * Keeps only the references [present] names.
     *
     * The start-up reconciliation: the store is the authority on what is
     * actually on disk, and a reference to a copy a dead process never finished
     * placing must not outlive it.
     */
    suspend fun retainCopyReferences(present: Set<String>)
}

/**
 * The copy references, kept as the `copies` host record of the account
 * document (#147).
 *
 * They are FastReader's and not the account's, so `:reader-library`'s engine
 * stores them without reading them — but in the same document and under the
 * same single writer as the queue, because a second writer racing it would be
 * the first way to lose a queued mutation. The key and the element shape are
 * the ones schema 3 introduced, so a document written before #147 reads back
 * unchanged.
 *
 * Every change is one [AccountHostRecords.updateHostRecord] call, so a
 * read-modify-write here is atomic with every other write to the document.
 * While nobody is signed in, reads are empty and writes do nothing (D4 — a copy
 * outlives the session that fetched it; signing back in re-binds it).
 */
class AccountDocumentCopyReferences(private val records: AccountHostRecords) : AccountCopyReferences {

    override fun accountId(): String? = records.accountId()

    override suspend fun copyReferences(): List<AccountCopy> = decode(records.hostRecord(KEY))

    override suspend fun putCopyReference(copy: AccountCopy) = update { copies ->
        val index = copies.indexOfFirst { it.contentSha256 == copy.contentSha256 }
        if (index < 0) copies + copy else copies.toMutableList().apply { this[index] = copy }
    }

    override suspend fun dropCopyReference(contentSha256: String) = update { copies ->
        copies.filterNot { it.contentSha256 == contentSha256 }
    }

    override suspend fun retainCopyReferences(present: Set<String>) = update { copies ->
        copies.filter { it.contentSha256 in present }
    }

    /**
     * Rewrites the list, and writes nothing when [change] leaves it as it was —
     * so an absent `copies` key is not turned into an empty one just by asking.
     */
    private suspend fun update(change: (List<AccountCopy>) -> List<AccountCopy>) {
        records.updateHostRecord(KEY) { current ->
            val copies = decode(current)
            val changed = change(copies)
            if (changed == copies) current else json.encodeToJsonElement(SERIALIZER, changed)
        }
    }

    /**
     * The stored list, or none. A value that is not a list of references is read
     * as none rather than failing the caller: the references are a cache of what
     * [AccountCopyStore] holds, and the next download or reconciliation rebuilds
     * them.
     */
    private fun decode(element: JsonElement?): List<AccountCopy> {
        if (element == null || element is JsonNull) return emptyList()
        return try {
            json.decodeFromJsonElement(SERIALIZER, element)
        } catch (e: IllegalArgumentException) {
            emptyList()
        }
    }

    companion object {
        /** The host record's key: schema 3's own top-level `copies`. */
        const val KEY: String = "copies"

        private val SERIALIZER: KSerializer<List<AccountCopy>> = ListSerializer(AccountCopy.serializer())

        /** The account codec's own settings, so a reference is written exactly as schema 3 wrote it. */
        private val json: Json = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
            encodeDefaults = true
        }
    }
}
