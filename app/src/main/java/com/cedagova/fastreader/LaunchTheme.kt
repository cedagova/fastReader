package com.cedagova.fastreader

import androidx.annotation.StyleRes
import com.cedagova.fastreader.settings.ThemeChoice

/**
 * The window theme a cold start opens with, for a mirrored [ThemeChoice] (AD-10).
 *
 * Two of the three answers ignore the device on purpose. `Theme.FastReader` is
 * the day/night theme and is the *only* correct answer for
 * [ThemeChoice.SYSTEM] — resolving it is the platform's job, through
 * `values-night`. The other two exist because a reader who picked Light or Dark
 * asked for that regardless of what the device is set to, and there is no
 * resource qualifier for "what this app was told".
 */
@StyleRes
fun launchThemeFor(theme: ThemeChoice): Int = when (theme) {
    ThemeChoice.LIGHT -> R.style.Theme_FastReader_Light
    ThemeChoice.DARK -> R.style.Theme_FastReader_Dark
    ThemeChoice.SYSTEM -> R.style.Theme_FastReader
}
