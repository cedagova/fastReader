package com.cedagova.fastreader

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cedagova.fastreader.external.ExternalOpenController
import com.cedagova.fastreader.external.incomingBook
import com.cedagova.fastreader.ui.theme.FastReaderTheme
import com.cedagova.fastreader.ui.theme.isDark

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        val graph = appGraph
        // AD-10, and the only three lines in the app whose *position* is the
        // behaviour. `super.onCreate` is where the window is created and its
        // background is read, so a theme applied after it arrives one frame too
        // late — which is the white flash REQ-102 forbids. The catalog is JSON on
        // a background dispatcher and cannot answer this early; the mirror can.
        val launchTheme = graph.themeMirror.read()
        setTheme(launchThemeFor(launchTheme))
        // Idempotent, and normally free. It earns its place when the platform's
        // app-level night override has drifted from the mirror — cleared app data,
        // a restored device — where it costs this launch nothing and makes the
        // *next* cold start's system splash right again.
        graph.themeMirror.write(launchTheme)

        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Only on a genuine launch (REQ-103). `savedInstanceState` is the exact
        // discriminator the behaviour needs: null means someone just handed this
        // app a book, while non-null means the activity is being rebuilt. A
        // rotation therefore does not re-accept the intent — the open book is
        // already held by the process-scoped controller — and a rebuild after
        // process death does not resurrect a session-only book, which is what
        // sends that reader back to the library with no row and their position
        // kept.
        if (savedInstanceState == null) graph.external.acceptIfExternal(intent)
        setContent {
            // REQ-022's single application point: the reader's theme and text size
            // wrap every destination, so both apply to the library and the reader
            // without either screen knowing the settings exist.
            val settings by graph.settingsStore.settings.collectAsStateWithLifecycle()
            FastReaderTheme(darkTheme = settings.theme.isDark(), fontSize = settings.fontSize) {
                FastReaderApp(graph)
            }
        }
    }

    /**
     * A second book arrived while the app was running (REQ-103).
     *
     * `singleTask` in the manifest is what makes this the normal case rather than
     * a second process: the running task comes forward and the book is swapped in
     * place, which is also why [setIntent] matters — a later rebuild of this
     * activity must see the book that is actually open, not the one it was
     * launched with.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        appGraph.external.acceptIfExternal(intent)
    }
}

/**
 * Unpacks an incoming intent and hands any book in it to the controller.
 *
 * The whole Android half of the hand-over: which field carries the document and
 * whether it is one this app may read are [incomingBook]'s to decide, on plain
 * strings, so both are provable without an emulator.
 */
private fun ExternalOpenController.acceptIfExternal(intent: Intent?) {
    if (intent == null) return
    @Suppress("DEPRECATION")
    val stream = intent.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)
    val book = incomingBook(
        action = intent.action,
        dataUri = intent.data?.toString(),
        streamUri = stream?.toString(),
    ) ?: return
    accept(book)
}
