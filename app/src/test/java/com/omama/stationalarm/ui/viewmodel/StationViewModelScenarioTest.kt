package com.omama.stationalarm.ui.viewmodel

import android.os.Looper
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.omama.stationalarm.StationAlarmApplication
import com.omama.stationalarm.data.ActiveStation
import com.omama.stationalarm.data.StationData
import com.omama.stationalarm.repository.StationRepository
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * StationViewModel's own logic: distance merging, search delegation and event passthrough. The repository's
 * lateinit flow is replaced by a MutableStateFlow (and restored), so no Room/geofence machinery is involved.
 * The mutator methods only forward to StationRepository (covered by the repository tests) and are not repeated.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], application = android.app.Application::class)
class StationViewModelScenarioTest {

    private val flowField = StationRepository::class.java.getDeclaredField("activeStationsFlow").apply { isAccessible = true }
    private val distancesField = StationRepository::class.java.getDeclaredField("_distancesFlow").apply { isAccessible = true }

    private var savedFlow: Any? = null
    private lateinit var source: MutableStateFlow<List<ActiveStation>>
    private lateinit var vm: StationViewModel
    private var observed: LiveData<List<ActiveStation>>? = null
    private val sink = Observer<List<ActiveStation>> { }

    private fun st(id: String) = ActiveStation(stationId = id, alertDistanceKm = 5.0, notify = true, vibrate = true, sound = true)

    @Suppress("UNCHECKED_CAST")
    private fun clearDistances() { (distancesField.get(StationRepository) as MutableStateFlow<Map<String, Double>>).value = emptyMap() }

    @Before
    fun setUp() {
        val app = StationAlarmApplication()         // no onCreate(): the VM only needs the cast to succeed
        StationData.initialize(ApplicationProvider.getApplicationContext())
        savedFlow = flowField.get(StationRepository)
        source = MutableStateFlow(emptyList())
        StationRepository.activeStationsFlow = source
        clearDistances()
        vm = StationViewModel(app)
    }

    @After
    fun tearDown() {
        observed?.removeObserver(sink)
        flowField.set(StationRepository, savedFlow)
        clearDistances()
    }

    private fun observe(): LiveData<List<ActiveStation>> =
        vm.activeStations.also { it.observeForever(sink); observed = it }

    /** Values are produced on Dispatchers.IO and posted to the main looper, so pump it until the condition holds. */
    private fun awaitValue(live: LiveData<List<ActiveStation>>, ok: (List<ActiveStation>?) -> Boolean): List<ActiveStation>? {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (ok(live.value)) return live.value
            Thread.sleep(20)
        }
        return live.value
    }

    @Test
    fun distances_areMergedIntoTheMatchingStationsOnly() {
        source.value = listOf(st("VMT1"), st("VMT2"))
        StationRepository.updateStationDistances(mapOf("VMT1" to 12.5, "SOMEONE_ELSE" to 99.0))
        val live = observe()
        val list = awaitValue(live) { it?.size == 2 && it.any { s -> s.currentDistanceKm != null } }!!
        assertEquals(12.5, list.single { it.stationId == "VMT1" }.currentDistanceKm!!, 0.0)
        assertNull(list.single { it.stationId == "VMT2" }.currentDistanceKm)
    }

    @Test
    fun aNewDistanceReading_updatesTheEmittedList() {
        source.value = listOf(st("VMT3"))
        val live = observe()
        awaitValue(live) { it?.size == 1 }
        StationRepository.updateStationDistances(mapOf("VMT3" to 7.0))
        val list = awaitValue(live) { it?.firstOrNull()?.currentDistanceKm == 7.0 }!!
        assertEquals(7.0, list.single().currentDistanceKm!!, 0.0)
        StationRepository.updateStationDistances(mapOf("VMT3" to 3.25))
        assertEquals(3.25, awaitValue(live) { it?.firstOrNull()?.currentDistanceKm == 3.25 }!!.single().currentDistanceKm!!, 0.0)
    }

    @Test
    fun addingAndRemovingStations_isReflected_andEmptyListIsEmptyNotNull() {
        val live = observe()
        assertEquals(emptyList<ActiveStation>(), awaitValue(live) { it != null })
        source.value = listOf(st("VMT4"), st("VMT5"))
        assertEquals(setOf("VMT4", "VMT5"), awaitValue(live) { it?.size == 2 }!!.map { it.stationId }.toSet())
        source.value = listOf(st("VMT5"))
        assertEquals(listOf("VMT5"), awaitValue(live) { it?.size == 1 }!!.map { it.stationId })
    }

    @Test
    fun mergingDoesNotMutateOtherFields() {
        source.value = listOf(st("VMT6").copy(alertDistanceKm = 9.5, status = "PAUSED", stationName = "Home"))
        StationRepository.updateStationDistances(mapOf("VMT6" to 1.0))
        val s = awaitValue(observe()) { it?.firstOrNull()?.currentDistanceKm == 1.0 }!!.single()
        assertEquals(9.5, s.alertDistanceKm, 0.0)
        assertEquals("PAUSED", s.status)
        assertEquals("Home", s.stationName)
    }

    @Test
    fun searchStations_delegatesToTheRankedOfflineSearch() {
        assertEquals("NDLS", vm.searchStations("ndls").first().id)
        assertEquals("LNL", vm.searchStations("lonavala").single().id)
        assertTrue(vm.searchStations("").isEmpty())
        assertTrue(vm.searchStations("zqxjzqxj").isEmpty())
    }

    @Test
    fun errorEvents_isTheRepositorysSharedFlow() {
        assertSame(StationRepository.errorEvents, vm.errorEvents)
        assertNotNull(vm.errorEvents)
    }
}
