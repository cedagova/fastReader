package com.cedagova.fastreader.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.cedagova.fastreader.settings.FontSize
import com.cedagova.fastreader.settings.ThemeChoice

private val LightColors = lightColorScheme(
    primary = Color(0xFF3D5AFE),
    onPrimary = Color.White,
    background = Color(0xFFFDFBF7),
    onBackground = Color(0xFF1B1B1F),
    surface = Color(0xFFFDFBF7),
    onSurface = Color(0xFF1B1B1F),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8C9EFF),
    onPrimary = Color(0xFF0A1172),
    background = Color(0xFF121316),
    onBackground = Color(0xFFE4E2E6),
    surface = Color(0xFF121316),
    onSurface = Color(0xFFE4E2E6),
)

/**
 * The app's one theme, and the one place REQ-022 is implemented.
 *
 * Both of that requirement's settings land here rather than on any screen, which
 * is what makes "theme and font size apply across reader and library" a single
 * mechanism instead of a pair of per-screen behaviours that could disagree:
 *
 * - [darkTheme] picks the colour scheme every surface reads through
 *   `MaterialTheme.colorScheme`;
 * - [fontSize] scales [LocalDensity]'s font scale, so every ordinary `sp` measured
 *   inside — library rows, reader chrome, the settings screen — grows or shrinks
 *   together.
 *
 * The font scale multiplies the device's rather than replacing it, so a reader who
 * has already enlarged system text keeps that and gets the app's step on top —
 * bounded by [MAX_FONT_SCALE].
 *
 * ## What the font scale does *not* carry, and why
 *
 * Android's `sp` scaling has been non-linear since Android 14: it grows small text
 * fully and large text barely at all, so that a headline does not double when body
 * text does. Measured on the reference device at a font scale of 1.5, `8.sp`
 * resolves to 12 dp and `12.sp` to 18 dp — both a full 1.5× — while `24.sp`
 * resolves to 28 dp and `36.sp` to **36 dp**, which is no growth whatsoever.
 *
 * The streamed word is drawn at 36 sp. Left to this mechanism alone, a reader who
 * chose "Largest" would get a larger library, larger controls and a word of
 * exactly the same size — the one element REQ-022 most obviously has to move. So
 * [com.cedagova.fastreader.settings.ReaderSettings.cues] applies
 * [FontSize.scale] to the word's size directly, and the curve above is left to do
 * what it is good at for everything else.
 */
@Composable
fun FastReaderTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    fontSize: FontSize = FontSize.MEDIUM,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val combined = (density.fontScale * fontSize.scale).coerceAtMost(MAX_FONT_SCALE)
    val scaled = if (combined == density.fontScale) {
        density
    } else {
        Density(density.density, combined)
    }
    CompositionLocalProvider(LocalDensity provides scaled) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
            content = content,
        )
    }
}

/**
 * The largest text scale the app's chrome is laid out for.
 *
 * Android's own accessibility setting stops at 2.0, and the top bars, transport
 * buttons and option rows are built to survive exactly that. Multiplying the app's
 * own [FontSize] on top of a device already at 2.0 reaches 3.0, where the Material
 * app bar — whose height is fixed at 64 dp — clips the descenders off its title.
 * So the two settings compose up to this ceiling and no further: a reader who has
 * already turned system text all the way up is at the app's largest legible size,
 * and the app-level step has nothing left to add rather than something to break.
 *
 * The cap only ever clamps growth; [FontSize.SMALL] can still take the combined
 * scale below the device's own, because that is a reader deliberately asking this
 * app for smaller text on their own device.
 */
private const val MAX_FONT_SCALE = 2f

/** Whether this choice means a dark page right now; [ThemeChoice.SYSTEM] asks the device. */
@Composable
@ReadOnlyComposable
fun ThemeChoice.isDark(): Boolean = when (this) {
    ThemeChoice.LIGHT -> false
    ThemeChoice.DARK -> true
    ThemeChoice.SYSTEM -> isSystemInDarkTheme()
}
