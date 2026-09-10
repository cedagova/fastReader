package com.cedagova.fastreader.crash.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cedagova.fastreader.R
import com.cedagova.fastreader.crash.CrashFacts
import com.cedagova.fastreader.crash.crashReportText
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The offer's promise, held to what the renderer actually does.
 *
 * The dialog tells the reader what the report holds and what it cannot hold, and
 * that sentence is the whole basis on which they decide to share. It is also the
 * easiest thing in this leaf to leave behind: a later change that started
 * including, say, the thread name or the free disk space would not touch this
 * copy, and the app would then be making a promise it had quietly stopped
 * keeping. So the claim is asserted against a rendered report rather than
 * against itself.
 */
@RunWith(AndroidJUnit4::class)
// Robolectric has no API 36 sandbox yet; the app's own compileSdk is unaffected.
@Config(sdk = [35])
class CrashOfferCopyTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val report = crashReportText(
        CrashFacts("1.1.0", 3, "Pixel 7", "16", 36),
        IllegalStateException("could not read Rayuela - Julio Cortazar.epub"),
    )

    @Test
    fun `the four things the offer says are in the report are in the report`() {
        listOf("1.1.0", "Pixel 7", "16", "IllegalStateException").forEach { fact ->
            assertTrue("the report no longer carries \"$fact\":\n$report", report.contains(fact))
        }
    }

    @Test
    fun `the three things the offer says are absent are absent`() {
        listOf("Rayuela", ".epub", "/").forEach { secret ->
            assertFalse("the report now carries \"$secret\":\n$report", report.contains(secret))
        }
    }

    /** The claim itself, so softening it into a vaguer promise is a deliberate edit. */
    @Test
    fun `the offer still names what it holds and what it cannot`() {
        val contents = context.getString(R.string.crash_offer_contents)

        listOf(
            "app version",
            "Android version",
            "no part of any book",
            "no book titles",
            "no file names and no paths",
        ).forEach { claim ->
            assertTrue("the offer no longer says \"$claim\": $contents", contents.contains(claim))
        }
    }

    /** "Nothing has been sent yet" is the reason the share is safe to tap. */
    @Test
    fun `the offer says nothing leaves the device until the reader chooses an app`() {
        val body = context.getString(R.string.crash_offer_body)

        assertTrue(body, body.contains("Nothing leaves it unless you share"))
        assertTrue(body, body.contains("choose an app"))
        assertTrue(body, body.contains("Deleting it is final"))
        assertTrue(body, body.contains("only asked this once"))
    }
}
