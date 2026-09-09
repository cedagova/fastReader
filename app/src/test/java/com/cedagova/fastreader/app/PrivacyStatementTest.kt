package com.cedagova.fastreader.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cedagova.fastreader.R
import com.cedagova.fastreader.settings.AppVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * REQ-107's "in the app and in the release notes": one statement, kept identical
 * in the two places it is published.
 *
 * The failure this exists to prevent is not a typo. It is the app and the release
 * notes making *different* promises about the same build — the kind of drift that
 * happens when one of them is edited months after the other, by which point
 * nobody can say which one the software actually honours. Holding
 * `docs/privacy-statement.md` to the shipped string means the block can be
 * pasted into release notes and the README knowing it is the app's own words —
 * and, since #49, the two pasted copies are held to it too, so a later edit to
 * one of the three published places turns this red instead of shipping.
 *
 * Line wrapping is the one difference allowed: the document wraps to the width of
 * the repository's prose and the resource is one paragraph, so both sides are
 * compared with runs of whitespace collapsed.
 */
@RunWith(AndroidJUnit4::class)
// Robolectric has no API 36 sandbox yet; the app's own compileSdk is unaffected.
@Config(sdk = [35])
class PrivacyStatementTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `the release-notes block is the statement the app shows`() {
        assertEquals(oneLine(shownInApp()), oneLine(releaseNotesBlock()))
    }

    /**
     * The claims the rest of this repository proves, named here so that softening
     * one of them into an intention has to be a deliberate edit to this test as
     * well. Each maps to a row of the table in `docs/privacy-statement.md`.
     */
    @Test
    fun `the statement still makes the five claims the build backs up`() {
        val statement = oneLine(shownInApp())

        listOf(
            "no internet permission",
            "hands a web address to your browser",
            "your books stay in the folders you chose",
            "is included in this device's backup or in a transfer to a new phone",
            "not added to your list and no permission to it is kept",
        ).forEach { claim ->
            assertTrue("the statement no longer says \"$claim\": $statement",
                statement.contains(claim, ignoreCase = true))
        }
    }

    /**
     * The two places the block is actually published to a reader outside the app:
     * the repository's front page and the notes attached to the GitHub Release.
     * Both carry the same marked block, so both are compared the same way.
     */
    @Test
    fun `the README and the release notes carry that same statement`() {
        val inApp = oneLine(shownInApp())

        assertEquals(inApp, oneLine(markedBlock("README.md")))
        assertEquals(inApp, oneLine(markedBlock(releaseNotesPath())))
    }

    /**
     * The notes file is named after the version being shipped, so cutting a
     * release without writing its notes fails here rather than at publish time,
     * when `scripts/release.sh --publish` would otherwise fall back to a
     * one-line default with no privacy statement in it.
     */
    @Test
    fun `this version has a release-notes file`() {
        val path = releaseNotesPath()
        assertTrue("$path does not exist", repositoryFile(path).isFile)
    }

    private fun releaseNotesPath(): String =
        "docs/release-notes/v${AppVersion.of(context).name}.md"

    private fun shownInApp(): String = context.getString(R.string.settings_privacy)

    private fun releaseNotesBlock(): String = markedBlock("docs/privacy-statement.md")

    private fun markedBlock(path: String): String {
        val document = repositoryFile(path).readText()
        val begin = "<!-- privacy-statement:begin -->"
        val end = "<!-- privacy-statement:end -->"
        val from = document.indexOf(begin)
        val to = document.indexOf(end)
        assertTrue("$path has no marked privacy-statement block", from >= 0 && to > from)
        return document.substring(from + begin.length, to)
    }

    private fun oneLine(text: String): String = text.replace(Regex("\\s+"), " ").trim()
}
