package com.omama.stationalarm.ui.viewmodel

import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * The two pure helpers behind the search/geocode analytics events. Their output feeds dashboards, so the labels
 * are a contract: renaming one silently splits a chart in two.
 */
class GeocodeAnalyticsHelpersTest {

    private fun http(code: Int) =
        HttpException(Response.error<Any>(code, "".toResponseBody("text/plain".toMediaTypeOrNull())))

    // ── geocodeFailureBand ───────────────────────────────────────────────────

    @Test
    fun timeout_isNotLumpedInWithOtherNetworkFailures() {
        // SocketTimeoutException is an IOException; the order of the branches decides which label wins.
        assertEquals("timeout", geocodeFailureBand(SocketTimeoutException("read timed out")))
    }

    @Test
    fun otherIoFailures_areNetwork() {
        assertEquals("network", geocodeFailureBand(IOException("connect failed")))
        assertEquals("network", geocodeFailureBand(UnknownHostException("no dns")))
        assertEquals("network", geocodeFailureBand(ConnectException("refused")))
    }

    @Test
    fun rateLimit_hasItsOwnLabel_soQuotaExhaustionIsVisible() {
        assertEquals("http_429", geocodeFailureBand(http(429)))
    }

    @Test
    fun serverErrors_areGroupedAs5xx() {
        assertEquals("http_5xx", geocodeFailureBand(http(500)))
        assertEquals("http_5xx", geocodeFailureBand(http(502)))
        assertEquals("http_5xx", geocodeFailureBand(http(599)))
    }

    @Test
    fun otherClientErrors_areGroupedAs4xx() {
        assertEquals("http_4xx", geocodeFailureBand(http(400)))
        assertEquals("http_4xx", geocodeFailureBand(http(404)))
        assertEquals("http_4xx", geocodeFailureBand(http(418)))
    }

    @Test
    fun unknownThrowables_areOther() {
        assertEquals("other", geocodeFailureBand(RuntimeException("???")))
        assertEquals("other", geocodeFailureBand(IllegalStateException()))
    }

    @Test
    fun band_neverContainsTheExceptionMessage() {
        val secret = "q=Secret Street 12"
        listOf<Throwable>(IOException(secret), SocketTimeoutException(secret), RuntimeException(secret)).forEach {
            val band = geocodeFailureBand(it)
            assertFalse("band leaked the message: $band", band.contains("secret", ignoreCase = true))
        }
    }

    // ── resultCountBand ──────────────────────────────────────────────────────

    @Test
    fun resultCountBand_matchesTheDocumentedBands() {
        assertEquals("0", resultCountBand(0))
        assertEquals("0", resultCountBand(-1))
        assertEquals("1", resultCountBand(1))
        assertEquals("2-5", resultCountBand(2))
        assertEquals("2-5", resultCountBand(5))
        assertEquals("6+", resultCountBand(6))
        assertEquals("6+", resultCountBand(8))
    }
}
