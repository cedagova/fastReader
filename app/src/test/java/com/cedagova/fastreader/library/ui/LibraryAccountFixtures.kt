package com.cedagova.fastreader.library.ui

import com.cedagova.fastreader.account.library.AccountBook
import com.cedagova.fastreader.account.library.AccountLibraryState
import com.cedagova.fastreader.account.library.AccountSyncPhase
import com.cedagova.fastreader.library.Book
import com.cedagova.fastreader.library.Catalog
import com.cedagova.fastreader.library.ReadingState
import com.cedagova.reader.library.model.ReaderLibraryStatus

/**
 * The account shelf's fixtures (LEAF703).
 *
 * The device ids here are real `sha256:<64 hex>` values rather than the short
 * names the v1 fixtures use, because the merge is by content identity and
 * nothing else (AD-23): a fixture whose id is not a digest would merge with
 * nothing and quietly prove the wrong thing.
 */
internal object LibraryAccountFixtures {

    /** Ficciones: on this device *and* in the account. */
    const val FICCIONES_HEX: String = "f1cc10e500000000000000000000000000000000000000000000000000000000"

    /** Rayuela: on this device only — no account row anywhere. */
    const val RAYUELA_HEX: String = "4a7e1a0011111111111111111111111111111111111111111111111111111111"

    /** Dubliners: in the account only — its bytes are not here. */
    const val DUBLINERS_ACCOUNT_ID: String = "3f0b2c41-9d6e-4a77-9a1c-7c2f5b8e40aa"

    /** Dubliners' bytes, once they are here. */
    const val DUBLINERS_HEX: String = "d0b112e522222222222222222222222222222222222222222222222222222222"

    const val DUBLINERS_COPY_ID: String = "sha256:$DUBLINERS_HEX"
    const val FICCIONES_ID: String = "sha256:$FICCIONES_HEX"
    const val RAYUELA_ID: String = "sha256:$RAYUELA_HEX"

    /** The two device books the shelf starts from. */
    fun deviceCatalog(): Catalog = Catalog(
        books = listOf(
            device(FICCIONES_ID, "Ficciones", "Jorge Luis Borges", "ficciones.epub"),
            device(RAYUELA_ID, "Rayuela", "Julio Cortázar", "rayuela.epub"),
        ),
        readingStates = mapOf(
            FICCIONES_ID to ReadingState(progressFraction = 0.37f, updatedAtEpochMs = 2_000),
            RAYUELA_ID to ReadingState(progressFraction = 0.12f, updatedAtEpochMs = 1_000),
        ),
    )

    /** Signed in, settled, with Ficciones in the account and matched by content. */
    fun signedIn(vararg books: AccountBook): AccountLibraryState = AccountLibraryState(
        phase = AccountSyncPhase.IDLE,
        userId = "user-1",
        books = books.toList(),
    )

    /** The account's copy of the device's Ficciones: one row, two halves. */
    fun ficcionesInAccount(): AccountBook = AccountBook(
        bookId = "b0f1a2c3-4d5e-4f60-8a71-92b3c4d5e6f7",
        title = "Ficciones",
        author = "Jorge Luis Borges",
        contentSha256 = FICCIONES_HEX,
        status = ReaderLibraryStatus.READING,
    )

    /** A book the account has and this device does not. */
    fun dublinersInAccountOnly(): AccountBook = AccountBook(
        bookId = DUBLINERS_ACCOUNT_ID,
        title = "Dubliners",
        author = "James Joyce",
        contentSha256 = DUBLINERS_HEX,
        status = ReaderLibraryStatus.QUEUED,
    )

    /**
     * The same three books, with Dubliners downloaded (#119).
     *
     * The copy is a device row keyed by the *same* digest the account book
     * carries, so the merge puts them on one row — which is the whole of what
     * "a downloaded copy shows as a device book" means to the shelf.
     */
    fun catalogWithDownloadedCopy(): Catalog {
        val base = deviceCatalog()
        return base.copy(
            books = base.books + LibraryFixtures.accountCopy(
                contentHex = DUBLINERS_HEX,
                title = "Dubliners",
                author = "James Joyce",
                hasCover = false,
            ),
            readingStates = base.readingStates +
                (DUBLINERS_COPY_ID to ReadingState(progressFraction = 0.08f, updatedAtEpochMs = 3_000)),
        )
    }

    private fun device(id: String, title: String, author: String, fileName: String): Book =
        LibraryFixtures.readable(id = id, title = title, author = author, fileName = fileName)
}
