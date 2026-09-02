package com.cedagova.fastreader.settings

import kotlinx.serialization.Serializable

/**
 * The cue layer's parameters (REQ-020/REQ-021), as plain data.
 *
 * This is the seam between the cue *rendering* and the settings *surface*. It is
 * deliberately Android-free and Compose-free so the settings screen can persist it
 * under the AD-3 versioned-schema rule and drive its live preview by handing a
 * different value to the same renderer — no new rendering path, and nothing here
 * that a store cannot round-trip.
 *
 * ## Two independent cues where there used to be one
 *
 * Increment 003 shipped one `pivotEnabled` flag that did two things at once:
 * it coloured the recognition letter *and* shifted the word so that letter sat on
 * a fixed column left of centre. Issue #32 split them, because they are separate
 * choices with separate costs:
 *
 * - [highlightEnabled] colours one letter of the word. On by default.
 * - [focusAlignmentEnabled] moves the word off centre so that letter always lands
 *   on the same column. **Off** by default — the word is centred, which is the
 *   presentation RSVP readers have had since the 1970s.
 *
 * ## Owner decision: the deviation from REQ-020
 *
 * The product definition's REQ-020 says the alignment cue is on by default. It is
 * not, and that is deliberate. Independent review of increment 003 (issues #32 and
 * #33) found that the shipped combination — compute a recognition point, position
 * the word so it sits on a fixed column left of centre, and mark that letter —
 * reads on the core claims of US 8,903,174. The owner decided on 2026-09-02 to
 * ship the centred presentation by default and keep the off-centre alignment as an
 * opt-in setting, without removing any capability. Nothing here is a legal
 * opinion; #33 tracks what remains open before a public launch.
 *
 * ## The guide-mark default
 *
 * REQ-021 makes the guide marks *optional* — it requires the toggle and says
 * nothing about which way it starts — so they stay **on**. They are quiet enough
 * to leave on (a hairline and a 5 dp caret), and it means the app's default
 * presentation is what the AD-6 static-luminance and max-speed measurements were
 * taken of, rather than a state only a test can reach.
 *
 * The word size is the one the reader screen has used since increment 002.
 */
data class CueSettings(
    /**
     * Colour the recognition letter of each word, in [pivotColor]. On by default.
     *
     * Independent of [focusAlignmentEnabled]: the letter is coloured in a centred
     * word exactly as it is in an aligned one.
     */
    val highlightEnabled: Boolean = true,
    /**
     * Shift each word so its recognition letter lands on a fixed column left of
     * centre, instead of centring the word. **Off** by default — see the class
     * note on the owner decision behind that.
     */
    val focusAlignmentEnabled: Boolean = false,
    /**
     * Which colour the highlighted letter is drawn in (REQ-020's bounded palette).
     *
     * Still named for the pivot because that is what it colours: the recognition
     * point [com.cedagova.fastreader.reader.ui.pivotOffset] computes. The name is
     * internal to the code and to the stored document; the settings screen calls
     * it "Highlight colour".
     */
    val pivotColor: PivotColor = PivotColor.ACCENT,
    /** The guide marks under the word's column. See the class note on this default. */
    val guideMarksEnabled: Boolean = true,
    /**
     * Word size in scale-independent pixels, before the system font scale and
     * before shrink-to-fit. The font-size setting binds here.
     */
    val wordSizeSp: Float = DEFAULT_WORD_SIZE_SP,
) {
    companion object {
        const val DEFAULT_WORD_SIZE_SP: Float = 36f

        /** What the app ships with: a centred word, a coloured letter, the marks. */
        val DEFAULTS: CueSettings = CueSettings()

        /** Every cue on, including the opt-in off-centre alignment. */
        val ALL_CUES: CueSettings = CueSettings(
            highlightEnabled = true,
            focusAlignmentEnabled = true,
            guideMarksEnabled = true,
        )

        /**
         * Highlight and off-centre alignment without the marks — increment 003's
         * `pivotEnabled` rendering, which is exactly what turning **Fixed focus
         * letter** on has to reproduce.
         */
        val FOCUS_ALIGNED_ONLY: CueSettings = CueSettings(
            highlightEnabled = true,
            focusAlignmentEnabled = true,
            guideMarksEnabled = false,
        )

        /** Nothing but a centred, single-colour word — REQ-020's "cue off" acceptance state. */
        val NO_CUES: CueSettings = CueSettings(
            highlightEnabled = false,
            focusAlignmentEnabled = false,
            guideMarksEnabled = false,
        )
    }
}

/**
 * The bounded highlight palette (REQ-020: "pivot color from a small palette").
 *
 * An enum rather than a colour value: the definition rules out a free-form theme
 * engine, a stored ARGB integer could name a colour invisible against the page,
 * and each entry has to resolve differently in the light and dark themes.
 */
@Serializable
enum class PivotColor {
    /** The app's own accent — the theme's primary colour. The default. */
    ACCENT,
    CRIMSON,
    AMBER,
    TEAL,
    VIOLET,
}
