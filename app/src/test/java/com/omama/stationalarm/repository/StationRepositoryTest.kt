package com.omama.stationalarm.repository

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkManager
import com.omama.stationalarm.data.ActiveStation
import com.omama.stationalarm.data.Station
import com.omama.stationalarm.geofence.GeofenceManager
import com.omama.stationalarm.repository.RepositoryTestEnv.ErrorCollector
import com.omama.stationalarm.repository.RepositoryTestEnv.addAndAwait
import com.omama.stationalarm.repository.RepositoryTestEnv.allRows

import com.omama.stationalarm.repository.RepositoryTestEnv.entity
import com.omama.stationalarm.repository.RepositoryTestEnv.eventually
import com.omama.stationalarm.repository.RepositoryTestEnv.geofenceIds
import com.omama.stationalarm.repository.RepositoryTestEnv.hasNoGeofences
import com.omama.stationalarm.repository.RepositoryTestEnv.isRegistered
import com.omama.stationalarm.repository.RepositoryTestEnv.newId
import com.omama.stationalarm.repository.RepositoryTestEnv.row
import com.omama.stationalarm.repository.RepositoryTestEnv.seed
import com.omama.stationalarm.repository.RepositoryTestEnv.setLocationPermission
import com.omama.stationalarm.repository.RepositoryTestEnv.status
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * Tests the real [StationRepository] together with the real (locked) GeofenceManager and Room. Only Play
 * Services is replaced ([FakeGms]); see [RepositoryTestEnv]. The repository does its work fire-and-forget on its
 * own IO scope, so tests wait for observable effects rather than for return values.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], application = Application::class, shadows = [ShadowLocationServices::class])
class StationRepositoryTest {

    @Before fun setUp() = RepositoryTestEnv.setUp()
    @After fun tearDown() = RepositoryTestEnv.tearDown()

    private fun activeStation(id: String, radius: Double = 5.0, name: String = "", lat: Double = 0.0, lon: Double = 0.0) =
        ActiveStation(stationId = id, alertDistanceKm = radius, notify = true, vibrate = true, sound = true, stationName = name, lat = lat, lon = lon)

    /** Lets a possible (wrong) follow-up effect land before asserting that something did NOT happen. */
    private fun settle() = Thread.sleep(400)

    // ── addActiveStation ─────────────────────────────────────────────────────

    @Test
    fun addRailwayStation_resolvesCoordinatesAndNameFromTheOfflineList_andRegistersNineGeofences() {
        StationRepository.addActiveStation(activeStation("NDLS"))
        eventually("NDLS geofences") { isRegistered("NDLS") }

        val r = row("NDLS")!!
        assertEquals(28.642464, r.lat, 0.0)
        assertEquals(77.220154, r.lon, 0.0)
        assertEquals("NEW DELHI", r.stationName)
        assertEquals("MONITORING", r.status)
        assertEquals(geofenceIds("NDLS"), FakeGms.registered.filter { it.startsWith("geofence_NDLS_") }.toSet())
    }

    @Test
    fun addStation_registersEachTierAtItsOffsetRadius_withTheAlertTierAtTheConfiguredDistance() {
        val id = newId()
        addAndAwait(id, radiusKm = 5.0)

        val expectedMetres = mapOf(
            "level1" to 5_000f, "level2" to 10_000f, "level3" to 35_000f, "level4" to 45_000f, "level5" to 65_000f,
            "level6" to 105_000f, "level7" to 155_000f, "level8" to 205_000f, "alert" to 5_000f,
        )
        expectedMetres.forEach { (tier, m) -> assertEquals(tier, m, FakeGms.radius["geofence_${id}_$tier"]!!, 0.5f) }
    }

    @Test
    fun addStation_registersTheLevelTiersFirstAndTheAlertTierInASeparateRequest() {
        val id = newId()
        addAndAwait(id)
        assertEquals(2, FakeGms.addCalls.size)
        assertEquals(8, FakeGms.addCalls[0].size)
        assertEquals(listOf("geofence_${id}_alert"), FakeGms.addCalls[1])
    }

    @Test
    fun addStation_clampsTheAlertGeofenceToTheOneHundredMetreMinimum() {
        val id = newId()
        addAndAwait(id, radiusKm = 0.05)
        assertEquals(100f, FakeGms.radius["geofence_${id}_alert"]!!, 0.01f)
        assertEquals(50f, FakeGms.radius["geofence_${id}_level1"]!!, 0.5f) // only the alert tier is clamped
    }

    @Test
    fun addCustomStation_sanitisesTheId_andEveryRequestIdSurvivesTheReceiversSplit() {
        StationRepository.addActiveStation(
            activeStation("liq_481312177", name = "Home"),
            Station("liq_481312177", "Geocoded Home", 12.9, 77.6),
        )
        eventually("sanitised geofences") { isRegistered("liq-481312177") }

        val r = row("liq-481312177")!!
        assertNull("the unsanitised id must never reach Room", row("liq_481312177"))
        assertEquals("Home", r.stationName) // the user-typed name wins over the geocoder's
        assertEquals(12.9, r.lat, 0.0)
        assertEquals(77.6, r.lon, 0.0)
        FakeGms.registered.forEach {
            val parts = it.split("_", limit = 3) // exactly what GeofenceBroadcastReceiver does
            assertEquals(it, 3, parts.size)
            assertEquals("liq-481312177", parts[1])
        }
    }

    @Test
    fun addStation_withBlankName_fallsBackToTheResolvedStationName() {
        val id = newId()
        StationRepository.addActiveStation(activeStation(id, name = "   "), Station(id, "Dropped Pin", 1.5, 2.5))
        eventually("row") { row(id) != null }
        assertEquals("Dropped Pin", row(id)!!.stationName)
    }

    @Test
    fun addStation_keepsUnicodeEmojiAndQuoteNamesVerbatim() {
        val id = newId()
        val name = "मुंबई 🚆 \"O'Brien\"; DROP TABLE active_stations;--"
        StationRepository.addActiveStation(activeStation(id, name = name), Station(id, "x", 1.0, 2.0))
        eventually("row") { row(id) != null }
        assertEquals(name, row(id)!!.stationName)
    }

    @Test
    fun addStation_withBlankId_getsAGeneratedSafeId() {
        StationRepository.addActiveStation(activeStation(""), Station("", "Pin", 1.0, 2.0))
        eventually("row") { allRows().isNotEmpty() }
        val id = allRows().single().stationId
        assertTrue(id, id.matches(Regex("place-\\d+")))
        eventually("geofences for generated id") { isRegistered(id) }
    }

    @Test
    fun addStation_acceptsTheExtremeValidCoordinates() {
        listOf(90.0 to 180.0, -90.0 to -180.0, 0.0 to 180.0, -90.0 to 0.5).forEach { (lat, lon) ->
            val id = newId()
            addAndAwait(id, lat = lat, lon = lon)
            assertEquals(lat, row(id)!!.lat, 0.0)
            assertEquals(lon, row(id)!!.lon, 0.0)
        }
    }

    @Test
    fun addStation_twice_replacesTheAlarmInsteadOfDuplicatingIt() {
        val id = newId()
        addAndAwait(id, radiusKm = 3.0)

        StationRepository.addActiveStation(activeStation(id, radius = 9.0, name = "Second"), Station(id, "x", 12.97, 77.59))
        eventually("re-registered at 9 km") { FakeGms.radius["geofence_${id}_level1"] == 9_000f }

        assertEquals(1, allRows().count { it.stationId == id })
        assertEquals(9.0, row(id)!!.alertDistanceKm, 0.0)
        assertEquals("Second", row(id)!!.stationName)
        assertEquals(geofenceIds(id), FakeGms.registered.filter { it.contains("_${id}_") }.toSet()) // still 9, not 18
    }

    @Test
    fun addStation_atTheCap_isRejectedWithTheDocumentedMessage_andNothingIsPersistedOrRegistered() {
        val seeded = (1..GeofenceManager.MAX_ACTIVE_STATIONS).map { newId("cap") }
        seed(*seeded.map { entity(it) }.toTypedArray())
        val extra = newId("extra")

        ErrorCollector().use { errors ->
            StationRepository.addActiveStation(activeStation(extra), Station(extra, "x", 1.0, 2.0))
            eventually("cap error") { errors.events.isNotEmpty() }
            assertEquals(
                "You have reached the maximum of ${GeofenceManager.MAX_ACTIVE_STATIONS} alarms. Please delete some before adding more.",
                errors.events.single(),
            )
        }
        assertNull(row(extra))
        assertEquals(GeofenceManager.MAX_ACTIVE_STATIONS, allRows().size)
        assertTrue("cap check must happen before any GMS call", FakeGms.addCalls.isEmpty())
    }

    @Test
    fun addStation_atTheCap_stillAllowsEditingAnExistingAlarm() {
        val seeded = (1..GeofenceManager.MAX_ACTIVE_STATIONS).map { newId("cap") }
        seed(*seeded.map { entity(it, radiusKm = 5.0) }.toTypedArray())
        val target = seeded.first()

        StationRepository.addActiveStation(activeStation(target, radius = 8.0), Station(target, "x", 12.97, 77.59))
        eventually("existing alarm re-saved") { row(target)!!.alertDistanceKm == 8.0 && isRegistered(target) }
        assertEquals(GeofenceManager.MAX_ACTIVE_STATIONS, allRows().size)
    }

    @Test
    fun addStation_withoutLocationPermission_rollsTheRowBack_andReportsIt() {
        setLocationPermission(false)
        val id = newId()
        ErrorCollector().use { errors ->
            StationRepository.addActiveStation(activeStation(id), Station(id, "x", 1.0, 2.0))
            eventually("error") { errors.events.isNotEmpty() }
            assertEquals("Location permission is required.", errors.events.single())
        }
        eventually("rollback") { row(id) == null }
        assertTrue(FakeGms.addCalls.isEmpty())
    }

    @Test
    fun addStation_unknownIdWithoutCoordinates_rollsBackWithAStationNotFoundMessage() {
        val id = newId("unknown")
        ErrorCollector().use { errors ->
            StationRepository.addActiveStation(activeStation(id)) // no customStation, not a railway code, lat/lon 0
            eventually("error") { errors.events.isNotEmpty() }
            assertEquals("Station $id not found.", errors.events.single())
        }
        eventually("rollback") { row(id) == null }
    }

    @Test
    fun addStation_transientGmsFailure_isRetriedAndEventuallySucceeds() {
        FakeGms.failNextAdds.set(1)
        val id = newId()
        ErrorCollector().use { errors ->
            StationRepository.addActiveStation(activeStation(id), Station(id, "x", 1.0, 2.0))
            eventually("registered after one retry", timeoutMs = 30_000) { isRegistered(id) }
            assertTrue(errors.events.isEmpty())
        }
        assertNotNull(row(id))
    }

    @Test
    fun addStation_permanentGmsFailure_rollsBackAfterThreeAttempts_andReportsTheCause() {
        FakeGms.failAdds = true
        val id = newId()
        ErrorCollector().use { errors ->
            StationRepository.addActiveStation(activeStation(id), Station(id, "x", 1.0, 2.0))
            eventually("error after retries", timeoutMs = 40_000) { errors.events.isNotEmpty() }
            assertEquals(FakeGms.BOOM, errors.events.single())
        }
        eventually("rollback") { row(id) == null }
        assertEquals(3, FakeGms.addCalls.size) // GeofenceManager's bounded retry
        assertTrue(hasNoGeofences(id))
    }

    // ── dismiss / pause / re-arm / remove ────────────────────────────────────

    @Test
    fun dismissStation_pausesTheRow_removesAllNineGeofences_andClearsTheDistance() {
        val id = newId()
        addAndAwait(id)
        StationRepository.updateStationDistances(mapOf(id to 4.2))
        FakeGms.removeCalls.clear()

        StationRepository.dismissStation(id)
        eventually("dismissed") { hasNoGeofences(id) && !StationRepository.distancesFlow.value.containsKey(id) }

        assertEquals("PAUSED", status(id))
        assertNotNull("the row is kept so the user can re-arm it", row(id))
        assertEquals(geofenceIds(id), FakeGms.removeCalls.single().toSet())
    }

    @Test
    fun dismissStation_whenGmsRemovalKeepsFailing_stillPausesTheRow_andClearsTheDistance() {
        val id = newId()
        RepositoryTestEnv.initWorkManager() // the failed removal enqueues a cleanup worker
        addAndAwait(id)
        StationRepository.updateStationDistances(mapOf(id to 4.2))
        FakeGms.failRemoves = true

        StationRepository.dismissStation(id)
        eventually("paused despite removal failure", timeoutMs = 30_000) { !StationRepository.distancesFlow.value.containsKey(id) }

        assertEquals("PAUSED", status(id))
        assertEquals("GeofenceManager retries a removal three times", 3, FakeGms.removeCalls.size)
    }

    @Test
    fun failedRemoval_enqueuesAPersistentCleanupWorker() {
        val id = newId()
        RepositoryTestEnv.initWorkManager()
        addAndAwait(id)
        FakeGms.failRemoves = true

        StationRepository.dismissStation(id)
        eventually("cleanup worker queued", timeoutMs = 30_000) {
            WorkManager.getInstance(RepositoryTestEnv.app).getWorkInfosForUniqueWork("geofence_cleanup_$id")
                .get(5, TimeUnit.SECONDS).isNotEmpty()
        }
    }

    @Test
    fun pauseThenRearm_roundTripsStatusAndGeofences_andForgetsTheOldDistance() {
        val id = newId()
        addAndAwait(id)

        StationRepository.pauseStation(id)
        eventually("paused") { hasNoGeofences(id) && status(id) == "PAUSED" }

        StationRepository.updateStationDistances(mapOf(id to 9.0))
        StationRepository.rearmStation(id)
        eventually("re-armed") { isRegistered(id) && status(id) == "MONITORING" }
        assertFalse("re-arm must forget the previous trip's distance", StationRepository.distancesFlow.value.containsKey(id))
    }

    @Test
    fun rearm_whenRegistrationFails_flipsTheAlarmBackToPaused_andReportsIt() {
        val id = newId()
        seed(entity(id, status = "PAUSED"))
        setLocationPermission(false)

        ErrorCollector().use { errors ->
            StationRepository.rearmStation(id)
            eventually("error") { errors.events.isNotEmpty() }
            assertEquals("Location permission is required.", errors.events.single())
        }
        assertEquals("PAUSED", status(id))
        assertTrue(hasNoGeofences(id))
    }

    @Test
    fun rearm_ofAnUnknownId_doesNothing() {
        val id = newId("ghost")
        StationRepository.rearmStation(id)
        settle()
        assertNull(row(id))
        assertTrue(FakeGms.addCalls.isEmpty())
    }

    @Test
    fun removeActiveStation_deletesRowAndGeofences_andClearsTheDistance() {
        val id = newId()
        addAndAwait(id)
        StationRepository.updateStationDistances(mapOf(id to 1.0))

        StationRepository.removeActiveStation(id)
        eventually("removed") { row(id) == null && hasNoGeofences(id) && !StationRepository.distancesFlow.value.containsKey(id) }
    }

    @Test
    fun removingTheLastStation_leavesAnEmptyList() {
        val id = newId()
        addAndAwait(id)
        StationRepository.removeActiveStation(id)
        eventually("empty") { allRows().isEmpty() && FakeGms.registered.isEmpty() }
        assertTrue(runBlocking { StationRepository.getAllActiveStationsList() }.isEmpty())
    }

    @Test
    fun removeActiveStation_ofAnUnknownId_isHarmless() {
        val id = newId("ghost")
        StationRepository.removeActiveStation(id)
        eventually("GMS asked to clean up anyway") { FakeGms.removeCalls.any { it.containsAll(geofenceIds(id)) } }
        assertTrue(allRows().isEmpty())
    }

    @Test
    fun pauseAndRearmRacing_endInAConsistentState_whateverTheInterleaving() {
        val id = newId()
        addAndAwait(id)
        FakeGms.addCalls.clear()
        FakeGms.removeCalls.clear()
        val rounds = 8

        repeat(rounds) { StationRepository.pauseStation(id); StationRepository.rearmStation(id) }
        eventually("all operations finished", timeoutMs = 30_000) {
            FakeGms.removeCalls.size == rounds && FakeGms.addCalls.size == rounds * 2
        }

        // The per-station lock must stop a late removal landing after a re-arm (or the reverse):
        // the row says MONITORING exactly when the geofences are live.
        assertEquals(status(id) == "MONITORING", isRegistered(id))
        assertTrue(isRegistered(id) || hasNoGeofences(id))
    }

    // ── alerting ─────────────────────────────────────────────────────────────

    @Test
    fun markAlertingSync_promotesMonitoring_stampsLastTriggered_andRemovesGeofences() = runBlocking {
        val id = newId()
        addAndAwait(id)
        val before = System.currentTimeMillis()

        StationRepository.markAlertingSync(id)

        assertEquals("ALERTING", status(id))
        val stamp = row(id)!!.lastTriggeredAt!!
        assertTrue(stamp in before..System.currentTimeMillis())
        assertTrue("alert geofences are removed so the alarm cannot re-fire", hasNoGeofences(id))
        assertTrue(StationRepository.isArmed(id)) // ALERTING still counts as armed
    }

    @Test
    fun markAlerting_fireAndForget_alsoPromotesToAlerting() {
        val id = newId()
        seed(entity(id))
        StationRepository.markAlerting(id)
        eventually("alerting") { status(id) == "ALERTING" }
    }

    @Test
    fun markAlertingSync_onAPausedRow_doesNotResurrectIt() = runBlocking {
        val id = newId()
        seed(entity(id, status = "PAUSED"))
        StationRepository.markAlertingSync(id)
        assertEquals("PAUSED", status(id))
        assertFalse(StationRepository.isArmed(id))
    }

    /**
     * The status guard says a late/duplicate trigger on a dismissed (PAUSED) alarm is ignored, but
     * markAlertingSync/markAlerting still call setLastTriggeredAt unconditionally, so the card would show
     * "Last triggered: just now" for an alarm that did not fire.
     */
    @Ignore("BUG: StationRepository.kt:229-230 and 244-245 stamp lastTriggeredAt even when markAlertingFromMonitoring rejected the transition (PAUSED/missing row)")
    @Test
    fun markAlertingSync_onAPausedRow_doesNotStampLastTriggered() = runBlocking {
        val id = newId()
        seed(entity(id, status = "PAUSED"))
        StationRepository.markAlertingSync(id)
        assertNull(row(id)!!.lastTriggeredAt)
    }

    @Test
    fun resetToMonitoring_andResetAllAlerting_onlyTouchTheRightRows() = runBlocking {
        val (a, b, c, d) = List(4) { newId() }
        seed(entity(a, status = "ALERTING"), entity(b, status = "ALERTING"), entity(c, status = "PAUSED"), entity(d, status = "MONITORING"))

        StationRepository.resetToMonitoring(a)
        assertEquals("MONITORING", status(a))
        assertEquals(1, StationRepository.resetAllAlertingToMonitoring()) // only b is left
        assertEquals("MONITORING", status(b))
        assertEquals("PAUSED", status(c))
        assertEquals(0, StationRepository.resetAllAlertingToMonitoring())
    }

    @Test
    fun isArmed_isTrueOnlyForMonitoringAndAlerting() = runBlocking {
        val (m, a, p) = List(3) { newId() }
        seed(entity(m, status = "MONITORING"), entity(a, status = "ALERTING"), entity(p, status = "PAUSED"))
        assertTrue(StationRepository.isArmed(m))
        assertTrue(StationRepository.isArmed(a))
        assertFalse(StationRepository.isArmed(p))
        assertFalse("a missing row is not armed", StationRepository.isArmed(newId("ghost")))
    }

    // ── settings / rename ────────────────────────────────────────────────────

    @Test
    fun updateActiveStationSettings_persistsTheNewValues_andReRegistersGeofencesAtTheNewRadius() {
        val id = newId()
        addAndAwait(id, radiusKm = 3.0)
        FakeGms.removeCalls.clear()

        StationRepository.updateActiveStationSettings(id, 8.0, notify = false, vibrate = false, sound = true, reminder = "Wake me", sendReminder = true)
        eventually("re-registered at 8 km") { FakeGms.radius["geofence_${id}_level1"] == 8_000f && isRegistered(id) }

        val r = row(id)!!
        assertEquals(8.0, r.alertDistanceKm, 0.0)
        assertEquals(false, r.notify)
        assertEquals(false, r.vibrate)
        assertEquals(true, r.sound)
        assertEquals("Wake me", r.customReminder)
        assertEquals(true, r.sendReminder)
        assertEquals("MONITORING", r.status)
        assertEquals("Pin $id", r.stationName)
        assertEquals(12.97, r.lat, 0.0)
        assertEquals(1, FakeGms.removeCalls.size) // the old geofences were removed first
    }

    @Test
    fun updateActiveStationSettings_whenReRegistrationFails_keepsTheSettings_butReportsTheError() {
        val id = newId()
        addAndAwait(id, radiusKm = 3.0)
        setLocationPermission(false)

        ErrorCollector().use { errors ->
            StationRepository.updateActiveStationSettings(id, 6.0, true, true, true, null, false)
            eventually("error") { errors.events.isNotEmpty() }
            assertEquals("Location permission is required.", errors.events.single())
        }
        assertEquals(6.0, row(id)!!.alertDistanceKm, 0.0)
    }

    /** A paused alarm owns no geofences (pauseStation/dismissStation remove them; boot/reconcile skip it). */
    @Ignore("BUG: StationRepository.kt:431-441 updateActiveStationSettings re-registers geofences without checking status, so editing a PAUSED alarm leaves live geofences behind")
    @Test
    fun updateActiveStationSettings_onAPausedAlarm_doesNotRegisterGeofences() {
        val id = newId()
        seed(entity(id, status = "PAUSED", radiusKm = 3.0))

        StationRepository.updateActiveStationSettings(id, 8.0, true, true, true, null, false)
        eventually("settings saved") { row(id)!!.alertDistanceKm == 8.0 }
        settle()

        assertEquals("PAUSED", status(id))
        assertTrue("a paused alarm must not own live geofences", hasNoGeofences(id))
    }

    @Test
    fun updateStationName_acceptsEmptyUnicodeAndHugeNames_withoutTouchingOtherColumns() {
        val id = newId()
        seed(entity(id, radiusKm = 7.0))
        for (name in listOf("", "मुंबई 🚆", "x".repeat(50_000), "  spaced  ")) {
            StationRepository.updateStationName(id, name)
            eventually("renamed to ${name.take(10)}") { row(id)!!.stationName == name }
        }
        assertEquals(7.0, row(id)!!.alertDistanceKm, 0.0)
        assertEquals("MONITORING", row(id)!!.status)
    }

    // ── bulk re-registration / reconcile ─────────────────────────────────────

    @Test
    fun reRegisterAllGeofencesNow_registersOnlyMonitoringRows() = runBlocking {
        val (m, a, p) = List(3) { newId() }
        seed(entity(m, status = "MONITORING"), entity(a, status = "ALERTING"), entity(p, status = "PAUSED"))

        assertTrue(StationRepository.reRegisterAllGeofencesNow())

        assertTrue(isRegistered(m))
        assertTrue("ALERTING geofences were removed on purpose", hasNoGeofences(a))
        assertTrue(hasNoGeofences(p))
    }

    @Test
    fun reRegisterAllGeofencesNow_withNothingToDo_succeedsWithoutTouchingGms() = runBlocking {
        assertTrue(StationRepository.reRegisterAllGeofencesNow())
        assertTrue(FakeGms.addCalls.isEmpty())
    }

    @Test
    fun reRegisterAllGeofencesNow_reportsFailure_whenAnyRegistrationFails() = runBlocking {
        seed(entity(newId()))
        setLocationPermission(false)
        assertFalse(StationRepository.reRegisterAllGeofencesNow())
    }

    @Test
    fun reRegisterAllGeofencesNow_keepsGoingAfterOneStationFails_butStillReportsFailure() = runBlocking {
        val ok = newId("ok")
        val unresolvable = newId("bad")
        seed(entity(ok), entity(unresolvable, lat = 0.0, lon = 0.0)) // (0,0) custom row cannot be resolved
        assertFalse(StationRepository.reRegisterAllGeofencesNow())
        assertTrue("the resolvable station is still registered", isRegistered(ok))
    }

    @Test
    fun reconcileOrphans_refreshesMonitoringRowsOnly() = runBlocking {
        val (m, p) = List(2) { newId() }
        seed(entity(m, status = "MONITORING"), entity(p, status = "PAUSED"))
        StationRepository.reconcileOrphans()
        assertTrue(isRegistered(m))
        assertTrue(hasNoGeofences(p))
    }

    @Test
    fun reconcileOrphans_withNoActiveRows_doesNothing() = runBlocking {
        StationRepository.reconcileOrphans()
        assertTrue(FakeGms.addCalls.isEmpty())
    }

    // ── lookups ──────────────────────────────────────────────────────────────

    @Test
    fun getStationById_prefersTheOfflineRailwayList_ignoringCase() = runBlocking {
        val s = StationRepository.getStationById("ndls")
        assertEquals("NEW DELHI", s!!.name)
        assertEquals("NDLS", s.id)
    }

    @Test
    fun getStationById_fallsBackToAStoredCustomRow_withLocationNameWhenUnnamed() = runBlocking {
        val named = newId(); val unnamed = newId()
        seed(entity(named, name = "Home", lat = 1.0, lon = 2.0), entity(unnamed, name = "", lat = 3.0, lon = 4.0))
        assertEquals(Station(named, "Home", 1.0, 2.0), StationRepository.getStationById(named))
        assertEquals(Station(unnamed, "Custom Location", 3.0, 4.0), StationRepository.getStationById(unnamed))
    }

    @Test
    fun getStationById_isNullForUnknownIds_andForRowsWithoutCoordinates() = runBlocking {
        val noCoords = newId()
        seed(entity(noCoords, lat = 0.0, lon = 0.0))
        assertNull(StationRepository.getStationById(noCoords))
        assertNull(StationRepository.getStationById(newId("ghost")))
    }

    @Test
    fun getStationByIdSync_onlyKnowsTheRailwayList() {
        val custom = newId()
        seed(entity(custom, lat = 1.0, lon = 2.0))
        assertNotNull(StationRepository.getStationByIdSync("PUNE"))
        assertNull(StationRepository.getStationByIdSync(custom))
    }

    @Test
    fun searchStations_blankQueryReturnsNothing_andARealQueryFindsRailwayStations() {
        assertTrue(StationRepository.searchStations("  ").isEmpty())
        assertTrue(StationRepository.searchStations("new delhi").any { it.id == "NDLS" })
    }

    // ── flows ────────────────────────────────────────────────────────────────

    @Test
    fun activeStationsFlow_emitsEachLifecycleStateInOrder() {
        val id = newId()
        val seen = CopyOnWriteArrayList<List<String>>()
        val job = CoroutineScope(Dispatchers.Default).launch {
            StationRepository.activeStationsFlow.collect { list -> seen.add(list.map { "${it.stationId}:${it.status}" }) }
        }
        fun await(expected: List<String>) = eventually("flow shows $expected, saw $seen") { seen.lastOrNull() == expected }
        try {
            await(emptyList())
            addAndAwait(id); await(listOf("$id:MONITORING"))
            StationRepository.pauseStation(id); await(listOf("$id:PAUSED"))
            StationRepository.rearmStation(id); await(listOf("$id:MONITORING"))
            StationRepository.removeActiveStation(id); await(emptyList())
        } finally {
            job.cancel()
        }
        val distinct = seen.fold(emptyList<List<String>>()) { acc, s -> if (acc.lastOrNull() == s) acc else acc + listOf(s) }
        assertEquals(
            listOf(emptyList(), listOf("$id:MONITORING"), listOf("$id:PAUSED"), listOf("$id:MONITORING"), emptyList()),
            distinct,
        )
    }

    @Test
    fun activeStationsFlow_mapsEntitiesToDomainModels_withoutALiveDistance() {
        val id = newId()
        seed(entity(id, name = "Home", lat = 1.0, lon = 2.0))
        val first = runBlocking { StationRepository.activeStationsFlow.first() }.single()
        assertEquals(id, first.stationId)
        assertEquals("Home", first.stationName)
        assertNull(first.currentDistanceKm)
    }

    @Test
    fun updateStationDistances_mergesAndOverwritesPerStation() {
        val (a, b) = List(2) { newId() }
        StationRepository.updateStationDistances(mapOf(a to 1.0, b to 2.0))
        StationRepository.updateStationDistances(mapOf(a to 5.0))
        assertEquals(5.0, StationRepository.distancesFlow.value[a]!!, 0.0)
        assertEquals(2.0, StationRepository.distancesFlow.value[b]!!, 0.0)
    }

    @Test
    fun updateStationDistances_fromManyThreads_losesNoEntries() {
        val ids = List(100) { newId("d") }
        val threads = ids.map { id -> Thread { StationRepository.updateStationDistances(mapOf(id to 1.0)) } }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        assertTrue(StationRepository.distancesFlow.value.keys.containsAll(ids))
    }

    @Test
    fun uninitialisedRepository_failsWithAClearException_ratherThanSilently() {
        val field = StationRepository::class.java.getDeclaredField("database").apply { isAccessible = true }
        val original = field.get(StationRepository)
        field.set(StationRepository, null)
        try {
            runBlocking { StationRepository.getAllActiveStationsList() }
            org.junit.Assert.fail("expected UninitializedPropertyAccessException")
        } catch (e: UninitializedPropertyAccessException) {
            // expected: workers rely on this being an Exception so they can Result.retry()
        } finally {
            field.set(StationRepository, original)
        }
    }
}
