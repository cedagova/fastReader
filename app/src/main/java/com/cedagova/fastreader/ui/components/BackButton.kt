package com.cedagova.fastreader.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.cedagova.fastreader.ui.theme.Sizes

/**
 * The top bar's way back.
 *
 * Sized explicitly: an `IconButton`'s own box is 40 dp, which is under REQ-301's
 * minimum however comfortable it looks. The label goes on the button rather than
 * on the icon inside it, so it lands on the node that carries the click.
 */
@Composable
fun BackButton(label: String, onClick: () -> Unit, tag: String) {
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(Sizes.TouchTarget)
            .semantics { contentDescription = label }
            .testTag(tag),
    ) {
        Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
    }
}
