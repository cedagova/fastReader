package com.cedagova.fastreader.reader.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.cedagova.fastreader.content.TokenPosition
import com.cedagova.fastreader.epub.EpubByteSource
import com.cedagova.fastreader.external.ExternalOpen
import com.cedagova.fastreader.library.LibraryGraph
import com.cedagova.fastreader.library.LibraryRepository
import com.cedagova.fastreader.library.ReadingState
import com.cedagova.fastreader.library.saf.SafDocumentGateway
import com.cedagova.fastreader.library.ui.PickPersistableDocuments
import com.cedagova.fastreader.reader.PlaybackScheduler
import com.cedagova.fastreader.reader.BookOpenRequest
import com.cedagova.fastreader.reader.ReaderBooks
import com.cedagova.fastreader.reader.ReaderMode
import com.cedagova.fastreader.reader.ReaderPosition
import com.cedagova.fastreader.reader.ReaderPositions
import com.cedagova.fastreader.reader.ReaderViewModel
import kotlinx.coroutines.flow.StateFlow

/**
 * Which book the reader is showing, and therefore where its bytes and its
 * identity come from (AD-9).
 *
 * Two cases, not two screens: everything below the open request — parsing,
 * playback, cues, persistence — is identical, and the only difference the reader
 * can see is the session-only notice.
 */
sealed interface ReaderTarget {

    /** A catalog book, opened from the library or resumed at launch. */
    data class Library(val bookId: String) : ReaderTarget

    /** A book handed over by another app through "Open with" or the share sheet (REQ-103). */
    data class External(val open: ExternalOpen) : ReaderTarget
}

/**
 * The reader wired to a real book: catalog bytes in, playback out.
 *
 * Everything visual lives in [ReaderScreen], which stays stateless so the
 * Roborazzi renders can drive every state directly. This is the part that needs
 * the platform — the frame clock, the window, and the activity lifecycle.
 */
@Composable
fun ReaderRoute(
    graph: LibraryGraph,
    target: ReaderTarget,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * The book turned out not to be openable. Reported, not acted on: whether a
     * dead reader is the right screen depends on how the reader got here, and
     * only the caller knows that (LEAF204).
     */
    onCannotOpen: (String) -> Unit = {},
    /** Opens the settings screen (LEAF302), so cues can be changed while reading. */
    onOpenSettings: () -> Unit = {},
) {
    val repository = graph.repository
    // The stored settings drive the cue layer LEAF301 built and the timing engine
    // LEAF202 built. This is the whole of "live preview" outside the settings
    // screen: the reader is drawn from the same value the settings screen writes,
    // so a change made mid-book is on screen as soon as the store accepts it.
    val settings by repository.settings.collectAsState()
    val reader = viewModel<ReaderViewModel>(
        factory = viewModelFactory {
            initializer { ReaderViewModel(CatalogBooks(repository), CatalogPositions(repository)) }
        },
    )
    // Idempotent: after a rotation this finds the book already parsed and the
    // position intact, and switching books drops the previous one — which is also
    // how an "Open with" arriving mid-book swaps the reader over without a second
    // process (REQ-103).
    LaunchedEffect(reader, target) {
        when (target) {
            is ReaderTarget.Library -> reader.openLibraryBook(target.bookId)
            is ReaderTarget.External -> reader.open(
                BookOpenRequest.external(
                    uri = target.open.uri,
                    title = target.open.title,
                    identity = target.open.identity,
                    origin = target.open.origin,
                    bytes = graph.external.byteSource(target.open.uri),
                ),
            )
        }
    }

    // REQ-011 mid-book: this both changes the next word's duration and rebuilds
    // the time-remaining index, which is a function of pause strength.
    LaunchedEffect(reader, settings.pauseStrength) { reader.setPauseStrength(settings.pauseStrength) }

    val state by reader.state.collectAsState()
    val playing = (state as? ReaderUiState.Reading)?.mode == ReaderMode.PLAYING

    val unavailable = state as? ReaderUiState.Unavailable
    val failedKey = (target as? ReaderTarget.Library)?.bookId
    LaunchedEffect(unavailable, failedKey) {
        if (unavailable != null && failedKey != null) onCannotOpen(failedKey)
    }

    val external = (target as? ReaderTarget.External)?.open
    ExternalIdentity(graph, external, reader, streaming = state is ReaderUiState.Reading)

    // Live, because the answer changes under this screen: the keepable half of
    // REQ-103 adds the row itself, and "Add to library" adds it through the
    // picker. Either way the notice has to go the moment the book has a row.
    val catalog by repository.catalog.collectAsState()
    val inLibrary = external?.identity?.let { catalog.book(it.value) != null } == true
    val addToLibrary = rememberLauncherForActivityResult(PickPersistableDocuments()) { uris ->
        if (uris.isNotEmpty()) repository.requestAddPickedBooks(uris.map(Uri::toString))
    }

    KeepScreenOn(playing)
    PauseWhenBackgrounded(reader)
    PlaybackLoop(reader, playing)

    // REQ-030. Screen state, not session state: it belongs to this reader view, so
    // leaving the book and coming back starts unfocused, while a rotation — which
    // does not change what the reader asked for — keeps it.
    var focused by rememberSaveable { mutableStateOf(false) }

    // Back leaves focused mode before it leaves the book. Without this the only
    // way out of a chrome-less screen is the long press, and a reader who does not
    // know that gesture is stuck looking at a bare word.
    BackHandler { if (focused) focused = false else onBack() }

    ReaderScreen(
        state = state,
        onBack = onBack,
        onTogglePlay = reader::togglePlay,
        onWpmChange = reader::setWpm,
        onBackSentence = reader::backSentence,
        onForwardSentence = reader::forwardSentence,
        onBackParagraph = reader::backParagraph,
        onForwardParagraph = reader::forwardParagraph,
        onScrub = reader::scrubTo,
        onChapterSelected = reader::jumpToChapter,
        modifier = modifier,
        cues = settings.cues,
        focused = focused,
        onToggleFocused = { focused = !focused },
        onOpenSettings = onOpenSettings,
        externalNotice = external != null && external.resolved && !external.noticeDismissed && !inLibrary,
        onAddToLibrary = { addToLibrary.launch(SafDocumentGateway.PICKER_MIME_TYPES) },
        onDismissExternalNotice = { graph.external.dismissNotice() },
    )
}

/**
 * The deferred half of an external open (AD-8).
 *
 * Two effects, and the order between them is the requirement. The first starts
 * the work that reads the whole file — the grant, the ingest, the digest — and it
 * is keyed on the stream actually running, which is the mechanical guarantee that
 * REQ-110 holds on this path: nothing here can run before the reader has text on
 * screen, and a book that turned out to be damaged or DRM-protected never reaches
 * it at all, so nothing is added for it. The second hands the identity, once
 * known, to the open book, from which point its position is stored like any
 * other's.
 */
@Composable
private fun ExternalIdentity(
    graph: LibraryGraph,
    external: ExternalOpen?,
    reader: ReaderViewModel,
    streaming: Boolean,
) {
    LaunchedEffect(external?.uri, streaming) {
        val uri = external?.uri ?: return@LaunchedEffect
        if (streaming) graph.external.resolveIdentity(uri)
    }
    LaunchedEffect(reader, external?.uri, external?.identity) {
        val identity = external?.identity ?: return@LaunchedEffect
        reader.identityResolved(external.uri, identity)
    }
}

/**
 * The catalog, as the reader needs it: one open request per book.
 *
 * The catalog id it hands over *is* the book's whole-file SHA-256 (AD-2), which
 * is exactly why the reader never has to compute one (AD-8).
 */
private class CatalogBooks(private val repository: LibraryRepository) : ReaderBooks {

    override fun libraryBook(bookId: String) = BookOpenRequest.library(
        bookId = bookId,
        title = repository.catalog.value.book(bookId)?.title.orEmpty(),
        bytes = object : EpubByteSource {
            override fun open() = repository.openBook(bookId)

            override fun openChannel() = repository.openBookChannel(bookId)
        },
    )
}

/**
 * The catalog store, as durability needs it (LEAF204).
 *
 * The only place the reader's [ReaderPosition] and the catalog's [ReadingState]
 * meet, so neither package has to know the other's shape.
 */
private class CatalogPositions(private val repository: LibraryRepository) : ReaderPositions {

    override val failure: StateFlow<String?> get() = repository.persistenceFailure

    override fun restore(bookId: String): ReaderPosition? {
        val stored = repository.readingState(bookId) ?: return null
        return ReaderPosition(
            position = TokenPosition(stored.bookDigest, stored.tokenIndex, stored.pipelineVersion),
            progressFraction = stored.progressFraction,
            wpm = stored.wpm,
        )
    }

    override fun record(bookId: String, position: ReaderPosition) = repository.recordReadingState(
        bookId,
        ReadingState(
            bookDigest = position.position.bookDigest,
            tokenIndex = position.position.tokenIndex,
            pipelineVersion = position.position.pipelineVersion,
            progressFraction = position.progressFraction,
            wpm = position.wpm,
        ),
    )

    override fun flush() {
        repository.flushReadingState()
    }
}

/**
 * REQ-070: the screen stays awake while the stream is playing, and only while it
 * is playing — a paused reader left on a table should still let the device sleep.
 */
@Composable
private fun KeepScreenOn(playing: Boolean) {
    val view = LocalView.current
    DisposableEffect(view, playing) {
        view.keepScreenOn = playing
        onDispose { view.keepScreenOn = false }
    }
}

/**
 * REQ-071: losing the foreground stops the stream on the word that was on screen,
 * so nothing advances unseen and coming back shows the paused context view there.
 *
 * A configuration change — rotating the phone, resizing the window — also runs
 * the activity through `ON_PAUSE`, but the reader has not gone anywhere and the
 * plan requires playback state to survive it, so that case is excluded explicitly.
 */
@Composable
private fun PauseWhenBackgrounded(reader: ReaderViewModel) {
    val activity = LocalActivity.current
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) {
        if (activity?.isChangingConfigurations != true) reader.pauseForBackground()
    }
}

/**
 * Playback, driven from the frame clock.
 *
 * `withFrameNanos` resumes once per drawn frame, so a word change is applied on a
 * frame boundary rather than whenever a timer happened to fire — see
 * [PlaybackScheduler] for why that matters at the 1000 WPM ceiling, where a word
 * lasts under four frames. The effect keys on [playing], so pausing cancels the
 * loop outright and nothing runs while the stream is stopped.
 */
@Composable
private fun PlaybackLoop(reader: ReaderViewModel, playing: Boolean) {
    val scheduler = remember(reader) { PlaybackScheduler() }
    LaunchedEffect(reader, playing) {
        if (!playing) return@LaunchedEffect
        scheduler.start(withFrameNanos { it })
        while (true) {
            val frame = withFrameNanos { it }
            val duration = reader.currentDurationMillis ?: break
            if (scheduler.isDue(frame, duration)) {
                reader.advance()
                scheduler.advanced(frame, duration)
            }
        }
    }
}
