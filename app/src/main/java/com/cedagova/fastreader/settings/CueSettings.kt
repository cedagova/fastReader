package com.cedagova.fastreader.settings

/**
 * The cue layer's parameters (REQ-020/REQ-021), as plain data.
 *
 * This is the seam between the cue *rendering* this leaf owns and the settings
 * *surface* LEAF302 owns. It is deliberately Android-free and Compose-free so
 * LEAF302 can persist it under the AD-3 versioned-schema rule and drive its live
 * preview by handing a different value to the same renderer — no new rendering
 * path, and nothing here that a store cannot round-trip.
 *
 * ## The defaults, and one decision inside them
 *
 * REQ-020 fixes the pivot cue as on. REQ-021 makes the guide marks *optional* —
 * it requires the toggle, and says nothing about which way it starts — so this
 * leaf chose: **on**. Alignment without a mark reads as a word that drifts, since
 * nothing on the page says where the column is, and the marks were designed to be
 * quiet enough to leave on (a hairline and a 5 dp caret). It also means the
 * app's default presentation is its maximum-cue presentation, so the AD-6
 * static-luminance and max-speed measurements are of what the reader actually
 * gets rather than of a state only a test can reach. LEAF302 adds the toggle that
 * makes the choice the reader's.
 *
 * The word size is the one the reader screen has used since increment 002.
 */
data class CueSettings(
    /** Pivot-letter alignment with the pivot in an accent colour. On by default. */
    val pivotEnabled: Boolean = true,
    val pivotColor: PivotColor = PivotColor.ACCENT,
    /** The guide marks under the alignment column. See the class note on this default. */
    val guideMarksEnabled: Boolean = true,
    /**
     * Word size in scale-independent pixels, before the system font scale and
     * before shrink-to-fit. LEAF302's font-size setting binds here.
     */
    val wordSizeSp: Float = DEFAULT_WORD_SIZE_SP,
) {
    companion object {
        const val DEFAULT_WORD_SIZE_SP: Float = 36f

        /** Every cue on — the default, named for the tests that assert on it. */
        val ALL_CUES: CueSettings = CueSettings(pivotEnabled = true, guideMarksEnabled = true)

        /** Pivot alignment and colour, without the marks under the column. */
        val PIVOT_ONLY: CueSettings = CueSettings(pivotEnabled = true, guideMarksEnabled = false)

        /** Nothing but a centred word — REQ-020's "cue off" acceptance state. */
        val NO_CUES: CueSettings = CueSettings(pivotEnabled = false, guideMarksEnabled = false)
    }
}

/**
 * The bounded pivot palette (REQ-020: "pivot color from a small palette").
 *
 * An enum rather than a colour value: the definition rules out a free-form theme
 * engine, a stored ARGB integer could name a colour invisible against the page,
 * and each entry has to resolve differently in the light and dark themes. LEAF302
 * owns the picker; these are the values it picks from.
 */
enum class PivotColor {
    /** The app's own accent — the theme's primary colour. The default. */
    ACCENT,
    CRIMSON,
    AMBER,
    TEAL,
    VIOLET,
}
