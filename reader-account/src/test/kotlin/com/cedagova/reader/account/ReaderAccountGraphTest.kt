package com.cedagova.reader.account

import com.cedagova.reader.account.library.AccountCopyStore
import com.cedagova.reader.account.testing.FakeAccountCopyCatalog
import com.cedagova.reader.account.testing.FakeDevicePublicationSources
import com.cedagova.reader.account.testing.PrefixedBookIdentity
import com.cedagova.reader.account.testing.RecordingResumeOfferRecords
import com.cedagova.reader.auth.testing.FakeReaderAuthOperations
import com.cedagova.reader.library.sync.FileAccountLibraryStores
import com.cedagova.reader.library.testing.FakeAssetDownloadGateway
import com.cedagova.reader.library.testing.FakePublicationImportGateway
import com.cedagova.reader.library.testing.FakeReaderLibraryGateway
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The one assembly call (#200, A197-F003) and its foreground hooks, which
 * the host's `Application` used to wire by hand: the session refresh on
 * every foreground, the copy sweep on the first one only, and nothing on the
 * network while nobody is signed in.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReaderAccountGraphTest {

    @get:Rule
    val temporary = TemporaryFolder()

    private val auth = FakeReaderAuthOperations()
    private val library = FakeReaderLibraryGateway()
    private val imports = FakePublicationImportGateway()
    private val catalog = FakeAccountCopyCatalog()

    private fun TestScope.graph(gateways: ReaderAccountGateways?) = ReaderAccountGraph(
        gateways = gateways,
        missingValues = listOf("reader.apiBaseUrl"),
        filesDir = temporary.root,
        copyCatalog = catalog,
        publicationSources = FakeDevicePublicationSources(),
        bookIdentity = PrefixedBookIdentity,
        resumeOffers = { RecordingResumeOfferRecords() },
        scope = backgroundScope,
    )

    private fun gateways() = ReaderAccountGateways(
        auth = auth,
        library = library,
        imports = imports,
        downloads = FakeAssetDownloadGateway(ByteArray(0)),
    )

    @Test
    fun `every foreground refreshes the session and only the first sweeps the copies`() =
        runTest(UnconfinedTestDispatcher()) {
            val graph = graph(gateways())
            advanceUntilIdle()

            graph.onForeground()
            advanceUntilIdle()
            graph.onBackground()
            graph.onForeground()
            advanceUntilIdle()

            assertEquals(
                "host requirement 5, once per foreground",
                listOf("onForeground()", "onForeground()"),
                auth.calls,
            )
            assertEquals("the start-up sweep runs once per process", listOf("reconcile"), catalog.calls)
            assertEquals("signed out, the account library is never asked", emptyList<String>(), library.calls)
            assertEquals("signed out, no import call is made", emptyList<String>(), imports.calls)
        }

    @Test
    fun `the graph never calls upsertProfile`() = runTest(UnconfinedTestDispatcher()) {
        val graph = graph(gateways())
        graph.onForeground()
        graph.account.signInWithPassword("reader@example.test", "password")
        advanceUntilIdle()
        graph.onForeground()
        graph.account.loadCapabilities()
        graph.account.signOut()
        advanceUntilIdle()

        assertTrue("${auth.calls}", auth.calls.none { it.startsWith("upsertProfile") })
    }

    @Test
    fun `with no gateways it is not configured and calls nothing`() = runTest(UnconfinedTestDispatcher()) {
        val graph = graph(gateways = null)
        advanceUntilIdle()

        graph.onForeground()
        advanceUntilIdle()

        assertEquals(ReaderAccountState.NotConfigured(listOf("reader.apiBaseUrl")), graph.account.state.value)
        assertEquals(emptyList<String>(), auth.calls)
        assertEquals("the local sweep still runs", listOf("reconcile"), catalog.calls)
    }

    /** No migration (#200): the account documents and copies stay where the first host kept them. */
    @Test
    fun `the stores live in the directories the first host used`() {
        assertEquals("account-library", FileAccountLibraryStores.DIRECTORY_NAME)
        assertEquals("account-copies", AccountCopyStore.DIRECTORY_NAME)
    }
}
