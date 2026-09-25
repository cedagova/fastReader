package com.cedagova.reader.account.library

/**
 * How the host names a device book by its content (#200, A197-F003): the
 * other host seam of [AccountImports].
 *
 * An import is keyed by the file's content SHA-256 (the account's identity for
 * a book, and what the stored import record carries), while everything the
 * host shows — the row, its progress in [AccountImportsState.byDeviceBookId] —
 * is keyed by the host's own device-book id. This is the one mapping between
 * the two. It must round-trip: `contentSha256(deviceBookId(hex)) == hex` for a
 * lowercase-hex digest.
 *
 * FastReader's device-book id is the digest itself under a `sha256:` prefix
 * (its catalog's identity), so its implementation adds and strips that prefix.
 */
public interface DeviceBookIdentity {

    /** The host's id for the device book whose content has this lowercase-hex SHA-256. */
    public fun deviceBookId(contentSha256: String): String

    /** The lowercase-hex content SHA-256 of the device book the host calls [deviceBookId]. */
    public fun contentSha256(deviceBookId: String): String
}
