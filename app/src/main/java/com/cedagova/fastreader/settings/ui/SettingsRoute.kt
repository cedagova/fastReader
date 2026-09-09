package com.cedagova.fastreader.settings.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.cedagova.fastreader.content.BundledSample
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import com.cedagova.fastreader.library.LibraryGraph
import com.cedagova.fastreader.settings.AppVersion
import com.cedagova.fastreader.settings.RELEASES_URL
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
 *
 * ## The update hand-off (REQ-106)
 *
 * This is the only outbound action in the app, and it is not a request: the route
 * starts an `ACTION_VIEW` for [RELEASES_URL] and the reader's browser goes to
 * GitHub. FastReader holds no network permission, so there is nothing here to
 * fail over a connection.
 *
 * A device with no app able to open a web link would otherwise take the uncaught
 * `ActivityNotFoundException` down with it, so the miss is caught and shown as a
 * line under the button carrying the address — the same "say what went wrong"
 * rule the persistence banner follows, rather than a tap that does nothing.
 */
@Composable
fun SettingsRoute(
    graph: LibraryGraph,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    /** Opens a text shipped inside the app (REQ-109), which stays reachable here. */
    onOpenSample: (BundledSample) -> Unit = {},
) {
    val repository = graph.repository
    val settings by repository.settings.collectAsState()
    val persistenceFailure by repository.persistenceFailure.collectAsState()

    val context = LocalContext.current
    val version = remember(context) { AppVersion.of(context) }
    var updateHandoffUnavailable by rememberSaveable { mutableStateOf(false) }

    BackHandler(onBack = onBack)

    SettingsScreen(
        settings = settings,
        onSettingsChange = { next -> repository.requestUpdateSettings { next } },
        onReset = { repository.requestUpdateSettings { ReaderSettings.DEFAULTS } },
        onBack = onBack,
        version = version,
        onCheckForUpdates = {
            updateHandoffUnavailable = try {
                context.startActivity(Intent(Intent.ACTION_VIEW, RELEASES_URL.toUri()))
                false
            } catch (missing: ActivityNotFoundException) {
                true
            }
        },
        modifier = modifier,
        persistenceFailure = persistenceFailure,
        onOpenSample = onOpenSample,
        updateHandoffUnavailable = updateHandoffUnavailable,
    )
}
