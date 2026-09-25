package com.cedagova.reader.account.library

import com.cedagova.reader.library.imports.PublicationSource

/**
 * The host's device books, as the add-to-account import reads them (#200,
 * A197-F003): one of [AccountImports]' two host seams, beside
 * `ReaderLibraryGateway` and for the same reason.
 *
 * A device book is whatever the host's catalog holds, named by the host's own
 * id ([DeviceBookIdentity]). This module never sees the host's book type: it
 * asks for the bytes behind an id and gets a `:reader-library`
 * [PublicationSource] or the reason there are none.
 *
 * A host's tests substitute
 * `com.cedagova.reader.account.testing.FakeDevicePublicationSources` from this
 * module's test fixtures.
 */
public interface DevicePublicationSources {

    /**
     * The bytes behind the device book [deviceBookId], or why they cannot be
     * read right now. A book the host no longer has is
     * [PublicationSourceProblem.UNREACHABLE].
     */
    public fun sourceFor(deviceBookId: String): PublicationSourceResult
}

/** Why a device book cannot be turned into a [PublicationSource] right now. */
public enum class PublicationSourceProblem {
    /** No source of this book is reachable: the file moved, or the grant is gone. */
    UNREACHABLE,

    /**
     * The provider will not say how long the file is, and nothing here could
     * measure it either.
     *
     * It matters because the size is not cosmetic: it is what the policy cap is
     * checked against and what `Upload-Length` declares to the storage
     * provider, so a guess would either refuse a book that fits or start a
     * transfer that can never complete.
     */
    SIZE_UNKNOWN,
}

/** A device book resolved to bytes, or the reason it could not be. */
public sealed interface PublicationSourceResult {
    public data class Ready(val source: PublicationSource) : PublicationSourceResult
    public data class Unavailable(val problem: PublicationSourceProblem) : PublicationSourceResult
}
