package com.cedagova.fastreader.settings

/**
 * The theme choice as the *launch path* can read it (AD-10, REQ-102).
 *
 * ## Why a second copy of a setting exists at all
 *
 * [ReaderSettings.theme] lives inside the catalog document, which is JSON on
 * disk read on a background dispatcher after the process starts. Everything that
 * decides what the reader sees *first* — the system splash, the activity window's
 * background, the window theme — is resolved before any of that has happened. Up
 * to increment 001 the consequence was visible: a reader on a dark device, or one
 * who had chosen Dark, got a white frame on every cold start.
 *
 * So the choice is mirrored into a store that can be read synchronously before
 * `super.onCreate` creates the window. The mirror is a **cache, not a second
 * source of truth**: the catalog document remains authoritative, the mirror
 * carries the same default ([ReaderSettings.DEFAULTS]`.theme`), and anything
 * unreadable resolves to that default rather than to a guess.
 *
 * ## The ordering rule
 *
 * The settings write path writes the catalog first and the mirror second, so a
 * catalog write that fails leaves both at the previous value and the two cannot
 * disagree about a change that did not happen. A mirror write that itself fails
 * is swallowed — see [write] — and re-synced the next time the catalog is loaded
 * or written; the cost of that window is one launch frame, never a wrong setting.
 */
interface ThemeMirror {

    /**
     * The mirrored choice, or [ReaderSettings.DEFAULTS]`.theme` when there is
     * nothing readable there.
     *
     * Called on the main thread on the launch path, so it must not do more work
     * than reading one small file.
     */
    fun read(): ThemeChoice

    /**
     * Makes [theme] the value the next cold start will launch into.
     *
     * Idempotent: writing the value that is already mirrored, and already applied
     * to whatever platform state the implementation keeps in step, does nothing.
     * That is what lets the launch path call `write(read())` as a cheap repair.
     *
     * Must not throw. It runs inside the catalog write path, where an exception
     * would be reported to the reader as a lost setting — which a stale cache is
     * not.
     */
    fun write(theme: ThemeChoice)

    companion object {

        /**
         * A mirror that stores nothing and always reads the default.
         *
         * The default for [com.cedagova.fastreader.library.LibraryRepository], so
         * a JVM test that does not care about the launch frame constructs the
         * repository exactly as it did before this leaf.
         */
        val None: ThemeMirror = object : ThemeMirror {
            override fun read(): ThemeChoice = ReaderSettings.DEFAULTS.theme
            override fun write(theme: ThemeChoice) = Unit
        }
    }
}
