package com.omama.stationalarm.service

import android.content.Context
import android.location.Location
import android.location.LocationManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.gms.location.Priority
import com.omama.stationalarm.data.ActiveStation
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The alarm-trigger decision of the behaviour-locked LocationService.processLocationUpdate(), exercised
 * through the real code path:
 *
 *   fix -> distance (Location.distanceBetween) -> FixQualityGate accuracy -> effectiveDistance =
 *   distance - accuracy -> "wait for exit" bookkeeping -> toAlert -> StationRepository.markAlerting()
 *
 * "ALERTING" (see [LocationServiceRig.status]) means StationRepository.markAlerting() was requested for the
 * station, i.e. the alarm fired; "MONITORING" proves it did not. The test station sits on the equator at
 * lon 10.0; [LocationServiceRig.fixAtKm] places a fix N km due east of it.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class LocationServiceAlertDecisionTest {

    private lateinit var rig: LocationServiceRig

    @Before
    fun setUp() {
        rig = LocationServiceRig()
    }

    @After
    fun tearDown() {
        rig.close()
    }

    /** Unique per call: StationRepository.distancesFlow is process-global and survives between tests. */
    private fun newId() = "zz_alert_${System.nanoTime()}"

    private fun armed(
        radiusKm: Double = 3.0,
        lon: Double = LocationServiceRig.STATION_LON,
        waitForInitialEvaluation: Boolean = false
    ): ActiveStation {
        val station = rig.monitor(rig.station(newId(), lon = lon, radiusKm = radiusKm))
        if (waitForInitialEvaluation) rig.needsInitialEvaluation.add(station.stationId)
        return station
    }

    private fun assertAlerting(s: ActiveStation, message: String = "") =
        assertEquals("$message ${s.stationId} should be ALERTING", "ALERTING", rig.status(s.stationId))

    private fun assertQuiet(s: ActiveStation, message: String = "") =
        assertEquals("$message ${s.stationId} should still be MONITORING", "MONITORING", rig.status(s.stationId))

    // =========================================================================================
    //  Radius check
    // =========================================================================================

    @Test
    fun fixOutsideTheRadius_doesNotAlert_butPublishesTheDistance() {
        val s = armed(radiusKm = 3.0)
        val fix = rig.fixAtKm(3.5)

        rig.process(fix)

        assertQuiet(s)
        val expected = rig.distanceKm(fix)
        assertEquals(expected, rig.publishedDistance(s.stationId)!!, 1e-9)
        assertEquals(expected, s.currentDistanceKm!!, 1e-9)
    }

    @Test
    fun fixInsideTheRadius_firesTheAlarm() {
        val s = armed(radiusKm = 3.0)
        rig.process(rig.fixAtKm(2.0))
        assertAlerting(s)
    }

    @Test
    fun standingOnTheStation_firesTheAlarm() {
        val s = armed(radiusKm = 3.0)
        rig.process(rig.fixAtKm(0.0))
        assertAlerting(s)
        assertEquals(0.0, rig.publishedDistance(s.stationId)!!, 1e-9)
    }

    @Test
    fun effectiveDistanceExactlyEqualToTheRadius_firesTheAlarm() {
        val acc = 10f
        val fix = rig.fixAtKm(3.0, accuracyM = acc)
        val effective = rig.distanceKm(fix) - (acc / 1000.0) // same arithmetic as processLocationUpdate
        val s = armed(radiusKm = effective)

        rig.process(fix)

        assertAlerting(s, "radius == distance - accuracy must count as inside (<=)")
    }

    @Test
    fun effectiveDistanceOneUlpBeyondTheRadius_doesNotFire() {
        val acc = 10f
        val fix = rig.fixAtKm(3.0, accuracyM = acc)
        val effective = rig.distanceKm(fix) - (acc / 1000.0)
        val s = armed(radiusKm = Math.nextDown(effective))

        rig.process(fix)

        assertQuiet(s, "one ULP outside the radius")
    }

    @Test
    fun zeroAccuracy_usesTheRawDistance_atTheExactBoundary() {
        val fix = rig.fixAtKm(2.5, accuracyM = 0f)
        val d = rig.distanceKm(fix)
        val onTheLine = armed(radiusKm = d)
        rig.process(fix)
        assertAlerting(onTheLine, "accuracy 0, radius == distance")

        rig.monitoringStations.clear()
        val justOutside = armed(radiusKm = Math.nextDown(d))
        rig.process(fix)
        assertQuiet(justOutside, "accuracy 0, radius one ULP smaller than distance")
    }

    @Test
    fun eachStationUsesItsOwnRadius() {
        val tight = armed(radiusKm = 3.0)
        val wide = armed(radiusKm = 5.0)

        rig.process(rig.fixAtKm(4.0))

        assertQuiet(tight)
        assertAlerting(wide)
    }

    // =========================================================================================
    //  Accuracy buffer (low-accuracy fixes)
    // =========================================================================================

    @Test
    fun poorAccuracy_thatStillLeavesTheBufferedDistanceOutsideTheRadius_doesNotAlert() {
        // 4.0 km away, radius 3.0 km, 500 m accuracy: nearest possible position is 3.5 km -> no alarm.
        val s = armed(radiusKm = 3.0)
        rig.process(rig.fixAtKm(4.0, accuracyM = 500f))
        assertQuiet(s)
    }

    @Test
    fun poorAccuracy_whoseCircleReachesTheRadius_alertsEarly() {
        // 4.0 km away, radius 3.0 km, 1.5 km accuracy: the user could already be at 2.5 km -> conservative alarm.
        // (A demoted fix is not discarded: the buffer arithmetic still decides, per the LocationService comment.)
        val s = armed(radiusKm = 3.0)
        rig.process(rig.fixAtKm(4.0, accuracyM = 1500f))
        assertAlerting(s)
    }

    @Test
    fun theSameSpot_alertsWithACoarseFix_butNotWithAPreciseOne() {
        val precise = armed(radiusKm = 3.0)
        rig.process(rig.fixAtKm(3.5, accuracyM = 10f)) // 3.49 km buffered: outside
        assertQuiet(precise)

        rig.process(rig.fixAtKm(3.5, accuracyM = 1000f)) // 2.5 km buffered: inside
        assertAlerting(precise)
    }

    // =========================================================================================
    //  "Wait for exit": a station armed while the user is already inside must not ring at once
    // =========================================================================================

    @Test
    fun armedWhileInsideTheRadius_doesNotRing_andWaitsForExit() {
        val s = armed(radiusKm = 3.0, waitForInitialEvaluation = true)

        rig.process(rig.fixAtKm(1.0))

        assertQuiet(s, "re-armed inside the radius")
        assertTrue(rig.waitingForExit.contains(s.stationId))
        assertFalse("evaluated once, flag consumed", rig.needsInitialEvaluation.contains(s.stationId))
    }

    @Test
    fun waitingForExit_isClearedByLeavingTheRadius_thenReEntryRings() {
        val s = armed(radiusKm = 3.0, waitForInitialEvaluation = true)

        rig.process(rig.fixAtKm(1.0))
        rig.process(rig.fixAtKm(1.5))
        assertQuiet(s, "still inside")
        assertTrue(rig.waitingForExit.contains(s.stationId))

        rig.process(rig.fixAtKm(5.0)) // walked out
        assertFalse(rig.waitingForExit.contains(s.stationId))
        assertQuiet(s, "outside")

        rig.process(rig.fixAtKm(2.0)) // came back in
        assertAlerting(s, "re-entry after exit")
    }

    @Test
    fun armedWhileOutside_ringsOnTheFirstEntry_withoutWaiting() {
        val s = armed(radiusKm = 3.0, waitForInitialEvaluation = true)

        rig.process(rig.fixAtKm(8.0))
        assertTrue(rig.waitingForExit.isEmpty())
        assertQuiet(s)

        rig.process(rig.fixAtKm(2.0))
        assertAlerting(s)
    }

    @Test
    fun initialEvaluation_usesTheAccuracyBuffer_soABlurryFirstFixCanParkTheStationInWaitMode() {
        val s = armed(radiusKm = 3.0, waitForInitialEvaluation = true)

        rig.process(rig.fixAtKm(3.5, accuracyM = 800f)) // buffered 2.7 km: "inside"
        assertTrue(rig.waitingForExit.contains(s.stationId))
        assertQuiet(s)

        rig.process(rig.fixAtKm(3.5, accuracyM = 10f)) // buffered 3.49 km: outside -> wait flag cleared
        assertFalse(rig.waitingForExit.contains(s.stationId))
        assertQuiet(s)

        rig.process(rig.fixAtKm(2.0, accuracyM = 10f))
        assertAlerting(s)
    }

    @Test
    fun waitingForExit_isOnlyClearedStrictlyBeyondTheRadius() {
        val acc = 10f
        val fix = rig.fixAtKm(3.0, accuracyM = acc)
        val effective = rig.distanceKm(fix) - (acc / 1000.0)

        val onTheLine = armed(radiusKm = effective)
        rig.waitingForExit.add(onTheLine.stationId)
        val oneUlpOutside = armed(radiusKm = Math.nextDown(effective))
        rig.waitingForExit.add(oneUlpOutside.stationId)

        rig.process(fix)

        assertTrue("effective == radius is not 'left the radius'", rig.waitingForExit.contains(onTheLine.stationId))
        assertQuiet(onTheLine, "still waiting, so silent")
        assertFalse("effective > radius clears the flag", rig.waitingForExit.contains(oneUlpOutside.stationId))
        assertQuiet(oneUlpOutside, "cleared but not inside")
    }

    // =========================================================================================
    //  Bookkeeping and multi-station behaviour
    // =========================================================================================

    @Test
    fun aStationThatIsAlreadyAlerting_isNotMarkedAlertingAgain() {
        val s = armed(radiusKm = 3.0)
        rig.alertingStationIds.add(s.stationId) // the service already rang for it

        rig.process(rig.fixAtKm(1.0))

        assertQuiet(s, "toAlert must skip stations in alertingStationIds")
    }

    @Test
    fun onlyStationsInsideTheirRadiusRing_andEachGetsItsOwnDistance() {
        val near = armed(radiusKm = 3.0)
        val far = armed(radiusKm = 3.0, lon = 12.0) // ~222 km east of `near`
        val fix = rig.fixAtKm(1.0)

        rig.process(fix)

        assertAlerting(near)
        assertQuiet(far)
        assertEquals(rig.distanceKm(fix), rig.publishedDistance(near.stationId)!!, 1e-9)
        assertEquals(rig.distanceKm(fix, stationLon = 12.0), rig.publishedDistance(far.stationId)!!, 1e-9)
        assertTrue(rig.publishedDistance(far.stationId)!! > 200.0)
    }

    @Test
    fun theNearestStationDrivesTheGearAfterEachFix() {
        armed()
        armed(lon = 12.0)
        rig.currentPollingIntervalMs = 123_456L
        rig.currentLocationPriority = Priority.PRIORITY_BALANCED_POWER_ACCURACY

        rig.process(rig.fixAtKm(40.0)) // 40 km from the first station, ~182 km from the second

        assertEquals(300_000L, rig.currentPollingIntervalMs)
        assertEquals(Priority.PRIORITY_HIGH_ACCURACY, rig.currentLocationPriority)
    }

    @Test
    fun stationWithoutResolvableCoordinates_isSkipped_notCrashedOn() {
        // lat == lon == 0.0 and an id that is not a railway code: ActiveStation.getStation() is null.
        val ghost = rig.monitor(rig.station(newId(), lat = 0.0, lon = 0.0))
        val real = armed(radiusKm = 3.0)

        rig.process(rig.fixAtKm(1.0))

        assertQuiet(ghost)
        assertNull(rig.publishedDistance(ghost.stationId))
        assertNull(ghost.currentDistanceKm)
        assertAlerting(real, "the other station must not be affected by the unresolvable one")
    }

    @Test
    fun noMonitoredStations_isANoOp() {
        val bystander = rig.station(newId()) // exists, but is NOT in the monitoring set
        rig.process(rig.fixAtKm(1.0))
        assertNull(rig.publishedDistance(bystander.stationId))
        assertTrue(rig.monitoringStations.isEmpty())
    }

    @Test
    fun aFixWithNaNCoordinates_neverRingsAnAlarm() {
        val s = armed(radiusKm = 3.0)
        val garbage = Location("fused").apply {
            latitude = Double.NaN
            longitude = Double.NaN
            accuracy = 10f
        }

        rig.process(garbage)

        assertQuiet(s, "NaN distance must compare false against the radius")
    }

    // =========================================================================================
    //  Jumping / teleporting fixes and the coarse fallback
    // =========================================================================================

    @Test
    fun aTeleportOutlier_dropsTheGear_butTheNextRealFixRestoresItAndStillRings() {
        val s = armed(radiusKm = 3.0)

        rig.process(rig.fixAtKm(500.0)) // bogus jump 500 km away
        assertQuiet(s)
        assertEquals(600_000L, rig.currentPollingIntervalMs)
        assertEquals(Priority.PRIORITY_BALANCED_POWER_ACCURACY, rig.currentLocationPriority)

        rig.process(rig.fixAtKm(2.0)) // the real position
        assertAlerting(s)
        assertEquals("every fix recomputes the gear, so an outlier self-corrects", 10_000L, rig.currentPollingIntervalMs)
        assertEquals(Priority.PRIORITY_HIGH_ACCURACY, rig.currentLocationPriority)
    }

    @Test
    fun coarseFallbackFix_travelsTheSameAlarmPath_andItsFlooredAccuracyMakesItRingEarlier() {
        val s = armed(radiusKm = 3.0)
        rig.process(rig.fixAtKm(3.5, accuracyM = 10f)) // an accurate fix at 3.5 km: outside the radius
        assertQuiet(s)

        // FLP stalls; the raw NETWORK-provider fallback delivers a "30 m" cell fix from the same spot.
        rig.coordinator.armCoarseFallback()
        val network = rig.fixAtKm(3.5, accuracyM = 30f, provider = LocationManager.NETWORK_PROVIDER)
        val lm = rig.app.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        rig.settling {
            shadowOf(lm).simulateLocation(network)
            rig.idleMainLooper()
        }

        assertAlerting(s, "coarse accuracy is floored to 1000 m => buffered 2.5 km => inside")
        assertTrue("the coarse path also refreshes the watchdog clock", rig.lastLocationTimeMs > 0L)
    }

    // =========================================================================================
    //  Stale and mock fixes: the engine has no handling for either (characterisation tests)
    // =========================================================================================

    private fun ageBy(fix: Location, ms: Long): Location = fix.apply {
        time = System.currentTimeMillis() - ms
        elapsedRealtimeNanos = android.os.SystemClock.elapsedRealtimeNanos() - ms * 1_000_000L
    }

    @Test
    fun characterisation_fixAgeIsNeverChecked_aThreeHourOldFixIsTreatedLikeALiveOne() {
        // processLocationUpdate never looks at Location.time / elapsedRealtimeNanos. Pinned so that adding a
        // freshness filter later is a conscious decision (see the report: "stale fixes").
        val s = armed(radiusKm = 3.0)

        rig.process(ageBy(rig.fixAtKm(2.0), 3 * 3_600_000L))

        assertAlerting(s, "a 3 h old fix inside the radius rang the alarm")
    }

    @Test
    fun characterisation_aStaleFarFix_consumesTheInitialEvaluation_soTheNextLiveInsideFixRingsAtOnce() {
        // For a newly armed station syncWithDatabase() feeds the (possibly hours old) last-known location through
        // processLocationUpdate. That fix uses up the one-shot "armed while already inside?" check, so a user who
        // arms the alarm while actually inside the radius gets an immediate ring on the next live fix.
        val s = armed(radiusKm = 3.0, waitForInitialEvaluation = true)

        rig.process(ageBy(rig.fixAtKm(50.0), 3 * 3_600_000L)) // stale, far away
        assertFalse("the stale fix consumed the evaluation", rig.needsInitialEvaluation.contains(s.stationId))
        assertTrue(rig.waitingForExit.isEmpty())
        assertQuiet(s)

        rig.process(rig.fixAtKm(1.0)) // the user is in fact inside the radius
        assertAlerting(s)
    }

    @Test
    fun characterisation_mockProviderFixes_arentFilteredByTheAlarmLogic() {
        // Location.isMock is only written to the GPS CSV log (GpsLogger); it does not influence the decision.
        val s = armed(radiusKm = 3.0)
        val mock = rig.fixAtKm(2.0).apply { isMock = true }
        assertTrue(mock.isMock)

        rig.process(mock)

        assertAlerting(s)
    }

    // =========================================================================================
    //  Full pipeline from the FLP callback
    // =========================================================================================

    @Test
    fun flpCallback_runsTheWholePipeline_andEndsTheColdStartHunt() {
        val s = armed(radiusKm = 3.0)
        rig.startLocationUpdates() // first start: registers the callback and begins the cold-start hunt
        assertTrue(rig.coordinator.isColdStartActive)

        rig.deliverFlpFix(rig.fixAtKm(2.0))

        assertAlerting(s)
        assertFalse("first FLP fix ends the hunt", rig.coordinator.isColdStartActive)
        assertTrue(rig.lastLocationTimeMs > 0L)
        assertEquals(10_000L, rig.currentPollingIntervalMs)
        assertEquals(Priority.PRIORITY_HIGH_ACCURACY, rig.currentLocationPriority)
    }

    @Test
    fun flpCallback_fixAFarFromTheStation_selectsTheMatchingGear_withoutRinging() {
        val s = armed(radiusKm = 3.0)
        rig.startLocationUpdates()

        rig.deliverFlpFix(rig.fixAtKm(45.0))

        assertQuiet(s)
        assertEquals(300_000L, rig.currentPollingIntervalMs)
        assertEquals("the live FLP request follows the gear", 300_000L, rig.fakeFlp.registrations.last().request.intervalMillis)
    }
}
