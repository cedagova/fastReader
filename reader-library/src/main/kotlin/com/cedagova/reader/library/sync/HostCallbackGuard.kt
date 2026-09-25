package com.cedagova.reader.library.sync

/**
 * The engine's guard against a host callback that calls back into it (#149).
 *
 * [AccountSyncEngine] runs a host's `transform` ([AccountHostRecords]) through
 * [hostCallback], and every public entry point calls [checkNotInHostCallback]
 * first, so such a call fails instead of waiting for ever on the lock it is
 * already inside. One guard per engine.
 */
internal class HostCallbackGuard {

    /**
     * True on the thread that is running a host's `transform`, for as long as it
     * runs (#149).
     *
     * A transform is a plain function called under the engine's lock, which is not
     * re-entrant, so anything it does that waits on the engine — a `runBlocking`
     * around one of the suspend calls here — would wait for ever. The transform
     * cannot suspend, so it runs start to finish on the thread that set this,
     * and every public entry point checks it and fails instead of hanging.
     */
    private val inHostCallback = ThreadLocal.withInitial { false }

    inline fun <T> hostCallback(block: () -> T): T {
        inHostCallback.set(true)
        try {
            return block()
        } finally {
            inHostCallback.set(false)
        }
    }

    fun checkNotInHostCallback() {
        check(!inHostCallback.get()) {
            "an AccountHostRecords transform must not call back into AccountSyncEngine: it runs under the " +
                "engine's lock, which is not re-entrant"
        }
    }
}
