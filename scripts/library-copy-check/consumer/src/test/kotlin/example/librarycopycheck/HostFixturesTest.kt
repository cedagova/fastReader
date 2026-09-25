package example.librarycopycheck

import com.cedagova.reader.account.ReaderAccountState
import com.cedagova.reader.account.testing.FakeAccountCopyCatalog
import com.cedagova.reader.account.testing.FakeDevicePublicationSources
import com.cedagova.reader.account.testing.PrefixedBookIdentity
import com.cedagova.reader.account.testing.RecordingResumeOfferRecords
import com.cedagova.reader.auth.ReaderSessionState
import com.cedagova.reader.auth.testing.CAPABILITIES
import com.cedagova.reader.auth.testing.CAPABILITIES_BODY
import com.cedagova.reader.auth.testing.FakeReaderAuthOperations
import com.cedagova.reader.auth.testing.json
import com.cedagova.reader.engine.content.BookContentResult
import com.cedagova.reader.engine.content.ContentFixtures
import com.cedagova.reader.engine.content.EpubContentPipeline
import com.cedagova.reader.library.testing.FakeReaderLibraryGateway
import com.cedagova.reader.library.testing.ReaderLibraryHarness
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The host side of the fixtures check (#199): a host's unit tests reach the
 * copied libraries' test fixtures — the scripted doubles and the real client
 * over the one mock server — with nothing but the copy set and a
 * testFixtures dependency.
 */
class HostFixturesTest {

    @Test
    fun `the scripted doubles stand in for the libraries`() = runTest {
        val auth = FakeReaderAuthOperations()
        auth.signInWithPassword("reader@example.test", "not-a-password")
        assertTrue(auth.currentState() is ReaderSessionState.SignedIn)

        val library = FakeReaderLibraryGateway()
        library.library()
        assertEquals(listOf("library()"), library.calls)
    }

    @Test
    fun `the real client runs over the one mock server`() = runTest {
        val harness = ReaderLibraryHarness()
        harness.servers.on(CAPABILITIES) { json(CAPABILITIES_BODY) }
        try {
            libraryOperations(harness.auth.client())
            harness.auth.client().capabilities()
        } finally {
            harness.close()
        }
        assertEquals("access-1", harness.servers.requestsTo("/v1/reader/capabilities").single().bearer)
    }

    @Test
    fun `the engine parses its own fixture book`() = runTest {
        val result = EpubContentPipeline().parse(ContentFixtures.source(ContentFixtures.englishNovel()))
        assertTrue((result as BookContentResult.Parsed).content.tokens.isNotEmpty())
    }

    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun `the account graph assembles over the account fixtures`() = runTest(UnconfinedTestDispatcher()) {
        val catalog = FakeAccountCopyCatalog()
        val graph = accountGraph(
            auth = null,
            filesDir = temporary.root,
            catalog = catalog,
            books = FakeDevicePublicationSources(),
            identity = PrefixedBookIdentity,
            resumeOffers = { RecordingResumeOfferRecords() },
            scope = backgroundScope,
        )
        graph.onForeground()

        assertEquals(ReaderAccountState.NotConfigured(emptyList()), graph.account.state.value)
        assertEquals(listOf("reconcile"), catalog.calls)
    }
}
