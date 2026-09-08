package com.cedagova.fastreader.library.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContracts

/**
 * The system document pickers, as this app needs them.
 *
 * The stock contracts do not ask for a persistable grant, so the read permission
 * would be gone the next time the app starts. Both contracts below add that flag;
 * the catalog then takes the long-lived grant (AD-1).
 *
 * They live here rather than beside one screen because two screens open the same
 * picker for the same reason: the library adds books, and the reader's
 * session-only notice offers "Add to library" for the book already on screen
 * (REQ-103). One definition means the flag cannot be right in one place and
 * missing in the other.
 */
internal class PickPersistableDocuments : ActivityResultContracts.OpenMultipleDocuments() {
    override fun createIntent(context: Context, input: Array<String>): Intent =
        super.createIntent(context, input).withPersistableRead()
}

internal class PickPersistableDocumentTree : ActivityResultContracts.OpenDocumentTree() {
    override fun createIntent(context: Context, input: Uri?): Intent =
        super.createIntent(context, input).withPersistableRead()
}

private fun Intent.withPersistableRead(): Intent = addFlags(
    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
)
