package com.cedagova.fastreader.external

/**
 * A book another app has handed over, named by the URI it granted access to
 * (REQ-103).
 */
data class IncomingBook(val uri: String)

/** The actions the manifest registers FastReader for. */
object ExternalOpenActions {
    const val VIEW = "android.intent.action.VIEW"
    const val SEND = "android.intent.action.SEND"
}

/**
 * The only scheme an incoming book may arrive on.
 *
 * Deliberately narrow. `file://` would need a storage permission this app does
 * not ask for and `http(s)://` would need a network permission REQ-303 forbids,
 * so offering FastReader for either would put its name in a chooser for a book it
 * then could not open. The manifest filters say the same thing; this is the
 * runtime half, because an intent can always name a scheme the filter did not.
 */
private const val CONTENT_SCHEME = "content:"

/**
 * Which URI, if any, an incoming intent is asking FastReader to read.
 *
 * Pure over plain strings — no `Intent`, no `Uri` — so every branch of the
 * hand-over is provable by a JVM test rather than by tapping through a share
 * sheet. The Android side of it is one call in [com.cedagova.fastreader
 * .MainActivity], which does nothing but unpack the intent's fields.
 *
 * Two shapes, because Android carries the document in a different place for each:
 *
 * - `ACTION_VIEW` ("Open with") puts it in the intent's own data.
 * - `ACTION_SEND` (the share sheet) puts it in `EXTRA_STREAM` and leaves the
 *   data null.
 *
 * The MIME type is *not* checked here. The manifest filters already decide which
 * chooser FastReader appears in, and a sender that mislabels an EPUB as
 * `application/octet-stream` is the ordinary case, not an error — what the bytes
 * actually are is settled by the content pipeline, which shows the existing
 * damaged/DRM explanation for anything that is not a readable EPUB.
 */
fun incomingBook(action: String?, dataUri: String?, streamUri: String?): IncomingBook? {
    val uri = when (action) {
        ExternalOpenActions.VIEW -> dataUri
        ExternalOpenActions.SEND -> streamUri
        else -> null
    } ?: return null
    if (!uri.startsWith(CONTENT_SCHEME, ignoreCase = true)) return null
    return IncomingBook(uri)
}
