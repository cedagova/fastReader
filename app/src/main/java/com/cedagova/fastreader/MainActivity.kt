package com.cedagova.fastreader

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.cedagova.fastreader.content.BundledSample
import com.cedagova.fastreader.crash.CrashReportStore
import com.cedagova.fastreader.crash.ui.CrashReportOfferHost
import com.cedagova.fastreader.external.incomingBook
import com.cedagova.fastreader.library.LaunchDestination
import com.cedagova.fastreader.library.LibraryGraph
import com.cedagova.fastreader.library.ResumeBlocked
import com.cedagova.fastreader.library.ResumeBlockedReason
import com.cedagova.fastreader.library.launchDestination
import com.cedagova.fastreader.library.ui.LibraryRoute
import com.cedagova.fastreader.reader.ReaderTarget
import com.cedagova.fastreader.reader.ui.ReaderRoute
import com.cedagova.fastreader.settings.SharedPreferencesThemeMirror
import com.cedagova.fastreader.settings.ui.SettingsRoute
import com.cedagova.fastreader.ui.theme.FastReaderTheme
import com.cedagova.fastreader.ui.theme.isDark

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // AD-10, and the only three lines in the app whose *position* is the
        // behaviour. `super.onCreate` is where the window is created and its
        // background is read, so a theme applied after it arrives one frame too
        // late — which is the white flash REQ-102 forbids. The catalog is JSON on
        // a background dispatcher and cannot answer this early; the mirror can.
        val themeMirror = SharedPreferencesThemeMirror(this)
        val launchTheme = themeMirror.read()
        setTheme(launchThemeFor(launchTheme))
        // Idempotent, and normally free. It earns its place when the platform's
        // app-level night override has drifted from the mirror — cleared app data,
        // a restored device — where it costs this launch nothing and makes the
        // *next* cold start's system splash right again.
        themeMirror.write(launchTheme)

        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as FastReaderApplication
        val library = app.library
        // Only on a genuine launch (REQ-103). `savedInstanceState` is the exact
        // discriminator the behaviour needs: null means someone just handed this
        // app a book, while non-null means the activity is being rebuilt. A
        // rotation therefore does not re-accept the intent — the open book is
        // already held by the process-scoped controller — and a rebuild after
        // process death does not resurrect a session-only book, which is what
        // sends that reader back to the library with no row and their position
        // kept.
        if (savedInstanceState == null) library.acceptIfExternal(intent)
        setContent {
            // REQ-022's single application point: the reader's theme and text size
            // wrap every destination, so both apply to the library and the reader
            // without either screen knowing the settings exist.
            val settings by library.repository.settings.collectAsState()
            FastReaderTheme(darkTheme = settings.theme.isDark(), fontSize = settings.fontSize) {
                FastReaderApp(library, app.crashReports)
            }
        }
    }

    /**
     * A second book arrived while the app was running (REQ-103).
     *
     * `singleTask` in the manifest is what makes this the normal case rather than
     * a second process: the running task comes forward and the book is swapped in
     * place, which is also why [setIntent] matters — a later rebuild of this
     * activity must see the book that is actually open, not the one it was
     * launched with.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val library = (application as FastReaderApplication).library
        library.acceptIfExternal(intent)
    }
}

/**
 * Unpacks an incoming intent and hands any book in it to the controller.
 *
 * The whole Android half of the hand-over: which field carries the document and
 * whether it is one this app may read are [incomingBook]'s to decide, on plain
 * strings, so both are provable without an emulator.
 */
private fun LibraryGraph.acceptIfExternal(intent: Intent?) {
    if (intent == null) return
    @Suppress("DEPRECATION")
    val stream = intent.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)
    val book = incomingBook(
        action = intent.action,
        dataUri = intent.data?.toString(),
        streamUri = stream?.toString(),
    ) ?: return
    external.accept(book)
}

/**
 * The three destinations, and which one a launch lands on (REQ-009).
 *
 * Three destinations is not a navigation graph. Settings sit *over* whichever of
 * the other two opened them — the state that says which book is open is not
 * cleared — so closing them puts the reader back where they were. What is worth
 * spelling out is the *first* one: the app reads the stored catalog before it draws anything, and
 * goes straight into the last-read book when that book can still be read. That
 * is what makes "open the app, press play" two interactions rather than three.
 *
 * The decision is taken once per process. Everything it produces is saved
 * instance state, so rotating the phone keeps the reader on screen and coming
 * back to the library does not bounce straight into the book again.
 *
 * The reader destination now covers two kinds of book. A bundled sample
 * (REQ-109) is held in its own piece of state and takes precedence while it is
 * open, so leaving it puts the reader back in whatever they were reading before.
 * It is never a launch destination: launch routing reads the catalog, and the
 * sample is not in it.
 */
@Composable
private fun FastReaderApp(library: LibraryGraph, crashReports: CrashReportStore) {
    var routed by rememberSaveable { mutableStateOf(false) }
    var openBookId by rememberSaveable { mutableStateOf<String?>(null) }
    // A book handed over by another app (REQ-103). Not saved state: it belongs to
    // the process, and its whole lifetime rule is written down on the controller.
    val external by library.external.open.collectAsState()
    // The bundled sample being read, by enum name (REQ-109). Kept apart from
    // [openBookId] rather than folded into it because a sample is not a catalog
    // book: nothing may look it up in the library, and launch routing must never
    // land here.
    var openSample by rememberSaveable { mutableStateOf<String?>(null) }
    // Whether the reader on screen was chosen by the launch routing rather than
    // by the reader tapping a row. It decides who owns a book that will not open.
    var routedIntoReader by rememberSaveable { mutableStateOf(false) }
    var blockedBookId by rememberSaveable { mutableStateOf<String?>(null) }
    var blockedReason by rememberSaveable { mutableStateOf<String?>(null) }
    // Settings sit over whichever destination opened them, so closing them returns
    // the reader to their book rather than to the library.
    var settingsOpen by rememberSaveable { mutableStateOf(false) }

    // An "Open with" outranks everything the app would otherwise be showing: the
    // reader asked for this book from another app, so the book they were in, the
    // settings screen they had open, and the launch routing all give way to it.
    // Clearing them is also what makes closing the external book land in the
    // library rather than back in whatever it interrupted.
    LaunchedEffect(external?.uri) {
        if (external == null) return@LaunchedEffect
        openBookId = null
        // The sample too: it is above the external branch in the `when` below, so
        // leaving it set would hide the book the reader just asked for.
        openSample = null
        routedIntoReader = false
        blockedBookId = null
        blockedReason = null
        settingsOpen = false
        routed = true
    }

    LaunchedEffect(library) {
        if (routed) return@LaunchedEffect
        // Reading the stored catalog and re-checking one book's reachability is the
        // whole cost of this decision: no folder scan, no parse. The reader's book
        // starts parsing as soon as the route resolves.
        library.repository.load()
        // Resuming into the last-read book would put a second parse behind the
        // book the reader actually asked for, and then be discarded.
        if (library.external.handoverPending) {
            routed = true
            return@LaunchedEffect
        }
        library.repository.refreshLastReadBook()
        when (val destination = launchDestination(library.repository.catalog.value)) {
            is LaunchDestination.Reader -> {
                openBookId = destination.bookId
                routedIntoReader = true
            }
            is LaunchDestination.Library -> {
                blockedBookId = destination.blocked?.bookId
                blockedReason = destination.blocked?.reason?.name
            }
        }
        routed = true
    }

    when {
        // Blank rather than a spinner: the decision costs a small file read, and a
        // spinner that flashes for one frame is worse than nothing. The same
        // blank covers the moment between an intent being taken and the book it
        // names being known, so an "Open with" never shows the library first.
        !routed || (library.external.handoverPending && external == null) ->
            Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {}

        settingsOpen -> SettingsRoute(
            graph = library,
            onBack = { settingsOpen = false },
            onOpenSample = { settingsOpen = false; openSample = it.name },
        )

        openSample != null -> ReaderRoute(
            graph = library,
            target = ReaderTarget.Sample(BundledSample.valueOf(requireNotNull(openSample))),
            onBack = { openSample = null },
            onOpenSettings = { settingsOpen = true },
        )

        external != null -> ReaderRoute(
            graph = library,
            target = ReaderTarget.External(requireNotNull(external)),
            // There is no row to go back to and nothing of this book is kept but
            // its position, so leaving it is leaving it entirely (REQ-103).
            onBack = { library.external.close() },
            onOpenSettings = { settingsOpen = true },
        )

        openBookId != null -> ReaderRoute(
            graph = library,
            target = ReaderTarget.Library(requireNotNull(openBookId)),
            onBack = { openBookId = null; routedIntoReader = false },
            // A book the reader chose from the library keeps the reader's own
            // explanation on screen: they picked it, and its row already said
            // what it is. A book the *launch* chose is different — nobody asked
            // for it, and a dead screen with a back arrow is the first thing the
            // app would show. That case goes back to the library, which can say
            // which book failed and offer removal or a re-grant (REQ-009).
            onCannotOpen = { failed ->
                if (routedIntoReader) {
                    routedIntoReader = false
                    openBookId = null
                    blockedBookId = failed
                    blockedReason = ResumeBlockedReason.UNREADABLE.name
                }
            },
            onOpenSettings = { settingsOpen = true },
        )

        else -> LibraryRoute(
            graph = library,
            onOpenBook = { openBookId = it },
            onOpenSample = { openSample = it.name },
            resumeBlocked = resumeBlocked(blockedBookId, blockedReason),
            onDismissResumeNotice = {
                blockedBookId = null
                blockedReason = null
            },
            onOpenSettings = { settingsOpen = true },
        )
    }

    // Over whichever destination the launch settled on, and only once the launch
    // has settled: a dialog above the blank routing frame would be the first
    // thing a reader saw, with nothing behind it to say which app it belongs to.
    // It is its own window, so it needs no place in the layout above.
    if (routed) CrashReportOfferHost(crashReports)
}

/**
 * Rebuilds the blocked-resume notice from saved instance state.
 *
 * Saved state holds primitives so it survives process death as cheaply as it
 * survives rotation; an unrecognised reason simply drops the notice rather than
 * crashing an app that has just been restored.
 */
private fun resumeBlocked(bookId: String?, reason: String?): ResumeBlocked? {
    if (bookId == null || reason == null) return null
    val parsed = ResumeBlockedReason.entries.firstOrNull { it.name == reason } ?: return null
    return ResumeBlocked(bookId, parsed)
}
