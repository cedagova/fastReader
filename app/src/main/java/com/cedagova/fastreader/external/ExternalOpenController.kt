package com.cedagova.fastreader.external

import com.cedagova.fastreader.content.BookIdentity
import com.cedagova.fastreader.epub.BookDigest
import com.cedagova.fastreader.epub.EpubByteSource
import com.cedagova.fastreader.library.DocumentGateway
import com.cedagova.fastreader.library.LibraryRepository
import com.cedagova.fastreader.reader.BookOrigin
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One book handed over from outside the app, for as long as this process lives.
 *
 * Everything the reader and [com.cedagova.fastreader.MainActivity] need to know
 * about an "Open with" or a share, and nothing about how it got here.
 */
data class ExternalOpen(
    /** The document URI another app granted access to. The book's [openKey]. */
    val uri: String,
    /** What to call the book until its own metadata is read: the provider's file name. */
    val title: String,
    /**
     * The whole-file SHA-256, or null while it is still unknown.
     *
     * Known at once only when the catalog already had this URI. Otherwise it is
     * computed off the open path, after streaming has begun (AD-8), and arrives
     * here later — which is exactly the window in which no position can be stored.
     */
    val identity: BookIdentity?,
    val origin: BookOrigin,
    /**
     * True once the deferred resolution has finished, whatever it concluded.
     *
     * The notice waits for it. Showing "only your reading position will be
     * remembered" before the app has found out whether it can keep the file would
     * make a claim that is about to be false for the keepable half of REQ-103,
     * and would flash a banner away again on the ordinary Files-app path.
     */
    val resolved: Boolean,
    val noticeDismissed: Boolean = false,
) {
    /** The title minus its file extension, the way the catalog names a picked file. */
    companion object {
        fun titleOf(displayName: String?, uri: String): String {
            val name = displayName?.takeIf { it.isNotBlank() }
                ?: uri.substringAfterLast('/').substringAfterLast("%2F").ifBlank { uri }
            return name.substringBeforeLast('.', name).ifBlank { name }
        }
    }
}

/**
 * The "Open with" / share-sheet entry point (REQ-103), from the intent to the
 * reading position.
 *
 * ## Why this is a process-scoped object and not screen state
 *
 * A book opened from outside has no catalog row to come back to, so the only
 * record that it is open is this one. Holding it for the life of the process
 * gives exactly the lifetime the definition asks for: it survives a rotation, and
 * it does *not* survive process death — after which the reader lands back in the
 * library with no row for that book and its position kept, because the position
 * is keyed by identity like every other one (AD-9).
 *
 * ## The two halves of REQ-103, and what decides which one happens
 *
 * Not the file, and not the sender's name: **whether the grant can be kept**. A
 * document the app may hold a long-lived read permission for is a book it can
 * still open tomorrow, so it is added exactly like a pick and gets an ordinary
 * library row. A document granted for this hand-over only cannot be re-opened
 * later, so persisting a row pointing at it would manufacture a library entry
 * that is dead on the next launch — that is the session-only path, with the
 * notice and the "Add to library" offer.
 *
 * ## What never happens on the open path
 *
 * Neither half reads the book's bytes before the reader sees text (AD-8,
 * REQ-110). [accept] does one provider metadata query and one in-memory catalog
 * lookup; [resolveIdentity] — the grant, the ingest, the whole-file digest — is
 * started by the reader only once the stream is running.
 */
class ExternalOpenController(
    private val repository: LibraryRepository,
    private val gateway: DocumentGateway,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
    /** Seam for tests; the real one hashes the file end to end. */
    private val digestOf: (EpubByteSource) -> BookIdentity? = BookDigest::of,
) {

    private val _open = MutableStateFlow<ExternalOpen?>(null)

    /** The book handed over from outside, or null when the app is on its own books. */
    val open: StateFlow<ExternalOpen?> = _open.asStateFlow()

    private var resolving: Job? = null

    private var pending: String? = null

    /**
     * True from the instant an intent naming a book is taken, before anything is
     * known about it.
     *
     * [open] cannot answer this: filling it needs the stored catalog and a
     * provider query, and the reader's title would be a URI fragment if it were
     * published before those. The gap is short, but launch routing runs inside
     * it — and without this it would resume into the last-read book, start
     * parsing a novel, and throw it away a few milliseconds later when the book
     * that was actually asked for arrived.
     */
    val handoverPending: Boolean get() = pending != null

    /**
     * Takes the hand-over described by [book] and makes it the open book.
     *
     * Cheap by contract: a display-name query and a catalog lookup, no bytes. The
     * catalog lookup is what produces [BookOrigin.EXTERNAL_KEEPABLE] — a file the
     * reader added earlier, arriving by "Open with" instead of from the library
     * list, is already a known book, so its identity is its catalog id and its
     * position works from the first word with nothing deferred at all.
     */
    fun accept(book: IncomingBook) {
        val uri = book.uri
        if (_open.value?.uri == uri) return
        resolving?.cancel()
        resolving = null
        pending = uri
        scope.launch {
            repository.load()
            val displayName = withContext(ioDispatcher) { gateway.displayName(uri) }
            val known = repository.catalog.value.books.firstOrNull { candidate ->
                candidate.sources.any { it.uri == uri }
            }
            _open.value = ExternalOpen(
                uri = uri,
                title = known?.title ?: ExternalOpen.titleOf(displayName, uri),
                identity = known?.let { BookIdentity(it.id) },
                origin = if (known != null) BookOrigin.EXTERNAL_KEEPABLE else BookOrigin.EXTERNAL_SESSION_ONLY,
                resolved = known != null,
            )
        }
    }

    /**
     * Settles what this app may keep of [uri], and finds the book's identity.
     *
     * Called by the reader once the stream is running, never before: everything
     * here reads the whole file or writes to the catalog, and REQ-110 applies to
     * this path as much as to the library one.
     *
     * The order matters. The grant is asked for first because its answer decides
     * everything else, and it costs nothing — a document with a keepable grant is
     * ingested like a pick, which produces the row, the cover and the identity in
     * one pass. Only the session-only branch hashes the file itself, and only
     * because it must key a position by something.
     *
     * A grant that persisted but produced no row is released again, so the
     * privacy invariant holds in both directions: nothing is kept about a
     * session-only book, and no grant outlives a book the library does not list.
     */
    fun resolveIdentity(uri: String) {
        val current = _open.value ?: return
        if (current.uri != uri || current.resolved || resolving?.isActive == true) return
        resolving = scope.launch {
            val keepable = withContext(ioDispatcher) { gateway.persistReadPermission(uri, isTree = false) }
            if (keepable) {
                repository.addPickedBooks(listOf(uri))
                val added = repository.catalog.value.books.firstOrNull { candidate ->
                    candidate.sources.any { it.uri == uri }
                }
                if (added != null) {
                    update(uri) { it.copy(identity = BookIdentity(added.id), resolved = true) }
                    return@launch
                }
                withContext(ioDispatcher) { gateway.releaseReadPermission(uri, isTree = false) }
            }
            val identity = withContext(ioDispatcher) { digestOf(byteSource(uri)) }
            update(uri) { it.copy(identity = identity, resolved = true) }
        }
    }

    /** The reader dismissed the session-only notice; it does not come back this session. */
    fun dismissNotice() {
        _open.value?.let { update(it.uri) { open -> open.copy(noticeDismissed = true) } }
    }

    /**
     * The reader left the book. Closing an external open is the whole of "closing
     * returns to the library without that book": there is no row to return to,
     * and this state was the only thing keeping it on screen.
     */
    fun close() {
        resolving?.cancel()
        resolving = null
        pending = null
        _open.value = null
    }

    /**
     * The book's bytes, seekable where the provider allows it, so an external open
     * reads the text it needs rather than the pictures it does not (REQ-110).
     */
    fun byteSource(uri: String): EpubByteSource = object : EpubByteSource {
        override fun open() = gateway.open(uri)

        override fun openChannel() = gateway.openSeekable(uri)
    }

    /** Applies [transform] only while [uri] is still the open book. */
    private fun update(uri: String, transform: (ExternalOpen) -> ExternalOpen) {
        val current = _open.value ?: return
        if (current.uri != uri) return
        _open.value = transform(current)
    }
}
