package com.omama.stationalarm.ui.viewmodel

import android.app.Application
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.omama.stationalarm.data.StationData
import com.omama.stationalarm.util.Analytics
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

/**
 * What MapSearchViewModel reports to analytics. Events are captured through [Analytics.eventSink], so no Firebase
 * project is involved. Same virtual-time rig and fakes as the other search tests.
 *
 * ONLINE is gibberish that no station name or code contains, so it always falls through to tier 2 (the API).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], application = android.app.Application::class)
class MapSearchViewModelAnalyticsTest {

    @get:Rule
    val mainRule = SearchMainDispatcherRule()

    private val ONLINE = "zqxjzqxj"

    private data class Event(val name: String, val params: Map<String, String?>)

    private val events = CopyOnWriteArrayList<Event>()
    private lateinit var app: Application
    private lateinit var fake: FakeGeocodingService
    private lateinit var vm: MapSearchViewModel

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        StationData.initialize(app)
        assumeTrue("stations.json did not load", StationData.searchStations("ndls").isNotEmpty())
        assertTrue(StationData.searchStations(ONLINE).isEmpty())
        Analytics.eventSink = { name, bundle ->
            val extras = HashMap<String, String?>()
            if (bundle != null) {
                for (key in bundle.keySet()) extras[key] = bundle.getString(key)
            }
            events += Event(name, extras)
        }
        fake = FakeGeocodingService()
        GeocodingClientHook.install(fake)
        vm = MapSearchViewModel(app)
    }

    @After
    fun tearDown() {
        vm.viewModelScope.cancel()
        GeocodingClientHook.clear()
        Analytics.eventSink = null
    }

    private fun vmTest(block: suspend TestScope.() -> Unit) = runTest { runCurrent(); block() }
    private fun named(name: String) = events.filter { it.name == name }
    private fun http(code: Int) =
        HttpException(Response.error<Any>(code, "".toResponseBody("text/plain".toMediaTypeOrNull())))

    // ── search ───────────────────────────────────────────────────────────────

    @Test
    fun offlineStationSearch_isReportedAsOffline_andMakesNoApiCall() = vmTest {
        vm.onQueryChanged("ndls")
        advanceTimeBy(501); runCurrent()
        assertTrue("an offline hit must not touch the geocoder", fake.searchCalls.isEmpty())
        val e = named("search_performed").single()
        assertEquals("offline", e.params["source"])
        assertTrue("results=${e.params["results"]}", e.params["results"] in setOf("1", "2-5", "6+"))
        assertTrue(named("geocode_failed").isEmpty())
    }

    @Test
    fun apiSearch_isReportedAsApi_withTheResultBand() = vmTest {
        fake.onSearch = { response(place("a"), place("b"), place("c")) }
        vm.onQueryChanged(ONLINE)
        advanceTimeBy(501); runCurrent()
        val e = named("search_performed").single()
        assertEquals("api", e.params["source"])
        assertEquals("2-5", e.params["results"])
    }

    @Test
    fun apiSearch_countsOnlyUsableResults() = vmTest {
        // (0,0) is the "no result" placeholder some providers return; the UI drops it, so it must not count.
        fake.onSearch = { response(place("nowhere", lat = 0.0, lon = 0.0)) }
        vm.onQueryChanged(ONLINE)
        advanceTimeBy(501); runCurrent()
        assertEquals("0", named("search_performed").single().params["results"])
    }

    @Test
    fun rateLimitedSearch_isReportedAsGeocodeFailed429_notAsASearch() = vmTest {
        fake.onSearch = { throw http(429) }
        vm.onQueryChanged(ONLINE)
        advanceTimeBy(501); runCurrent()
        val e = named("geocode_failed").single()
        assertEquals("search", e.params["kind"])
        assertEquals("http_429", e.params["reason"])
        assertTrue(named("search_performed").isEmpty())
    }

    @Test
    fun abandonedSearch_whoseRequestStillCompletes_isStillCounted() = vmTest {
        // The request was made and spent quota even though the user cleared the box before it answered.
        fake.onSearch = { delay(2_000); response(place("late")) }
        vm.onQueryChanged(ONLINE); advanceTimeBy(501)
        vm.onQueryChanged("")
        advanceTimeBy(3_000); runCurrent()
        assertEquals("api", named("search_performed").single().params["source"])
    }

    @Test
    fun searchCancelledByANewerKeystroke_isNotReportedAsFailureOrSearch() = vmTest {
        fake.onSearch = { c -> delay(2_000); response(place("r-" + c.query)) }
        vm.onQueryChanged("zqxj1"); advanceTimeBy(501)           // request #1 in flight
        vm.onQueryChanged("zqxj12"); advanceTimeBy(501)          // debounce fires -> #1 cancelled
        advanceTimeBy(4_000); runCurrent()                       // #2 completes
        assertEquals(listOf("zqxj1"), fake.searchCancelled)
        assertTrue("cancellation is not a failure", named("geocode_failed").isEmpty())
        assertEquals("only the surviving request counts", 1, named("search_performed").size)
    }

    // ── map taps / reverse geocoding ─────────────────────────────────────────

    @Test
    fun mapTap_isReportedOnce_withoutAnyParameters() = vmTest {
        vm.onMapTap(18.5204, 73.8567); runCurrent()
        val e = named("map_pin_dropped").single()
        assertTrue("a tap event must carry no coordinates", e.params.isEmpty())
    }

    @Test
    fun failedReverseGeocode_isReportedAsGeocodeFailedReverse() = vmTest {
        fake.onReverse = { throw IOException("offline") }
        vm.onMapTap(18.5204, 73.8567); runCurrent()
        val e = named("geocode_failed").single()
        assertEquals("reverse", e.params["kind"])
        assertEquals("network", e.params["reason"])
    }

    // ── privacy ──────────────────────────────────────────────────────────────

    @Test
    fun noEventCarriesTheQueryCoordinatesOrAddress() = vmTest {
        fake.onSearch = { throw http(503) }
        vm.onQueryChanged(ONLINE)
        advanceTimeBy(501); runCurrent()
        fake.onReverse = { response(place("liq_1", 18.5204, 73.8567, "Secret Cafe", "1 Private Rd")) }
        vm.onMapTap(18.5204, 73.8567); runCurrent()

        assertTrue("expected some events to inspect", events.isNotEmpty())
        val everything = events.flatMap { listOf(it.name) + it.params.keys + it.params.values.filterNotNull() }
        for (text in everything) {
            assertFalse("query leaked: $text", text.contains(ONLINE))
            assertFalse("coordinates leaked: $text", text.contains("18.52") || text.contains("73.85"))
            assertFalse("address leaked: $text", text.contains("Secret") || text.contains("Private"))
        }
    }

    // ── the wrapper itself ───────────────────────────────────────────────────

    @Test
    fun analyticsNeverThrows_evenWhenTheSinkFails() {
        Analytics.eventSink = { _, _ -> throw IllegalStateException("sink exploded") }
        Analytics.event("anything")
        Analytics.event("with_params") { putString("k", "v") }
    }
}
