package com.cedagova.fastreader.reader.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
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
import com.cedagova.fastreader.R
import com.cedagova.fastreader.reader.ReaderViewModel
import kotlinx.coroutines.delay
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

    // REQ-108. The notice is the *whole* of the gesture's feedback, and it is
    // screen state for the same reason `focused` is: what the reader last did with
    // the drag is a property of this view, not of the book or the session.
    //
    // The serial is not decoration. Two steps in a row can produce identical text —
    // holding the drag against the 1000 WPM ceiling repeats "1000 WPM" — and
    // without it the effect below would not restart, so the second step would
    // inherit the first one's already-running timer and the line would vanish early.
    var notice by remember { mutableStateOf<SpeedNotice?>(null) }
    var noticeSerial by remember { mutableIntStateOf(0) }
    LaunchedEffect(notice) {
        if (notice == null) return@LaunchedEffect
        delay(SPEED_NOTICE_MILLIS)
        notice = null
    }

    // Where v1 explains focused mode is focused mode itself, so that is where the
    // gesture is named: entering it puts the hint in the same slot the readout
    // uses, on the same timer. Leaving takes whatever is there with it, which is
    // the issue's "leaving focused mode dismisses the readout".
    val gestureHint = stringResource(R.string.reader_focused_speed_hint)
    LaunchedEffect(focused) {
        notice = if (focused) {
            noticeSerial += 1
            SpeedNotice(gestureHint, noticeSerial)
        } else {
            null
        }
    }

    // `getString` rather than `stringResource`: the text is chosen inside a
    // callback, which is not a composable scope.
    val context = LocalContext.current

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
        speedNotice = notice?.text,
        onSpeedStep = { steps ->
            val current = (state as? ReaderUiState.Reading)?.wpm
            if (current != null) {
                // Straight through `setWpm`, the same call the slider makes, so the
                // gesture inherits REQ-016's persistence and REQ-012's "never stops
                // playback" for free rather than reimplementing either.
                val next = steppedWpm(current, steps)
                reader.setWpm(next)
                noticeSerial += 1
                notice = SpeedNotice(context.getString(R.string.reader_speed, next), noticeSerial)
            }
        },
        onOpenSettings = onOpenSettings,
    )
}

/**
 * One self-dismissing line on the focused surface, with the serial that makes two
 * identical lines in a row two separate notices.
 */
private data class SpeedNotice(val text: String, val serial: Int)

/**
 * How long the readout stays. The issue's ceiling is two seconds; this sits under
 * it with room for the frame the reader lifts their thumb on, and is long enough
 * to read three digits without being long enough to sit in the way of the stream.
 */
private const val SPEED_NOTICE_MILLIS = 1_400L

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
