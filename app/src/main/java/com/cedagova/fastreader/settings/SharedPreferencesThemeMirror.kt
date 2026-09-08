package com.cedagova.fastreader.settings

import android.app.UiModeManager
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import androidx.core.content.edit

/**
 * The [ThemeMirror] the app actually runs on: a one-key preferences file plus the
 * platform's own app-level night override.
 *
 * ## Two stores, because two different things draw the first frame
 *
 * The **preferences file** is what [MainActivity][com.cedagova.fastreader.MainActivity]
 * reads before `super.onCreate` to pick the window theme. It is a few hundred
 * bytes and its first read is the only blocking I/O on the launch path.
 *
 * The **night override** ([UiModeManager.setApplicationNightMode], API 31+) is
 * what fixes the frame the app cannot paint at all. The system splash is drawn by
 * `system_server` from the manifest theme *before this process exists*, so no
 * amount of `setTheme` reaches it; resolving `Theme.FastReader` against
 * `values-night` is the only lever, and this call is how an app tells the system
 * which way to resolve it. Without it, "Dark" on a light device still shows a
 * white splash and REQ-102 fails on the recording even though every frame the app
 * draws is correct.
 *
 * ## Why the writes commit rather than apply
 *
 * `apply()` returns before the bytes are on disk and flushes them when a
 * component's lifecycle next lets it. `adb shell am force-stop` — and the reader
 * equivalent, swiping the app away and force-stopping it from Settings — does not
 * give it that chance, and REQ-102's fourth acceptance case is exactly "change the
 * theme, force-stop, cold start". A preference that was accepted on screen and
 * then lost would show the *old* theme on the very launch the reader is checking.
 * Both writes are small, both happen off the launch's critical path (the settings
 * write path runs on the IO dispatcher; the launch path's repair write is a no-op
 * unless the platform drifted), so the block costs nothing worth having.
 *
 * ## Ordering and failure
 *
 * [write] records the choice, then pushes the override, then records what it
 * pushed — so a failed push is retried rather than remembered as done. Every step
 * is guarded: a mirror that cannot be written is a stale cache costing one launch
 * frame, and it is repaired by the next catalog load or settings write. It is
 * never allowed to surface as "your settings are not being saved", which would be
 * a lie about the catalog.
 *
 * On API 26-30 the override does not exist. The window theme is still applied
 * from the mirror, so the app's own first frame is correct; the system's starting
 * window on those releases follows the device and can still show one
 * device-coloured frame for an explicit Light/Dark choice that disagrees with it.
 * The platform offers no API to change that, and every AVD in the project's test
 * matrix is API 33 or newer.
 */
class SharedPreferencesThemeMirror(context: Context) : ThemeMirror {

    private val appContext = context.applicationContext

    private val preferences: SharedPreferences?
        get() = runCatching { appContext.getSharedPreferences(FILE, Context.MODE_PRIVATE) }.getOrNull()

    override fun read(): ThemeChoice {
        val stored = runCatching { preferences?.getString(KEY_THEME, null) }.getOrNull()
        return ThemeChoice.entries.firstOrNull { it.name == stored } ?: ReaderSettings.DEFAULTS.theme
    }

    override fun write(theme: ThemeChoice) {
        val preferences = preferences ?: return
        runCatching {
            if (preferences.getString(KEY_THEME, null) != theme.name) {
                preferences.edit(commit = true) { putString(KEY_THEME, theme.name) }
            }
        }
        syncApplicationNightMode(preferences, theme)
    }

    /**
     * Makes the platform's app-level night override agree with [theme].
     *
     * The pushed value is remembered rather than read back, because the platform
     * exposes no getter for it: [UiModeManager.getNightMode] answers for the
     * *device*, not for this app's override. Remembering it keeps the common case
     * — every launch, every unrelated settings change — free of a binder call,
     * while an override that was never pushed (a fresh install, cleared app data)
     * reads as unknown and is pushed once.
     */
    private fun syncApplicationNightMode(preferences: SharedPreferences, theme: ThemeChoice) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val desired = nightModeFor(theme)
        val pushed = runCatching { preferences.getInt(KEY_NIGHT_MODE, UNKNOWN_NIGHT_MODE) }
            .getOrDefault(UNKNOWN_NIGHT_MODE)
        if (pushed == desired) return
        val manager = runCatching { appContext.getSystemService(UiModeManager::class.java) }
            .getOrNull() ?: return
        if (runCatching { manager.setApplicationNightMode(desired) }.isFailure) return
        runCatching { preferences.edit(commit = true) { putInt(KEY_NIGHT_MODE, desired) } }
    }

    private companion object {

        /**
         * Deliberately not the catalog's file and deliberately not `default`
         * preferences: this is one derived value with one writer, and mixing it
         * into a general preferences file would invite a second settings store.
         */
        const val FILE = "launch-theme"

        const val KEY_THEME = "theme"
        const val KEY_NIGHT_MODE = "pushed_night_mode"

        /** No override has been pushed, or the push failed. Distinct from every real mode. */
        const val UNKNOWN_NIGHT_MODE = Int.MIN_VALUE

        /**
         * `MODE_NIGHT_AUTO` is the platform's "clear this app's override and follow
         * the device" for [UiModeManager.setApplicationNightMode] — the same
         * mapping AppCompat uses for `MODE_NIGHT_FOLLOW_SYSTEM`.
         */
        fun nightModeFor(theme: ThemeChoice): Int = when (theme) {
            ThemeChoice.LIGHT -> UiModeManager.MODE_NIGHT_NO
            ThemeChoice.DARK -> UiModeManager.MODE_NIGHT_YES
            ThemeChoice.SYSTEM -> UiModeManager.MODE_NIGHT_AUTO
        }
    }
}
