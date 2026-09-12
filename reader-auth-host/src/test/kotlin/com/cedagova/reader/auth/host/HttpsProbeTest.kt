package com.cedagova.reader.auth.host

import java.net.URL
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** The screen line for each outcome, and the one input the probe refuses. */
class HttpsProbeTest {

    private val url = URL("https://www.gstatic.com/generate_204")

    @Test
    fun `a response is described by its status and host`() {
        assertEquals("HTTPS 204 from www.gstatic.com", ProbeResult.Response(204).describe(url))
    }

    @Test
    fun `a failure is described by its exception type and message`() {
        assertEquals(
            "Failed: UnknownHostException — Unable to resolve host",
            ProbeResult.Failure("UnknownHostException", "Unable to resolve host").describe(url),
        )
        assertEquals(
            "Failed: SocketTimeoutException",
            ProbeResult.Failure("SocketTimeoutException", "").describe(url),
        )
    }

    @Test
    fun `the probe refuses a non-https target`() {
        assertThrows(IllegalArgumentException::class.java) {
            HttpsProbe.get(URL("http://10.0.2.2/"))
        }
    }
}
