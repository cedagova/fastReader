package com.cedagova.reader.auth

import com.cedagova.reader.auth.session.SessionCipher
import com.cedagova.reader.auth.testing.FakeServers
import com.cedagova.reader.auth.testing.session
import java.io.File

// This module's own test support. The shared pieces — the one mock server
// (FakeServers), the settable clock and waiter, the in-memory session store and
// the documents the servers answer with — are the module's test fixtures in
// src/testFixtures (package com.cedagova.reader.auth.testing, #199), which
// :reader-library's tests and a host's use too.

/** XORs every byte; reversible, and guarantees no plaintext byte survives unchanged. */
class FakeCipher(private val key: Byte = 0x5A, var failDecrypt: Boolean = false) : SessionCipher {
    override fun encrypt(plaintext: ByteArray): ByteArray = ByteArray(plaintext.size) {
        (
            plaintext[it].toInt() xor
                key.toInt()
            ).toByte()
    }
    override fun decrypt(blob: ByteArray): ByteArray {
        if (failDecrypt) throw java.security.GeneralSecurityException("key lost")
        return encrypt(blob)
    }
}

const val NO_SELECTOR_PRE_AUTH =
    """{"schemaVersion":"reader.pre-auth.v1",""" +
        """"compatibility":{"status":"client_unknown","requestedVersion":"1.0.0",""" +
        """"minimumVersion":null,"supportedMajor":null},""" +
        """"accountEntry":{"availability":"unavailable","reason":"client_selection_missing","retryable":false},""" +
        """"configuration":null}"""

/**
 * A protected route that is *not* one of the three the contract names, used to
 * pin the generic `get`/`put`/`post` verbs a host builds further calls on. The
 * path is deliberately not a real reader-api route: this module knows the
 * policy, not the route list.
 */
const val GENERIC_PATH = "/v1/reader/generic"
const val GENERIC_POST = "POST /v1/reader/generic"

/** The repository root, for tests that read tracked documents. */
fun repositoryRoot(): File {
    var candidate: File? = File("").absoluteFile
    while (candidate != null) {
        if (File(candidate, "settings.gradle.kts").isFile) return candidate
        candidate = candidate.parentFile
    }
    error("no settings.gradle.kts above ${File("").absoluteFile}")
}
