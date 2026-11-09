package com.omama.stationalarm.service

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.gms.location.Priority
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowPowerManager

/**
 * Adaptive polling ("gearbox") of the behaviour-locked LocationService, driven through reflection on the
 * private adjustPollingInterval() / startLocationUpdates() / restartLocationUpdates().
 *
 *   nearest station <= 5 km: 10 s | <= 15 km: 30 s | <= 30 km: 60 s | <= 60 km: 5 min | beyond: 10 min
 *   priority: HIGH_ACCURACY up to and including 100 km, BALANCED_POWER beyond.
 *
 * (CLAUDE.md still says "5 s .. 5 m"; the code is 10 s .. 10 min. These tests pin the code.)
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class LocationServicePollingTest {

    private lateinit var rig: LocationServiceRig
    private var ids = 0

    @Before
    fun setUp() {
        rig = LocationServiceRig()
    }

    @After
    fun tearDown() {
        rig.close()
    }

    private val high = Priority.PRIORITY_HIGH_ACCURACY
    private val balanced = Priority.PRIORITY_BALANCED_POWER_ACCURACY

    private fun nextId() = "zz_poll_${++ids}"

    /** Replaces the monitored set with one station per entry (null = distance not measured yet). */
    private fun monitorAt(vararg distancesKm: Double?) {
        rig.monitoringStations.clear()
        for (d in distancesKm) {
            val id = nextId()
            rig.monitoringStations[id] = rig.station(id, distanceKm = d)
        }
    }

    /** Puts the gear in a state the production code would never choose, so "no change" cannot pass by luck. */
    private fun seedSentinelGear(expectedPriority: Int) {
        rig.currentPollingIntervalMs = 123_456L
        rig.currentLocationPriority = if (expectedPriority == high) balanced else high
    }

    private fun assertGear(label: String, intervalMs: Long, priority: Int) {
        assertEquals("$label: interval", intervalMs, rig.currentPollingIntervalMs)
        assertEquals("$label: priority", priority, rig.currentLocationPriority)
    }

    // =========================================================================================
    //  adjustPollingInterval(): distance -> gear
    // =========================================================================================

    private data class Row(val distanceKm: Double, val intervalMs: Long, val priority: Int)

    @Test
    fun gearTable_everyBoundary() {
        val rows = listOf(
            Row(-5.0, 10_000, high), // invalid (negative) distance is simply "very near"
            Row(0.0, 10_000, high),
            Row(0.001, 10_000, high),
            Row(4.999, 10_000, high),
            Row(5.0, 10_000, high), // 5 km is still the fast gear
            Row(Math.nextUp(5.0), 30_000, high),
            Row(10.0, 30_000, high),
            Row(15.0, 30_000, high),
            Row(Math.nextUp(15.0), 60_000, high),
            Row(20.0, 60_000, high),
            Row(30.0, 60_000, high),
            Row(Math.nextUp(30.0), 300_000, high),
            Row(45.0, 300_000, high),
            Row(60.0, 300_000, high),
            Row(Math.nextUp(60.0), 600_000, high),
            Row(80.0, 600_000, high),
            Row(100.0, 600_000, high), // priority switches only strictly beyond 100 km
            Row(Math.nextUp(100.0), 600_000, balanced),
            Row(500.0, 600_000, balanced),
            Row(20_000.0, 600_000, balanced),
            Row(Double.MAX_VALUE, 600_000, balanced),
            Row(Double.POSITIVE_INFINITY, 600_000, balanced)
        )
        for (row in rows) {
            monitorAt(row.distanceKm)
            seedSentinelGear(row.priority)
            rig.adjustPollingInterval()
            assertGear("distance=${row.distanceKm}", row.intervalMs, row.priority)
        }
    }

    @Test
    fun nearestStationDrivesTheGear() {
        val cases = listOf(
            listOf<Double?>(50.0, 4.0) to Row(4.0, 10_000, high),
            listOf<Double?>(200.0, 150.0) to Row(150.0, 600_000, balanced),
            listOf<Double?>(61.0, 29.0) to Row(29.0, 60_000, high),
            listOf<Double?>(null, 3.0) to Row(3.0, 10_000, high),
            listOf<Double?>(null, 200.0) to Row(200.0, 600_000, balanced),
            listOf<Double?>(100.0, 100.5, 9_000.0) to Row(100.0, 600_000, high)
        )
        for ((distances, expected) in cases) {
            monitorAt(*distances.toTypedArray())
            seedSentinelGear(expected.priority)
            rig.adjustPollingInterval()
            assertGear("stations=$distances", expected.intervalMs, expected.priority)
        }
    }

    @Test
    fun noMonitoredStations_leavesTheGearUntouched() {
        rig.monitoringStations.clear()
        rig.currentPollingIntervalMs = 123_456L
        rig.currentLocationPriority = balanced
        rig.adjustPollingInterval()
        assertGear("empty set", 123_456L, balanced)
    }

    @Ignore("BUG: LocationService.kt:874 maps an unknown distance (currentDistanceKm == null) to Double.MAX_VALUE, so adjustPollingInterval() picks the 10 min / BALANCED gear (876-890). onColdStartComplete (171-179) calls it when the 90 s cold-start hunt ends without any fix, so a no-fix cold start drops to BALANCED 10 min polling and the watchdog then tolerates 30 min of silence (tier 1 = 3 x interval)")
    @Test
    fun unknownDistance_mustNotSelectTheSlowestGear() {
        // Right after a cold start (no last-known location, no fix yet) every station's currentDistanceKm is
        // null. adjustPollingInterval() maps null -> Double.MAX_VALUE, i.e. "infinitely far": it drops to
        // 10-minute BALANCED polling. This is exactly what the cold-start-timeout path
        // (onColdStartComplete -> adjustPollingInterval) does when no fix arrived within 90 s. "Unknown" must
        // keep the current fast gear (10 s / HIGH_ACCURACY): the regular updates stay on cell/Wi-Fi at a
        // 10-minute cadence until some fix finally arrives.
        monitorAt(null)
        assertGear("initial gear", 10_000L, high)

        rig.adjustPollingInterval()

        assertGear("gear after adjusting with an unknown distance", 10_000L, high)
    }

    @Ignore("BUG: same root cause as above (LocationService.kt:874): the unknown distance counts as >= 60 s gear, so lines 903-905 disarm the 30 s emergency coarse fallback exactly when no fix has been received at all (e.g. when the 90 s cold-start hunt that armed it ends)")
    @Test
    fun unknownDistance_mustNotDisarmTheEmergencyCoarseFallback() {
        // Same root cause: the "gear >= 1 min => disarm the 30 s coarse fallback" rule fires for an
        // unknown distance, killing the fallback in exactly the situation it exists for (no fix at all).
        monitorAt(null)
        rig.coordinator.armCoarseFallback()
        assertTrue(rig.coordinator.isCoarseArmed)

        rig.adjustPollingInterval()

        assertTrue("coarse fallback must survive an adjust with no known distance", rig.coordinator.isCoarseArmed)
    }

    // =========================================================================================
    //  Gear changes re-register the FLP request
    // =========================================================================================

    private fun beginPollingWithoutColdStart(distanceKm: Double) {
        monitorAt(distanceKm)
        // A non-zero lastLocationTimeMs means "a fix was already seen", so no cold-start hunt is started.
        rig.lastLocationTimeMs = System.currentTimeMillis()
        rig.startLocationUpdates()
        assertTrue(rig.isPolling)
        rig.fakeFlp.clearRecords()
    }

    private fun setNearestDistance(km: Double) {
        rig.monitoringStations.values.single().currentDistanceKm = km
    }

    @Test
    fun gearChange_reRegistersUpdatesWithTheNewIntervalPriorityAndBatchingBounds() {
        beginPollingWithoutColdStart(4.0)

        setNearestDistance(40.0)
        rig.adjustPollingInterval()

        assertEquals("old registration removed first", 1, rig.fakeFlp.removedCallbacks.size)
        val request = rig.fakeFlp.registrations.single().request
        assertEquals(300_000L, request.intervalMillis)
        assertEquals(high, request.priority)
        assertEquals("min update interval = interval / 2", 150_000L, request.minUpdateIntervalMillis)
        assertEquals("max update delay = interval * 2", 600_000L, request.maxUpdateDelayMillis)
        assertFalse("rough fixes must not be withheld", request.isWaitForAccurateLocation)
    }

    @Test
    fun unchangedGear_doesNotTouchTheClient() {
        beginPollingWithoutColdStart(4.0)

        rig.adjustPollingInterval()
        rig.adjustPollingInterval()

        assertTrue("no churn while the gear is stable", rig.fakeFlp.calls.isEmpty())
    }

    @Test
    fun movingWithinTheSameGear_doesNotRestartUpdates() {
        beginPollingWithoutColdStart(40.0)
        rig.adjustPollingInterval() // settle into 5 min gear (registers once)
        rig.fakeFlp.clearRecords()

        for (d in listOf(35.0, 55.0, 31.0, 60.0)) {
            setNearestDistance(d)
            rig.adjustPollingInterval()
        }
        assertTrue(rig.fakeFlp.calls.isEmpty())
        assertEquals(300_000L, rig.currentPollingIntervalMs)
    }

    @Test
    fun priorityOnlyChange_stillReRegisters() {
        beginPollingWithoutColdStart(150.0)
        rig.adjustPollingInterval() // 10 min / BALANCED
        rig.fakeFlp.clearRecords()

        setNearestDistance(90.0) // same 10 min interval, HIGH_ACCURACY again
        rig.adjustPollingInterval()

        val request = rig.fakeFlp.registrations.single().request
        assertEquals(600_000L, request.intervalMillis)
        assertEquals(high, request.priority)
    }

    @Test
    fun approachingTheStation_stepsDownThroughEveryGear() {
        beginPollingWithoutColdStart(500.0)
        val seen = mutableListOf<Long>()
        for (d in listOf(500.0, 80.0, 50.0, 25.0, 12.0, 4.0)) {
            setNearestDistance(d)
            rig.adjustPollingInterval()
            seen += rig.currentPollingIntervalMs
        }
        assertEquals(listOf(600_000L, 600_000L, 300_000L, 60_000L, 30_000L, 10_000L), seen)
        assertEquals(high, rig.currentLocationPriority)
        assertEquals("the last registration on the client is the 10 s one", 10_000L, rig.fakeFlp.registrations.last().request.intervalMillis)
    }

    @Test
    fun enteringTheOneMinuteGear_disarmsTheCoarseFallback_fasterGearsKeepIt() {
        beginPollingWithoutColdStart(4.0)
        rig.coordinator.armCoarseFallback()
        assertTrue(rig.coordinator.isCoarseArmed)

        setNearestDistance(12.0) // 30 s gear
        rig.adjustPollingInterval()
        assertTrue("30 s gear keeps the fallback", rig.coordinator.isCoarseArmed)

        setNearestDistance(20.0) // 60 s gear
        rig.adjustPollingInterval()
        assertFalse("1 min+ gear: the 30 s coarse poll is a battery killer", rig.coordinator.isCoarseArmed)
    }

    // =========================================================================================
    //  startLocationUpdates(): request construction, cold start, guards
    // =========================================================================================

    @Test
    fun startLocationUpdates_buildsTheRequestFromTheCurrentGear_andMarksPolling() {
        monitorAt(4.0)
        rig.lastLocationTimeMs = System.currentTimeMillis()
        rig.startLocationUpdates()

        assertTrue(rig.isPolling)
        val request = rig.fakeFlp.registrations.single().request
        assertEquals(10_000L, request.intervalMillis)
        assertEquals(high, request.priority)
        assertEquals(5_000L, request.minUpdateIntervalMillis)
        assertEquals(20_000L, request.maxUpdateDelayMillis)
    }

    @Test
    fun startLocationUpdates_holdsThePartialGpsWakeLock() {
        monitorAt(4.0)
        rig.lastLocationTimeMs = System.currentTimeMillis()
        rig.startLocationUpdates()

        val lock = ShadowPowerManager.getLatestWakeLock()
        assertNotNull("polling must hold a wake lock so Doze cannot nap between polls", lock)
        assertTrue(lock!!.isHeld)
    }

    @Test
    fun stopLocationUpdates_releasesEverythingItHeld() {
        monitorAt(4.0)
        rig.lastLocationTimeMs = System.currentTimeMillis()
        rig.startLocationUpdates()
        rig.coordinator.armCoarseFallback()

        rig.stopLocationUpdates()

        assertFalse(rig.isPolling)
        assertFalse(rig.coordinator.isCoarseArmed)
        assertEquals(1, rig.fakeFlp.removedCallbacks.size)
        assertFalse(ShadowPowerManager.getLatestWakeLock()!!.isHeld)
    }

    @Test
    fun startLocationUpdates_withoutPermission_doesNotStart() {
        shadowOf(rig.app).denyPermissions(android.Manifest.permission.ACCESS_FINE_LOCATION)
        monitorAt(4.0)
        rig.startLocationUpdates()
        assertFalse(rig.isPolling)
        assertTrue(rig.fakeFlp.registrations.isEmpty())
    }

    @Test
    fun startLocationUpdates_withNothingToMonitor_doesNotStart() {
        rig.monitoringStations.clear()
        rig.startLocationUpdates()
        assertFalse(rig.isPolling)
        assertTrue(rig.fakeFlp.registrations.isEmpty())
    }

    @Test
    fun restartLocationUpdates_whenNotPolling_isANoOp() {
        monitorAt(4.0)
        rig.restartLocationUpdates()
        assertTrue(rig.fakeFlp.calls.isEmpty())
        assertFalse(rig.isPolling)
    }

    @Test
    fun firstStart_beginsTheColdStartHunt_andArmsTheCoarseFallback() {
        monitorAt(4.0)
        assertEquals("fresh service: no fix seen yet", 0L, rig.lastLocationTimeMs)

        rig.startLocationUpdates()

        assertTrue(rig.coordinator.isColdStartActive)
        assertTrue(rig.coordinator.isCoarseArmed)
    }

    @Test
    fun duringColdStart_restartsUseTheFiveSecondHighAccuracyRequest_whateverTheGear() {
        monitorAt(500.0)
        rig.startLocationUpdates() // begins the hunt
        rig.currentPollingIntervalMs = 600_000L
        rig.currentLocationPriority = balanced
        rig.fakeFlp.clearRecords()

        rig.restartLocationUpdates()

        val request = rig.fakeFlp.registrations.single().request
        assertEquals(5_000L, request.intervalMillis)
        assertEquals(high, request.priority)
        assertEquals(2_500L, request.minUpdateIntervalMillis)
        assertEquals(10_000L, request.maxUpdateDelayMillis)
    }

    @Test
    fun whenColdStartEnds_theRealGearIsApplied() {
        monitorAt(40.0)
        rig.startLocationUpdates()
        // syncWithDatabase()/onStartCommand() stamp lastLocationTimeMs right after the first start returns;
        // without it the restart inside onColdStartComplete would begin a second hunt.
        rig.lastLocationTimeMs = System.currentTimeMillis()
        rig.fakeFlp.clearRecords()

        rig.coordinator.onFirstFixReceived() // -> restartLocationUpdates(); adjustPollingInterval()

        assertFalse(rig.coordinator.isColdStartActive)
        assertEquals(300_000L, rig.currentPollingIntervalMs)
        assertEquals(300_000L, rig.fakeFlp.registrations.last().request.intervalMillis)
    }

    @Ignore("BUG: LocationService.kt:925-928 picks the request interval from gpsCoordinator.isColdStartActive, but the hunt is only started at 954-956, after the first request was built and registered, so the documented 5 s aggressive first-fix request is never used for the first registration (only after a later restart)")
    @Test
    fun theFirstRegistrationDuringColdStart_shouldAlreadyBeTheFiveSecondRequest() {
        // Documented: "Cold-Start Hunter: aggressive first-fix mode ... sets polling to 5 s" and the comment in
        // startLocationUpdates(): "During cold-start, poll aggressively at 5s HIGH_ACCURACY". But the hunt is
        // only started at the END of startLocationUpdates() (after the request was built from the 10 s default),
        // so the very first FLP registration is the ordinary 10 s one; 5 s applies only after a later restart.
        monitorAt(4.0)
        rig.startLocationUpdates()

        assertTrue(rig.coordinator.isColdStartActive)
        assertEquals(5_000L, rig.fakeFlp.registrations.first().request.intervalMillis)
    }

    // =========================================================================================
    //  Analytics banding helpers (private, pure)
    // =========================================================================================

    private fun callBand(name: String, arg: Any): String = Reflect.call(rig.service, name, arg) as String

    @Test
    fun accuracyBand_boundaries() {
        val expected = listOf(
            -1f to "unknown", 0f to "<20m", 19.99f to "<20m", 20f to "20-50m", 49.99f to "20-50m",
            50f to "50-100m", 99.99f to "50-100m", 100f to "100-500m", 499.99f to "100-500m",
            500f to ">500m", 1_000_000f to ">500m"
        )
        for ((value, label) in expected) assertEquals("accuracy=$value", label, callBand("accuracyBand", value))
    }

    @Test
    fun fixAgeBand_boundaries() {
        val expected = listOf(
            -1L to "unknown", 0L to "<10s", 9L to "<10s", 10L to "10-60s", 59L to "10-60s",
            60L to "1-5min", 299L to "1-5min", 300L to "5-30min", 1799L to "5-30min", 1800L to ">30min", 86_400L to ">30min"
        )
        for ((value, label) in expected) assertEquals("ageSec=$value", label, callBand("fixAgeBand", value))
    }

    @Test
    fun dismissBand_boundaries() {
        val expected = listOf(
            -1L to "unknown", 0L to "<5s", 4L to "<5s", 5L to "5-15s", 14L to "5-15s", 15L to "15-60s",
            59L to "15-60s", 60L to "1-3min", 179L to "1-3min", 180L to ">3min", 86_400L to ">3min"
        )
        for ((value, label) in expected) assertEquals("sec=$value", label, callBand("dismissBand", value))
    }

    @Test
    fun stallBand_boundaries() {
        val expected = listOf(
            0L to "<1min", 59L to "<1min", 60L to "1-3min", 179L to "1-3min", 180L to "3-10min",
            599L to "3-10min", 600L to "10-30min", 1799L to "10-30min", 1800L to ">30min"
        )
        for ((value, label) in expected) assertEquals("sec=$value", label, callBand("stallBand", value))
    }

    // =========================================================================================
    //  Distance helper used by the alarm engine
    // =========================================================================================

    @Test
    fun calculateDistance_isThePlatformGeodesicInKilometres() {
        val d = Reflect.call(rig.service, "calculateDistance", 0.0, 10.0, 0.0, 11.0) as Double
        // One equatorial degree on WGS-84 is a * pi / 180 = 111.3195 km.
        assertEquals(111.3195, d, 0.001)

        assertEquals(0.0, Reflect.call(rig.service, "calculateDistance", 18.5, 73.8, 18.5, 73.8) as Double, 0.0)

        val ab = Reflect.call(rig.service, "calculateDistance", 28.6428, 77.2197, 18.5292, 73.8729) as Double
        val ba = Reflect.call(rig.service, "calculateDistance", 18.5292, 73.8729, 28.6428, 77.2197) as Double
        assertEquals(ab, ba, 1e-3)
    }

    @Test
    fun calculateDistance_nanCoordinatesGiveNaN_notAFakeNearbyValue() {
        val d = Reflect.call(rig.service, "calculateDistance", Double.NaN, 10.0, 0.0, 10.0) as Double
        assertTrue(d.isNaN())
    }
}
