package com.cedagova.fastreader

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.cedagova.fastreader.library.LibraryGraph
import com.cedagova.fastreader.library.ScanTrigger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Application entry point.
 *
 * Owns the library graph and rescans added folders every time the app comes to
 * the foreground, so a book copied into an added folder shows up without the
 * reader doing anything (REQ-002).
 */
class FastReaderApplication : Application() {

    private val applicationScope = CoroutineScope(SupervisorJob())

    lateinit var library: LibraryGraph
        private set

    override fun onCreate() {
        super.onCreate()
        library = LibraryGraph(this, applicationScope)
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    library.repository.requestRescan(ScanTrigger.APP_OPEN)
                }
            },
        )
    }

    override fun onTerminate() {
        applicationScope.cancel()
        super.onTerminate()
    }
}
