package com.cedagova.reader.auth

import android.Manifest

/**
 * Manifest-level facts of the Reader authentication library.
 *
 * The runtime entry point is [ReaderAuthClient]; the rules it implements are
 * written in `CONTRACT.md` beside this module's README.
 */
public object ReaderAuth {

    /**
     * The permission this library declares in its own manifest and therefore
     * every host receives through manifest merging. A host can read it back
     * with `checkSelfPermission` to confirm the merge happened; it is a normal
     * install-time permission, so a granted result is what a correctly merged
     * manifest yields and a denied one means the library manifest was dropped.
     */
    public const val REQUIRED_PERMISSION: String = Manifest.permission.INTERNET
}
