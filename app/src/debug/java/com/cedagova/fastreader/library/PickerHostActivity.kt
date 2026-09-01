package com.cedagova.fastreader.library

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Debug-only activity that opens the real system folder picker and hands the
 * resulting tree URI back to the on-device Storage Access Framework test.
 *
 * It lives in the debug source set so the pick is made by the app package (and
 * so the grant lands on the app), and it is not part of a release build.
 */
class PickerHostActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
                )
                initialUri?.let { putExtra("android.provider.extra.INITIAL_URI", it) }
            }
            startActivityForResult(intent, REQUEST_TREE)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_TREE) {
            results.offer(Result(resultCode, data?.data))
            finish()
        }
    }

    data class Result(val resultCode: Int, val treeUri: Uri?)

    companion object {
        private const val REQUEST_TREE = 41

        private val results = ArrayBlockingQueue<Result>(1)

        /** Folder the picker should open in, set by the test before launching. */
        var initialUri: Uri? = null

        fun reset() {
            results.clear()
        }

        fun awaitResult(timeoutSeconds: Long): Result? = results.poll(timeoutSeconds, TimeUnit.SECONDS)
    }
}
