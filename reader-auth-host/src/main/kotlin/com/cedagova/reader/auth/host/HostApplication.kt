package com.cedagova.reader.auth.host

import android.app.Application
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.cedagova.reader.auth.ReaderAuthClient
import com.cedagova.reader.auth.ReaderAuthConfig
import com.cedagova.reader.auth.ReaderAuthException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

internal const val TAG = "ReaderAuthHost"

/**
 * Owns the one [ReaderAuthClient] for the process and the foreground hook
 * the contract asks every host for (CONTRACT.md, "Host requirements"): on
 * every return to the foreground the module gets a chance to refresh a
 * session that is inside the margin. There is no timer anywhere.
 */
class HostApplication : Application() {

    /** The three service values as built in; blank when the build had none. */
    val config: ReaderAuthConfig = ReaderAuthConfig(
        supabaseUrl = BuildConfig.READER_SUPABASE_URL,
        publishableKey = BuildConfig.READER_SUPABASE_PUBLISHABLE_KEY,
        readerApiBaseUrl = BuildConfig.READER_API_BASE_URL,
    )

    /** `null` when the build is not configured; the screen then says so and nothing is called. */
    val auth: ReaderAuthClient? by lazy {
        if (config.isConfigured) ReaderAuthClient.create(this, config) else null
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "package=$packageName configured=${config.isConfigured}")
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                val client = auth ?: return
                scope.launch {
                    try {
                        val state = client.onForeground()
                        Log.i(TAG, "foreground state=${state.javaClass.simpleName}")
                    } catch (e: ReaderAuthException) {
                        Log.i(TAG, "foreground refresh failed: ${e.message}")
                    }
                }
            }
        })
    }
}
