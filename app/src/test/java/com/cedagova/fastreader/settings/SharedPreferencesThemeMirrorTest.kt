package com.cedagova.fastreader.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The store behind AD-10's mirror, tested for the two things the launch path
 * depends on: that it survives a process boundary, and that it never answers with
 * anything but a real [ThemeChoice].
 *
 * The second matters more than it looks. This value is read before the window
 * exists, so there is nowhere to report a problem to; a mirror that could return
 * a null or throw on a truncated file would take the app down on the one code
 * path that has no UI yet. Every unreadable state resolves to the documented
 * default instead.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class SharedPreferencesThemeMirrorTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun mirror() = SharedPreferencesThemeMirror(context)

    @Test
    fun `an app that has never written a theme launches into the setting's own default`() {
        assertEquals(ReaderSettings.DEFAULTS.theme, mirror().read())
    }

    @Test
    fun `every choice survives being written by one instance and read by another`() {
        for (choice in ThemeChoice.entries) {
            mirror().write(choice)

            assertEquals(choice, mirror().read())
        }
    }

    @Test
    fun `a value from a build this one does not know reads as the default`() {
        // What a downgrade looks like: a newer build stored a theme this one has
        // no constant for. The launch path must still pick a window theme.
        context.getSharedPreferences("launch-theme", Context.MODE_PRIVATE)
            .edit()
            .putString("theme", "SEPIA")
            .commit()

        assertEquals(ReaderSettings.DEFAULTS.theme, mirror().read())
    }

    @Test
    fun `writing the same choice twice is not an error`() {
        mirror().write(ThemeChoice.DARK)
        mirror().write(ThemeChoice.DARK)

        assertEquals(ThemeChoice.DARK, mirror().read())
    }
}
