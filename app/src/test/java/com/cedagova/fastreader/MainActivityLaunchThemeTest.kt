package com.cedagova.fastreader

import android.content.Context
import android.graphics.drawable.ColorDrawable
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cedagova.fastreader.settings.SharedPreferencesThemeMirror
import com.cedagova.fastreader.settings.ThemeChoice
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * REQ-102 at the cheapest layer that can see it: the window a cold start creates
 * already carries the reader's theme, so there is no light background waiting
 * underneath the first composed frame.
 *
 * ## What this proves and what it does not
 *
 * It proves the *window*. The activity's decor background is read from the theme
 * when the window is built, so a theme applied too late shows up here as the
 * wrong colour — including the case that has no resource qualifier at all: Dark
 * chosen on a device that is set to light.
 *
 * It does not prove the *system splash*, which is drawn by `system_server` from
 * the manifest theme before this process exists and is therefore invisible to any
 * JVM test. That half of REQ-102 is proved by the 60 fps launch recordings under
 * `docs/evidence/42/`, which is also where the plan puts it.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class MainActivityLaunchThemeTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun launchWindowColor(mirrored: ThemeChoice?): Int {
        mirrored?.let { SharedPreferencesThemeMirror(context).write(it) }
        val activity = Robolectric.buildActivity(MainActivity::class.java).create().get()
        return (activity.window.decorView.background as ColorDrawable).color
    }

    @Test
    fun `dark chosen on a light device opens a dark window`() {
        // The case `values-night` alone cannot reach, and the reason the mirror
        // exists at all.
        assertEquals(PAGE_DARK, launchWindowColor(ThemeChoice.DARK))
    }

    @Test
    @Config(qualifiers = "night")
    fun `light chosen on a dark device opens a light window`() {
        assertEquals(PAGE_LIGHT, launchWindowColor(ThemeChoice.LIGHT))
    }

    @Test
    @Config(qualifiers = "night")
    fun `system on a dark device opens a dark window`() {
        assertEquals(PAGE_DARK, launchWindowColor(ThemeChoice.SYSTEM))
    }

    @Test
    fun `system on a light device opens a light window`() {
        assertEquals(PAGE_LIGHT, launchWindowColor(ThemeChoice.SYSTEM))
    }

    @Test
    @Config(qualifiers = "night")
    fun `an app that has never stored a choice follows the device`() {
        // A fresh install: nothing mirrored, so the default is System and the
        // platform resolves it. No first frame is left to chance.
        assertEquals(PAGE_DARK, launchWindowColor(mirrored = null))
    }

    private companion object {
        /** `@color/page_light`, and `LightColors.background` in `ui/theme/Theme.kt`. */
        const val PAGE_LIGHT = 0xFFFDFBF7.toInt()

        /** `@color/page_dark`, and `DarkColors.background` in `ui/theme/Theme.kt`. */
        const val PAGE_DARK = 0xFF121316.toInt()
    }
}
