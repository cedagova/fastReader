package com.cedagova.fastreader.ui.components

import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.cedagova.fastreader.ui.theme.Sizes
import com.cedagova.fastreader.ui.theme.Spacing

/**
 * A change the reader can still take back, anchored to the bottom of the screen.
 *
 * At the bottom rather than stacked with the banners at the top: a row removed
 * from the end of a long list would put a top banner off-screen, and an offer
 * that expires in seconds is worth nothing if the reader has to scroll to find it.
 *
 * A plain [Snackbar] driven by screen state rather than a `SnackbarHostState`, so
 * it stays as testable as every other state: goldens prove the copy and the
 * control instead of a timing-dependent overlay, and the window itself belongs to
 * whoever holds the change.
 *
 * [actionDescription] is what TalkBack says for the button, and it should name
 * the thing being restored: "Undo" alone, beside a second undo bar, would not say
 * which one it is. The action is tagged `<tag>_action`.
 */
@Composable
fun UndoBar(message: String, actionLabel: String, actionDescription: String, onUndo: () -> Unit, tag: String) {
    Snackbar(
        modifier = Modifier.padding(Spacing.Medium).testTag(tag),
        action = {
            TextButton(
                onClick = onUndo,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.inversePrimary,
                ),
                modifier = Modifier
                    .defaultMinSize(minWidth = Sizes.TouchTarget, minHeight = Sizes.TouchTarget)
                    .testTag("${tag}_action")
                    .semantics { contentDescription = actionDescription },
            ) {
                Text(actionLabel)
            }
        },
    ) {
        Text(message)
    }
}
