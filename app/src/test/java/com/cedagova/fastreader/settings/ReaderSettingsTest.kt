package com.cedagova.fastreader.settings

import com.cedagova.fastreader.timing.PauseStrength
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The settings model's own contract, as distinct from how it is stored
 * (`CatalogStoreTest`) or drawn (`SettingsScreenScreenshotTest`).
 */
class ReaderSettingsTest {

    /** The seam to the renderer: the four cue choices reach it unchanged. */
    @Test
    fun `the cue projection carries exactly the four cue choices`() {
        val settings = ReaderSettings.DEFAULTS.copy(
            highlightEnabled = false,
            focusAlignmentEnabled = true,
            pivotColor = PivotColor.TEAL,
            guideMarksEnabled = false,
            // Not a cue: it must not leak into the value the renderer is given.
            pauseStrength = PauseStrength.STRONG,
        )

        val cues = settings.cues

        assertFalse(cues.highlightEnabled)
        assertTrue(cues.focusAlignmentEnabled)
        assertEquals(PivotColor.TEAL, cues.pivotColor)
        assertFalse(cues.guideMarksEnabled)
        // Font size is not a cue, but the word's size is derived from it.
        assertEquals(CueSettings.DEFAULT_WORD_SIZE_SP, cues.wordSizeSp, 0f)
    }

    /**
     * The owner decision of #32, as an assertion rather than a comment: the app
     * ships a centred word with a coloured letter and the marks, and the
     * off-centre alignment is something a reader has to turn on.
     */
    @Test
    fun `the shipped defaults are a centred word with a highlighted letter`() {
        assertTrue(ReaderSettings.DEFAULTS.highlightEnabled)
        assertFalse(ReaderSettings.DEFAULTS.focusAlignmentEnabled)
        assertTrue(ReaderSettings.DEFAULTS.guideMarksEnabled)
        assertEquals(CueSettings.DEFAULTS, ReaderSettings.DEFAULTS.cues)
    }

    /**
     * REQ-201's default, and D4's promise with it: turning the chapter pause into
     * a choice must not change what anyone already had.
     *
     * The cue assertion beside it is not padding — it is the check that a new
     * field did not disturb an existing default, which is the way a schema
     * addition usually goes wrong.
     */
    @Test
    fun `the chapter pause ships on and is not a cue`() {
        assertTrue(ReaderSettings.DEFAULTS.chapterPauseEnabled)
        assertEquals(CueSettings.DEFAULTS, ReaderSettings.DEFAULTS.copy(chapterPauseEnabled = false).cues)
    }

    /** Turning it off is a change, so reset-to-defaults has something to undo (REQ-023). */
    @Test
    fun `turning the chapter pause off is not the default state`() {
        assertTrue(ReaderSettings.DEFAULTS.isDefault)
        assertFalse(ReaderSettings.DEFAULTS.copy(chapterPauseEnabled = false).isDefault)
    }

    /**
     * The paragraph stays paused-only unless asked for: a running stream is one
     * word on a static page, and nobody who updates gets prose under it unasked.
     */
    @Test
    fun `the paragraph is paused-only by default and is not a cue`() {
        assertFalse(ReaderSettings.DEFAULTS.paragraphAlwaysShown)
        assertEquals(CueSettings.DEFAULTS, ReaderSettings.DEFAULTS.copy(paragraphAlwaysShown = true).cues)
        assertFalse(ReaderSettings.DEFAULTS.copy(paragraphAlwaysShown = true).isDefault)
    }

    /** The progress readouts are on by default, and hiding them is a change from the defaults. */
    @Test
    fun `the progress readouts are shown by default`() {
        assertTrue(ReaderSettings.DEFAULTS.progressShown)
        assertFalse(ReaderSettings.DEFAULTS.copy(progressShown = false).isDefault)
    }

    /** The two cues are separate choices, so all four combinations are reachable. */
    @Test
    fun `the highlight and the alignment move independently`() {
        val highlightOnly = ReaderSettings.DEFAULTS.cues
        val alignedOnly = ReaderSettings.DEFAULTS
            .copy(highlightEnabled = false, focusAlignmentEnabled = true).cues
        val both = ReaderSettings.DEFAULTS.copy(focusAlignmentEnabled = true).cues
        val neither = ReaderSettings.DEFAULTS.copy(highlightEnabled = false).cues

        assertEquals(
            listOf(true to false, false to true, true to true, false to false),
            listOf(highlightOnly, alignedOnly, both, neither)
                .map { it.highlightEnabled to it.focusAlignmentEnabled },
        )
    }

    /**
     * REQ-022 reaching the streamed word.
     *
     * Android's font-scale curve is flat at 36 sp — the size the word is drawn at —
     * so the theme's density alone leaves it unchanged at every setting. This is
     * the assertion that catches a regression back to that: each step has to give
     * the word its own size, in step order.
     */
    @Test
    fun `the word size follows the word-size setting`() {
        val sizes = FontSize.entries.map { ReaderSettings.DEFAULTS.copy(wordSize = it).cues.wordSizeSp }

        assertEquals(sizes.sorted(), sizes)
        assertEquals(sizes.distinct().size, sizes.size)
        assertEquals(
            CueSettings.DEFAULT_WORD_SIZE_SP,
            ReaderSettings.DEFAULTS.copy(wordSize = FontSize.MEDIUM).cues.wordSizeSp,
            0f,
        )
        assertEquals(
            CueSettings.DEFAULT_WORD_SIZE_SP * FontSize.EXTRA_LARGE.scale,
            ReaderSettings.DEFAULTS.copy(wordSize = FontSize.EXTRA_LARGE).cues.wordSizeSp,
            0f,
        )
    }

    /** The two sizes are independent: the app's text size leaves the word alone. */
    @Test
    fun `the text size does not touch the word`() {
        FontSize.entries.forEach { size ->
            assertEquals(
                CueSettings.DEFAULT_WORD_SIZE_SP,
                ReaderSettings.DEFAULTS.copy(fontSize = size).cues.wordSizeSp,
                0f,
            )
        }
    }

    /** The step the screens were designed at has to be the neutral one. */
    @Test
    fun `the default font size does not scale anything`() {
        assertEquals(FontSize.MEDIUM, ReaderSettings.DEFAULTS.fontSize)
        assertEquals(1f, FontSize.MEDIUM.scale, 0f)
    }

    /** The steps are a bounded ladder, so no two of them can look the same. */
    @Test
    fun `the font sizes increase strictly`() {
        val scales = FontSize.entries.map { it.scale }

        assertEquals(scales.sorted(), scales)
        assertEquals(scales.distinct().size, scales.size)
    }

    /** What the reset control is enabled by (REQ-023). */
    @Test
    fun `only the documented defaults count as default`() {
        assertTrue(ReaderSettings.DEFAULTS.isDefault)
        assertFalse(ReaderSettings.DEFAULTS.copy(theme = ThemeChoice.DARK).isDefault)
        assertFalse(ReaderSettings.DEFAULTS.copy(guideMarksEnabled = false).isDefault)
        assertFalse(ReaderSettings.DEFAULTS.copy(highlightEnabled = false).isDefault)
        assertFalse(ReaderSettings.DEFAULTS.copy(focusAlignmentEnabled = true).isDefault)
        assertFalse(ReaderSettings.DEFAULTS.copy(pauseStrength = PauseStrength.OFF).isDefault)
        assertFalse(ReaderSettings.DEFAULTS.copy(libraryOrder = LibraryOrder.TITLE).isDefault)
    }

    /**
     * REQ-203's default. Recently read, not the alphabet v1 shipped: a reader who
     * never opens the control still gets the book they were last in at the top.
     *
     * The cue assertion beside it is the same check the chapter-pause default
     * carries — that a new field did not disturb an existing default.
     */
    @Test
    fun `the library ships ordered by recently read`() {
        assertEquals(LibraryOrder.RECENTLY_READ, ReaderSettings.DEFAULTS.libraryOrder)
        assertEquals(CueSettings.DEFAULTS, ReaderSettings.DEFAULTS.copy(libraryOrder = LibraryOrder.TITLE).cues)
    }

    /** The bounded set the definition allows, so an addition to it is a deliberate change. */
    @Test
    fun `the customisation set stays bounded`() {
        assertEquals(3, ThemeChoice.entries.size)
        assertEquals(4, FontSize.entries.size)
        assertEquals(5, PivotColor.entries.size)
        assertEquals(4, PauseStrength.entries.size)
        assertEquals(3, LibraryOrder.entries.size)
    }
}
