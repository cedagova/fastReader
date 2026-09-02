package com.cedagova.fastreader.library

import com.cedagova.fastreader.library.ui.LibraryFixtures
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * REQ-009's routing table.
 *
 * Every branch here is a state a reader can reach without doing anything unusual
 * — moving a folder, revoking a grant, removing a book — and each one has to lead
 * somewhere that explains itself rather than to a blank library.
 */
class LaunchDestinationTest {

    @Test
    fun `a readable last-read book is opened directly`() {
        val catalog = catalog(LibraryFixtures.readable("sha256:a", "Quiet"), lastRead = "sha256:a")

        assertEquals(LaunchDestination.Reader("sha256:a"), launchDestination(catalog))
    }

    @Test
    fun `a first launch opens the library with nothing to explain`() {
        assertEquals(LaunchDestination.Library(), launchDestination(Catalog()))
    }

    @Test
    fun `a library that was never read from opens with nothing to explain`() {
        val catalog = catalog(LibraryFixtures.readable("sha256:a", "Quiet"), lastRead = null)

        assertEquals(LaunchDestination.Library(), launchDestination(catalog))
    }

    @Test
    fun `a missing last-read book opens the library saying so`() {
        val catalog = catalog(
            LibraryFixtures.unavailable("sha256:a", "Quiet", SourceAvailability.MISSING),
            lastRead = "sha256:a",
        )

        assertEquals(
            LaunchDestination.Library(ResumeBlocked("sha256:a", ResumeBlockedReason.MISSING)),
            launchDestination(catalog),
        )
    }

    // REQ-009's second acceptance: revoke the folder grant, launch, see why.
    @Test
    fun `a last-read book whose permission was revoked opens the library saying so`() {
        val catalog = catalog(
            LibraryFixtures.unavailable("sha256:a", "Quiet", SourceAvailability.PERMISSION_LOST),
            lastRead = "sha256:a",
        )

        assertEquals(
            LaunchDestination.Library(ResumeBlocked("sha256:a", ResumeBlockedReason.PERMISSION_LOST)),
            launchDestination(catalog),
        )
    }

    @Test
    fun `a last-read book that turned out to be unreadable opens the library saying so`() {
        val catalog = catalog(
            LibraryFixtures.rejected("sha256:a", "Quiet", BookContentStatus.DRM_PROTECTED, "drm"),
            lastRead = "sha256:a",
        )

        assertEquals(
            LaunchDestination.Library(ResumeBlocked("sha256:a", ResumeBlockedReason.UNREADABLE)),
            launchDestination(catalog),
        )
    }

    // REQ-004: removal keeps the position, so the id outlives the catalog entry.
    @Test
    fun `a removed last-read book opens the library saying the position is kept`() {
        val catalog = Catalog(
            lastReadBookId = "sha256:a",
            readingStates = mapOf("sha256:a" to ReadingState(bookDigest = "sha256:a", tokenIndex = 42)),
            removedBookIds = setOf("sha256:a"),
        )

        assertEquals(
            LaunchDestination.Library(ResumeBlocked("sha256:a", ResumeBlockedReason.REMOVED)),
            launchDestination(catalog),
        )
    }

    /**
     * Found on the emulator. Identity is the content digest (AD-2), so replacing a
     * book's file in place makes it a different book and the old entry disappears
     * without anyone removing anything. Saying "you removed it" would be untrue.
     */
    @Test
    fun `a last-read book that vanished without being removed says so neutrally`() {
        val catalog = Catalog(
            lastReadBookId = "sha256:a",
            readingStates = mapOf("sha256:a" to ReadingState(bookDigest = "sha256:a", tokenIndex = 42)),
        )

        assertEquals(
            LaunchDestination.Library(ResumeBlocked("sha256:a", ResumeBlockedReason.NOT_IN_LIBRARY)),
            launchDestination(catalog),
        )
    }

    @Test
    fun `a last-read id with nothing behind it at all opens a plain library`() {
        assertEquals(
            LaunchDestination.Library(),
            launchDestination(Catalog(lastReadBookId = "sha256:gone")),
        )
    }

    private fun catalog(book: Book, lastRead: String?) = Catalog(
        books = listOf(book),
        lastReadBookId = lastRead,
        readingStates = mapOf(book.id to ReadingState(bookDigest = book.id, tokenIndex = 7)),
    )
}
