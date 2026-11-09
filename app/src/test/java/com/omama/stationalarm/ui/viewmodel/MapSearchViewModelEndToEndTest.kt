package com.omama.stationalarm.ui.viewmodel

import android.app.Application
import android.os.Looper
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.omama.stationalarm.R
import com.omama.stationalarm.data.StationData
import com.omama.stationalarm.network.GeocodingClient
import com.omama.stationalarm.network.TinyHttpServer
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/**
 * MapSearchViewModel -> the real GeocodingClient (real Retrofit + Gson + OkHttp) -> loopback server.
 * The ViewModel runs on Robolectric's paused main looper: idleFor() advances the 500 ms debounce on the virtual
 * clock and the loop below pumps the looper while the real socket I/O completes on OkHttp threads.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], application = android.app.Application::class)
class MapSearchViewModelEndToEndTest {

    private val ONLINE = "zqxjzqxj"

    private lateinit var app: Application
    private lateinit var server: TinyHttpServer
    private lateinit var vm: MapSearchViewModel
    private lateinit var savedUrl: String

    private fun urlField() = GeocodingClient::class.java.getDeclaredField("workerBaseUrl").apply { isAccessible = true }

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        StationData.initialize(app)
        assumeTrue(StationData.searchStations("ndls").isNotEmpty())
        assertTrue(StationData.searchStations(ONLINE).isEmpty())
        savedUrl = urlField().get(GeocodingClient) as String
        GeocodingClientHook.clear()
        server = TinyHttpServer()
        GeocodingClient.setWorkerUrl(server.baseUrl)
        vm = MapSearchViewModel(app)
    }

    @After
    fun tearDown() {
        vm.viewModelScope.cancel()
        server.close()
        GeocodingClient.setWorkerUrl(savedUrl)
        GeocodingClientHook.clear()
    }

    private fun str(id: Int, vararg a: Any) = app.getString(id, *a)
    private fun row(id: String, lat: String, lon: String, name: String = "N $id") =
        """{"id":"$id","name":"$name","formattedAddress":"Addr $id","lat":$lat,"lon":$lon}"""
    private fun body(vararg rows: String) = """{"results":[${rows.joinToString(",")}]}"""
    private fun reply(status: Int = 200, body: String) { server.reply = { TinyHttpServer.Reply(status = status, body = body) } }

    private fun pumpUntil(timeoutMs: Long = 10_000, what: String = "condition", cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (cond()) return
            Thread.sleep(10)
        }
        throw AssertionError(
            "$what not reached in ${timeoutMs}ms: searching=${vm.isSearching.value} results=${vm.searchResults.value.size} " +
                "error=${vm.searchError.value} requests=${server.requests.size}"
        )
    }

    /** Types [q], lets the debounce elapse, then waits until the request has been answered and the spinner is off. */
    private fun search(q: String, until: () -> Boolean = { server.requests.isNotEmpty() && !vm.isSearching.value }) {
        vm.onQueryChanged(q)
        shadowOf(Looper.getMainLooper()).idleFor(600, TimeUnit.MILLISECONDS)
        pumpUntil(what = "search of <$q>", cond = until)
    }

    @Test
    fun success_goesToTheWorkerWithTheDocumentedParams_andShowsTheRows() {
        reply(body = body(row("liq_1", "18.5", "73.8", "Pune Cafe"), row("liq_2", "19.0", "72.8")))
        search(ONLINE)
        assertEquals(listOf("liq_1", "liq_2"), vm.searchResults.value.map { it.id })
        assertEquals("Pune Cafe", vm.searchResults.value.first().name)
        assertNull(vm.searchError.value)
        val req = server.requests.single()
        assertEquals("/search", req.path)
        assertEquals(setOf("q", "limit", "lang"), req.paramNames)
        assertEquals(ONLINE, req.param("q"))
        assertEquals("8", req.param("limit"))
        assertTrue(req.paramNames.none { it.contains("country", ignoreCase = true) })
    }

    @Test
    fun emptyArray_isNoResultsAndNoError() {
        reply(body = body())
        search(ONLINE)
        assertTrue(vm.searchResults.value.isEmpty())
        assertNull(vm.searchError.value)
    }

    @Test
    fun http429_showsTheRateLimitMessage() {
        reply(429, """{"error":"rate"}""")
        search(ONLINE)
        assertEquals(str(R.string.error_too_many_requests), vm.searchError.value)
        assertTrue(vm.searchResults.value.isEmpty())
    }

    @Test
    fun http5xx_showsTheServiceDownMessage() {
        for ((i, code) in listOf(500, 502, 503).withIndex()) {
            reply(code, "oops")
            search("$ONLINE$i", until = { server.requests.size > i && !vm.isSearching.value })
            assertEquals("status $code", str(R.string.error_search_down), vm.searchError.value)
        }
    }

    @Test
    fun http404_showsTheGenericCodedMessage() {
        reply(404, """{"error":"nope"}""")
        search(ONLINE)
        assertEquals(str(R.string.error_search_unavailable_format, 404), vm.searchError.value)
    }

    @Test
    fun malformedBodies_endAsAnErrorState_neverAsEmptyResults() {
        val bodies = listOf("<html>Wi-Fi login</html>", """{"results":""", "", "[]", """"x"""")
        bodies.forEachIndexed { i, b ->
            reply(body = b)
            search("$ONLINE$i", until = { server.requests.size > i && !vm.isSearching.value })
            val msg = vm.searchError.value
            assertNotNull("body <$b> must surface an error", msg)
            // IOException subclasses (EOF/MalformedJson) read as "No internet"; JsonSyntaxException as "unavailable".
            assertTrue("body <$b> -> $msg", msg in setOf(str(R.string.error_no_internet), str(R.string.error_search_unavailable)))
            assertTrue(vm.searchResults.value.isEmpty())
        }
    }

    @Test
    fun unusableRowsAreFilteredAfterARealParse() {
        reply(
            body = body(
                row("ok", "18.5", "73.8"),
                """{"id":"nolatlon","name":"n","formattedAddress":"a"}""",
                row("island", "0", "0"),
                row("lat91", "91", "5"),
                row("ok2", "-33.8688", "151.2093"),
            )
        )
        search(ONLINE)
        assertEquals(listOf("ok", "ok2"), vm.searchResults.value.map { it.id })
    }

    @Test
    fun oneNonNumericCoordinate_failsTheWholeSearch() {
        reply(body = body(row("ok", "18.5", "73.8"), row("bad", "\"north\"", "1")))
        search(ONLINE)
        assertNotNull(vm.searchError.value)
        assertTrue(vm.searchResults.value.isEmpty())
    }

    @Test
    fun stationQuery_isAnsweredOfflineWithoutAnyRequest() {
        reply(500, "must not be called")
        search("ndls", until = { vm.searchResults.value.isNotEmpty() && !vm.isSearching.value })
        assertEquals("NDLS", vm.searchResults.value.first().id)
        assertNull(vm.searchError.value)
        assertTrue("offline hit must not touch the network: ${server.requests}", server.requests.isEmpty())
    }

    @Test
    fun connectionRefused_showsNoInternet() {
        val dead = TinyHttpServer().also { it.close() }
        GeocodingClient.setWorkerUrl(dead.baseUrl)
        search(ONLINE, until = { vm.searchError.value != null && !vm.isSearching.value })
        assertEquals(str(R.string.error_no_internet), vm.searchError.value)
    }

    @Test
    fun serverThatHangsUp_showsAnError() {
        server.reply = { TinyHttpServer.Reply(hangUp = true) }
        search(ONLINE, until = { vm.searchError.value != null && !vm.isSearching.value })
        assertEquals(str(R.string.error_no_internet), vm.searchError.value)
    }

    @Test
    fun errorThenGoodAnswer_recovers() {
        reply(503, "down")
        search(ONLINE)
        assertNotNull(vm.searchError.value)
        reply(body = body(row("back", "10.0", "20.0")))
        search("${ONLINE}2", until = { server.requests.size >= 2 && !vm.isSearching.value })
        assertNull(vm.searchError.value)
        assertEquals(listOf("back"), vm.searchResults.value.map { it.id })
    }

    // ── reverse geocoding through the real client ─────────────────────────────

    private fun tap(lat: Double, lon: Double) {
        vm.onMapTap(lat, lon)
        val loading = str(R.string.map_pin_loading_address)
        pumpUntil(what = "reverse lookup") {
            server.requests.isNotEmpty() && vm.selectedResult.value?.formattedAddress != loading
        }
    }

    @Test
    fun reverse_closeResult_snapsTheNameAndPoint_andSendsLatLonLang() {
        reply(body = body(row("liq_rev_5", "18.5005", "73.8000", "Pune Station")))
        tap(18.5000, 73.8000)
        val r = vm.selectedResult.value!!
        assertEquals("liq-rev-5", r.id)
        assertEquals("Pune Station", r.name)
        assertEquals(18.5005, r.lat, 0.0)
        val req = server.requests.single()
        assertEquals("/reverse", req.path)
        assertEquals(setOf("lat", "lon", "lang"), req.paramNames)
        assertEquals(18.5, req.param("lat")!!.toDouble(), 0.0)
    }

    @Test
    fun reverse_serverError_fallsBackToRawCoordinates() {
        reply(500, "boom")
        tap(12.9716, 77.5946)
        val r = vm.selectedResult.value!!
        assertEquals(str(R.string.map_pin_dropped_default), r.name)
        assertFalse(r.formattedAddress == str(R.string.map_pin_loading_address))
        assertEquals(12.9716, r.lat, 0.0)
        assertTrue(r.formattedAddress, r.formattedAddress.contains("12.9716"))
    }
}
