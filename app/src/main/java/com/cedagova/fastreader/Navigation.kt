package com.cedagova.fastreader

import androidx.compose.runtime.saveable.Saver
import com.cedagova.fastreader.library.LaunchDestination
import com.cedagova.fastreader.library.ResumeBlocked
import com.cedagova.fastreader.library.ResumeBlockedReason
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * One place the reader can be, as a typed value (`docs/app-shell.md`).
 *
 * Adding a destination is adding one subtype here; the exhaustive `when` in
 * [FastReaderApp] then refuses to compile until it is drawn. Every subtype is
 * serializable, so the whole [BackStack] is saved instance state and survives a
 * rotation and a process death alike.
 */
@Serializable
sealed interface Destination {

    /**
     * Whether a book handed over from another app (REQ-103) is shown in place of
     * this destination.
     *
     * The handed-over book is not a destination: it belongs to the process, not to
     * saved state, so it cannot be restored after process death (AD-9). It
     * replaces the library and a library book, and gives way to anything the
     * reader opened over it — settings, and the account over settings.
     */
    val showsHandedOverBook: Boolean

    /** The library. [resumeBlocked] is the notice launch routing left for it (REQ-009). */
    @Serializable
    @SerialName("library")
    data class Library(val resumeBlocked: ResumeBlocked? = null) : Destination {
        override val showsHandedOverBook: Boolean get() = true
    }

    /**
     * A catalog book in the reader.
     *
     * [chosenByLaunch] says who owns a book that will not open: one the launch
     * routing chose goes back to the library, which can say which book failed
     * (REQ-009); one the reader tapped keeps the reader's own explanation.
     */
    @Serializable
    @SerialName("reader")
    data class Reader(val bookId: String, val chosenByLaunch: Boolean = false) : Destination {
        override val showsHandedOverBook: Boolean get() = true
    }

    /** Settings, over whichever destination opened them (LEAF302). */
    @Serializable
    @SerialName("settings")
    data object Settings : Destination {
        override val showsHandedOverBook: Boolean get() = false
    }

    /** The Reader account, over Settings (#100). */
    @Serializable
    @SerialName("readerAccount")
    data object ReaderAccount : Destination {
        override val showsHandedOverBook: Boolean get() = false
    }
}

/**
 * Where the reader is and how they got there: the app's whole navigation state.
 *
 * Empty until launch routing has decided where the app opens; the last entry is
 * the one on screen and back pops it. Settings sit over whichever destination
 * opened them, so closing them puts the reader back where they were, and the
 * account sits over Settings in turn.
 *
 * Every change is a pure function here, so the routing rules are provable by
 * unit test without composing anything (`NavigationTest`).
 */
data class BackStack(val entries: List<Destination>) {

    /** What is on screen, or null before launch routing has decided. */
    val top: Destination? get() = entries.lastOrNull()

    /** Launch routing has decided; the app shows a destination rather than a blank frame. */
    val routed: Boolean get() = entries.isNotEmpty()

    /**
     * The catalog book open in the reader, even while Settings sit over it — the
     * book the account records as opened.
     */
    val openBookId: String? get() = entries.filterIsInstance<Destination.Reader>().lastOrNull()?.bookId

    fun push(destination: Destination): BackStack = BackStack(entries + destination)

    fun pop(): BackStack = BackStack(entries.dropLast(1))

    /** The reader put away the resume notice on the library. */
    fun dismissResumeNotice(): BackStack = when (val top = top) {
        is Destination.Library -> BackStack(entries.dropLast(1) + top.copy(resumeBlocked = null))
        else -> this
    }

    /**
     * [bookId] turned out not to open. Only a book the launch chose goes back to
     * the library, with the notice that names it (REQ-009); a book the reader
     * tapped stays on the reader's own explanation.
     */
    fun cannotOpen(bookId: String): BackStack {
        val top = top as? Destination.Reader ?: return this
        if (!top.chosenByLaunch) return this
        return BackStack(listOf(Destination.Library(ResumeBlocked(bookId, ResumeBlockedReason.UNREADABLE))))
    }

    companion object {

        /** Before launch routing: the blank frame. */
        val UNROUTED = BackStack(emptyList())

        /**
         * The library alone: after an "Open with", which outranks everything the
         * app was showing (REQ-103), and when a hand-over was pending at launch.
         * Clearing the rest is also what makes closing the handed-over book land
         * in the library rather than back in whatever it interrupted.
         */
        val LIBRARY = BackStack(listOf(Destination.Library()))

        /**
         * Where launch routing lands (REQ-009): straight into the last-read book,
         * with the library under it for back, or the library with the notice that
         * says why not.
         */
        fun launchedInto(destination: LaunchDestination): BackStack = when (destination) {
            is LaunchDestination.Reader -> BackStack(
                listOf(Destination.Library(), Destination.Reader(destination.bookId, chosenByLaunch = true)),
            )

            is LaunchDestination.Library -> BackStack(listOf(Destination.Library(destination.blocked)))
        }

        private val entriesSerializer = ListSerializer(Destination.serializer())

        /**
         * Saves the stack as one JSON string. A string that does not decode — only
         * possible across app versions — restores nothing, so the launch routing
         * simply runs again rather than crashing an app that has just been
         * restored.
         */
        val SAVER: Saver<BackStack, String> = Saver(
            save = { Json.encodeToString(entriesSerializer, it.entries) },
            restore = { saved ->
                runCatching { BackStack(Json.decodeFromString(entriesSerializer, saved)) }.getOrNull()
            },
        )
    }
}
