package com.cedagova.fastreader.crash

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * What actually leaves the app when the reader taps Share (REQ-303).
 *
 * The device run shows the share sheet opening; it cannot show what is inside
 * the intent. This does, and it is the join between the two halves of the
 * privacy claim: the text handed to another app is the same redacted text
 * `CrashReportTest` proved carries nothing of the reader's.
 */
@RunWith(AndroidJUnit4::class)
// Robolectric has no API 36 sandbox yet; the app's own compileSdk is unaffected.
@Config(sdk = [35])
class CrashShareTest {

    private val report = crashReportText(
        CrashFacts("1.1.0", 3, "Pixel 7", "16", 36),
        IllegalStateException("could not read Rayuela - Julio Cortazar.epub"),
    )

    @Test
    fun `the share carries the stored report as plain text and nothing else`() {
        val intent = crashShareIntent(report, "FastReader crash report")

        assertEquals(Intent.ACTION_SEND, intent.action)
        assertEquals("text/plain", intent.type)
        assertEquals(report, intent.getStringExtra(Intent.EXTRA_TEXT))
        assertEquals("FastReader crash report", intent.getStringExtra(Intent.EXTRA_SUBJECT))
    }

    /**
     * No `EXTRA_STREAM`, so no `content://` URI into this app's private storage
     * is handed to whatever the reader picks: the text travels, the file does
     * not, and nothing is granted access to the directory it came from.
     */
    @Test
    fun `no file and no permission to this app's storage travels with it`() {
        val intent = crashShareIntent(report, "FastReader crash report")

        @Suppress("DEPRECATION")
        assertNull(intent.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM))
        assertNull(intent.data)
        assertEquals(0, intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    @Test
    fun `what would be shared is the redacted text, not the raw crash`() {
        val shared = crashShareIntent(report, "s").getStringExtra(Intent.EXTRA_TEXT).orEmpty()

        assertEquals(shared, false, shared.contains("Rayuela"))
        assertEquals(shared, false, shared.contains(".epub"))
    }
}
