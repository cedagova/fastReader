package com.cedagova.fastreader.content

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules a bundled sample follows before any Android class is involved:
 * which one is offered first, and what must never be mistaken for a book.
 */
class BundledSampleTest {

    @Test
    fun `a spanish device is offered the spanish sample first`() {
        assertEquals(
            listOf(BundledSample.SPANISH, BundledSample.ENGLISH),
            BundledSample.offeredFor("es"),
        )
    }

    @Test
    fun `an english device is offered the english sample first`() {
        assertEquals(
            listOf(BundledSample.ENGLISH, BundledSample.SPANISH),
            BundledSample.offeredFor("en"),
        )
    }

    /**
     * The rule is "a sample in your language first", not "Spanish unless English".
     * A device in a third language gets the declared order rather than nothing.
     */
    @Test
    fun `a device in neither language keeps the declared order`() {
        assertEquals(
            listOf(BundledSample.ENGLISH, BundledSample.SPANISH),
            BundledSample.offeredFor("fr"),
        )
    }

    @Test
    fun `the language match ignores case`() {
        assertEquals(BundledSample.SPANISH, BundledSample.offeredFor("ES").first())
    }

    @Test
    fun `every sample is offered exactly once whatever the language`() {
        listOf("en", "es", "fr", "", "zz").forEach { language ->
            assertEquals(
                "offer for '$language'",
                BundledSample.entries.toSet(),
                BundledSample.offeredFor(language).toSet(),
            )
            assertEquals(BundledSample.entries.size, BundledSample.offeredFor(language).size)
        }
    }

    @Test
    fun `a sample identity is recognised so no position is ever stored under it`() {
        BundledSample.entries.forEach { sample ->
            assertTrue(sample.name, BundledSample.isSampleIdentity(sample.identity.value))
        }
    }

    @Test
    fun `a catalog id is not a sample identity`() {
        assertFalse(BundledSample.isSampleIdentity("sha256:${"a".repeat(64)}"))
        assertFalse(BundledSample.isSampleIdentity(""))
    }

    /**
     * The open key is compared against catalog ids and document URIs, so a bare
     * digest would collide with the same file added from the file system.
     */
    @Test
    fun `the open key is not the identity`() {
        BundledSample.entries.forEach { sample ->
            assertEquals("sample:${sample.name}", sample.openKey)
            assertFalse(sample.openKey == sample.identity.value)
        }
    }

    @Test
    fun `the two samples are distinct in every way that identifies them`() {
        assertEquals(
            BundledSample.entries.size,
            BundledSample.entries.map { it.identity.value }.toSet().size,
        )
        assertEquals(
            BundledSample.entries.size,
            BundledSample.entries.map { it.assetPath }.toSet().size,
        )
        assertEquals(
            BundledSample.entries.size,
            BundledSample.entries.map { it.languageTag }.toSet().size,
        )
    }

    @Test
    fun `every pinned identity is a well formed sha256`() {
        BundledSample.entries.forEach { sample ->
            assertTrue(sample.identity.value, sample.identity.value.matches(Regex("sha256:[0-9a-f]{64}")))
        }
    }
}
