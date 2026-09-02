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
import com.cedagova.fastreader.ui.theme.FastReaderTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val library = (application as FastReaderApplication).library
        setContent {
            FastReaderTheme {
                FastReaderApp(library)
            }
        }
    }
}

/**
 * The two destinations, and which one a launch lands on (REQ-009).
 *
 * Two destinations is not a navigation graph. What is worth spelling out is the
 * *first* one: the app reads the stored catalog before it draws anything, and
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
    var blockedBookId by rememberSaveable { mutableStateOf<String?>(null) }
    var blockedReason by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(library) {
        if (routed) return@LaunchedEffect
        // Reading the stored catalog and re-checking one book's reachability is the
        // whole cost of this decision: no folder scan, no parse. The reader's book
        // starts parsing as soon as the route resolves.
        library.repository.load()
        library.repository.refreshLastReadBook()
        when (val destination = launchDestination(library.repository.catalog.value)) {
            is LaunchDestination.Reader -> openBookId = destination.bookId
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

        openBookId != null -> ReaderRoute(
            graph = library,
            bookId = requireNotNull(openBookId),
            onBack = { openBookId = null },
        )

        else -> LibraryRoute(
            graph = library,
            onOpenBook = { openBookId = it },
            resumeBlocked = resumeBlocked(blockedBookId, blockedReason),
            onDismissResumeNotice = {
                blockedBookId = null
                blockedReason = null
            },
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
