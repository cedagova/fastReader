package com.cedagova.fastreader.library.ui

import com.cedagova.fastreader.account.library.AccountBook
import com.cedagova.fastreader.account.library.AccountDownloadsState
import com.cedagova.fastreader.account.library.AccountImportsState
import com.cedagova.fastreader.account.library.AccountLibraryState
import com.cedagova.fastreader.account.library.AccountRemotePosition
import com.cedagova.fastreader.account.library.AccountSyncError
import com.cedagova.fastreader.account.library.AccountSyncPhase
import com.cedagova.fastreader.account.library.BookDownloadState
import com.cedagova.fastreader.account.library.BookImportState
import com.cedagova.fastreader.account.library.DownloadProblem
import com.cedagova.fastreader.account.library.ImportsOff
import com.cedagova.fastreader.library.Catalog
import com.cedagova.fastreader.library.IngestionState
import com.cedagova.fastreader.library.ReadingState
import com.cedagova.fastreader.library.SourceAvailability
import com.cedagova.reader.library.model.ReaderCapabilityReason
import com.cedagova.reader.library.model.ReaderLibraryStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The account half of the shelf (LEAF703), proven where it is decided: the
 * merge, the shelf states and the one status the shelf reports back are plain
 * functions of the catalog and the account store, so this is the primary proof
 * and the Roborazzi renders only show what these values look like.
 */
class LibraryAccountUiStateTest {

    // --- REQ-501, one row per content identity (AD-23) ------------------------

    @Test
    fun `a device book and an account book with the same sha256 are one row`() {
        val state = shelf(
            catalog = catalogOf(FICCIONES to "Ficciones"),
            account = accountOf(accountBook("acc-1", "Ficciones", FICCIONES_HEX)),
        )

        assertEquals(1, state.books.size)
        val row = state.books.single()
        assertEquals("acc-1", row.account?.bookId)
        assertTrue("a merged row is still on this device", row.account?.onThisDevice == true)
        assertTrue("a merged row still opens", row.canOpen)
    }

    /**
     * The `sha256:` prefix the catalog stores is not part of the identity, and
     * the backend types `checksum` as a free-form string: both sides normalise
     * to the same 64 hex characters or they do not merge at all.
     */
    @Test
    fun `the sha256 prefix and letter case do not decide the merge`() {
        val state = shelf(
            catalog = catalogOf(FICCIONES to "Ficciones"),
            account = accountOf(accountBook("acc-1", "Ficciones", "SHA256:" + FICCIONES_HEX.uppercase())),
        )

        assertEquals(1, state.books.size)
        assertEquals("acc-1", state.books.single().account?.bookId)
    }

    @Test
    fun `the same title and file name do not merge two different books`() {
        val state = shelf(
            catalog = catalogOf(FICCIONES to "Ficciones"),
            // Same title, same file name, different bytes: two books.
            account = accountOf(accountBook("acc-1", "Ficciones", RAYUELA_HEX)),
        )

        assertEquals(2, state.books.size)
        assertEquals(setOf(null, "acc-1"), state.books.map { it.account?.bookId }.toSet())
    }

    @Test
    fun `an account book with no checksum merges with nothing`() {
        val state = shelf(
            catalog = catalogOf(FICCIONES to "Ficciones"),
            account = accountOf(accountBook("acc-1", "Ficciones", contentSha256 = null)),
        )

        assertEquals(2, state.books.size)
        assertTrue(state.books.any { it.isAccountOnly })
    }

    @Test
    fun `an account book this device does not have says so and does not open`() {
        val state = shelf(
            catalog = Catalog(),
            account = accountOf(accountBook("acc-1", "Dubliners", RAYUELA_HEX, author = "James Joyce")),
        )

        val row = state.books.single()
        assertEquals("Dubliners", row.title)
        assertEquals("James Joyce", row.author)
        assertTrue("it is the account's and not this device's", row.isAccountOnly)
        assertFalse("there are no bytes here to open: it has to be downloaded first", row.canOpen)
        assertTrue("and that is what it offers", row.canDownload)
        assertFalse("a cover is bytes, and the bytes are not here", row.hasCover)
        assertEquals(LibraryContent.BOOKS, state.content)
    }

    @Test
    fun `an account-only row is searched by title and author`() {
        val account = accountOf(accountBook("acc-1", "Dubliners", RAYUELA_HEX, author = "James Joyce"))

        assertEquals(1, shelf(Catalog(), account, query = "joyce").books.size)
        assertEquals(0, shelf(Catalog(), account, query = "borges").books.size)
        assertEquals(LibraryContent.NO_SEARCH_RESULTS, shelf(Catalog(), account, query = "borges").content)
    }

    // --- REQ-516 / D4, signed out --------------------------------------------

    @Test
    fun `signed out the shelf is exactly the device shelf`() {
        val catalog = catalogOf(FICCIONES to "Ficciones")

        val signedOut = shelf(catalog, AccountLibraryState.SIGNED_OUT)
        val v160 = buildLibraryUiState(catalog, IngestionState.Idle, "")

        assertEquals(v160, signedOut)
        assertNull("signed out says nothing about an account", signedOut.accountNotice)
        assertTrue(signedOut.books.all { it.account == null })
    }

    @Test
    fun `a build with no stage values shows no account notice`() {
        val state = shelf(
            catalog = catalogOf(FICCIONES to "Ficciones"),
            account = AccountLibraryState(
                phase = AccountSyncPhase.SIGNED_OUT,
                lastError = AccountSyncError.NotConfigured,
            ),
        )

        assertNull(state.accountNotice)
    }

    // --- REQ-508 / REQ-509, removal ------------------------------------------

    @Test
    fun `a removed account book leaves the shelf and its device book stays readable`() {
        // What the engine publishes after a removal is admitted: the tombstoned
        // row is not in `books` at all.
        val state = shelf(
            catalog = catalogOf(FICCIONES to "Ficciones"),
            account = accountOf(),
        )

        val row = state.books.single()
        assertNull("the account half is gone", row.account)
        assertTrue("the device book is untouched and still opens", row.canOpen)
    }

    @Test
    fun `an account-only book that was removed leaves the shelf entirely`() {
        val state = shelf(catalog = Catalog(), account = accountOf())

        assertEquals(emptyList<LibraryBookItem>(), state.books)
        assertEquals(LibraryContent.EMPTY_LIBRARY, state.content)
    }

    // --- the states table ----------------------------------------------------

    @Test
    fun `bootstrapping is said, not silent`() {
        val notice = shelf(Catalog(), AccountLibraryState(phase = AccountSyncPhase.BOOTSTRAPPING)).accountNotice

        assertEquals(AccountNoticeKind.BOOTSTRAPPING, notice?.kind)
        assertFalse("a live state clears itself", notice?.dismissible == true)
    }

    @Test
    fun `offline names how many changes are waiting`() {
        val notice = shelf(
            Catalog(),
            AccountLibraryState(phase = AccountSyncPhase.OFFLINE, queued = 2),
        ).accountNotice

        assertEquals(AccountNoticeKind.OFFLINE, notice?.kind)
        assertEquals(2, notice?.queued)
    }

    @Test
    fun `a capability the backend has not made available is shown with its own reason`() {
        val notice = shelf(
            Catalog(),
            AccountLibraryState(
                phase = AccountSyncPhase.DEFERRED,
                capabilityReason = ReaderCapabilityReason.SERVICE_NOT_ENABLED,
            ),
        ).accountNotice

        assertEquals(AccountNoticeKind.UNAVAILABLE, notice?.kind)
        assertEquals("service_not_enabled", notice?.code)
    }

    @Test
    fun `a backend asking for a retry later is deferred, never a sign-out`() {
        val notice = shelf(
            Catalog(),
            AccountLibraryState(
                phase = AccountSyncPhase.DEFERRED,
                lastError = AccountSyncError.TryLater(503, null, 30, "req-1"),
            ),
        ).accountNotice

        assertEquals(AccountNoticeKind.UNAVAILABLE, notice?.kind)
        assertEquals("HTTP 503", notice?.code)
        assertEquals("req-1", notice?.requestId)
    }

    @Test
    fun `a session the backend no longer accepts shows its reason and can be put away`() {
        val notice = shelf(
            Catalog(),
            AccountLibraryState(
                phase = AccountSyncPhase.SIGNED_OUT,
                lastError = AccountSyncError.SessionGone("session_revoked", "req-9"),
            ),
        ).accountNotice

        assertEquals(AccountNoticeKind.SESSION_GONE, notice?.kind)
        assertEquals("session_revoked", notice?.code)
        assertTrue("the table says it is shown once", notice?.dismissible == true)
    }

    @Test
    fun `a refused change shows the backend's own code`() {
        val notice = shelf(
            Catalog(),
            AccountLibraryState(
                phase = AccountSyncPhase.IDLE,
                lastError = AccountSyncError.Rejected("acc-1", "invalid_payload", "bad", retryable = false),
            ),
        ).accountNotice

        assertEquals(AccountNoticeKind.REJECTED, notice?.kind)
        assertEquals("invalid_payload", notice?.code)
    }

    @Test
    fun `dismissing one notice cannot hide a different one`() {
        val first = AccountNotice(AccountNoticeKind.SESSION_GONE, code = "session_revoked")
        val second = AccountNotice(AccountNoticeKind.REJECTED, code = "invalid_payload")

        assertFalse(first.key == second.key)
    }

    // --- library status ------------------------------------------------------

    @Test
    fun `a book read to its last word is reported finished once`() {
        val catalog = catalogOf(FICCIONES to "Ficciones").let {
            it.copy(readingStates = mapOf(FICCIONES to ReadingState(progressFraction = 1f)))
        }

        val reading = shelf(catalog, accountOf(accountBook("acc-1", "Ficciones", FICCIONES_HEX)))
        assertEquals(listOf("acc-1"), finishedAccountBooks(reading.books))

        val settled = shelf(
            catalog,
            accountOf(accountBook("acc-1", "Ficciones", FICCIONES_HEX, status = ReaderLibraryStatus.FINISHED)),
        )
        assertEquals(emptyList<String>(), finishedAccountBooks(settled.books))
    }

    @Test
    fun `an unfinished book reports nothing`() {
        val catalog = catalogOf(FICCIONES to "Ficciones").let {
            it.copy(readingStates = mapOf(FICCIONES to ReadingState(progressFraction = 0.99f)))
        }

        val state = shelf(catalog, accountOf(accountBook("acc-1", "Ficciones", FICCIONES_HEX)))

        assertEquals(emptyList<String>(), finishedAccountBooks(state.books))
    }

    @Test
    fun `opening a device book finds the account book it is`() {
        val account = accountOf(accountBook("acc-1", "Ficciones", FICCIONES_HEX))

        assertEquals("acc-1", accountBookIdForDevice(FICCIONES, account))
        assertNull(accountBookIdForDevice("sha256:" + RAYUELA_HEX, account))
        assertNull("a book with no content identity maps to nothing", accountBookIdForDevice("ficciones", account))
    }

    // --- helpers -------------------------------------------------------------

    private fun shelf(
        catalog: Catalog,
        account: AccountLibraryState,
        query: String = "",
        imports: AccountImportsState = AccountImportsState.NONE,
        downloads: AccountDownloadsState = AccountDownloadsState.NONE,
    ): LibraryUiState = buildLibraryUiState(
        catalog = catalog,
        ingestion = IngestionState.Idle,
        query = query,
        account = account,
        imports = imports,
        downloads = downloads,
    )

    private fun catalogOf(vararg books: Pair<String, String>) = Catalog(
        books = books.map { (id, title) -> LibraryFixtures.readable(id, title, fileName = "$title.epub") },
    )

    private fun accountOf(vararg books: AccountBook) = AccountLibraryState(
        phase = AccountSyncPhase.IDLE,
        userId = "user-1",
        books = books.toList(),
    )

    private fun accountBook(
        bookId: String,
        title: String,
        contentSha256: String? = null,
        author: String? = null,
        status: ReaderLibraryStatus = ReaderLibraryStatus.READING,
    ) = AccountBook(
        bookId = bookId,
        title = title,
        author = author,
        contentSha256 = contentSha256,
        status = status,
    )

    private companion object {
        const val FICCIONES_HEX = "11111111111111111111111111111111111111111111111111111111aaaaaaaa"
        const val RAYUELA_HEX = "22222222222222222222222222222222222222222222222222222222bbbbbbbb"
        const val ULYSSES_HEX = "33333333333333333333333333333333333333333333333333333333cccccccc"
        const val FICCIONES = "sha256:$FICCIONES_HEX"
    }

    // ---- adding a device book to the account (#117, REQ-505) ---------------------------

    @Test
    fun `a device book the account does not have offers the add`() {
        val state = shelf(
            catalogOf(FICCIONES to "Ficciones"),
            accountOf(accountBook("acc-1", "Dubliners", contentSha256 = RAYUELA_HEX)),
        )

        val add = state.books.single { it.id == FICCIONES }.addToAccount

        assertEquals(true, add?.offered)
    }

    @Test
    fun `a book the account already has offers nothing to upload`() {
        val state = shelf(
            catalogOf(FICCIONES to "Ficciones"),
            accountOf(accountBook("acc-1", "Ficciones", contentSha256 = FICCIONES_HEX)),
        )

        assertNull(state.books.single { it.id == FICCIONES }.addToAccount)
    }

    @Test
    fun `an account-only row has no bytes here to add`() {
        val state = shelf(
            catalogOf(),
            accountOf(accountBook("acc-1", "Dubliners", contentSha256 = RAYUELA_HEX)),
        )

        assertNull(state.books.single().addToAccount)
    }

    @Test
    fun `signed out, no row offers the add at all`() {
        val state = shelf(catalogOf(FICCIONES to "Ficciones"), AccountLibraryState.SIGNED_OUT)

        assertEquals(emptyList<AddToAccount>(), state.books.mapNotNull { it.addToAccount })
    }

    @Test
    fun `a book this device cannot read has no file to send`() {
        val catalog = Catalog(
            books = listOf(
                LibraryFixtures.unavailable(FICCIONES, "Ficciones", SourceAvailability.MISSING),
            ),
        )

        val state = shelf(catalog, accountOf())

        assertNull(state.books.single().addToAccount)
    }

    @Test
    fun `a backend that is not serving the library does not offer an upload against it`() {
        val state = shelf(
            catalogOf(FICCIONES to "Ficciones"),
            AccountLibraryState(
                phase = AccountSyncPhase.DEFERRED,
                userId = "user-1",
                capabilityReason = ReaderCapabilityReason.SERVICE_NOT_ENABLED,
            ),
        )

        assertNull(state.books.single().addToAccount)
    }

    @Test
    fun `a deployment that admits nothing shows the reason on every addable row`() {
        val state = shelf(
            catalogOf(FICCIONES to "Ficciones"),
            accountOf(),
            imports = AccountImportsState(disabled = ImportsOff("req-1")),
        )

        assertEquals(ImportsOff("req-1"), state.books.single().addToAccount?.off)
    }

    @Test
    fun `an add in flight belongs to the row whose bytes it is`() {
        val state = shelf(
            catalogOf(FICCIONES to "Ficciones", "sha256:$RAYUELA_HEX" to "Rayuela"),
            accountOf(),
            imports = AccountImportsState(
                byDeviceBookId = mapOf(FICCIONES to BookImportState.Sending(0.5f)),
            ),
        )

        assertEquals(
            BookImportState.Sending(0.5f),
            state.books.single { it.id == FICCIONES }.addToAccount?.state,
        )
        assertEquals(true, state.books.single { it.title == "Rayuela" }.addToAccount?.offered)
    }

    // ---- downloading an account book, and freeing the copy (#119, REQ-510) -------------

    @Test
    fun `an account-only row offers the download and a device row never does`() {
        val state = shelf(
            catalogOf(FICCIONES to "Ficciones"),
            accountOf(
                accountBook("acc-1", "Ficciones", FICCIONES_HEX),
                accountBook("acc-2", "Dubliners", RAYUELA_HEX),
            ),
        )

        assertEquals(
            listOf("Dubliners"),
            state.books.filter { it.canDownload }.map { it.title },
        )
        assertNull("a row that is here has nothing to fetch", state.books.single { it.id == FICCIONES }.download)
    }

    @Test
    fun `a download belongs to the account book it was started for`() {
        val state = shelf(
            catalogOf(),
            accountOf(
                accountBook("acc-1", "Dubliners", RAYUELA_HEX),
                accountBook("acc-2", "Ulysses", ULYSSES_HEX),
            ),
            downloads = AccountDownloadsState(
                byAccountBookId = mapOf("acc-1" to BookDownloadState.Downloading(received = 3, total = 4)),
            ),
        )

        val downloading = state.books.single { it.title == "Dubliners" }
        assertEquals(0.75f, (downloading.download as BookDownloadState.Downloading).fraction!!, 0.001f)
        assertFalse("a row already downloading does not offer to start again", downloading.canDownload)
        assertNull("and its neighbour is untouched", state.books.single { it.title == "Ulysses" }.download)
    }

    @Test
    fun `a refused download keeps the book listed and offers no open`() {
        val state = shelf(
            catalogOf(),
            accountOf(accountBook("acc-1", "Dubliners", RAYUELA_HEX)),
            downloads = AccountDownloadsState(
                byAccountBookId = mapOf(
                    "acc-1" to BookDownloadState.Refused(DownloadProblem.TAMPERED),
                ),
            ),
        )

        val row = state.books.single()
        assertEquals("the book stays on the shelf", "Dubliners", row.title)
        assertFalse("and it still does not open", row.canOpen)
        assertEquals(DownloadProblem.TAMPERED, (row.download as BookDownloadState.Refused).problem)
    }

    /**
     * D4's copy rule, and the whole of it: a downloaded copy is a device book.
     * Signed out there is no account half at all, and the row still opens and
     * still offers the one removal that frees its bytes.
     */
    @Test
    fun `a downloaded copy is a device book after sign-out, openable and removable`() {
        val catalog = Catalog(books = listOf(LibraryFixtures.accountCopy(RAYUELA_HEX, "Dubliners")))

        val state = shelf(catalog, AccountLibraryState.SIGNED_OUT)

        val row = state.books.single()
        assertNull("no account half: the rows left with the session", row.account)
        assertTrue("it opens like any other book on this phone", row.canOpen)
        assertEquals(RAYUELA_HEX, row.accountCopy?.contentSha256)
        assertEquals(6_291_456L, row.accountCopy?.sizeBytes)
    }

    @Test
    fun `signing in again shows the copy as an account book, still one row`() {
        val catalog = Catalog(books = listOf(LibraryFixtures.accountCopy(RAYUELA_HEX, "Dubliners")))

        val state = shelf(catalog, accountOf(accountBook("acc-1", "Dubliners", RAYUELA_HEX)))

        val row = state.books.single()
        assertEquals("re-bound by content identity, never by a path", "acc-1", row.account?.bookId)
        assertTrue(row.account?.onThisDevice == true)
        assertEquals("and it is still the same copy", RAYUELA_HEX, row.accountCopy?.contentSha256)
        assertNull("a book the account already holds offers nothing to upload", row.addToAccount)
    }

    @Test
    fun `a book from a folder has no copy to free`() {
        val state = shelf(catalogOf(FICCIONES to "Ficciones"), accountOf())

        assertNull(state.books.single().accountCopy)
    }

    // ---- the account's place on the shelf (#121, REQ-511) -----------------------------

    /**
     * The row carries both numbers, and the device's is untouched: the account's
     * place is a *second* value the row can show, never a replacement for the one
     * the catalog holds.
     */
    @Test
    fun `an account place further on is carried beside this device's`() {
        val state = shelf(readAt(0.37f), accountOf(withPlace(percent = 68.0)))

        val row = state.books.single()
        assertEquals("the catalog's number is unchanged", 37, row.progressPercent)
        assertEquals(68, row.account?.remotePercent)
        assertEquals("and it is worth showing", 68, row.accountPercentAhead)
    }

    /**
     * Level, behind, and absent all say nothing extra — #121's "a remote position
     * older than local shows nothing on the shelf", and the case that makes it
     * matter: this device's own published position coming back.
     */
    @Test
    fun `an account place level with or behind this device's is not shown`() {
        assertNull(
            "the same place, which is what this device's own publish looks like on return",
            shelf(readAt(0.37f), accountOf(withPlace(percent = 37.0)))
                .books.single().accountPercentAhead,
        )
        assertNull(
            "and a place further back",
            shelf(readAt(0.37f), accountOf(withPlace(percent = 4.0)))
                .books.single().accountPercentAhead,
        )
        assertNull(
            "and an account row that holds no place at all",
            shelf(readAt(0.37f), accountOf(accountBook("acc-1", "Ficciones", FICCIONES_HEX)))
                .books.single().accountPercentAhead,
        )
    }

    /**
     * An account-only row has no local place for the account's to be ahead *of*,
     * and its status line already says the book is not on this device — so it
     * carries the percent as a value and shows nothing extra.
     */
    @Test
    fun `an account-only row shows nothing extra`() {
        val state = shelf(catalogOf(), accountOf(withPlace(percent = 68.0, contentSha256 = RAYUELA_HEX)))

        val row = state.books.single()
        assertTrue(row.isAccountOnly)
        assertEquals(68, row.account?.remotePercent)
        assertNull(row.accountPercentAhead)
    }

    /** Signed out there is no account half, so there is no second number either. */
    @Test
    fun `signed out no row shows a second place`() {
        val state = shelf(readAt(0.37f), AccountLibraryState.SIGNED_OUT)

        assertNull(state.books.single().account)
        assertNull(state.books.single().accountPercentAhead)
    }

    /** Ficciones, on this device, read to [fraction]. */
    private fun readAt(fraction: Float) = Catalog(
        books = listOf(LibraryFixtures.readable(FICCIONES, "Ficciones", fileName = "Ficciones.epub")),
        readingStates = mapOf(FICCIONES to ReadingState(progressFraction = fraction)),
    )

    private fun withPlace(percent: Double, contentSha256: String = FICCIONES_HEX) =
        accountBook("acc-1", "Ficciones", contentSha256).copy(
            remotePosition = AccountRemotePosition(
                href = "OEBPS/ch8.xhtml",
                chapterTitle = "Chapter Eight",
                progression = percent / 100.0,
                percent = percent,
                updatedAt = "2026-09-20T10:00:00Z",
                revision = 4,
            ),
        )
}
