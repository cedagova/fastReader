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

    /** The seam to LEAF301: the three cue choices reach the renderer unchanged. */
    @Test
    fun `the cue projection carries exactly the three cue choices`() {
        val settings = ReaderSettings.DEFAULTS.copy(
            pivotEnabled = false,
            pivotColor = PivotColor.TEAL,
            guideMarksEnabled = false,
            // Not a cue: it must not leak into the value the renderer is given.
            pauseStrength = PauseStrength.STRONG,
        )

        val cues = settings.cues

        assertFalse(cues.pivotEnabled)
        assertEquals(PivotColor.TEAL, cues.pivotColor)
        assertFalse(cues.guideMarksEnabled)
        // Font size is not a cue, but the word's size is derived from it.
        assertEquals(CueSettings.DEFAULT_WORD_SIZE_SP, cues.wordSizeSp, 0f)
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
    fun `the word size follows the font-size setting`() {
        val sizes = FontSize.entries.map { ReaderSettings.DEFAULTS.copy(fontSize = it).cues.wordSizeSp }

        assertEquals(sizes.sorted(), sizes)
        assertEquals(sizes.distinct().size, sizes.size)
        assertEquals(
            CueSettings.DEFAULT_WORD_SIZE_SP,
            ReaderSettings.DEFAULTS.copy(fontSize = FontSize.MEDIUM).cues.wordSizeSp,
            0f,
        )
        assertEquals(
            CueSettings.DEFAULT_WORD_SIZE_SP * FontSize.EXTRA_LARGE.scale,
            ReaderSettings.DEFAULTS.copy(fontSize = FontSize.EXTRA_LARGE).cues.wordSizeSp,
            0f,
        )
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
        assertFalse(ReaderSettings.DEFAULTS.copy(pauseStrength = PauseStrength.OFF).isDefault)
    }

    /** The bounded set the definition allows, so an addition to it is a deliberate change. */
    @Test
    fun `the customisation set stays bounded`() {
        assertEquals(3, ThemeChoice.entries.size)
        assertEquals(4, FontSize.entries.size)
        assertEquals(5, PivotColor.entries.size)
        assertEquals(4, PauseStrength.entries.size)
    }
}
