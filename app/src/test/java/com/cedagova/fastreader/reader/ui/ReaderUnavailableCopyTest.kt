package com.cedagova.fastreader.reader.ui

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cedagova.fastreader.content.ContentFailureReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The copy a reader sees when a book will not open.
 *
 * Since LEAF204 this screen can be the *first* thing the app shows: launch routes
 * straight into the last-read book, and a file deleted since the last session
 * fails here. The reason it fails carries a technical detail — `open failed:
 * ENOENT (No such file or directory)`, `unexpected end of stream` — and none of
 * that belongs on a reader's screen.
 *
 * The structural guarantee is that [ReaderUiState.Unavailable] holds a
 * [ContentFailureReason] and no free-form text, so there is nowhere for an
 * exception to be interpolated. These tests hold the copy itself to the same
 * standard, so a reason added later cannot reintroduce it quietly.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ReaderUnavailableCopyTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    /** Fragments of the technical vocabulary these failures actually produce. */
    private val technical = listOf(
        "exception", "failed:", "errno", "enoent", "eacces", "null", "/data/",
        "stream", "java.", "throw", "%s", "%1\$s",
    )

    @Test
    fun `every reason has plain-language copy with nothing technical in it`() {
        for (reason in ContentFailureReason.entries) {
            val copy = context.getString(reason.messageRes())
            assertTrue("$reason has no copy", copy.isNotBlank())
            assertTrue("$reason should read as a sentence: $copy", copy.trim().endsWith("."))
            for (fragment in technical) {
                assertFalse(
                    "$reason copy must not carry technical text ($fragment): $copy",
                    copy.lowercase().contains(fragment),
                )
            }
        }
    }

    @Test
    fun `each reason says something different`() {
        val copies = ContentFailureReason.entries.map { context.getString(it.messageRes()) }

        assertEquals(ContentFailureReason.entries.size, copies.toSet().size)
    }

    /** The state itself has no slot a detail string could be smuggled through. */
    @Test
    fun `the unavailable state carries a reason rather than a message`() {
        val state = ReaderUiState.Unavailable("The Long Signal", ContentFailureReason.UNREADABLE_SOURCE)

        assertEquals(ContentFailureReason.UNREADABLE_SOURCE, state.reason)
        assertEquals(
            "the state's only strings are the title and the reason",
            listOf("bookTitle", "reason"),
            ReaderUiState.Unavailable::class.java.declaredFields
                // Compose's compiler adds a `${'$'}stable` field to every class it sees.
                .filterNot { it.isSynthetic || it.name.startsWith("${'$'}") }
                .map { it.name },
        )
    }
}
