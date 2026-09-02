package com.cedagova.fastreader.reader.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
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
import com.cedagova.fastreader.library.LibraryGraph
import com.cedagova.fastreader.library.LibraryRepository
import com.cedagova.fastreader.library.ReadingState
import com.cedagova.fastreader.reader.PlaybackScheduler
import com.cedagova.fastreader.reader.ReaderBooks
import com.cedagova.fastreader.reader.ReaderMode
import com.cedagova.fastreader.reader.ReaderPosition
import com.cedagova.fastreader.reader.ReaderPositions
import com.cedagova.fastreader.reader.ReaderViewModel
import kotlinx.coroutines.flow.StateFlow

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
    bookId: String,
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
    // position intact, and switching books drops the previous one.
    LaunchedEffect(reader, bookId) { reader.open(bookId) }

    // REQ-011 mid-book: this both changes the next word's duration and rebuilds
    // the time-remaining index, which is a function of pause strength.
    LaunchedEffect(reader, settings.pauseStrength) { reader.setPauseStrength(settings.pauseStrength) }

    val state by reader.state.collectAsState()
    val playing = (state as? ReaderUiState.Reading)?.mode == ReaderMode.PLAYING

    val unavailable = state as? ReaderUiState.Unavailable
    LaunchedEffect(unavailable, bookId) { if (unavailable != null) onCannotOpen(bookId) }

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
    )
}

/** The catalog, as the reader needs it: a title now and the book's bytes when asked. */
private class CatalogBooks(private val repository: LibraryRepository) : ReaderBooks {

    override fun title(bookId: String): String =
        repository.catalog.value.book(bookId)?.title.orEmpty()

    override fun bytes(bookId: String): EpubByteSource =
        EpubByteSource { repository.openBook(bookId) }
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
