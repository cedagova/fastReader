package com.cedagova.reader.auth

import android.Manifest

/**
 * The entry point of the Reader authentication library.
 *
 * At this stage (#92) the module is a skeleton: it exists so that a host can
 * depend on it, receive its manifest, and prove the wiring on a device before
 * any authentication code is written. The sign-in contract and its
 * implementation land under #93 and grow from here.
 */
object ReaderAuth {

    /**
     * The permission this library declares in its own manifest and therefore
     * every host receives through manifest merging. A host can read it back
     * with `checkSelfPermission` to confirm the merge happened; it is a normal
     * install-time permission, so a granted result is what a correctly merged
     * manifest yields and a denied one means the library manifest was dropped.
     */
    const val REQUIRED_PERMISSION: String = Manifest.permission.INTERNET
}
