package com.cedagova.fastreader.account

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cedagova.fastreader.AppGraph
import com.cedagova.fastreader.BuildConfig
import com.cedagova.fastreader.app.repositoryFile
import com.cedagova.fastreader.appGraph
import com.cedagova.reader.account.ReaderAccountState
import com.cedagova.reader.auth.ReaderAuthClient
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
 * FastReader's configuration obligation as the library's host
 * (`reader-auth/CONTRACT.md`, "Host requirements", item 1), moved from the
 * retired `:reader-auth-host` in #100: the three stage values reach the
 * module as one [ReaderAuthConfig] with the `reader-android` identity, the
 * build never bakes a value into the tree, and a build without them refuses
 * at runtime instead of calling anything. The hosted CI runner has no
 * `local.properties`, so there `BuildConfig` is blank and this test runs the
 * not-configured branch for real; on a developer machine with the values it
 * runs the configured one. Neither changes the result.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ReaderAccountConfigTest {

    private val graph: AppGraph = ApplicationProvider.getApplicationContext<Application>().appGraph

    @Test
    fun `the build config values become the module configuration with the android client identity`() {
        val config = graph.readerAccountConfig

        assertEquals(BuildConfig.READER_SUPABASE_URL, config.supabaseUrl)
        assertEquals(BuildConfig.READER_SUPABASE_PUBLISHABLE_KEY, config.publishableKey)
        assertEquals(BuildConfig.READER_API_BASE_URL, config.readerApiBaseUrl)
        assertEquals(ReaderAuthPolicy.CLIENT_ID, config.clientId)
        assertEquals("reader-android", config.clientId)
        assertEquals(ReaderAuthPolicy.CLIENT_VERSION, config.clientVersion)
        assertEquals("1.0.0", config.clientVersion)
    }

    @Test
    fun `a build without the values is not configured, creates no client, and names what is missing`() {
        val blank = ReaderAuthConfig(supabaseUrl = "", publishableKey = "", readerApiBaseUrl = "")

        assertFalse(blank.isConfigured)
        assertEquals(ReaderAccountConfiguration.PROPERTY_KEYS, ReaderAccountConfiguration.missingValues(blank))
        try {
            ReaderAuthClient.create(ApplicationProvider.getApplicationContext(), blank)
            error("expected NotConfigured")
        } catch (e: ReaderAuthException.NotConfigured) {
            // The contract's refusal: nothing was called.
        }
        if (!graph.readerAccountConfig.isConfigured) {
            assertNull(graph.readerAuth)
            assertTrue(graph.readerAccount.account.state.value is ReaderAccountState.NotConfigured)
        }
    }

    @Test
    fun `only the blank values are named as missing`() {
        val partial =
            ReaderAuthConfig(supabaseUrl = "https://x.test", publishableKey = "", readerApiBaseUrl = "https://y.test")

        assertFalse(partial.isConfigured)
        assertEquals(listOf("reader.supabasePublishableKey"), ReaderAccountConfiguration.missingValues(partial))
        assertEquals(emptyList<String>(), ReaderAccountConfiguration.missingValues(ReaderAuthConfig("a", "b", "c")))
    }

    /**
     * The values are public, and they are still never committed: nothing
     * tracked under the app carries a fragment of a stage value, and the file
     * that holds them stays ignored.
     */
    @Test
    fun `no service value is committed to the tree`() {
        val tracked = listOf(
            "app/build.gradle.kts",
            "app/src/main/AndroidManifest.xml",
            "app/src/debug/AndroidManifest.xml",
            "app/src/main/res/values/strings.xml",
            "app/src/main/res/values-es/strings.xml",
        ).map { repositoryFile(it).readText() }
        listOf("supabase.co", "sb_publishable_", "api.chunipers.com", "chunipers.com").forEach { fragment ->
            assertTrue("a service value ($fragment) is committed", tracked.none { it.contains(fragment) })
        }
        val gitignore = repositoryFile(".gitignore").readText()
        assertTrue("local.properties must stay untracked", gitignore.lines().any { it.trim() == "local.properties" })
    }
}
