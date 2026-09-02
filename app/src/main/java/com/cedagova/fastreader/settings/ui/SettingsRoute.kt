package com.cedagova.fastreader.settings.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.cedagova.fastreader.library.LibraryGraph
import com.cedagova.fastreader.settings.ReaderSettings

/**
 * The settings screen wired to the store: the saved settings in, changes out.
 *
 * ## One source of truth, on purpose
 *
 * The screen renders the value the *store* holds, and every control writes
 * through the repository. There is no local draft the screen edits and syncs
 * later, which is what makes the live preview (REQ-023) show what a reader will
 * actually get rather than what the screen hopes to save: the preview, the theme
 * around it, and the reader behind it all move together when — and only when —
 * the write lands.
 *
 * The write is a small JSON document on the IO dispatcher, so at the rate a
 * person taps a switch it is not perceptible. When it *fails*, the control snaps
 * back to the value that is genuinely saved and the banner says why, which is the
 * definition's "writes fail loud" applied to settings.
 */
@Composable
fun SettingsRoute(
    graph: LibraryGraph,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val repository = graph.repository
    val settings by repository.settings.collectAsState()
    val persistenceFailure by repository.persistenceFailure.collectAsState()

    BackHandler(onBack = onBack)

    SettingsScreen(
        settings = settings,
        onSettingsChange = { next -> repository.requestUpdateSettings { next } },
        onReset = { repository.requestUpdateSettings { ReaderSettings.DEFAULTS } },
        onBack = onBack,
        modifier = modifier,
        persistenceFailure = persistenceFailure,
    )
}
