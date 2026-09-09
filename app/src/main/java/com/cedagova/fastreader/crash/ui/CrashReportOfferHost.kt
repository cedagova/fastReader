package com.cedagova.fastreader.crash.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.cedagova.fastreader.R
import com.cedagova.fastreader.crash.CrashReportStore
import com.cedagova.fastreader.crash.crashShareIntent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Offers the pending crash report once, and gets rid of it either way
 * (REQ-207).
 *
 * ## "Once" is the file, not a flag
 *
 * There is no "already asked" preference to keep in step with the report,
 * because the report *is* the state: it exists only between the crash that wrote
 * it and the reader answering, and both answers delete it. A launch with no file
 * shows nothing, which is every launch after the first.
 *
 * That also decides the two awkward cases without a special rule. The app killed
 * while the dialog is up leaves the file, so the next launch asks again — nobody
 * declined anything. A rotation re-reads the file, so the offer survives it.
 *
 * ## Sharing does not send
 *
 * [crashShareIntent] is started through a chooser: at that moment the text is in
 * an intent and the reader is looking at a list of apps. The report is deleted
 * as the sheet opens, because the offer has been answered — what happens to the
 * text afterwards belongs to the app they pick, and FastReader, which has no
 * network permission, is not part of it.
 *
 * The one failure worth a word on screen is a device with nothing that accepts
 * `text/plain`. The chooser normally handles that itself, so this is a
 * belt-and-braces path: the report is kept, the dialog stays, and a line says
 * why — the same shape as the update hand-off's missing browser, rather than a
 * tap that appears to do nothing.
 */
@Composable
fun CrashReportOfferHost(store: CrashReportStore) {
    val context = LocalContext.current
    val subject = stringResource(R.string.crash_offer_chooser)
    var report by remember { mutableStateOf<String?>(null) }
    var shareUnavailable by remember { mutableStateOf(false) }

    LaunchedEffect(store) {
        report = withContext(Dispatchers.IO) { store.read() }
    }

    val pending = report ?: return

    // Both answers end the same way: the file goes, and nothing is left to offer
    // on the next launch.
    //
    // Deliberately synchronous, unlike the read above. Deleting is one `unlink`
    // of a file a few kilobytes long, and it has to have happened by the time the
    // dialog leaves the screen: handing it to a composition-scoped coroutine
    // would race the cancellation caused by this very state change, and a
    // "declined" report that survived that race would be offered again — exactly
    // what REQ-207 forbids.
    fun discard() {
        store.clear()
        report = null
    }

    CrashReportOffer(
        shareUnavailable = shareUnavailable,
        onShare = {
            try {
                context.startActivity(
                    Intent.createChooser(crashShareIntent(pending, subject), subject),
                )
                discard()
            } catch (missing: ActivityNotFoundException) {
                shareUnavailable = true
            }
        },
        onDelete = ::discard,
    )
}
