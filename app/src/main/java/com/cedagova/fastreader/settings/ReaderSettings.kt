package com.cedagova.fastreader.settings

import com.cedagova.fastreader.timing.PauseStrength
import kotlinx.serialization.Serializable

/**
 * Everything a reader can change about how the app presents a book (LEAF302).
 *
 * This is both the settings screen's state and the persisted schema: it is stored
 * inside the catalog document under the AD-3 versioned-schema rule, so adding a
 * field here is a schema change that needs a migration in
 * [com.cedagova.fastreader.library.store.CatalogSchema].
 *
 * ## Every field has a documented default, and an absent key reads it back
 *
 * The definition's REQ-040 acceptance — a reader updates the app and keeps their
 * library, positions *and* settings — depends on that discipline starting here. So
 * this class holds no nullable settings and no sentinel values: a document written
 * before this leaf existed, a document a partial write truncated, and a document
 * carrying an enum value from a newer build all resolve to exactly the values
 * below. [DEFAULTS] names them once, and reset-to-defaults (REQ-023) restores that
 * value and nothing else.
 *
 * ## The bounded set (definition constraint)
 *
 * The definition rules out a free-form theme engine, so every choice here is an
 * enum or a boolean over a small fixed set: three themes, four font sizes, five
 * highlight colours, four pause strengths, three library orders, four toggles.
 * There is deliberately no stored colour value, no stored point size, and no
 * per-multiplier timing panel.
 *
 * ## Why the cue fields are flat rather than a nested [CueSettings]
 *
 * [CueSettings] is the *render-time* seam and carries a word size in
 * scale-independent pixels — a derived rendering value rather than a reader's
 * choice. Persisting it whole would put that pixel value in the schema and tie the
 * store to the renderer. The four cue choices a reader actually makes are stored
 * flat, and [cues] reassembles them — with the word size derived from
 * [fontSize] — on the way to the renderer.
 */
@Serializable
data class ReaderSettings(
    /** Light, dark, or follow the device (REQ-022). Applies to the reader and the library. */
    val theme: ThemeChoice = ThemeChoice.SYSTEM,
    /** Text size across the whole app (REQ-022), on top of the device's own font scale. */
    val fontSize: FontSize = FontSize.MEDIUM,
    /**
     * Colour the recognition letter of each word (REQ-020). On by default; the
     * settings screen calls it "Highlight letter".
     *
     * Replaces increment 003's `pivotEnabled`, which also moved the word. A
     * document written before this build migrates that flag here — see
     * [com.cedagova.fastreader.library.store.CueSplitV4Migration].
     */
    val highlightEnabled: Boolean = true,
    /**
     * Hold the highlighted letter on a fixed column left of centre instead of
     * centring the word (REQ-020). **Off by default**; the settings screen calls
     * it "Fixed focus letter". [CueSettings] carries the owner decision behind
     * that default.
     */
    val focusAlignmentEnabled: Boolean = false,
    /** Which colour the highlighted letter is drawn in, from the bounded palette (REQ-020). */
    val pivotColor: PivotColor = PivotColor.ACCENT,
    /** The guide marks under the word's column (REQ-021). */
    val guideMarksEnabled: Boolean = true,
    /**
     * How much extra pause the timing engine applies at sentence, clause and
     * paragraph boundaries (REQ-011). `OFF` makes every word uniform.
     */
    val pauseStrength: PauseStrength = PauseStrength.NORMAL,
    /**
     * Stop the stream on the first word of every new chapter (REQ-201). **On by
     * default**, which is exactly v1's behaviour: v1's REQ-015 made that stop
     * mandatory, and decision D4 turns it into a choice without changing what
     * an existing reader gets.
     *
     * It is not a [pauseStrength] value. Pause strength stretches a word's
     * *duration* and is a timing input; this ends a run outright and is a
     * [com.cedagova.fastreader.reader.ReaderSession] transition. Folding the two
     * together would make "no pauses at all" silently mean "never stop at a
     * chapter", which is two different readers' preferences on one control.
     *
     * A document written before schema 5 reads this back as `true` — see
     * [com.cedagova.fastreader.library.store.ChapterPauseV5Migration].
     */
    val chapterPauseEnabled: Boolean = true,
    /**
     * How the library lists books (REQ-203). **Recently read by default**, which
     * is what a v1.1.0 reader who never chooses gets: the book they were last in
     * is the one at the top.
     *
     * It lives here, in the reader's settings, rather than in the library's own
     * screen state, because REQ-203 requires the choice to survive a restart and
     * this class is already the persisted place a reader's presentation choices
     * live. It is therefore also part of [isDefault], so reset-to-defaults
     * (REQ-023) puts the library back to recently read along with everything
     * else — a sort the reader chose is a setting they changed.
     *
     * A document written before schema 6 reads this back as `RECENTLY_READ` —
     * see [com.cedagova.fastreader.library.store.LibraryOrderV6Migration].
     */
    val libraryOrder: LibraryOrder = LibraryOrder.RECENTLY_READ,
) {

    /**
     * These settings as the cue renderer consumes them (LEAF301's seam).
     *
     * The word size is [FontSize.scale] applied to the renderer's own base size,
     * *not* left to the font scale that carries the rest of REQ-022. Android's
     * font scaling is non-linear above roughly 20 sp and flat by 36 sp — the exact
     * size the streamed word is drawn at — so at a device font scale of 1.5 a
     * 12 sp label becomes 18 dp while the 36 sp word stays 36 dp. That curve is
     * right for body text and wrong here: the word *is* the reading surface, and a
     * reader who asks for larger text and gets a larger library, larger controls
     * and an identical word has not had their setting applied. See
     * [com.cedagova.fastreader.ui.theme.FastReaderTheme].
     */
    val cues: CueSettings get() = CueSettings(
        highlightEnabled = highlightEnabled,
        focusAlignmentEnabled = focusAlignmentEnabled,
        pivotColor = pivotColor,
        guideMarksEnabled = guideMarksEnabled,
        wordSizeSp = CueSettings.DEFAULT_WORD_SIZE_SP * fontSize.scale,
    )

    /** True when nothing has been changed from [DEFAULTS] — the reset control's enabled state. */
    val isDefault: Boolean get() = this == DEFAULTS

    companion object {

        /**
         * The documented defaults, in one place.
         *
         * Reset-to-defaults restores exactly this value, an absent stored key
         * decodes to the matching field of it, and the tests assert against it
         * rather than against repeated literals.
         */
        val DEFAULTS: ReaderSettings = ReaderSettings()
    }
}

/** Light, dark, or whatever the device is set to (REQ-022). */
@Serializable
enum class ThemeChoice {
    LIGHT,
    DARK,

    /** Follow the device's own light/dark setting. The default. */
    SYSTEM,
}

/**
 * How the library orders its books (REQ-203).
 *
 * All three are computed from timestamps the catalog already keeps, so choosing
 * one costs no new stored per-book field: [RECENTLY_READ] reads
 * `ReadingState.updatedAtEpochMs`, which the position writer stamps on every
 * write, and [RECENTLY_ADDED] reads `Book.addedAtEpochMs`, stamped when the book
 * first entered the catalog.
 *
 * Every order is total. The two by-time orders put the most recent first and
 * fall back to [TITLE] for everything they cannot separate — two books read in
 * the same millisecond, and every book whose timestamp is the `0` that means
 * "never" — so the list can never reshuffle between two renders of the same
 * catalog.
 */
@Serializable
enum class LibraryOrder {

    /** Alphabetical, the way the reader's own language sorts. v1's only order. */
    TITLE,

    /**
     * Most recently read first, then every book never read, alphabetically. The
     * default: it puts the book being read at the top without the reader
     * choosing anything.
     */
    RECENTLY_READ,

    /** Most recently added to the library first, then everything added before timestamps were kept. */
    RECENTLY_ADDED,
}

/**
 * The bounded font-size set (REQ-022).
 *
 * [scale] multiplies the device's own font scale rather than replacing it, so a
 * reader who has already enlarged system text gets larger app text still, and a
 * reader who has not gets exactly these steps.
 *
 * It reaches the app by two routes, and both are needed. The theme applies it to
 * the density's font scale, which carries every ordinary `sp` in the app — library
 * rows, reader chrome, this screen's own text. [ReaderSettings.cues] applies it
 * again, linearly, to the streamed word, because Android's font-scale curve is
 * deliberately flat at the size that word is drawn at and would otherwise leave
 * the one thing a reader actually reads exactly as it was.
 */
@Serializable
enum class FontSize(val scale: Float) {
    SMALL(0.85f),

    /** The size every screen was designed at. The default. */
    MEDIUM(1.0f),
    LARGE(1.25f),
    EXTRA_LARGE(1.5f),
}
