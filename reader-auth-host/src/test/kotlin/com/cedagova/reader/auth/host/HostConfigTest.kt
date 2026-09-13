package com.cedagova.reader.auth.host

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cedagova.reader.auth.ReaderAuthConfig
import com.cedagova.reader.auth.ReaderAuthException
import com.cedagova.reader.auth.ReaderAuthPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The host's configuration obligation (CONTRACT.md, "Host requirements"):
 * the three service values reach the module as one [ReaderAuthConfig], the
 * build never bakes a value into the tree, and a build without them refuses
 * at runtime instead of calling anything. The hosted CI runner has no
 * `local.properties`, so there `BuildConfig` is blank and this test runs the
 * not-configured branch for real.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = HostApplication::class)
class HostConfigTest {

    private val app: HostApplication = ApplicationProvider.getApplicationContext<Application>() as HostApplication

    @Test
    fun `the build config values become the module configuration with the android client identity`() {
        assertEquals(BuildConfig.READER_SUPABASE_URL, app.config.supabaseUrl)
        assertEquals(BuildConfig.READER_SUPABASE_PUBLISHABLE_KEY, app.config.publishableKey)
        assertEquals(BuildConfig.READER_API_BASE_URL, app.config.readerApiBaseUrl)
        assertEquals(ReaderAuthPolicy.CLIENT_ID, app.config.clientId)
        assertEquals(ReaderAuthPolicy.CLIENT_VERSION, app.config.clientVersion)
    }

    @Test
    fun `a build without the values is not configured and creates no client`() {
        val blank = ReaderAuthConfig(supabaseUrl = "", publishableKey = "", readerApiBaseUrl = "")
        assertFalse(blank.isConfigured)
        try {
            com.cedagova.reader.auth.ReaderAuthClient.create(app, blank)
            error("expected NotConfigured")
        } catch (e: ReaderAuthException.NotConfigured) {
            // The contract's refusal: nothing was called.
        }
        if (!app.config.isConfigured) assertNull(app.auth)
    }

    @Test
    fun `no service value is committed to the tree`() {
        val tracked = listOf(
            "reader-auth-host/build.gradle.kts",
            "reader-auth-host/src/main/AndroidManifest.xml",
            "reader-auth-host/src/main/res/values/strings.xml",
        ).map { repositoryFile(it).readText() }
        listOf("supabase.co", "sb_publishable_", "api.chunipers.com").forEach { fragment ->
            assertTrue("a service value ($fragment) is committed", tracked.none { it.contains(fragment) })
        }
        val gitignore = repositoryFile(".gitignore").readText()
        assertTrue("local.properties must stay untracked", gitignore.lines().any { it.trim() == "local.properties" })
    }
}
