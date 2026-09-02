package com.cedagova.fastreader

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
import com.cedagova.fastreader.library.LaunchDestination
import com.cedagova.fastreader.library.LibraryGraph
import com.cedagova.fastreader.library.ResumeBlocked
import com.cedagova.fastreader.library.ResumeBlockedReason
import com.cedagova.fastreader.library.launchDestination
import com.cedagova.fastreader.library.ui.LibraryRoute
import com.cedagova.fastreader.reader.ui.ReaderRoute
import com.cedagova.fastreader.settings.ui.SettingsRoute
import com.cedagova.fastreader.ui.theme.FastReaderTheme
import com.cedagova.fastreader.ui.theme.isDark

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val library = (application as FastReaderApplication).library
        setContent {
            // REQ-022's single application point: the reader's theme and text size
            // wrap every destination, so both apply to the library and the reader
            // without either screen knowing the settings exist.
            val settings by library.repository.settings.collectAsState()
            FastReaderTheme(darkTheme = settings.theme.isDark(), fontSize = settings.fontSize) {
                FastReaderApp(library)
            }
        }
    }
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
 */
@Composable
private fun FastReaderApp(library: LibraryGraph) {
    var routed by rememberSaveable { mutableStateOf(false) }
    var openBookId by rememberSaveable { mutableStateOf<String?>(null) }
    // Whether the reader on screen was chosen by the launch routing rather than
    // by the reader tapping a row. It decides who owns a book that will not open.
    var routedIntoReader by rememberSaveable { mutableStateOf(false) }
    var blockedBookId by rememberSaveable { mutableStateOf<String?>(null) }
    var blockedReason by rememberSaveable { mutableStateOf<String?>(null) }
    // Settings sit over whichever destination opened them, so closing them returns
    // the reader to their book rather than to the library.
    var settingsOpen by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(library) {
        if (routed) return@LaunchedEffect
        // Reading the stored catalog and re-checking one book's reachability is the
        // whole cost of this decision: no folder scan, no parse. The reader's book
        // starts parsing as soon as the route resolves.
        library.repository.load()
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
        // spinner that flashes for one frame is worse than nothing.
        !routed -> Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {}

        settingsOpen -> SettingsRoute(graph = library, onBack = { settingsOpen = false })

        openBookId != null -> ReaderRoute(
            graph = library,
            bookId = requireNotNull(openBookId),
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
            resumeBlocked = resumeBlocked(blockedBookId, blockedReason),
            onDismissResumeNotice = {
                blockedBookId = null
                blockedReason = null
            },
            onOpenSettings = { settingsOpen = true },
        )
    }
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
