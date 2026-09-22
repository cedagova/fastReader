package com.cedagova.fastreader.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cedagova.fastreader.R
import com.cedagova.fastreader.settings.AppVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
     *
     * The crash-report claim is the one REQ-303 gained in v1.2.0: #54 gave the
     * app a report it keeps in private storage, so the statement has to name the
     * reader-initiated share as the second outbound action beside the browser
     * hand-off. The account claims are #100's (REQ-409) as #115 (REQ-513)
     * rewrote them: the app holds the internet permission for the optional
     * Reader account, and since #113 and #114 the account library is a second
     * thing that account sends — which books it holds, and the reading status
     * and last-opened time of those books.
     *
     * So four promises are retired across v1.7.0, not one. "No internet permission" went
     * with #100. "Your books, your reading positions, your settings and any
     * crash report stay on this device and are never sent" goes with #113 and
     * #114, because the account half of it stopped being true the moment a
     * `library_item` mutation left the device (AD-27). The replacement narrows
     * the claim to what the merged code actually does and no further: no book
     * *file* is sent, a device-only book is never named to the Reader API, and
     * positions, reading speed, the other settings and crash reports still
     * never leave.
     *
     * The positions quarter of *that* replacement lasted three increments.
     * #120 and #121 retire "your reading positions … are never sent", because
     * for a book the account already holds FastReader now publishes the
     * portable position — the chapter's spine path, the book-level fraction and
     * the whole percent already on screen — as a `reading_progress` upsert.
     * Again nothing is softened: the replacement states the two conditions the
     * code enforces (only for a book the account holds, and only as the chapter
     * and the fraction) and keeps the three quarters that are still true, adding
     * the exact *word* to them — `PutReaderProgressRequest` has no field a token
     * index could travel in, so that is a claim about a type rather than about
     * care. The inbound half sends nothing and is named in the fourth claim
     * because a request is made for it, not because anything leaves with it.
     *
     * The import half of that replacement lasted exactly one increment. #117
     * retires "no book file is ever sent" and the unconditional "a book that is
     * only on this device is never named to the Reader API", because a book the
     * owner adds is both sent and named. Neither was softened into an
     * intention: each is replaced by the same claim made conditional on the
     * consent the code actually requires.
     *
     * #119 retires nothing at all, and that is the point of asserting its four
     * download claims here rather than trusting the block. Downloading an
     * account book is the first thing this app does that moves a whole file in
     * the *inbound* direction, so "that is all that leaves this device" is
     * still true word for word — but the statement now has to say three things
     * it did not: which request fetches the book, that the account's bearer is
     * never handed to the storage it comes from (`AssetDownloadClient` holds no
     * session and could not send one), and that the copy which lands is a
     * private file the owner can free. The last three claims below are AD-27's
     * "downloaded copies live in private storage, are excluded from backup, and
     * are removable" held to the shipped words.
     *
     * A third phrase is asserted absent although it never shipped: "while you
     * are signed in, a copy of your account's own book list". The first draft
     * of #115 said that, and it was false — `AccountSyncEngine.signOut` keeps
     * the account document (D4: "stop reading the store, keep the file, delete
     * nothing"), and signing in as a different user clears only that account's
     * outbox. Sitting two sentences after "the session … is removed when you
     * sign out", a temporal clause there reads as a retention limit the code
     * does not honour. The shipped sentence states the retention instead, and
     * this assertion keeps the over-claim from coming back.
     *
     * Nothing holds the Spanish copy of the statement to this one — lint fails
     * a *missing* translation, not a stale one — so a change here is a hand
     * edit of `values-es/strings.xml` too.
     */
    @Test
    fun `the statement still makes the twenty-nine claims the build backs up`() {
        val statement = oneLine(shownInApp())

        listOf(
            "has the internet permission and uses it for one thing only: the optional Reader account",
            "your email address, the code or password you type and the account's session go to the Reader identity provider and the Reader API",
            "asks the Reader API which books your account already holds",
            "for those books only it tells the Reader API that you opened one, when you last opened it, how far through it you are and which chapter you are in, whether you have finished it, and when you take one out of your account or put it back",
            "it also asks for the place another device left in those books, so it can offer to take you there",
            "when you choose Add to account library for a book on this device and confirm",
            "asks the Reader API what kinds and sizes of file your account accepts",
            "sends that book's file, its name, its size, its format and its checksum to the Reader API and its storage, where your account keeps them",
            "nothing about that book is sent before you confirm",
            "when you choose Download and open for a book your account already holds",
            "asks the Reader API for a one-off address for that book's file and fetches the file from that address",
            "the request names only a book your account already has",
            "the account's sign-in is never given to the storage the file comes from",
            "a book file is sent only for a book you add that way",
            "a book that is only on this device is never named to the Reader API until you add it",
            "your place in a book leaves this device only for a book your account holds and only as the chapter and how far through it you are",
            "the exact word you are on, your reading speed, your other settings and any crash report stay on this device and are never sent",
            "kept encrypted on this device, outside its backup, and is removed when you sign out",
            "hands a web address to your browser",
            "your books stay in the folders you chose",
            "once you sign in, a copy of your account's own book list, a note of any book you are part-way through adding to it and the file of any account book you have downloaded, in its private storage",
            "a downloaded copy is a file in that private storage like the rest",
            "it is left out of this device's backup and of a transfer to a new phone",
            "Remove downloaded copy on the book's row deletes it from this device without taking the book out of your Reader account",
            "that copy of the account's list is not deleted when you sign out",
            "only uninstalling FastReader or clearing its data removes it",
            "is included in this device's backup or in a transfer to a new phone",
            "it goes nowhere unless you share it and pick an app to send it to",
            "not added to your list and no permission to it is kept",
        ).forEach { claim ->
            assertTrue("the statement no longer says \"$claim\": $statement",
                statement.contains(claim, ignoreCase = true))
        }
        listOf(
            // Retired with #100: the app has the permission now.
            "no internet permission",
            // Retired with #113 and #114: the books half is no longer true.
            "your books, your reading positions, your settings and any crash report " +
                "stay on this device and are never sent",
            // Never shipped, and must never ship: the account book list is NOT
            // removed on sign-out (AccountSyncEngine.signOut keeps the file, D4),
            // so a temporal clause here would over-claim privacy — the exact
            // failure AD-27 forbids.
            "while you are signed in, a copy of your account's own book list",
            // The narrower half of the retired v1.6.0 sentence, in case only its
            // opening is trimmed rather than the whole clause rewritten.
            "and nothing else does",
            // Retired with #117: adding a book uploads its file, so this one
            // stopped being true the moment the consent dialog got a yes.
            "no book file is ever sent",
            // The unconditional half of the same sentence. Quoted with the comma
            // that followed it, because the replacement contains the old words
            // followed by "until you add it" — matching the bare clause would
            // fail against the *new*, truthful sentence.
            "never named to the Reader API, and your reading positions",
            // Retired with #120 and #121: for a book the account holds, the
            // portable position — the chapter's spine path, the book-level
            // fraction and the whole percent already on screen — is published as
            // a `reading_progress` upsert, so "your reading positions … are
            // never sent" stopped being true. The three quarters of it that are
            // still true (speed, other settings, crash report) are kept verbatim
            // in the replacement, which also adds the exact *word* — no token
            // index has a field in `PutReaderProgressRequest` to travel in.
            "your reading positions, your reading speed, your other settings " +
                "and any crash report stay on this device and are never sent",
        ).forEach { retired ->
            assertFalse(
                "the retired promise \"$retired\" must not survive in the statement: $statement",
                statement.contains(retired, ignoreCase = true),
            )
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
