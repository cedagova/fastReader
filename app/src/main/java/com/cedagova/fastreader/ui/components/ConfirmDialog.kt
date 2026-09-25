package com.cedagova.fastreader.ui.components

import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.cedagova.fastreader.ui.theme.Sizes

/**
 * A question with two answers, before something the reader cannot see the
 * whole of from where they are: a removal, an upload, a crash report leaving.
 *
 * The title is a heading, so TalkBack lands on the question first, and both
 * buttons clear [Sizes.TouchTarget]. Dismissing the dialog — Back, or a tap
 * outside — is the same answer as [onDismiss], because "no" has to hold however
 * the reader declines.
 *
 * The confirm label should say what happens ("Remove book"), never a bare "OK".
 * The dialog is tagged [tag]; its buttons [confirmTag] and [dismissTag].
 */
@Composable
fun ConfirmDialog(
    title: String,
    confirmLabel: String,
    dismissLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    tag: String,
    modifier: Modifier = Modifier,
    confirmTag: String = "${tag}_confirm",
    dismissTag: String = "${tag}_cancel",
    body: @Composable () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier.testTag(tag),
        title = {
            Text(
                text = title,
                modifier = Modifier.semantics { heading() },
            )
        },
        text = body,
        confirmButton = { DialogButton(label = confirmLabel, onClick = onConfirm, tag = confirmTag) },
        dismissButton = { DialogButton(label = dismissLabel, onClick = onDismiss, tag = dismissTag) },
    )
}

@Composable
private fun DialogButton(label: String, onClick: () -> Unit, tag: String) {
    TextButton(
        onClick = onClick,
        modifier = Modifier
            .defaultMinSize(minWidth = Sizes.TouchTarget, minHeight = Sizes.TouchTarget)
            .testTag(tag),
    ) {
        Text(label)
    }
}
