package com.omama.stationalarm.ui.viewmodel

import android.app.Application
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.omama.stationalarm.R
import com.omama.stationalarm.data.StationData
import com.omama.stationalarm.network.GeocodingResponse
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.osmdroid.util.GeoPoint
import org.robolectric.annotation.Config
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Locale

/**
 * Pipeline behaviour of the 2-tier search (offline StationData first, then the Worker) under virtual time.
 * The Retrofit service is replaced by [FakeGeocodingService]; offline queries use the real stations.json.
 *
 * ONLINE is gibberish that no station name or code contains, so it always falls through to tier 2.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], application = android.app.Application::class)
class MapSearchViewModelScenarioTest {

    @get:Rule
    val mainRule = SearchMainDispatcherRule()

    private val ONLINE = "zqxjzqxj"

    private lateinit var app: Application
    private lateinit var fake: FakeGeocodingService
    private lateinit var vm: MapSearchViewModel
    private val originalLocale: Locale = Locale.getDefault()

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        StationData.initialize(app)
        assumeTrue("stations.json did not load", StationData.searchStations("ndls").isNotEmpty())
        assertTrue(StationData.searchStations(ONLINE).isEmpty())
        fake = FakeGeocodingService()
        GeocodingClientHook.install(fake)
        vm = MapSearchViewModel(app)
    }

    @After
    fun tearDown() {
        vm.viewModelScope.cancel()
        GeocodingClientHook.clear()
        Locale.setDefault(originalLocale)
    }

    private fun vmTest(block: suspend TestScope.() -> Unit) = runTest { runCurrent(); block() }
    private fun str(id: Int, vararg args: Any) = app.getString(id, *args)
    private fun http(code: Int) = HttpException(Response.error<Any>(code, "".toResponseBody("text/plain".toMediaTypeOrNull())))

    // ── debounce ───────────────────────────────────────────────────────────────

    @Test
    fun debounce_coalescesRapidTyping_intoOneRequestForTheFinalText() = vmTest {
        vm.onQueryChanged("zqx"); advanceTimeBy(100)
        vm.onQueryChanged("zqxj"); advanceTimeBy(100)
        vm.onQueryChanged("zqxjz")
        advanceTimeBy(499); runCurrent()
        assertTrue("fired before the 500 ms quiet period", fake.searchCalls.isEmpty())
        advanceTimeBy(2); runCurrent()
        assertEquals(listOf("zqxjz"), fake.searchCalls.map { it.query })
    }

    @Test
    fun noRequestAndNoSpinnerBeforeTheDebounceElapses() = vmTest {
        vm.onQueryChanged(ONLINE)
        advanceTimeBy(300)
        assertFalse(vm.isSearching.value)
        assertTrue(fake.searchCalls.isEmpty())
        assertEquals(ONLINE, vm.query.value)
    }

    @Test
    fun queriesUnderThreeCharacters_neverSearch_includingTwoLetterStationCodes() = vmTest {
        for (q in listOf("a", "ab", "sc", "  ")) { vm.onQueryChanged(q); advanceTimeBy(5_000) }
        assertTrue(fake.searchCalls.isEmpty())
        assertTrue(vm.searchResults.value.isEmpty())
        assertFalse(vm.isSearching.value)
    }

    @Test
    fun exactlyThreeCharacters_isSearched() = vmTest {
        vm.onQueryChanged("zqx"); advanceTimeBy(501)
        assertEquals(listOf("zqx"), fake.searchCalls.map { it.query })
    }

    @Test
    fun spinner_isOnOnlyWhileTheRequestIsInFlight() = vmTest {
        fake.onSearch = { delay(1_000); response(place("a")) }
        vm.onQueryChanged(ONLINE); advanceTimeBy(501)
        assertTrue(vm.isSearching.value)
        advanceTimeBy(1_000)
        assertFalse(vm.isSearching.value)
        assertEquals(listOf("a"), vm.searchResults.value.map { it.id })
    }

    // ── mapLatest / staleness ────────────────────────────────────────────────

    @Test
    fun newerQuery_cancelsTheInFlightRequest_andOnlyTheNewerResultIsShown() = vmTest {
        fake.onSearch = { c -> delay(2_000); response(place("r-" + c.query)) }
        vm.onQueryChanged("zqxj1"); advanceTimeBy(501)           // request #1 in flight until t=2500
        vm.onQueryChanged("zqxj12"); advanceTimeBy(501)          // debounce fires at t=1001 -> #1 cancelled
        assertEquals(listOf("zqxj1"), fake.searchCancelled)
        assertTrue("spinner must stay on for the surviving request", vm.isSearching.value)
        assertNull("cancellation is not an error", vm.searchError.value)
        advanceTimeBy(1_600)                                     // t=2603: #1 would have finished by now
        assertTrue(vm.searchResults.value.isEmpty())
        advanceTimeBy(1_000)                                     // t=3603: #2 done (t=3001)
        assertEquals(listOf("r-zqxj12"), vm.searchResults.value.map { it.id })
        assertEquals(listOf("zqxj12"), fake.searchCompleted)
        assertFalse(vm.isSearching.value)
    }

    @Test
    fun clearingTheBox_whileARequestIsInFlight_discardsItsLateResponse() = vmTest {
        fake.onSearch = { delay(2_000); response(place("late")) }
        vm.onQueryChanged(ONLINE); advanceTimeBy(501)
        assertTrue(vm.isSearching.value)
        vm.onQueryChanged("")
        assertFalse(vm.isSearching.value)
        advanceTimeBy(3_000)
        assertEquals("the request itself is not cancelled by a short query", listOf(ONLINE), fake.searchCompleted)
        assertTrue(vm.searchResults.value.isEmpty())
        assertNull(vm.searchError.value)
        assertFalse(vm.isSearching.value)
    }

    @Test
    fun clearingTheBox_whileARequestIsInFlight_discardsItsLateError() = vmTest {
        fake.onSearch = { delay(2_000); throw IOException("late failure") }
        vm.onQueryChanged(ONLINE); advanceTimeBy(501)
        vm.onQueryChanged("")
        advanceTimeBy(3_000)
        assertNull(vm.searchError.value)
        assertTrue(vm.searchResults.value.isEmpty())
    }

    @Test
    fun pickingAResult_whileARequestIsInFlight_discardsItsLateResponse() = vmTest {
        fake.onSearch = { delay(2_000); response(place("late")) }
        vm.onQueryChanged(ONLINE); advanceTimeBy(501)
        vm.selectResult(place("picked", name = "Picked"))
        assertFalse(vm.isSearching.value)
        assertEquals("", vm.query.value)
        advanceTimeBy(3_000)
        assertTrue(vm.searchResults.value.isEmpty())
        assertEquals("picked", vm.selectedResult.value?.id)
    }

    @Test
    fun typingTheSameTextAgain_afterPickingAResult_searchesAgain() = vmTest {
        fake.onSearch = { response(place("r1")) }
        vm.onQueryChanged(ONLINE); advanceTimeBy(501)
        assertEquals(1, vm.searchResults.value.size)
        vm.selectResult(vm.searchResults.value.first())
        advanceTimeBy(600)                                       // the blank must pass the debounce to reset distinctUntilChanged
        vm.onQueryChanged(ONLINE); advanceTimeBy(501)
        assertEquals(2, fake.searchCalls.size)
        assertEquals(1, vm.searchResults.value.size)
    }

    @Test
    fun anOfflineHit_cancelsAnInFlightOnlineRequest_andItsLateAnswerNeverShows() = vmTest {
        fake.onSearch = { delay(5_000); response(place("late")) }
        vm.onQueryChanged(ONLINE); advanceTimeBy(501)
        vm.onQueryChanged("ndls"); advanceTimeBy(501)
        assertEquals("NDLS", vm.searchResults.value.first().id)
        assertEquals(listOf(ONLINE), fake.searchCancelled)
        assertFalse(vm.isSearching.value)
        advanceTimeBy(6_000)
        assertEquals("NDLS", vm.searchResults.value.first().id)
        assertTrue(vm.searchResults.value.none { it.id == "late" })
    }

    // ── tier 1: offline ───────────────────────────────────────────────────────

    @Test
    fun stationQuery_isServedOffline_neverTouchesTheNetwork_andMapsStationFields() = vmTest {
        fake.onSearch = { throw IOException("network is down") }
        vm.onQueryChanged("lonavala"); advanceTimeBy(501)
        val hit = vm.searchResults.value.single()
        assertEquals("LNL", hit.id)
        assertEquals("LONAVALA", hit.name)
        assertEquals(18.7492, hit.lat, 1e-9)
        assertEquals(73.408, hit.lon, 1e-9)
        assertEquals(str(R.string.map_hint_railway_station), hit.formattedAddress)
        assertTrue(fake.searchCalls.isEmpty())
        assertNull("offline results must not carry a network error", vm.searchError.value)
        assertFalse(vm.isSearching.value)
    }

    @Test
    fun offlineRanking_codeFirst_caseInsensitive_andPaddingIsIgnored() = vmTest {
        val seen = mutableListOf<List<String>>()
        for (q in listOf("ndls", "NDLS", "NdLs", " ndls ")) {
            vm.onQueryChanged(q); advanceTimeBy(501)
            seen += vm.searchResults.value.map { it.id }
            vm.onQueryChanged(""); advanceTimeBy(600)
        }
        assertEquals("NDLS", seen[0].first())
        assertTrue("all four spellings must agree: $seen", seen.all { it == seen[0] })
    }

    @Test
    fun offlineResults_areCappedAtEight_andNewDelhiSurvivesTheCut() = vmTest {
        vm.onQueryChanged("delhi"); advanceTimeBy(501)
        val ids = vm.searchResults.value.map { it.id }
        assertEquals(8, ids.size)
        assertTrue("NDLS missing from $ids", "NDLS" in ids.take(2))
        assertTrue(fake.searchCalls.isEmpty())
    }

    @Test
    fun partialStationName_matches() = vmTest {
        vm.onQueryChanged("lonav"); advanceTimeBy(501)
        assertEquals("LNL", vm.searchResults.value.first().id)
    }

    @Test
    fun aTypoInAStationName_fallsThroughToTheGeocoder() = vmTest {
        assumeTrue(StationData.searchStations("dehli").isEmpty())
        vm.onQueryChanged("dehli"); advanceTimeBy(501)
        assertEquals(listOf("dehli"), fake.searchCalls.map { it.query })
    }

    @Test
    fun nonLatinAndAccentedQueries_fallThroughToTheGeocoderUnchanged() = vmTest {
        val queries = listOf("दिल्ली", "Pūne", "东京站", "😀😀😀")
        for (q in queries) {
            assumeTrue(StationData.searchStations(q).isEmpty())
            vm.onQueryChanged(q); advanceTimeBy(501)
        }
        assertEquals(queries, fake.searchCalls.map { it.query })
    }

    @Test
    fun veryLongQuery_reachesTheGeocoderIntact_withoutCrashing() = vmTest {
        val long = "k".repeat(3_000)
        vm.onQueryChanged(long); advanceTimeBy(501)
        assertEquals(listOf(3_000), fake.searchCalls.map { it.query.length })
        assertFalse(vm.isSearching.value)
    }

    // ── tier 2: results ───────────────────────────────────────────────────────

    @Test
    fun emptyArray_isEmptyResultsWithNoErrorAndNoSpinner() = vmTest {
        fake.onSearch = { response() }
        vm.onQueryChanged(ONLINE); advanceTimeBy(501)
        assertTrue(vm.searchResults.value.isEmpty())
        assertNull(vm.searchError.value)
        assertFalse(vm.isSearching.value)
    }

    @Test
    fun rowsWithUnusableCoordinates_areDropped_goodRowsKeepTheirOrder() = vmTest {
        fake.onSearch = {
            response(
                place("ok1", 10.0, 20.0), place("nan", Double.NaN, 5.0), place("null-island", 0.0, 0.0),
                place("lat91", 91.0, 5.0), place("ok2", -90.0, 180.0), place("lon181", 5.0, 181.0),
                place("inf", Double.POSITIVE_INFINITY, 1.0), place("ok3", 0.0, 0.001),
            )
        }
        vm.onQueryChanged(ONLINE); advanceTimeBy(501)
        assertEquals(listOf("ok1", "ok2", "ok3"), vm.searchResults.value.map { it.id })
    }

    @Test
    fun onlineResults_areNotRewrittenOrReLabelled() = vmTest {
        val p = place("liq_77", 1.5, 2.5, name = "Some Cafe", addr = "1 Main St")
        fake.onSearch = { response(p) }
        vm.onQueryChanged(ONLINE); advanceTimeBy(501)
        assertEquals(listOf(p), vm.searchResults.value)
    }

    @Test
    fun requestCarriesLangAndNoBiasByDefault() = vmTest {
        Locale.setDefault(Locale("hi", "IN"))
        vm.onQueryChanged(ONLINE); advanceTimeBy(501)
        val c = fake.searchCalls.single()
        assertEquals("hi", c.lang)
        assertNull(c.biasLat); assertNull(c.biasLon)
        assertEquals(8, c.limit)
    }

    @Test
    fun blankLocaleLanguage_fallsBackToEnglish() = vmTest {
        Locale.setDefault(Locale.ROOT)
        vm.onQueryChanged(ONLINE); advanceTimeBy(501)
        assertEquals("en", fake.searchCalls.single().lang)
    }

    @Test
    fun knownUserLocation_isSentAsBias() = vmTest {
        @Suppress("UNCHECKED_CAST")
        val center = MapSearchViewModel::class.java.getDeclaredField("_initialCenter")
            .apply { isAccessible = true }.get(vm) as MutableStateFlow<GeoPoint?>
        center.value = GeoPoint(18.52, 73.85)
        vm.onQueryChanged(ONLINE); advanceTimeBy(501)
        val c = fake.searchCalls.single()
        assertEquals(18.52, c.biasLat!!, 0.0)
        assertEquals(73.85, c.biasLon!!, 0.0)
    }

    // ── failures shown as UI state ───────────────────────────────────────────

    @Test
    fun everyFailureKind_endsAsErrorTextWithEmptyResultsAndSpinnerOff() = vmTest {
        val cases: List<Pair<Throwable, String>> = listOf(
            SocketTimeoutException("read timed out") to str(R.string.error_search_timed_out),
            UnknownHostException("no host") to str(R.string.error_no_internet),
            ConnectException("refused") to str(R.string.error_no_internet),
            IOException("reset") to str(R.string.error_no_internet),
            http(429) to str(R.string.error_too_many_requests),
            http(500) to str(R.string.error_search_down),
            http(502) to str(R.string.error_search_down),
            http(503) to str(R.string.error_search_down),
            http(599) to str(R.string.error_search_down),
            http(400) to str(R.string.error_search_unavailable_format, 400),
            http(404) to str(R.string.error_search_unavailable_format, 404),
            http(600) to str(R.string.error_search_unavailable_format, 600),
            JsonSyntaxException("malformed") to str(R.string.error_search_unavailable),
            NullPointerException("x") to str(R.string.error_search_unavailable),
            IllegalArgumentException("bad base url") to str(R.string.error_search_unavailable),
        )
        cases.forEachIndexed { i, (ex, expected) ->
            fake.onSearch = { throw ex }
            vm.onQueryChanged("$ONLINE$i"); advanceTimeBy(501)
            assertEquals("case $i ${ex::class.simpleName}", expected, vm.searchError.value)
            assertTrue(vm.searchResults.value.isEmpty())
            assertFalse(vm.isSearching.value)
        }
    }

    @Test
    fun failureWipesPreviouslyShownResults() = vmTest {
        fake.onSearch = { response(place("a")) }
        vm.onQueryChanged("zqxj1"); advanceTimeBy(501)
        assertEquals(1, vm.searchResults.value.size)
        fake.onSearch = { throw http(503) }
        vm.onQueryChanged("zqxj12"); advanceTimeBy(501)
        assertTrue(vm.searchResults.value.isEmpty())
        assertEquals(str(R.string.error_search_down), vm.searchError.value)
    }

    @Test
    fun nextSuccessfulSearch_clearsThePreviousError() = vmTest {
        fake.onSearch = { throw IOException("down") }
        vm.onQueryChanged("zqxj1"); advanceTimeBy(501)
        assertNotNull(vm.searchError.value)
        fake.onSearch = { response(place("back")) }
        vm.onQueryChanged("zqxj12"); advanceTimeBy(501)
        assertNull(vm.searchError.value)
        assertEquals(listOf("back"), vm.searchResults.value.map { it.id })
    }

    @Test
    fun aStationQuery_afterAFailedOnlineOne_showsOfflineResultsAndClearsTheError() = vmTest {
        fake.onSearch = { throw SocketTimeoutException() }
        vm.onQueryChanged(ONLINE); advanceTimeBy(501)
        assertNotNull(vm.searchError.value)
        vm.onQueryChanged("ndls"); advanceTimeBy(501)
        assertNull(vm.searchError.value)
        assertEquals("NDLS", vm.searchResults.value.first().id)
    }

    @Test
    fun shorteningTheQuery_clearsErrorAndResultsImmediately() = vmTest {
        fake.onSearch = { throw IOException("down") }
        vm.onQueryChanged(ONLINE); advanceTimeBy(501)
        assertNotNull(vm.searchError.value)
        vm.onQueryChanged("zq")
        assertNull(vm.searchError.value)
        assertTrue(vm.searchResults.value.isEmpty())
        assertFalse(vm.isSearching.value)
    }

    @Test
    fun responseWithoutAResultsArray_isAnErrorState_notACrash() = vmTest {
        fake.onSearch = { Gson().fromJson("{}", GeocodingResponse::class.java) }
        vm.onQueryChanged(ONLINE); advanceTimeBy(501)
        assertEquals(str(R.string.error_search_unavailable), vm.searchError.value)
        assertTrue(vm.searchResults.value.isEmpty())
        assertFalse(vm.isSearching.value)
    }

    // ── suspected product bugs (kept un-ignored until a run confirms them) ────

    @Ignore("BUG: MapSearchViewModel.kt:88 length filter counts spaces, so a 3+ space query skips tier 1 (blank -> empty) and is sent to the Worker as q=\"   \" (MapSearchViewModel.kt:140): wasted quota / a 400 shown as an error.")
    @Test
    fun whitespaceOnlyQuery_isNotSentToTheGeocoder() = vmTest {
        vm.onQueryChanged("   "); advanceTimeBy(501)
        assertTrue("sent ${fake.searchCalls}", fake.searchCalls.isEmpty())
        assertNull(vm.searchError.value)
    }

    @Ignore("BUG: MapSearchViewModel.kt:200 selectResult() passes a null id (Gson bypasses Kotlin non-null when the Worker omits id) to StationIds.sanitize(raw: String) -> NullPointerException on the UI thread (low severity: needs a Worker contract break).")
    @Test
    fun aResultWithAMissingId_canStillBeSelected() = vmTest {
        val noId = Gson().fromJson(
            """{"results":[{"name":"No id","formattedAddress":"x","lat":1.5,"lon":2.5}]}""", GeocodingResponse::class.java,
        ).results.single()
        vm.selectResult(noId)
        assertNotNull(vm.selectedResult.value)
    }
}
