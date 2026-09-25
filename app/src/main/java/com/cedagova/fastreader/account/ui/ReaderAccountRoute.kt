package com.cedagova.fastreader.account.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cedagova.reader.account.ReaderAccountController

/**
 * The account screen wired to the process-scoped [ReaderAccountController]:
 * its state in, the reader's taps out. Back returns to Settings, which is
 * where this surface was opened from and which stays underneath it.
 */
@Composable
fun ReaderAccountRoute(controller: ReaderAccountController, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val state by controller.state.collectAsStateWithLifecycle()
    BackHandler(onBack = onBack)
    ReaderAccountScreen(
        state = state,
        actions = controller,
        onBack = onBack,
        modifier = modifier,
    )
}
