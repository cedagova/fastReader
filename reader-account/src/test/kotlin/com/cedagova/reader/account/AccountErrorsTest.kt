package com.cedagova.reader.account

import com.cedagova.reader.account.library.BookDownloadState
import com.cedagova.reader.account.library.BookImportState
import com.cedagova.reader.account.library.DownloadProblem
import com.cedagova.reader.account.library.ImportProblem
import com.cedagova.reader.auth.ReaderAuthException
import java.io.IOException
import kotlin.time.Duration.Companion.seconds
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The one mapping of `ReaderAuthException` (#200, A197-F003), branch by branch,
 * for the two row surfaces projected from it.
 *
 * Before #200 the download and import rows each read the exception with a
 * `when` of their own. The expected values below are exactly what those two
 * readings produced, so this table is the proof that centralising the
 * classification changed no row a reader sees. (`ReaderAccountControllerTest`
 * holds the sign-in surface's own outcome for every branch.)
 */
class AccountErrorsTest {

    private val offline = ReaderAuthException.NetworkUnavailable(IOException("offline"))
    private val rateLimited = ReaderAuthException.TryLater(429, "over_request_rate_limit", 30.seconds, "r1")
    private val unavailable = ReaderAuthException.TryLater(503, null, null)
    private val sessionGone = ReaderAuthException.SignedOut("auth.invalid_token", "r2")
    private val forbidden = ReaderAuthException.Forbidden("forbidden", "r3")
    private val forbiddenBare = ReaderAuthException.Forbidden(null, null)
    private val notFound = ReaderAuthException.ApiError(404, "not_found", "r4", "gone")
    private val serverError = ReaderAuthException.ApiError(500, null, null, "boom")
    private val rejected = ReaderAuthException.ProviderRejected(422, "weak_password", "too short")
    private val notConfigured = ReaderAuthException.NotConfigured()
    private val mismatch = ReaderAuthException.ConfigurationMismatch("client.applicationId is x")
    private val signInOff = ReaderAuthException.SignInUnavailable("configuration_stale", retryable = true)
    private val storage = ReaderAuthException.StorageUnavailable(IOException("keystore"))
    private val unreadable = ReaderAuthException.UnexpectedResponse(IllegalArgumentException("<html>"))

    @Test
    fun `a refused download grant reads as the row always showed it`() {
        val expected = mapOf(
            offline to BookDownloadState.Refused(DownloadProblem.OFFLINE, retryable = true),
            rateLimited to BookDownloadState.Refused(
                DownloadProblem.REFUSED,
                code = "over_request_rate_limit",
                requestId = "r1",
                retryable = true,
            ),
            unavailable to BookDownloadState.Refused(DownloadProblem.REFUSED, code = "HTTP 503", retryable = true),
            forbidden to BookDownloadState.Refused(DownloadProblem.REFUSED, code = "forbidden", requestId = "r3"),
            forbiddenBare to BookDownloadState.Refused(DownloadProblem.REFUSED, code = "403"),
            sessionGone to BookDownloadState.Refused(
                DownloadProblem.REFUSED,
                code = "auth.invalid_token",
                requestId = "r2",
            ),
            notFound to BookDownloadState.Refused(DownloadProblem.REFUSED, code = "not_found", requestId = "r4"),
            serverError to BookDownloadState.Refused(DownloadProblem.REFUSED, code = "HTTP 500"),
            // Every other branch was one catch-all REFUSED with nothing to quote.
            rejected to BookDownloadState.Refused(DownloadProblem.REFUSED),
            notConfigured to BookDownloadState.Refused(DownloadProblem.REFUSED),
            mismatch to BookDownloadState.Refused(DownloadProblem.REFUSED),
            signInOff to BookDownloadState.Refused(DownloadProblem.REFUSED),
            storage to BookDownloadState.Refused(DownloadProblem.REFUSED),
            unreadable to BookDownloadState.Refused(DownloadProblem.REFUSED),
        )

        expected.forEach { (error, row) ->
            assertEquals(error::class.simpleName, row, error.toOutcome().toDownloadRefusal())
        }
    }

    @Test
    fun `a failed import call reads as the row always showed it`() {
        val expected = mapOf(
            offline to BookImportState.Refused(ImportProblem.NeedsConnection),
            sessionGone to
                BookImportState.Refused(ImportProblem.Api(null), code = "auth.invalid_token", requestId = "r2"),
            forbidden to BookImportState.Refused(ImportProblem.Api(null), code = "forbidden", requestId = "r3"),
            rateLimited to BookImportState.Refused(
                ImportProblem.Api(429),
                code = "over_request_rate_limit",
                requestId = "r1",
            ),
            unavailable to BookImportState.Refused(ImportProblem.Api(503)),
            notFound to BookImportState.Refused(ImportProblem.Api(404), code = "not_found", requestId = "r4"),
            serverError to BookImportState.Refused(ImportProblem.Api(500)),
            rejected to BookImportState.Refused(ImportProblem.Api(422), code = "weak_password"),
            notConfigured to BookImportState.Refused(ImportProblem.Api(null), retryable = false),
            mismatch to BookImportState.Refused(ImportProblem.Api(null), retryable = false),
            signInOff to BookImportState.Refused(ImportProblem.Api(null), retryable = false),
            storage to BookImportState.Refused(ImportProblem.Api(null), code = "storage_unavailable"),
            unreadable to BookImportState.Refused(ImportProblem.Api(null), code = "unexpected_response"),
        )

        expected.forEach { (error, row) ->
            assertEquals(error::class.simpleName, row, error.toOutcome().toImportRefusal())
        }
    }
}
