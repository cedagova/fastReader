package com.cedagova.fastreader.crash

import android.content.Intent

/**
 * The share the reader chooses, and the only way a report ever leaves the device
 * (REQ-303).
 *
 * `ACTION_SEND` with `text/plain` and the report as `EXTRA_TEXT`: the text
 * itself travels in the intent, so no file is exposed, no `FileProvider` is
 * needed, and no grant to this app's storage is handed to whatever the reader
 * picks. FastReader holds no network permission and makes no request — the app
 * the reader chooses is the one that decides where the text goes, after they
 * choose it.
 *
 * Built as a plain function so `CrashShareTest` can assert that what is offered
 * to other apps is byte for byte the redacted text that was stored, rather than
 * that claim resting on a device run nobody can repeat.
 */
fun crashShareIntent(report: String, subject: String): Intent =
    Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, subject)
        putExtra(Intent.EXTRA_TEXT, report)
    }
