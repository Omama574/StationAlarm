package com.omama.stationalarm.network

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import retrofit2.HttpException
import java.io.IOException

/**
 * The real GeocodingClient + GeocodingService + Gson converter against a loopback server.
 *
 * Note for the report: GeocodingClient builds its OkHttpClient in the object's static initialiser, so whichever
 * thread touches it first pays for that (in the app: the main thread, from the Remote Config callback in
 * StationAlarmApplication). These tests touch it from the JUnit thread.
 */
class GeocodingClientScenarioTest {

    private lateinit var server: TinyHttpServer
    private lateinit var savedUrl: String

    private fun urlField() = GeocodingClient::class.java.getDeclaredField("workerBaseUrl").apply { isAccessible = true }
    private fun serviceField() = GeocodingClient::class.java.getDeclaredField("_geocodingService").apply { isAccessible = true }
    private fun currentWorkerUrl() = urlField().get(GeocodingClient) as String

    @Before
    fun setUp() {
        savedUrl = currentWorkerUrl()
        serviceField().set(GeocodingClient, null)
        server = TinyHttpServer()
        GeocodingClient.setWorkerUrl(server.baseUrl)
    }

    @After
    fun tearDown() {
        server.close()
        GeocodingClient.setWorkerUrl(savedUrl)
        serviceField().set(GeocodingClient, null)
    }

    private fun search(q: String = "pune", lang: String? = "en", lat: Double? = null, lon: Double? = null) =
        runBlocking { GeocodingClient.geocodingService.search(query = q, biasLat = lat, biasLon = lon, lang = lang) }

    private fun body(vararg rows: String) = """{"results":[${rows.joinToString(",")}]}"""
    private fun row(id: String = "liq_1", name: String = "X", addr: String = "A", lat: String = "18.5", lon: String = "73.8") =
        """{"id":"$id","name":"$name","formattedAddress":"$addr","lat":$lat,"lon":$lon}"""

    private fun failureOf(block: () -> Any?): Throwable {
        try { block() } catch (t: Throwable) { return t }
        fail("expected an exception")
        throw IllegalStateException()
    }

    // ── request shape ───────────────────────────────────────────────────────────

    @Test
    fun search_sendsOnlyQLimitAndLang_whenNoBias() {
        search("pune junction", lang = "en")
        val req = server.requests.single()
        assertEquals("/search", req.path)
        assertEquals(setOf("q", "limit", "lang"), req.paramNames)
        assertEquals("pune junction", req.param("q"))
        assertEquals("8", req.param("limit"))
        assertEquals("en", req.param("lang"))
    }

    @Test
    fun search_includesBiasOnlyWhenGiven_andOmitsNullLang() {
        search("pune", lang = null, lat = 18.52, lon = 73.85)
        val req = server.requests.single()
        assertEquals(setOf("q", "limit", "bias_lat", "bias_lon"), req.paramNames)
        assertEquals(18.52, req.param("bias_lat")!!.toDouble(), 0.0)
        assertEquals(73.85, req.param("bias_lon")!!.toDouble(), 0.0)
    }

    @Test
    fun reverse_sendsLatLonLang() {
        runBlocking { GeocodingClient.geocodingService.reverse(lat = 18.5204, lon = 73.8567, lang = "hi") }
        val req = server.requests.single()
        assertEquals("/reverse", req.path)
        assertEquals(setOf("lat", "lon", "lang"), req.paramNames)
        assertEquals(18.5204, req.param("lat")!!.toDouble(), 0.0)
        assertEquals("hi", req.param("lang"))
    }

    @Test
    fun noCountryRestrictionIsEverSent_app_is_global() {
        search("mumbai"); search("london", lang = null); search("x y", lat = 1.0, lon = 2.0)
        runBlocking { GeocodingClient.geocodingService.reverse(51.5, -0.12) }
        assertEquals(4, server.requests.size)
        for (r in server.requests) {
            assertFalse("${r.requestLine}", r.requestLine.contains("country", ignoreCase = true))
            assertTrue(r.paramNames.none { it.contains("country", ignoreCase = true) })
        }
    }

    @Test
    fun noApiKeyOrCredentialIsSent_inUrlOrHeaders() {
        search("pune", lat = 1.0, lon = 2.0)
        runBlocking { GeocodingClient.geocodingService.reverse(1.0, 2.0, "en") }
        val forbiddenParams = setOf("key", "api_key", "apikey", "token", "access_token", "auth", "secret")
        val forbiddenHeaders = setOf("authorization", "x-api-key", "api-key", "x-auth-token", "cookie", "proxy-authorization")
        for (r in server.requests) {
            assertTrue("params ${r.paramNames}", r.paramNames.none { it.lowercase() in forbiddenParams })
            assertTrue("headers ${r.headers.keys}", r.headers.keys.none { it in forbiddenHeaders })
            assertFalse(r.requestLine.contains("pk."))
        }
    }

    @Test
    fun queryTextSurvivesEncodingRoundTrip() {
        val tricky = listOf("दिल्ली स्टेशन", "São Paulo", "a&b=c#d?e", "100% sure", "plus+sign", "quote\"s", "tab\there", "😀 station", "x".repeat(2000))
        for (q in tricky) search(q)
        assertEquals(tricky, server.requests.map { it.param("q") })
        for (r in server.requests) {
            assertEquals("exactly q/limit/lang for ${r.requestLine.take(60)}", setOf("q", "limit", "lang"), r.paramNames)
            assertFalse(r.rawQuery.contains(' '))
            assertFalse(r.rawQuery.contains('#'))
        }
    }

    // ── dynamic worker URL ─────────────────────────────────────────────────────

    @Test
    fun urlWithoutTrailingSlash_isNormalised() {
        GeocodingClient.setWorkerUrl(server.baseUrl) // already no slash
        search()
        assertEquals("/search", server.requests.single().path)
    }

    @Test
    fun urlWithTrailingSlash_isLeftAlone() {
        GeocodingClient.setWorkerUrl(server.baseUrl + "/")
        assertTrue(currentWorkerUrl().endsWith("/") && !currentWorkerUrl().endsWith("//"))
        search()
        assertEquals("/search", server.requests.single().path)
    }

    @Test
    fun urlWithPathPrefix_keepsThePrefix() {
        GeocodingClient.setWorkerUrl(server.baseUrl + "/geo/v2")
        search()
        assertEquals("/geo/v2/search", server.requests.single().path)
    }

    @Test
    fun updatedRemoteConfigUrl_isPickedUpByTheNextCall() {
        val other = TinyHttpServer()
        try {
            search()
            assertEquals(1, server.requests.size)
            GeocodingClient.setWorkerUrl(other.baseUrl)
            search()
            assertEquals("old backend must not be hit again", 1, server.requests.size)
            assertEquals(1, other.requests.size)
        } finally {
            other.close()
        }
    }

    @Test
    fun sameUrlAgain_keepsTheServiceInstance_differentUrlRebuildsIt() {
        val first = GeocodingClient.geocodingService
        GeocodingClient.setWorkerUrl(server.baseUrl)
        assertSame(first, GeocodingClient.geocodingService)
        GeocodingClient.setWorkerUrl(server.baseUrl + "/")      // same URL, different spelling
        assertSame(first, GeocodingClient.geocodingService)
        GeocodingClient.setWorkerUrl(server.baseUrl + "/v2")
        assertNotSame(first, GeocodingClient.geocodingService)
    }

    @Test
    fun theCompiledInDefaultIsTheCloudflareWorkerOverHttps() {
        val default = GeocodingClient::class.java.getDeclaredField("DEFAULT_WORKER_URL").apply { isAccessible = true }.get(null) as String
        assertTrue(default, default.startsWith("https://") && default.endsWith("/") && default.contains("workers.dev"))
        assertFalse(default.contains("locationiq", ignoreCase = true))
    }

    @Ignore("BUG: setWorkerUrl(\"\") is not ignored: RetrofitClient.kt:43-49 setWorkerUrl() stores any string and nulls the cached service, so Retrofit.baseUrl() throws IllegalArgumentException on every later geocodingService access (search and reverse geocode dead until the next valid Remote Config value). StationAlarmApplication.kt:103 only guards isNotBlank().")
    @Test
    fun blankUrl_isIgnoredAndTheWorkingBackendKeepsServing() {
        // The only production caller guards isNotBlank(), but setWorkerUrl is public: "" must not wipe the URL.
        GeocodingClient.setWorkerUrl("")
        search()
        assertEquals(1, server.requests.size)
    }

    @Ignore("BUG: a scheme-less Remote Config URL is accepted: RetrofitClient.kt:43-49 setWorkerUrl() stores any string and nulls the cached service, so Retrofit.baseUrl() throws IllegalArgumentException on every later geocodingService access (search and reverse geocode dead until the next valid Remote Config value). StationAlarmApplication.kt:103 only guards isNotBlank().")
    @Test
    fun invalidUrl_isRejectedAndTheWorkingBackendKeepsServing() {
        GeocodingClient.setWorkerUrl("stationalarm-geo.example.dev") // typo: scheme missing
        search()
        assertEquals(1, server.requests.size)
    }

    @Ignore("BUG: surrounding whitespace is not trimmed (becomes \"Invalid URL port\"): RetrofitClient.kt:43-49 setWorkerUrl() stores any string and nulls the cached service, so Retrofit.baseUrl() throws IllegalArgumentException on every later geocodingService access (search and reverse geocode dead until the next valid Remote Config value). StationAlarmApplication.kt:103 only guards isNotBlank().")
    @Test
    fun urlWithSurroundingWhitespace_isTrimmed() {
        GeocodingClient.setWorkerUrl("  " + server.baseUrl + "  ")
        search()
        assertEquals("/search", server.requests.single().path)
    }

    // ── responses and failures ────────────────────────────────────────────────

    @Test
    fun fullResponse_isParsed() {
        server.reply = { TinyHttpServer.Reply(body = body(row("liq_1", "Mumbai Central", "Tardeo, Mumbai", "18.969", "72.819"), row("liq_2", "Bandra", "Bandra", "19.06", "72.84"))) }
        val r = search().results
        assertEquals(listOf("liq_1", "liq_2"), r.map { it.id })
        assertEquals("Mumbai Central", r[0].name)
        assertEquals(72.819, r[0].lon, 0.0)
    }

    @Test
    fun emptyArray_isEmptyNotAnError() {
        assertTrue(search().results.isEmpty())
    }

    @Test
    fun unknownExtraFields_areIgnored() {
        server.reply = { TinyHttpServer.Reply(body = """{"results":[{"id":"a","name":"n","formattedAddress":"f","lat":1,"lon":2,"provider":"liq","importance":0.9,"extra":{"x":[1,2]}}],"cached":true}""") }
        assertEquals("a", search().results.single().id)
    }

    @Test
    fun httpErrorStatuses_surfaceAsHttpExceptionWithTheirCode() {
        for (code in listOf(400, 401, 403, 404, 429, 500, 502, 503, 504)) {
            server.reply = { TinyHttpServer.Reply(status = code, body = """{"error":"x"}""") }
            val t = failureOf { search() }
            assertTrue("status $code gave $t", t is HttpException)
            assertEquals(code, (t as HttpException).code())
        }
    }

    @Test
    fun http200WithMalformedJson_failsAsException_neverAsFakeEmptyResults() {
        val bodies = listOf("not json at all", """{"results":""", """{"results":[{"id":""", "<html><body>Wi-Fi login</body></html>", "", "[]", """"just a string"""")
        for (b in bodies) {
            server.reply = { TinyHttpServer.Reply(body = b) }
            val t = failureOf { search() }
            // The ViewModel maps IOException -> "No internet", anything else -> "Search unavailable.".
            assertTrue("body <$b> gave $t", t is IOException || t is RuntimeException)
        }
    }

    @Test
    fun missingResultsKey_yieldsNothingUsable() {
        server.reply = { TinyHttpServer.Reply(body = "{}") }
        val resp = search()
        // Gson bypasses Kotlin's non-null contract here (results is null at runtime); the ViewModel turns the
        // resulting NPE into an error state. Either null or empty is acceptable, a populated list is not.
        @Suppress("USELESS_CAST")
        assertTrue((resp.results as List<*>?).isNullOrEmpty())
    }

    @Test
    fun rowsWithMissingNullOrZeroCoordinates_parseButAreNotValid() {
        server.reply = {
            TinyHttpServer.Reply(
                body = body(
                    """{"id":"a","name":"no coords","formattedAddress":""}""",
                    """{"id":"b","name":"null coords","formattedAddress":"","lat":null,"lon":null}""",
                    row("c", lat = "0", lon = "0"),
                    row("d", lat = "91", lon = "10"),
                    row("e", lat = "10", lon = "-181"),
                    row("f", lat = "\"12.5\"", lon = "\"77.5\""),   // numeric strings are accepted by Gson
                    row("g", lat = "90", lon = "180"),
                    row("h", lat = "-90", lon = "-180"),
                    row("i", lat = "1e2", lon = "5"),
                )
            )
        }
        val byId = search().results.associateBy { it.id }
        assertEquals(setOf("f", "g", "h"), byId.filterValues { it.hasValidCoords }.keys)
        assertEquals(12.5, byId.getValue("f").lat, 0.0)
    }

    @Test
    fun nonNumericCoordinate_failsTheWholeResponse() {
        // One unparsable row throws for the entire payload (Gson is strict), so the ViewModel shows an error
        // rather than the good rows. Worker is trusted to send numbers; this pins the blast radius.
        server.reply = { TinyHttpServer.Reply(body = body(row("ok"), row("bad", lat = "\"abc\""))) }
        val t = failureOf { search() }
        assertTrue("gave $t", t is RuntimeException || t is IOException)
    }

    @Test
    fun nanOrInfinityCoordinate_isRejected() {
        for (bad in listOf("\"NaN\"", "\"Infinity\"", "1e999")) {
            server.reply = { TinyHttpServer.Reply(body = body(row("n", lat = bad))) }
            val outcome = runCatching { search().results.single() }
            assertTrue("lat=$bad -> $outcome", outcome.isFailure || !outcome.getOrThrow().hasValidCoords)
        }
    }

    @Test
    fun connectionRefused_isAnIOException() {
        val dead = TinyHttpServer().also { it.close() }
        GeocodingClient.setWorkerUrl(dead.baseUrl)
        val t = failureOf { search() }
        assertTrue("gave $t", t is IOException)
    }

    @Test
    fun serverHangsUpWithoutAnswering_isAnIOException() {
        server.reply = { TinyHttpServer.Reply(hangUp = true) }
        val t = failureOf { search() }
        assertTrue("gave $t", t is IOException)
    }

    @Test
    fun bodyCutOffMidResponse_isAnIOException() {
        val full = body(row("liq_1", "A very long name ".repeat(20)))
        server.reply = { TinyHttpServer.Reply(body = full, truncateBodyTo = 40) }
        val t = failureOf { search() }
        assertTrue("gave $t", t is IOException)
    }

    @Test
    fun reverse_emptyResultsIsValid_andFirstRowParses() {
        assertTrue(runBlocking { GeocodingClient.geocodingService.reverse(0.5, 0.5) }.results.isEmpty())
        server.reply = { TinyHttpServer.Reply(body = body(row("liq_rev_9", "Stn", "Rd", "18.5", "73.8"))) }
        assertEquals("liq_rev_9", runBlocking { GeocodingClient.geocodingService.reverse(18.5, 73.8) }.results.single().id)
    }
}
