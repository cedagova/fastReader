package com.cedagova.reader.account.testing

import com.cedagova.reader.account.library.AccountCopy
import com.cedagova.reader.account.library.AccountCopyCatalog
import com.cedagova.reader.account.library.AccountCopyReferences
import com.cedagova.reader.account.library.DeviceBookIdentity
import com.cedagova.reader.account.library.DevicePublicationSources
import com.cedagova.reader.account.library.PublicationSourceProblem
import com.cedagova.reader.account.library.PublicationSourceResult
import com.cedagova.reader.account.library.ResumeOfferRecords
import com.cedagova.reader.library.imports.PublicationSource
import java.io.File

// In-memory stand-ins for :reader-account's host seams and its copy-reference
// interface (#200, A197-F003), for a host's tests of the code that sits on the
// account pipeline — so a host defines no fake of a library type (#199).

/** The account document's copy references, recorded rather than persisted, for the signed-in [accountId]. */
public class RecordingCopyReferences(private val accountId: String? = "user-1") : AccountCopyReferences {
    public val stored: MutableList<AccountCopy> = mutableListOf()

    override fun accountId(): String? = accountId

    override suspend fun copyReferences(): List<AccountCopy> = stored.toList()

    override suspend fun putCopyReference(copy: AccountCopy) {
        stored.removeAll { it.contentSha256 == copy.contentSha256 }
        stored += copy
    }

    override suspend fun dropCopyReference(contentSha256: String) {
        stored.removeAll { it.contentSha256 == contentSha256 }
    }

    override suspend fun retainCopyReferences(present: Set<String>) {
        stored.retainAll { it.contentSha256 in present }
    }
}

/** Every answered resume offer, in order, as `<bookId>:<changeKey>` in [settled]. Sends nothing, like the real note. */
public class RecordingResumeOfferRecords : ResumeOfferRecords {
    public val settled: MutableList<String> = mutableListOf()

    override suspend fun settle(bookId: String, changeKey: String) {
        settled += "$bookId:$changeKey"
    }
}

/**
 * A device catalog in memory: [copies] maps a content identity to the device
 * book id it was added as and the file behind it. [addAccountCopy] answers
 * [readableAs] (the device book id, or null for "not a book this host opens"),
 * and every call is in [calls].
 */
public class FakeAccountCopyCatalog(private val readableAs: (contentSha256: String) -> String? = { "device:$it" }) :
    AccountCopyCatalog {
    public val calls: MutableList<String> = mutableListOf()
    public val copies: MutableMap<String, Pair<String, File>> = mutableMapOf()

    override suspend fun addAccountCopy(contentSha256: String, file: File, displayName: String): String? {
        calls += "add:$contentSha256:$displayName"
        val id = readableAs(contentSha256) ?: return null
        copies[contentSha256] = id to file
        return id
    }

    override suspend fun removeAccountCopy(contentSha256: String) {
        calls += "remove:$contentSha256"
        copies.remove(contentSha256)
    }

    override suspend fun reconcileAccountCopies(exists: (path: String) -> Boolean) {
        calls += "reconcile"
    }
}

/** Device books by id: [books] holds the bytes a host would read; an id not in it is UNREACHABLE. */
public class FakeDevicePublicationSources : DevicePublicationSources {
    public val books: MutableMap<String, PublicationSource> = mutableMapOf()

    override fun sourceFor(deviceBookId: String): PublicationSourceResult = books[deviceBookId]
        ?.let { PublicationSourceResult.Ready(it) }
        ?: PublicationSourceResult.Unavailable(PublicationSourceProblem.UNREACHABLE)
}

/** A device-book id that is the content identity under a `sha256:` prefix, FastReader's convention. */
public object PrefixedBookIdentity : DeviceBookIdentity {
    override fun deviceBookId(contentSha256: String): String = "sha256:$contentSha256"

    override fun contentSha256(deviceBookId: String): String = deviceBookId.removePrefix("sha256:").lowercase()
}
