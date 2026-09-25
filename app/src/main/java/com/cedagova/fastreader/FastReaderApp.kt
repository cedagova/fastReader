package com.cedagova.fastreader

import androidx.activity.compose.BackHandler
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cedagova.fastreader.account.ui.ReaderAccountRoute
import com.cedagova.fastreader.crash.ui.CrashReportOfferHost
import com.cedagova.fastreader.library.LaunchDestination
import com.cedagova.fastreader.library.launchDestination
import com.cedagova.fastreader.library.ui.LibraryRoute
import com.cedagova.fastreader.library.ui.accountBookIdForDevice
import com.cedagova.fastreader.reader.ui.ReaderRoute
import com.cedagova.fastreader.reader.ui.ReaderTarget
import com.cedagova.fastreader.settings.ui.SettingsRoute

/**
 * The app's destinations, drawn from one saved [BackStack] (REQ-009).
 *
 * What is worth spelling out is the *first* destination: the app reads the
 * stored catalog before it draws anything, and goes straight into the last-read
 * book when that book can still be read. That is what makes "open the app, press
 * play" two interactions rather than three.
 *
 * The decision is taken once per process. Everything it produces is saved
 * instance state, so rotating the phone keeps the reader on screen and coming
 * back to the library does not bounce straight into the book again.
 *
 * Each Route below receives from [graph] only the objects it uses.
 */
@Composable
internal fun FastReaderApp(graph: AppGraph) {
    var stack by rememberSaveable(stateSaver = BackStack.SAVER) { mutableStateOf(BackStack.UNROUTED) }
    val repository = graph.repository
    val account = graph.readerAccount
    // A book handed over by another app (REQ-103). Not saved state: it belongs to
    // the process, and its whole lifetime rule is written down on the controller.
    val external by graph.external.open.collectAsStateWithLifecycle()

    // An "Open with" outranks everything the app would otherwise be showing: the
    // reader asked for this book from another app, so the book they were in, the
    // settings screen they had open, and the launch routing all give way to it.
    LaunchedEffect(external?.uri) {
        if (external == null) return@LaunchedEffect
        stack = BackStack.LIBRARY
    }

    LaunchedEffect(graph) {
        if (stack.routed) return@LaunchedEffect
        // Reading the stored catalog and re-checking one book's reachability is the
        // whole cost of this decision: no folder scan, no parse. The reader's book
        // starts parsing as soon as the route resolves.
        repository.load()
        // Resuming into the last-read book would put a second parse behind the
        // book the reader actually asked for, and then be discarded.
        if (graph.external.handoverPending) {
            if (!stack.routed) stack = BackStack.LIBRARY
            return@LaunchedEffect
        }
        repository.refreshLastReadBook()
        stack = BackStack.launchedInto(launchDestination(repository.catalog.value))
    }

    // Opening an account book records last-opened and `reading` for the account
    // (the definition's "Library status and last opened"). Hooked to the book
    // the app actually has open rather than to the row that was tapped, so a
    // launch that goes straight back into the last-read book counts as opening
    // it too. The key is the *account's* book id, which is null for a device
    // book the account does not have — such a book sends nothing at all — and
    // which appears the moment a bootstrap finds the book, so signing in while
    // reading records it once and then settles.
    val accountLibrary by account.shelf.state.collectAsStateWithLifecycle()
    val openAccountBookId = stack.openBookId?.let { accountBookIdForDevice(it, accountLibrary) }
    LaunchedEffect(openAccountBookId) { openAccountBookId?.let(account.shelf::recordOpened) }

    val top = stack.top
    val handedOver = external
    when {
        // Blank rather than a spinner: the decision costs a small file read, and a
        // spinner that flashes for one frame is worse than nothing. The same
        // blank covers the moment between an intent being taken and the book it
        // names being known, so an "Open with" never shows the library first.
        top == null || (graph.external.handoverPending && handedOver == null) ->
            Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {}

        handedOver != null && top.showsHandedOverBook -> ReaderRoute(
            repository = repository,
            settingsStore = graph.settingsStore,
            positions = graph.readingPositions,
            bookBytes = graph.bookBytes,
            handover = graph.external,
            target = ReaderTarget.External(handedOver),
            // There is no row to go back to and nothing of this book is kept but
            // its position, so leaving it is leaving it entirely (REQ-103).
            onBack = { graph.external.close() },
            onOpenSettings = { stack = stack.push(Destination.Settings) },
            // Handed over, so it has no account row and `accountBookIdForDevice`
            // answers null for it — the gate is the account's own, rather than a
            // wire left unconnected here.
            account = account.shelf,
        )

        else -> when (top) {
            Destination.ReaderAccount -> ReaderAccountRoute(
                controller = account.account,
                onBack = { stack = stack.pop() },
            )

            Destination.Settings -> SettingsRoute(
                settingsStore = graph.settingsStore,
                readerAccount = account.account,
                onBack = { stack = stack.pop() },
                onOpenReaderAccount = { stack = stack.push(Destination.ReaderAccount) },
            )

            is Destination.Reader -> ReaderRoute(
                repository = repository,
                settingsStore = graph.settingsStore,
                positions = graph.readingPositions,
                bookBytes = graph.bookBytes,
                handover = graph.external,
                target = ReaderTarget.Library(top.bookId),
                onBack = { stack = stack.pop() },
                // Where a portable position is published from for an account book
                // (#120). A device-only book resolves to no account id and sends
                // nothing.
                account = account.shelf,
                // A book the reader chose from the library keeps the reader's own
                // explanation on screen: they picked it, and its row already said
                // what it is. A book the *launch* chose is different — nobody asked
                // for it, and a dead screen with a back arrow is the first thing the
                // app would show. That case goes back to the library, which can say
                // which book failed and offer removal or a re-grant (REQ-009).
                onCannotOpen = { failed -> stack = stack.cannotOpen(failed) },
                onOpenSettings = { stack = stack.push(Destination.Settings) },
            )

            is Destination.Library -> {
                // Back from the library goes to the book, not out of the app: the
                // library is a place to pick the next book, and the one being read
                // is where a reader who has finished picking wants to be. Only when
                // there is a readable last-read book — the same rule launch uses —
                // otherwise back leaves as it always did.
                val catalog by repository.catalog.collectAsStateWithLifecycle()
                val resumable = (launchDestination(catalog) as? LaunchDestination.Reader)?.bookId
                BackHandler(enabled = resumable != null) {
                    // Nobody tapped a row, so a book that will not open is the
                    // library's to explain, exactly as after a launch.
                    resumable?.let { stack = stack.push(Destination.Reader(it, chosenByLaunch = true)) }
                }
                LibraryRoute(
                    repository = repository,
                    settingsStore = graph.settingsStore,
                    covers = graph.covers,
                    account = account.shelf,
                    imports = account.imports,
                    downloads = account.downloads,
                    onOpenBook = { stack = stack.push(Destination.Reader(it)) },
                    resumeBlocked = top.resumeBlocked,
                    onDismissResumeNotice = { stack = stack.dismissResumeNotice() },
                    onOpenSettings = { stack = stack.push(Destination.Settings) },
                )
            }
        }
    }

    // Over whichever destination the launch settled on, and only once the launch
    // has settled: a dialog above the blank routing frame would be the first
    // thing a reader saw, with nothing behind it to say which app it belongs to.
    // It is its own window, so it needs no place in the layout above.
    if (stack.routed) CrashReportOfferHost(graph.crashReports)
}
