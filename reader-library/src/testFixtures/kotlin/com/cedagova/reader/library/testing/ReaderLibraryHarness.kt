package com.cedagova.reader.library.testing

import com.cedagova.reader.auth.testing.FakeClock
import com.cedagova.reader.auth.testing.FakeServers
import com.cedagova.reader.auth.testing.ReaderAuthHarness
import com.cedagova.reader.auth.testing.RecordingWaiter
import com.cedagova.reader.auth.testing.TestSession
import com.cedagova.reader.library.ReaderLibraryClient
import com.cedagova.reader.library.downloads.AssetDownloadClient
import com.cedagova.reader.library.imports.PublicationTransferClient
import io.ktor.client.engine.HttpClientEngine
import kotlin.time.Duration.Companion.hours

/**
 * One signed-in [ReaderLibraryClient] whose transport is [servers] (#199,
 * A197-F002): the real `:reader-auth` client — real bearer, single-flight
 * refresh and 401/403/429/502 policy — under this module's operations, over
 * the one mock server of `:reader-auth`'s test fixtures.
 *
 * There is deliberately no double of the reader-api client here: a test of
 * this module's operations, or of a host's code on them, proves that they
 * inherit the call policy rather than restate it, which only the real client
 * can show. [session] is the stored session the client starts with — a
 * signed-in hour by default; null for a test that proves a signed-out client
 * never calls out.
 */
public class ReaderLibraryHarness(
    public val servers: FakeServers = FakeServers(),
    public val clock: FakeClock = FakeClock(),
    public val waiter: RecordingWaiter = RecordingWaiter(),
    session: TestSession? = TestSession(expiresAt = clock.now + 1.hours),
) {
    /** The auth client underneath, for a test that needs it directly. */
    public val auth: ReaderAuthHarness = ReaderAuthHarness(
        servers = servers,
        clock = clock,
        waiter = waiter,
        session = session,
    )

    /** The session the store holds now (null once a sign-out or a rejected token cleared it); settable. */
    public var storedSession: TestSession?
        get() = auth.storedSession
        set(value) {
            auth.storedSession = value
        }

    /** The module's operations over a new ready client (a process start; see [ReaderAuthHarness.client]). */
    public suspend fun operations(): ReaderLibraryClient = ReaderLibraryClient(auth.client().api)

    /** Releases the provider SDK's client. */
    public suspend fun close() {
        auth.close()
    }
}

/**
 * The production [AssetDownloadClient] over [engine] instead of OkHttp — same
 * code path, same timeouts and redirect rules — for a test that plays the
 * storage provider. It replaces `AssetDownloadClient.createForTests` (#199).
 */
public fun assetDownloadClientOver(engine: HttpClientEngine): AssetDownloadClient =
    AssetDownloadClient(AssetDownloadClient.httpClient(engine))

/**
 * The production [PublicationTransferClient] over [engine] instead of OkHttp,
 * for a test that plays the storage provider. It replaces
 * `PublicationTransferClient.createForTests` (#199).
 */
public fun publicationTransferClientOver(engine: HttpClientEngine): PublicationTransferClient =
    PublicationTransferClient(PublicationTransferClient.httpClient(engine))
