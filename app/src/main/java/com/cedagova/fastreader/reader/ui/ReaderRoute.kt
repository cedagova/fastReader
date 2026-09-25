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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.cedagova.fastreader.R
import com.cedagova.fastreader.account.library.AccountShelf
import com.cedagova.fastreader.account.library.PortableReadingPosition
import com.cedagova.fastreader.account.library.resumeOfferSettledFor
import com.cedagova.fastreader.external.ExternalOpen
import com.cedagova.fastreader.library.LibraryGraph
import com.cedagova.fastreader.library.LibraryRepository
import com.cedagova.fastreader.library.ReadingState
import com.cedagova.fastreader.library.saf.SafDocumentGateway
import com.cedagova.fastreader.library.ui.PickPersistableDocuments
import com.cedagova.fastreader.library.ui.accountBookIdForDevice
import com.cedagova.fastreader.reader.BookOpenRequest
import com.cedagova.fastreader.reader.PlaybackScheduler
import com.cedagova.fastreader.reader.ReaderBooks
import com.cedagova.fastreader.reader.ReaderMode
import com.cedagova.fastreader.reader.ReaderPosition
import com.cedagova.fastreader.reader.ReaderPositions
import com.cedagova.fastreader.reader.ReaderTarget
import com.cedagova.fastreader.reader.ReaderViewModel
import com.cedagova.fastreader.reader.ResumeOffer
import com.cedagova.reader.engine.content.BookContent
import com.cedagova.reader.engine.content.TokenPosition
import com.cedagova.reader.library.sync.AccountLibraryState
import com.cedagova.reader.library.sync.RemoteReadingPosition
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first

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
     * The catalog book turned out not to be openable. Reported, not acted on:
     * whether a dead reader is the right screen depends on how the reader got
     * here, and only the caller knows that (LEAF204).
     */
    onCannotOpen: (String) -> Unit = {},
    /** Opens the settings screen (LEAF302), so cues can be changed while reading. */
    onOpenSettings: () -> Unit = {},
    /**
     * The account library, when this build has one: where the portable position
     * of an account book is published from (#120). Null leaves the reader
     * exactly as it was — every position stays on the device.
     */
    account: AccountShelf? = null,
) {
    val repository = graph.repository
    // The stored settings drive the cue layer LEAF301 built and the timing engine
    // LEAF202 built. This is the whole of "live preview" outside the settings
    // screen: the reader is drawn from the same value the settings screen writes,
    // so a change made mid-book is on screen as soon as the store accepts it.
    val settings by repository.settings.collectAsState()
    val reader = viewModel<ReaderViewModel>(
        factory = viewModelFactory {
            initializer { ReaderViewModel(CatalogBooks(repository), CatalogPositions(repository, account)) }
        },
    )
    // Keyed on which book, not on the target value: an external target changes
    // whenever its identity lands or its notice is dismissed, and neither is a
    // different book to open.
    //
    // Idempotent: after a rotation this finds the book already parsed and the
    // position intact, and switching books drops the previous one — which is also
    // how an "Open with" arriving mid-book swaps the reader over without a second
    // process (REQ-103).
    LaunchedEffect(reader, target.openKey) {
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

    // REQ-201 mid-book: whether a boundary stops the stream. Unlike pause
    // strength this changes no duration, so nothing is rebuilt.
    LaunchedEffect(reader, settings.chapterPauseEnabled) {
        reader.setChapterPause(settings.chapterPauseEnabled)
    }

    // Presentation only: whether the paragraph stays under the running word.
    LaunchedEffect(reader, settings.paragraphAlwaysShown) {
        reader.setParagraphAlwaysShown(settings.paragraphAlwaysShown)
    }

    val state by reader.state.collectAsState()
    val playing = (state as? ReaderUiState.Reading)?.mode == ReaderMode.PLAYING

    val unavailable = state as? ReaderUiState.Unavailable
    val failedKey = (target as? ReaderTarget.Library)?.bookId
    LaunchedEffect(unavailable, failedKey) {
        if (unavailable != null && failedKey != null) onCannotOpen(failedKey)
    }

    val external = (target as? ReaderTarget.External)?.open
    ExternalIdentity(graph, external, reader)

    // Live, because the answer changes under this screen: the keepable half of
    // REQ-103 adds the row itself, and "Add to library" adds it through the
    // picker. Either way the notice has to go the moment the book has a row.
    val catalog by repository.catalog.collectAsState()
    val inLibrary = external?.identity?.let { catalog.book(it.value) != null } == true

    // REQ-202. The reader knows this book opens on front matter and that the
    // reader is still on its first word; only the catalog knows whether the offer
    // has already been made for this book, so the two are joined here.
    //
    // A book with no identity yet — an "Open with" whose digest is still being
    // computed — has no key to look up, so it is offered: nothing durable was
    // ever written about it, and answering is what writes the record.
    val offer by reader.frontMatterOffer.collectAsState()
    val offeredBefore = offer?.positionKey?.let { it in catalog.frontMatterOfferedBookIds } == true
    val frontMatterOffer = offer?.takeIf { !offeredBefore }
    // Answering settles the offer for this book for good, whichever way it was
    // answered: the requirement is that it is *offered* once (REQ-202).
    val settleFrontMatterOffer = {
        offer?.positionKey?.let { repository.requestMarkFrontMatterOffered(it) }
        Unit
    }
    // REQ-511. The reader knows the account holds a place ahead of this one and
    // which token it maps to; only the account document knows whether *this*
    // remote change has already been answered on this device, so the two are
    // joined here — the same split the front-matter offer makes two blocks up.
    //
    // A build with no account surface collects a constant signed-out state, so
    // this is one unconditional collection either way rather than a composable
    // call that appears and disappears with `account`.
    val accountLibrary by remember(account) {
        account?.state ?: MutableStateFlow(AccountLibraryState.SIGNED_OUT)
    }.collectAsState()
    val offered by reader.resumeOffer.collectAsState()
    val resumeSettledBefore = offered?.let { offer ->
        accountLibrary.books.firstOrNull { it.bookId == offer.accountBookId }
            ?.resumeOfferSettledFor == offer.changeKey
    } == true
    val resumeOffer = offered?.takeIf { !resumeSettledBefore }
    // REQ-502's position clause: a position changed on another device arrives on
    // an ordinary foreground sync, and the book can already be open when it does.
    // Keyed on the rows rather than the phase, so a sync that changed nothing
    // about this account's books does not re-ask.
    LaunchedEffect(reader, accountLibrary.books) { reader.considerResumeOffer() }
    // Answering settles this remote change for good, whichever way it was
    // answered: the requirement is that it is *offered* once per change.
    val settleResumeOffer = {
        offered?.let { account?.settleResumeOffer(it.accountBookId, it.changeKey) }
        Unit
    }
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
    // LocalResources, not LocalContext.getString: a Configuration change (locale,
    // font scale) invalidates this read, so the speed notice is always formatted
    // with the current configuration. Lint's LocalContextGetResourceValueCall.
    val resources = LocalResources.current

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
        progressShown = settings.progressShown,
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
                notice = SpeedNotice(resources.getString(R.string.reader_speed, next), noticeSerial)
            }
        },
        onOpenSettings = onOpenSettings,
        externalNotice = external != null && external.resolved && !external.noticeDismissed && !inLibrary,
        onAddToLibrary = { addToLibrary.launch(SafDocumentGateway.PICKER_MIME_TYPES) },
        onDismissExternalNotice = { graph.external.dismissNotice() },
        frontMatterOffer = frontMatterOffer?.chapterTitle,
        onSkipFrontMatter = {
            settleFrontMatterOffer()
            reader.skipFrontMatter()
        },
        onDismissFrontMatterOffer = {
            settleFrontMatterOffer()
            reader.dismissFrontMatterOffer()
        },
        resumeOffer = resumeOffer,
        onAcceptResumeOffer = {
            settleResumeOffer()
            reader.acceptResumeOffer()
        },
        onDismissResumeOffer = {
            settleResumeOffer()
            reader.dismissResumeOffer()
        },
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
private fun ExternalIdentity(graph: LibraryGraph, external: ExternalOpen?, reader: ReaderViewModel) {
    LaunchedEffect(reader, external?.uri) {
        val uri = external?.uri ?: return@LaunchedEffect
        // Waits for *this* book's stream, not for "a" stream. A state value read
        // during composition can still describe the book before this one — an
        // "Open with" arriving mid-book recomposes with the new URI while the
        // reader is still showing the old book's Reading state — and starting the
        // deferred work there would hash one book while another is on screen,
        // which is both the wrong REQ-110 claim and the window in which
        // [ReaderViewModel.identityResolved] has no session to stamp.
        reader.state.first { it is ReaderUiState.Reading && reader.openKey == uri }
        graph.external.resolveIdentity(uri)
    }
    LaunchedEffect(reader, external?.uri, external?.identity) {
        val identity = external?.identity ?: return@LaunchedEffect
        reader.identityResolved(external.uri, identity)
    }
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
        // Whether those bytes are a picked file, a folder's file or a verified
        // private copy of an account book is the repository's business alone
        // (#118): this asks for the book and gets the book.
        bytes = repository.byteSource(bookId),
    )
}

/**
 * The catalog store, as durability needs it (LEAF204).
 *
 * The only place the reader's [ReaderPosition] and the catalog's [ReadingState]
 * meet, so neither package has to know the other's shape.
 */
internal class CatalogPositions(
    private val repository: LibraryRepository,
    /**
     * Where a portable position goes, or null when this build has no account
     * surface at all (#120).
     *
     * Deliberately the whole shelf rather than a book id: whether the open book
     * is an account book is a question about the account's *current* rows, and
     * the answer changes while the reader is reading — a book added to the
     * account from the shelf, a sign-out mid-chapter. Resolving it at each flush
     * asks the live state; resolving it once at open would answer from a shelf
     * that has since changed.
     */
    private val account: AccountShelf? = null,
) : ReaderPositions {

    override val failure: StateFlow<String?> get() = repository.persistenceFailure

    override fun restore(bookId: String): ReaderPosition? {
        val stored = repository.readingState(bookId) ?: return null
        return ReaderPosition(
            position = TokenPosition(stored.bookDigest, stored.tokenIndex, stored.pipelineVersion),
            progressFraction = stored.progressFraction,
            wpm = stored.wpm,
            structuralFingerprint = stored.structuralFingerprint,
        )
    }

    override fun record(bookId: String, position: ReaderPosition) {
        repository.recordReadingState(
            bookId,
            ReadingState(
                bookDigest = position.position.bookDigest,
                tokenIndex = position.position.tokenIndex,
                pipelineVersion = position.position.pipelineVersion,
                progressFraction = position.progressFraction,
                wpm = position.wpm,
                // Null when this open read no central directory. The store keeps
                // whatever it already holds rather than clearing it (AD-18).
                structuralFingerprint = position.structuralFingerprint,
            ),
        )
    }

    override fun flush() {
        repository.flushReadingState()
    }

    /**
     * Publishes the portable position, for an account book only.
     *
     * Two gates, and a device book fails the second exactly as REQ-512 requires.
     * `accountBookIdForDevice` is the same content-identity bridge the shelf uses
     * to tell the reader's open apart from the account's row (AD-23), and it
     * returns null for every book the account does not hold — so a device-only
     * book never names itself to the Reader API from here, any more than it does
     * from the open that `MainActivity` reports.
     *
     * Signed out there is no shelf state to resolve against and `books` is empty,
     * so the same null comes back and nothing is sent (D4).
     */
    override fun publishPortable(bookId: String, content: BookContent, tokenIndex: Int) {
        val shelf = account ?: return
        val accountBookId = accountBookIdForDevice(bookId, shelf.state.value) ?: return
        val portable = PortableReadingPosition.of(content, tokenIndex) ?: return
        shelf.recordPosition(accountBookId, portable)
    }

    /**
     * The resume offer for this book, or null when there is nothing to ask
     * (REQ-511).
     *
     * The same two gates [publishPortable] has, in the same order and for the
     * same reasons — a device book resolves to no account id, and signed out
     * there are no rows to resolve against — and then four of its own:
     *
     * 1. **The account holds no position for this book.** Nothing has been said
     *    about it by anybody, so there is nothing to offer.
     * 2. **The position maps to where the reader already is, or behind it.**
     * 3. **The book has no tokens.** There is no word to land on.
     * 4. **The position is this device's own** (#140): the backend admitted it
     *    from this device's publish, as
     *    [com.cedagova.reader.library.sync.AccountBook.ownPositionChangeKey]
     *    records. Gate 2 alone does not cover it — publish at 40 %, rewind to
     *    30 %, and the account's 40 % is ahead of the reader but was never
     *    another device's.
     *
     * ## Gate 2 is a question about whether to ask, not about who wins
     *
     * This is the one comparison in the app that puts a remote position next to
     * a local one, and the distinction matters enough to state twice. It decides
     * whether a *question* is worth putting on the screen; it never selects a
     * position. Nothing downstream of it reads the comparison: accepting always
     * moves to [ResumeOffer.targetTokenIndex] exactly as the mapping produced
     * it, declining always keeps the local position untouched, and the position
     * this device publishes is unaffected either way — a backward move still
     * goes out like any other, and `reader.activity-convergence.v1` still
     * decides which position the account ends up holding.
     *
     * What it rules out is the case that made the gate necessary: this device's
     * *own* published position comes back through the change stream, so without
     * it every pause would be followed by an offer to resume at the percent the
     * reader is already reading. The strict `>` is what makes "the same place"
     * silent, and #121's "a remote position older than local produces no offer"
     * is the same `>` seen from the other side.
     */
    override fun remoteOffer(bookId: String, content: BookContent, tokenIndex: Int): ResumeOffer? {
        if (content.isEmpty) return null
        val shelf = account ?: return null
        val state = shelf.state.value
        val accountBookId = accountBookIdForDevice(bookId, state) ?: return null
        val row = state.books.firstOrNull { it.bookId == accountBookId } ?: return null
        val remote = row.remotePosition ?: return null
        // Gate 4 (#140): the account's position is this device's own admitted
        // publish. It is not another device's place, whatever the reader has done
        // since — a rewind below it included — so it is never offered as one.
        if (remote.changeKey == row.ownPositionChangeKey) return null
        val position = RemoteReadingPosition(
            href = remote.href,
            chapterTitle = remote.chapterTitle,
            progression = remote.progression,
            percent = remote.percent,
            updatedAt = remote.updatedAt,
        )
        val target = PortableReadingPosition.tokenIndexFor(content, position)
        if (target <= tokenIndex) return null
        return ResumeOffer(
            accountBookId = accountBookId,
            changeKey = remote.changeKey,
            targetTokenIndex = target,
            // This parse's own title for the section the record named, and null
            // for a section it does not have — the record's own chapter title is
            // not a substitute, because it describes an edition this device
            // cannot land in.
            chapterTitle = remote.href
                ?.let { href -> content.chapters.firstOrNull { it.spinePath == href } }
                ?.title
                ?.takeIf { it.isNotBlank() },
            // The other client's own number when it stated one, so the reader
            // sees what was published rather than a re-derivation of it; the
            // mapped token's percent otherwise, which is the same fallback the
            // mapping made to get there.
            percent = remote.percent?.roundToInt()?.coerceIn(0, 100)
                ?: PortableReadingPosition.of(content, target)?.percent
                ?: return null,
        )
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
