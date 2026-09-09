package com.cedagova.fastreader.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cedagova.fastreader.content.BookContent
import com.cedagova.fastreader.content.BookContentResult
import com.cedagova.fastreader.content.BookIdentity
import com.cedagova.fastreader.content.EpubContentPipeline
import com.cedagova.fastreader.reader.ui.ReaderBookView
import com.cedagova.fastreader.reader.ui.ReaderUiState
import com.cedagova.fastreader.timing.PauseStrength
import com.cedagova.fastreader.timing.RsvpTiming
import com.cedagova.fastreader.timing.TimingSettings
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** How the reader gets at a book, so the ViewModel does not need the catalog's whole API. */
interface ReaderBooks {

    /**
     * The open request for a catalog book: its title, its bytes read in place
     * (AD-1), and its catalog id as the identity the reader must not recompute
     * (AD-8).
     */
    fun libraryBook(bookId: String): BookOpenRequest
}

/**
 * Holds the open book for as long as the reader is on screen.
 *
 * A `ViewModel` rather than composition state for one concrete reason: rotating
 * the phone destroys and recreates the activity, and re-parsing a novel every time
 * it turns would be both slow and a lost reading position. Surviving the
 * configuration change here is what makes "rotation preserves position and
 * playback state" true.
 *
 * **Exactly one book at a time.** A parsed novel is the largest thing this app
 * holds in memory, and a `ViewModel` lives until its activity is destroyed, not
 * until the composable that created it goes away. Keying one per book id would
 * therefore keep every book opened in a session resident — which the 2 GB device
 * in the test matrix will not forgive. [open] instead replaces the current book,
 * dropping the previous token stream and cancelling a parse still in flight.
 *
 * Everything interesting is delegated: [ReaderSession] owns playback semantics and
 * [ReaderBookView] owns the screen state. This class owns the parse, the current
 * session value, and the [ReaderUiState] flow the screen collects.
 *
 * ## Durability (LEAF204)
 *
 * Every state change funnels through [update], which is therefore the one place
 * position and speed reach [ReaderPositions]. Two kinds of change, two costs:
 *
 * - [advance] — the next word, up to sixteen times a second. It only *records*:
 *   an in-memory note that the writer coalesces into at most two writes a second,
 *   so no durable write ever lands between two frames of a running stream.
 * - everything else — pausing, jumping, changing speed, a chapter pause, the end
 *   of the book, losing the foreground, closing the book. Each is a discrete act
 *   at human frequency, so each is flushed immediately and is durable before the
 *   reader can do anything else.
 *
 * The only exposure left is a kill of a *foreground* process mid-stream, which
 * Android gives no callback for; the writer's interval bounds it.
 */
class ReaderViewModel(
    private val books: ReaderBooks,
    private val positions: ReaderPositions,
    private val pipeline: EpubContentPipeline = EpubContentPipeline(),
    private val indexDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {

    private val _state = MutableStateFlow<ReaderUiState>(ReaderUiState.Opening("", null))
    val state: StateFlow<ReaderUiState> = _state.asStateFlow()

    private var openRequest: BookOpenRequest? = null
    private var parse: Job? = null
    private var view: ReaderBookView? = null
    private var session: ReaderSession? = null
    private var persistenceFailure: String? = null

    /**
     * The reader's chosen pause strength (LEAF302). Held here rather than only in
     * the session because the *time-remaining index* depends on it too, and that
     * index is rebuilt off the main thread — so the wanted value and the value the
     * current [ReaderBookView] was built at have to be comparable.
     */
    private var pauseStrength: PauseStrength = PauseStrength.NORMAL
    private var rebuild: Job? = null

    init {
        // A failing store must be visible on the reading surface, not only on the
        // library's banner: this is where the reader is when their place is lost.
        viewModelScope.launch {
            positions.failure.collect { message ->
                persistenceFailure = message
                publish()
            }
        }
    }

    /** How long the token on screen is shown, or null when nothing is streaming. */
    val currentDurationMillis: Long? get() = session?.takeIf { it.isPlaying }?.currentDurationMillis

    /**
     * Which book the reader currently holds — its [BookOpenRequest.openKey] — or
     * null before the first open.
     *
     * Exists so a caller can tell "the reader is streaming" from "the reader is
     * streaming *this* book". [ReaderUiState] deliberately carries no key, and a
     * state read one recomposition ago can still describe the book before this
     * one.
     */
    val openKey: String? get() = openRequest?.openKey

    /** Opens the catalog book [bookId], unless it is already open. */
    fun openLibraryBook(bookId: String) = open(books.libraryBook(bookId))

    /**
     * Opens the book [request] describes, unless it is already open.
     *
     * The one entry to the reader (AD-9). Everything it needs is in the request —
     * bytes, identity, origin — so nothing here hashes a file or consults the
     * catalog, and a book handed over from outside the app (#44) or shipped in
     * the APK (#48) arrives through exactly this call.
     *
     * Idempotent on purpose: the reader calls it on every composition, and after a
     * rotation that call must find the book already parsed and the position intact.
     */
    fun open(request: BookOpenRequest) {
        if (request.openKey == openRequest?.openKey) return
        // The book being left has to become durable before its session is dropped.
        if (openRequest != null) persist(flush = true)
        openRequest = request
        parse?.cancel()
        rebuild?.cancel()
        view = null
        session = null
        val title = request.title
        _state.value = ReaderUiState.Opening(title, null)
        parse = viewModelScope.launch { parse(request) }
    }

    private suspend fun parse(request: BookOpenRequest) {
        val title = request.title
        val result = pipeline.parse(request.bytes, request.identity) { progress ->
            _state.value = ReaderUiState.Opening(title, progress.fraction.takeIf { progress.totalItems > 0 })
        }
        when (result) {
            is BookContentResult.Failed ->
                _state.value = ReaderUiState.Unavailable(title, result.reason)

            is BookContentResult.Parsed -> {
                // The identity can land *while this parse runs* (AD-8), and then
                // [identityResolved] has no session to stamp: it is created here,
                // a moment later. The request this parse started from is
                // therefore not the last word on who the book is — `openRequest`
                // is. Reading it here is what closes that window; without it the
                // session keeps the empty digest the pipeline stamped from a null
                // identity, while `positionKey` is already set, and the first
                // write stores a first-word position under the right key with the
                // wrong digest — overwriting a real stored position with nothing.
                val identity = openRequest?.takeIf { it.openKey == request.openKey }?.identity
                val content = result.content.let { parsed ->
                    if (identity == null || parsed.bookDigest == identity.value) parsed
                    else parsed.copy(bookDigest = identity.value)
                }
                // Building the time-remaining index is one sweep of the book; it
                // belongs on the parsing thread, next to the parse, not on the
                // first frame of the reader.
                val book = withContext(indexDispatcher) {
                    ReaderBookView(title, content, pauseStrength)
                }
                view = book
                // Resuming lands paused, on the stored word, at the stored speed
                // (REQ-010, REQ-016). A book never opens playing.
                val stored = (identity?.value ?: request.positionKey)?.let { positions.restore(it) }
                session = ReaderSession(
                    content = content,
                    index = stored?.resolveIndex(content) ?: 0,
                    settings = TimingSettings(
                        wpm = stored?.wpm ?: RsvpTiming.DEFAULT_WPM,
                        pauseStrength = pauseStrength,
                    ),
                )
                publish()
                // The setting can have changed while this book was parsing, and
                // the index above was measured at whatever it was when the parse
                // began. Reconciling here means a book always opens with an
                // estimate that matches the strength it is about to be read at.
                rebuildIndexIfStale()
                // Opening a book is what makes it the last-read one, so launch can
                // come back to it (REQ-009) even if nothing is read this session.
                persist(flush = true)
            }
        }
    }

    /**
     * The identity of the open book has been worked out after the fact (AD-8).
     *
     * The one deferred half of the external open path: a book handed over from
     * another app starts streaming with no identity at all, and
     * [com.cedagova.fastreader.external.ExternalOpenController] computes the
     * whole-file digest once the stream is running. From this call on, the book
     * has a [BookOpenRequest.positionKey] and its position is stored like any
     * other.
     *
     * It also *restores* a stored position, but only into an untouched session —
     * still on the first token and not playing. That is the case where opening
     * before the digest was known cost something: a book read before, under
     * whatever origin, would otherwise silently restart from its first word. Once
     * the reader has moved or pressed play, where they are is what they asked
     * for, and pulling them back to a stored word would undo the very
     * responsiveness this deferral exists to buy.
     *
     * The parsed book is *re-stamped* with the identity, and that is the part
     * that carries the promise. [EpubContentPipeline] writes the identity it was
     * given onto [BookContent.bookDigest], which for this book was nothing; every
     * position taken from it would therefore be stored against an empty digest,
     * and the row that appears when the reader adds the book would refuse to
     * match it — the resume-after-add half of REQ-103 failing quietly, months
     * later, with no error anywhere. Stamping it here is what makes a position
     * written before the add and a position written after it the same position.
     *
     * Ignores a key that is no longer open and an identity that is already known,
     * so a late resolution for a book the reader has since left cannot touch the
     * book they are in now.
     */
    fun identityResolved(openKey: String, identity: BookIdentity) {
        val request = openRequest ?: return
        if (request.openKey != openKey || request.identity != null) return
        openRequest = request.withIdentity(identity)
        val current = session ?: return
        val identified = current.content.copy(bookDigest = identity.value)
        val stored = positions.restore(identity.value)
        session = if (stored != null && current.index == 0 && !current.isPlaying) {
            current.copy(content = identified)
                .jumpTo(stored.resolveIndex(identified))
                .withWpm(stored.wpm)
        } else {
            current.copy(content = identified)
        }
        publish()
        persist(flush = true)
    }

    fun togglePlay() = update { if (it.isPlaying) it.pause() else it.play() }

    /**
     * REQ-071: the app is no longer in the foreground, so the stream stops on the
     * word that was on screen. Deliberately not [togglePlay]: coming back must not
     * start playing again by itself.
     */
    fun pauseForBackground() = update { it.pause() }

    /**
     * One word has had its full display time; the scheduler is the only caller.
     *
     * The only transition that does not force a write. A chapter pause or the end
     * of the book still does, because both stop the stream on a word the reader
     * will come back to.
     */
    fun advance() = update(flush = false) { it.advance() }

    fun setWpm(wpm: Int) = update { it.withWpm(wpm) }

    /**
     * Applies the reader's pause-strength setting (REQ-011), mid-book included.
     *
     * Two things have to move, and they move at different costs:
     *
     * 1. **Playback.** [ReaderSession.withPauseStrength] changes the next word's
     *    duration immediately — it is one field on an immutable value.
     * 2. **Time remaining.** [ReaderBookView]'s index is a *sum over the whole
     *    book* of durations measured at one strength, so it is now wrong and
     *    cannot be patched. It is rebuilt on [indexDispatcher], one sweep of the
     *    book, and the screen keeps the old estimate for those few milliseconds
     *    rather than blanking. Leaving it unrebuilt would show a reader who turned
     *    pauses off a time remaining that still includes every pause.
     *
     * Idempotent: called from a `LaunchedEffect` that re-runs on recomposition,
     * so an unchanged strength must not start a sweep of the book.
     */
    fun setPauseStrength(strength: PauseStrength) {
        if (strength == pauseStrength) return
        pauseStrength = strength
        // Not a position change, so it does not force a durable write of its own;
        // the next ordinary transition carries it.
        update(flush = false) { current -> current.withPauseStrength(strength) }
        rebuildIndexIfStale()
    }

    /**
     * Rebuilds the time-remaining index when it no longer matches [pauseStrength].
     *
     * The rebuild is cancellable and re-entrant: flicking through all four
     * strengths starts and drops sweeps rather than queueing them, and the result
     * is applied only if it still describes the book that is open and the strength
     * that is still wanted.
     */
    private fun rebuildIndexIfStale() {
        val current = view ?: return
        val content = session?.content ?: return
        val wanted = pauseStrength
        if (current.pauseStrength == wanted) return
        val openKey = openRequest?.openKey
        rebuild?.cancel()
        rebuild = viewModelScope.launch {
            val rebuilt = withContext(indexDispatcher) {
                ReaderBookView(current.bookTitle, content, wanted)
            }
            if (openRequest?.openKey != openKey || pauseStrength != wanted) return@launch
            view = rebuilt
            publish()
        }
    }

    fun backSentence() = update { it.backSentence() }

    fun forwardSentence() = update { it.forwardSentence() }

    fun backParagraph() = update { it.backParagraph() }

    fun forwardParagraph() = update { it.forwardParagraph() }

    fun scrubTo(fraction: Float) = update { it.scrubTo(fraction) }

    fun jumpToChapter(chapterIndex: Int) = update { it.jumpToChapter(chapterIndex) }

    /** Everything the reader does, so persistence has exactly one place to sit. */
    private fun update(flush: Boolean = true, transform: (ReaderSession) -> ReaderSession) {
        val current = session ?: return
        val next = transform(current)
        session = next
        publish()
        // A stream that stopped itself — a chapter boundary, the end of the book —
        // is a place the reader returns to, so it is made durable like a tap.
        persist(flush = flush || !next.isPlaying)
    }

    /**
     * Records the current position under the open book's identity.
     *
     * Silent no-op while that identity is unknown — an external book whose digest
     * is still being computed (#44). There is nothing to key a position by yet,
     * and inventing a key would strand it under something no later open matches.
     */
    private fun persist(flush: Boolean) {
        val positionKey = openRequest?.positionKey ?: return
        val current = session ?: return
        positions.record(positionKey, current.toPosition())
        if (flush) positions.flush()
    }

    /** Leaving the reader for good; the last word read must not depend on timing. */
    override fun onCleared() {
        persist(flush = true)
        super.onCleared()
    }

    private fun publish() {
        val book = view ?: return
        val current = session ?: return
        _state.value = book.present(current, persistenceFailure)
    }
}
