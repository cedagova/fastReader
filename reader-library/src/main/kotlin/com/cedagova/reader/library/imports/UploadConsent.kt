package com.cedagova.reader.library.imports

/**
 * The owner's explicit decision that this file's bytes may leave the device.
 *
 * It has exactly one member and carries no information, which is the whole
 * point: it is a *token*, not a flag. Because every function that can admit an
 * import takes one and none of them defaults it, there is no way to write a
 * call that uploads a book without naming consent at the call site — the
 * compiler enforces REQ-505's "requires an explicit consent before anything is
 * sent" rather than a runtime `if`.
 *
 * There is deliberately no `DENIED`. Refusing is not a value you pass to an
 * import; it is not calling one.
 *
 * The runtime half of the same rule lives in
 * `ReaderLibraryClient.admitImport`, which refuses to put a request on the wire
 * whose `upload_consent` is not `true`.
 */
enum class UploadConsent {
    /** The owner said yes, to this file, in this account, on purpose. */
    GRANTED,
}
