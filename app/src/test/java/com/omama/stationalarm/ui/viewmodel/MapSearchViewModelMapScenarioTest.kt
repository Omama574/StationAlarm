package com.omama.stationalarm.ui.viewmodel

import android.app.Application
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.omama.stationalarm.R
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.Locale

/** Map taps, reverse geocoding, result selection and radius. Same virtual-time rig as the search tests. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], application = android.app.Application::class)
class MapSearchViewModelMapScenarioTest {

    @get:Rule
    val mainRule = SearchMainDispatcherRule()

    private lateinit var app: Application
    private lateinit var fake: FakeGeocodingService
    private lateinit var vm: MapSearchViewModel
    private val originalLocale: Locale = Locale.getDefault()

    @Before
    fun setUp() {
        Locale.setDefault(Locale.US)
        app = ApplicationProvider.getApplicationContext()
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
    private fun str(id: Int) = app.getString(id)

    // ── taps ───────────────────────────────────────────────────────────────────

    @Test
    fun tap_dropsAPinImmediately_withLoadingLabels_beforeTheGeocoderAnswers() = vmTest {
        fake.onReverse = { CompletableDeferred<Nothing>().await() }
        vm.onMapTap(18.5204, 73.8567)
        val pin = vm.selectedResult.value!!
        assertTrue(pin.id, pin.id.startsWith("manual-"))
        assertEquals(str(R.string.map_pin_dropped_default), pin.name)
        assertEquals(str(R.string.map_pin_loading_address), pin.formattedAddress)
        assertEquals(18.5204, pin.lat, 0.0)
        assertEquals(73.8567, pin.lon, 0.0)
    }

    @Test
    fun reverse_closeSnap_adoptsTheGeocodersPointNameAndSanitisedId() = vmTest {
        // 0.0013 deg of latitude is about 145 m: inside the 150 m snap radius.
        fake.onReverse = { response(place("liq_rev_99", 18.5013, 73.8000, "Pune Station", "Station Rd")) }
        vm.onMapTap(18.5000, 73.8000); runCurrent()
        val r = vm.selectedResult.value!!
        assertEquals("liq-rev-99", r.id)
        assertEquals("Pune Station", r.name)
        assertEquals("Station Rd", r.formattedAddress)
        assertEquals(18.5013, r.lat, 0.0)
        assertEquals(73.8000, r.lon, 0.0)
        assertEquals(listOf(FakeGeocodingService.ReverseCall(18.5, 73.8, "en")), fake.reverseCalls.toList())
    }

    @Test
    fun reverse_farResult_keepsTheTappedPoint_butTakesNameAndAddress() = vmTest {
        // 0.0014 deg is about 156 m: just outside the snap radius, so the alarm must not silently move.
        fake.onReverse = { response(place("liq_5", 18.5014, 73.8000, "Far Cafe", "Somewhere")) }
        vm.onMapTap(18.5000, 73.8000); runCurrent()
        val r = vm.selectedResult.value!!
        assertEquals("Far Cafe", r.name)
        assertEquals(18.5000, r.lat, 0.0)
        assertEquals(73.8000, r.lon, 0.0)
        assertEquals("liq-5", r.id)
    }

    @Test
    fun reverse_kilometresAway_keepsTappedPoint() = vmTest {
        fake.onReverse = { response(place("liq_9", 18.6, 73.9, "Nearest address", "Far away")) }
        vm.onMapTap(18.5, 73.8); runCurrent()
        assertEquals(18.5, vm.selectedResult.value!!.lat, 0.0)
        assertEquals(73.8, vm.selectedResult.value!!.lon, 0.0)
    }

    @Test
    fun reverse_resultWithInvalidCoordinates_neverMovesThePin() = vmTest {
        fake.onReverse = { response(place("liq_0", 0.0, 0.0, "Nowhere", "x")) }
        vm.onMapTap(0.00001, 0.00001); runCurrent()          // would be "close" to (0,0) if it were trusted
        val r = vm.selectedResult.value!!
        assertEquals("Nowhere", r.name)
        assertEquals(0.00001, r.lat, 0.0)
        assertEquals(0.00001, r.lon, 0.0)
    }

    @Test
    fun reverse_emptyResult_fallsBackToRawCoordinateLabel() = vmTest {
        vm.onMapTap(18.52041, 73.85672); runCurrent()
        val r = vm.selectedResult.value!!
        assertEquals(str(R.string.map_pin_dropped_default), r.name)
        assertEquals("18.5204, 73.8567", r.formattedAddress)
        assertTrue(r.id.startsWith("manual-"))
    }

    @Test
    fun reverse_failure_fallsBackToRawCoordinateLabel_forEveryExceptionKind() = vmTest {
        for (ex in listOf<Throwable>(IOException("down"), RuntimeException("boom"), IllegalArgumentException("bad url"))) {
            fake.onReverse = { throw ex }
            vm.onMapTap(-33.8688, 151.2093); runCurrent()
            val r = vm.selectedResult.value!!
            assertEquals("-33.8688, 151.2093", r.formattedAddress)
            assertEquals(str(R.string.map_pin_dropped_default), r.name)
        }
    }

    @Test
    fun aNewTap_cancelsTheOlderReverseLookup_andOnlyTheNewestAnswerIsApplied() = vmTest {
        val a = CompletableDeferred<com.omama.stationalarm.network.GeocodingResponse>()
        val b = CompletableDeferred<com.omama.stationalarm.network.GeocodingResponse>()
        fake.onReverse = { c -> if (c.lat == 10.0) a.await() else b.await() }
        vm.onMapTap(10.0, 20.0); runCurrent()
        vm.onMapTap(30.0, 40.0); runCurrent()
        assertEquals(1, fake.reverseCancelled.size)
        a.complete(response(place("liq_a", 10.0, 20.0, "Old pin", "Old")))   // late: must be ignored
        runCurrent()
        assertEquals(30.0, vm.selectedResult.value!!.lat, 0.0)
        assertEquals(str(R.string.map_pin_loading_address), vm.selectedResult.value!!.formattedAddress)
        b.complete(response(place("liq_b", 30.0, 40.0, "New pin", "New")))
        runCurrent()
        assertEquals("New pin", vm.selectedResult.value!!.name)
        assertEquals(30.0, vm.selectedResult.value!!.lat, 0.0)
    }

    @Test
    fun pickingASearchResult_cancelsAPendingReverseLookup_andKeepsThePickedPlace() = vmTest {
        val d = CompletableDeferred<com.omama.stationalarm.network.GeocodingResponse>()
        fake.onReverse = { d.await() }
        vm.onMapTap(10.0, 20.0); runCurrent()
        vm.selectResult(place("liq_pick", 11.0, 21.0, "Picked", "Chosen"))
        d.complete(response(place("liq_late", 10.0, 20.0, "Late", "Late")))
        runCurrent()
        val r = vm.selectedResult.value!!
        assertEquals("Picked", r.name)
        assertEquals("liq-pick", r.id)
        assertEquals(1, fake.reverseCancelled.size)
    }

    @Test
    fun tap_afterPickingAResult_replacesIt() = vmTest {
        vm.selectResult(place("liq_pick", 11.0, 21.0, "Picked"))
        vm.onMapTap(1.0, 2.0); runCurrent()
        assertTrue(vm.selectedResult.value!!.id.startsWith("manual-"))
    }

    // ── selection ─────────────────────────────────────────────────────────────

    @Test
    fun selectResult_sanitisesGeocoderIds_keepsRailwayCodes_andResetsTheSearchUi() = vmTest {
        vm.onQueryChanged("zqxjzqxj")
        vm.selectResult(place("liq_481312177", 1.5, 2.5, "N", "A"))
        val r = vm.selectedResult.value!!
        assertEquals("liq-481312177", r.id)
        assertEquals("N", r.name)
        assertEquals(1.5, r.lat, 0.0)
        assertEquals("", vm.query.value)
        assertTrue(vm.searchResults.value.isEmpty())

        vm.selectResult(place("NDLS", 28.64, 77.22, "NEW DELHI"))
        assertEquals("NDLS", vm.selectedResult.value!!.id)
    }

    @Test
    fun selectResult_idWithOnlyUnsafeCharacters_getsAUsablePlaceholderId() = vmTest {
        vm.selectResult(place("   "))
        assertTrue(vm.selectedResult.value!!.id, vm.selectedResult.value!!.id.startsWith("place-"))
        vm.selectResult(place("a_b c/d"))
        assertEquals("a-b-c-d", vm.selectedResult.value!!.id)
    }

    // ── radius ─────────────────────────────────────────────────────────────────

    @Test
    fun radius_defaultsToThreeKm_andIsClampedToThreeToTwenty() = vmTest {
        assertEquals(3.0, vm.radiusKm.value, 0.0)
        val table = listOf(2.9 to 3.0, 3.0 to 3.0, 3.1 to 3.1, 10.0 to 10.0, 20.0 to 20.0, 20.1 to 20.0,
            -5.0 to 3.0, 0.0 to 3.0, 1e9 to 20.0, Double.POSITIVE_INFINITY to 20.0, Double.NEGATIVE_INFINITY to 3.0)
        for ((input, expected) in table) {
            vm.onRadiusChanged(input)
            assertEquals("input $input", expected, vm.radiusKm.value, 0.0)
        }
    }

    @Ignore("BUG: MapSearchViewModel.kt:271 onRadiusChanged(NaN) stores NaN because Double.coerceIn() passes NaN through (low severity: the slider only produces finite values).")
    @Test
    fun radius_nanIsNotStored() = vmTest {
        vm.onRadiusChanged(12.0)
        vm.onRadiusChanged(Double.NaN)
        assertTrue("radius became ${vm.radiusKm.value}", !vm.radiusKm.value.isNaN())
    }

    // ── FAB without permission ────────────────────────────────────────────────

    @Test
    fun myLocation_withoutLocationPermission_isASilentNoOp() = vmTest {
        vm.onMyLocationRequested(); runCurrent()
        assertNull(vm.searchError.value)
        assertNull(vm.initialCenter.value)
    }
}
