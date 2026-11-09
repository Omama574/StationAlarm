package com.omama.stationalarm.service.gps

import android.Manifest
import android.app.Application
import android.content.Context
import android.location.GnssStatus
import android.location.Location
import android.location.LocationManager
import android.os.Looper
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.omama.stationalarm.service.gps.GpsReliabilityCoordinator.StallAction
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLocationManager
import java.util.concurrent.TimeUnit

/**
 * GPS reliability layers: cold-start hunter (L3), emergency coarse fallback through the raw
 * LocationManager NETWORK provider (L2), GNSS telemetry (L5) and the watchdog stall classifier.
 *
 * Looper timers (the 90 s cold-start deadline) are advanced with Robolectric's paused main looper.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class GpsReliabilityCoordinatorTest {

    private lateinit var app: Application
    private lateinit var locationManager: LocationManager
    private lateinit var shadowLm: ShadowLocationManager
    private lateinit var coordinator: GpsReliabilityCoordinator

    private val coarseFixes = mutableListOf<Location>()
    private var coldStartCompletions = 0
    private var fixCounter = 0

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        locationManager = app.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        shadowLm = shadowOf(locationManager)
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        shadowLm.setProviderEnabled(LocationManager.NETWORK_PROVIDER, true)
        shadowLm.setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        coordinator = GpsReliabilityCoordinator(
            context = app,
            onCoarseFix = { coarseFixes += it },
            onColdStartComplete = { coldStartCompletions++ }
        )
    }

    @After
    fun tearDown() {
        coordinator.destroy()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun advanceMs(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(ms, TimeUnit.MILLISECONDS)

    private fun fix(accuracy: Float, provider: String = LocationManager.NETWORK_PROVIDER): Location {
        fixCounter++
        return Location(provider).apply {
            latitude = 18.5292
            longitude = 73.8729
            this.accuracy = accuracy
            time = 1_700_000_000_000L + fixCounter * 120_000L
            // Spaced far beyond the 30 s coarse interval so the shadow never throttles delivery.
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos() + fixCounter * 120_000_000_000L
        }
    }

    private fun networkListenerCount() = shadowLm.getLocationUpdateListeners(LocationManager.NETWORK_PROVIDER).size

    private fun gnssCallbackCount(): Int {
        val field = ShadowLocationManager::class.java.getDeclaredField("gnssStatusTransports")
        field.isAccessible = true
        return (field.get(shadowLm) as List<*>).size
    }

    private fun gnssStatus(vararg usedInFix: Boolean): GnssStatus {
        val builder = GnssStatus.Builder()
        usedInFix.forEachIndexed { i, used ->
            builder.addSatellite(GnssStatus.CONSTELLATION_GPS, i + 1, 30f, 45f, 10f, true, true, used, false, 0f, false, 0f)
        }
        return builder.build()
    }

    // =========================================================================================
    //  classifyStall (watchdog escalation)
    // =========================================================================================

    @Test
    fun classifyStall_threeOrMoreGuardianFailures_alwaysRecommendsNuke() {
        for (failures in listOf(3, 4, 100, Int.MAX_VALUE)) {
            for (stall in listOf(0L, 1L, 179_999L, 180_000L, Long.MAX_VALUE)) {
                assertEquals("failures=$failures stall=$stall", StallAction.NUKE_FLP, coordinator.classifyStall(stall, failures))
            }
        }
    }

    @Test
    fun classifyStall_nukeWinsOverArmCoarse_evenWhenCoarseIsNotArmed() {
        assertFalse(coordinator.isCoarseArmed)
        assertEquals(StallAction.NUKE_FLP, coordinator.classifyStall(10 * 60_000L, 3))
    }

    @Test
    fun classifyStall_belowThreeMinutes_isNormalRetry() {
        for (failures in 0..2) {
            for (stall in listOf(0L, 1L, 30_000L, 60_000L, 179_999L)) {
                assertEquals("failures=$failures stall=$stall", StallAction.NORMAL_RETRY, coordinator.classifyStall(stall, failures))
            }
        }
    }

    @Test
    fun classifyStall_atExactlyThreeMinutes_armsCoarse() {
        assertEquals(StallAction.NORMAL_RETRY, coordinator.classifyStall(179_999L, 0))
        assertEquals(StallAction.ARM_COARSE, coordinator.classifyStall(180_000L, 0))
        assertEquals(StallAction.ARM_COARSE, coordinator.classifyStall(180_001L, 2))
        assertEquals(StallAction.ARM_COARSE, coordinator.classifyStall(Long.MAX_VALUE, 0))
    }

    @Test
    fun classifyStall_longStallWithCoarseAlreadyArmed_doesNotReArm() {
        coordinator.armCoarseFallback()
        assertTrue(coordinator.isCoarseArmed)
        assertEquals(StallAction.NORMAL_RETRY, coordinator.classifyStall(30 * 60_000L, 0))
        assertEquals(StallAction.NORMAL_RETRY, coordinator.classifyStall(Long.MAX_VALUE, 2))
        // ... but the guardian escalation still wins.
        assertEquals(StallAction.NUKE_FLP, coordinator.classifyStall(30 * 60_000L, 3))
    }

    @Test
    fun classifyStall_negativeStallAndNegativeFailures_areHarmless() {
        // Wall clock moved backwards (NTP / manual change) -> negative stall.
        assertEquals(StallAction.NORMAL_RETRY, coordinator.classifyStall(-1L, 0))
        assertEquals(StallAction.NORMAL_RETRY, coordinator.classifyStall(Long.MIN_VALUE, 0))
        assertEquals(StallAction.NORMAL_RETRY, coordinator.classifyStall(60_000L, -5))
    }

    // =========================================================================================
    //  Cold-start hunter (L3)
    // =========================================================================================

    @Test
    fun coldStart_beginsActive_armsCoarseFallback_andIsIdempotent() {
        assertFalse(coordinator.isColdStartActive)

        coordinator.startColdStartHunt()
        assertTrue(coordinator.isColdStartActive)
        assertTrue("cold start must arm the emergency fallback at the same time", coordinator.isCoarseArmed)
        assertEquals(1, networkListenerCount())
        assertEquals(0, coldStartCompletions)

        coordinator.startColdStartHunt() // second call must not stack a second timer or listener
        assertEquals(1, networkListenerCount())
        advanceMs(90_000)
        assertEquals("one hunt = one completion", 1, coldStartCompletions)
    }

    @Test
    fun coldStart_endsAtTheNinetySecondDeadline_exactlyOnce() {
        coordinator.startColdStartHunt()

        advanceMs(89_999)
        assertTrue("still hunting 1 ms before the deadline", coordinator.isColdStartActive)
        assertEquals(0, coldStartCompletions)

        advanceMs(1)
        assertFalse(coordinator.isColdStartActive)
        assertEquals(1, coldStartCompletions)

        advanceMs(10 * 60_000L)
        assertEquals("deadline must not fire twice", 1, coldStartCompletions)
    }

    @Test
    fun coldStart_deadlineDoesNotDisarmTheCoarseFallback() {
        // Documented: coarse is only disarmed by 3 good FLP fixes, not by the end of the hunt.
        coordinator.startColdStartHunt()
        advanceMs(90_000)
        assertFalse(coordinator.isColdStartActive)
        assertTrue(coordinator.isCoarseArmed)
    }

    @Test
    fun coldStart_firstFixEndsItImmediately_andCancelsTheDeadline() {
        coordinator.startColdStartHunt()
        advanceMs(10_000)

        coordinator.onFirstFixReceived()
        assertFalse(coordinator.isColdStartActive)
        assertEquals(1, coldStartCompletions)

        advanceMs(5 * 60_000L)
        assertEquals("the cancelled deadline must not complete the hunt a second time", 1, coldStartCompletions)
    }

    @Test
    fun coldStart_firstFixWhenNotHunting_isANoOp() {
        coordinator.onFirstFixReceived()
        assertEquals(0, coldStartCompletions)
        assertFalse(coordinator.isColdStartActive)
    }

    @Test
    fun coldStart_canBeStartedAgainAfterItCompleted() {
        coordinator.startColdStartHunt()
        coordinator.onFirstFixReceived()
        assertEquals(1, coldStartCompletions)

        coordinator.startColdStartHunt()
        assertTrue(coordinator.isColdStartActive)
        advanceMs(90_000)
        assertEquals(2, coldStartCompletions)
    }

    @Test
    fun coldStart_destroyCancelsThePendingDeadline() {
        coordinator.startColdStartHunt()
        coordinator.destroy()
        assertFalse(coordinator.isColdStartActive)
        assertFalse("destroy must also drop the coarse listener", coordinator.isCoarseArmed)
        assertEquals(0, networkListenerCount())

        advanceMs(5 * 60_000L)
        assertEquals(0, coldStartCompletions)
    }

    @Test
    fun coldStart_withoutLocationPermission_stillTimesOut_butNoCoarseFallback() {
        shadowOf(app).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION)

        coordinator.startColdStartHunt()
        assertTrue(coordinator.isColdStartActive)
        assertFalse(coordinator.isCoarseArmed)
        assertEquals(0, networkListenerCount())

        advanceMs(90_000)
        assertEquals(1, coldStartCompletions)
    }

    @Test
    fun coldStart_withNetworkProviderDisabled_stillTimesOut_butNoCoarseFallback() {
        shadowLm.setProviderEnabled(LocationManager.NETWORK_PROVIDER, false)

        coordinator.startColdStartHunt()
        assertTrue(coordinator.isColdStartActive)
        assertFalse(coordinator.isCoarseArmed)

        advanceMs(90_000)
        assertEquals(1, coldStartCompletions)
    }

    // =========================================================================================
    //  Emergency coarse fallback (L2)
    // =========================================================================================

    @Test
    fun coarse_requiresFineLocationPermission() {
        shadowOf(app).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        coordinator.armCoarseFallback()
        assertFalse(coordinator.isCoarseArmed)
        assertEquals(0, networkListenerCount())
    }

    @Test
    fun coarse_requiresTheNetworkProviderToBeEnabled() {
        shadowLm.setProviderEnabled(LocationManager.NETWORK_PROVIDER, false)
        coordinator.armCoarseFallback()
        assertFalse(coordinator.isCoarseArmed)
        assertEquals(0, networkListenerCount())
    }

    @Test
    fun coarse_registersExactlyOneNetworkListener_andNothingOnGps() {
        coordinator.armCoarseFallback()
        assertTrue(coordinator.isCoarseArmed)
        assertEquals(1, networkListenerCount())
        assertEquals(0, shadowLm.getLocationUpdateListeners(LocationManager.GPS_PROVIDER).size)

        coordinator.armCoarseFallback() // idempotent
        assertEquals(1, networkListenerCount())
    }

    @Test
    fun coarse_fixAccuracyIsFlooredTo1000Metres() {
        coordinator.armCoarseFallback()
        for (reported in listOf(0f, 1f, 25f, 999.9f)) {
            coarseFixes.clear()
            shadowLm.simulateLocation(fix(reported))
            idle()
            assertEquals("reported=$reported", 1, coarseFixes.size)
            assertEquals("reported=$reported", 1000f, coarseFixes[0].accuracy, 0f)
        }
    }

    @Test
    fun coarse_fixAccuracyAtOrAboveTheFloorIsKept() {
        coordinator.armCoarseFallback()
        for (reported in listOf(1000f, 1000.5f, 4500f, 60_000f)) {
            coarseFixes.clear()
            shadowLm.simulateLocation(fix(reported))
            idle()
            assertEquals("reported=$reported", 1, coarseFixes.size)
            assertEquals("reported=$reported", reported, coarseFixes[0].accuracy, 0f)
        }
    }

    @Test
    fun coarse_fixKeepsPositionAndProvider_andDoesNotMutateTheOriginal() {
        coordinator.armCoarseFallback()
        val original = fix(30f)
        shadowLm.simulateLocation(original)
        idle()

        val delivered = coarseFixes.single()
        assertEquals(18.5292, delivered.latitude, 1e-9)
        assertEquals(73.8729, delivered.longitude, 1e-9)
        assertEquals(LocationManager.NETWORK_PROVIDER, delivered.provider)
        assertEquals("the caller's Location must not be touched", 30f, original.accuracy, 0f)
    }

    @Test
    fun coarse_providerSwitchedOff_doesNotCrashOrSilentlyDisarm() {
        coordinator.armCoarseFallback()
        shadowLm.setProviderEnabled(LocationManager.NETWORK_PROVIDER, false)
        idle()
        assertTrue("characterisation: the listener stays registered and waits for the provider", coordinator.isCoarseArmed)
    }

    @Test
    fun coarse_goodFlpFixWhileNotArmed_isANoOp() {
        coordinator.onGoodFlpFix(fix(10f, "fused"))
        assertFalse(coordinator.isCoarseArmed)
    }

    @Test
    fun coarse_threeConsecutiveGoodFlpFixes_disarmIt() {
        coordinator.armCoarseFallback()
        coordinator.onGoodFlpFix(fix(10f, "fused"))
        coordinator.onGoodFlpFix(fix(10f, "fused"))
        assertTrue("two good fixes are not enough", coordinator.isCoarseArmed)
        coordinator.onGoodFlpFix(fix(10f, "fused"))
        assertFalse(coordinator.isCoarseArmed)
        assertEquals("the listener must really be removed", 0, networkListenerCount())
    }

    @Test
    fun coarse_aBadFlpFixResetsTheStreak() {
        coordinator.armCoarseFallback()
        coordinator.onGoodFlpFix(fix(10f, "fused"))
        coordinator.onGoodFlpFix(fix(10f, "fused"))
        coordinator.onGoodFlpFix(fix(5000.5f, "fused")) // cell-tower-ish garbage: resets
        coordinator.onGoodFlpFix(fix(10f, "fused"))
        coordinator.onGoodFlpFix(fix(10f, "fused"))
        assertTrue("streak restarted at the bad fix", coordinator.isCoarseArmed)
        coordinator.onGoodFlpFix(fix(10f, "fused"))
        assertFalse(coordinator.isCoarseArmed)
    }

    @Test
    fun coarse_goodFixBoundaryIs5000Metres_inclusive() {
        coordinator.armCoarseFallback()
        repeat(3) { coordinator.onGoodFlpFix(fix(5000f, "fused")) }
        assertFalse("5000 m still counts as a healthy IPC heartbeat", coordinator.isCoarseArmed)

        coordinator.armCoarseFallback()
        repeat(10) { coordinator.onGoodFlpFix(fix(Math.nextUp(5000f), "fused")) }
        assertTrue("anything above 5000 m never counts", coordinator.isCoarseArmed)
    }

    @Test
    fun coarse_nanAccuracyFlpFix_countsAsBad() {
        coordinator.armCoarseFallback()
        coordinator.onGoodFlpFix(fix(10f, "fused"))
        coordinator.onGoodFlpFix(fix(10f, "fused"))
        coordinator.onGoodFlpFix(fix(Float.NaN, "fused"))
        coordinator.onGoodFlpFix(fix(10f, "fused"))
        assertTrue(coordinator.isCoarseArmed)
    }

    @Test
    fun coarse_reArmingAfterDisarm_startsAFreshStreak() {
        coordinator.armCoarseFallback()
        repeat(3) { coordinator.onGoodFlpFix(fix(10f, "fused")) }
        assertFalse(coordinator.isCoarseArmed)

        coordinator.armCoarseFallback()
        assertTrue(coordinator.isCoarseArmed)
        coordinator.onGoodFlpFix(fix(10f, "fused"))
        coordinator.onGoodFlpFix(fix(10f, "fused"))
        assertTrue("old streak must not carry over", coordinator.isCoarseArmed)
        coordinator.onGoodFlpFix(fix(10f, "fused"))
        assertFalse(coordinator.isCoarseArmed)
    }

    @Test
    fun coarse_disarmIsIdempotentAndRemovesTheListener() {
        coordinator.armCoarseFallback()
        coordinator.disarmCoarseFallback()
        assertFalse(coordinator.isCoarseArmed)
        assertEquals(0, networkListenerCount())
        coordinator.disarmCoarseFallback() // must not throw
        assertFalse(coordinator.isCoarseArmed)
    }

    @Test
    fun coarse_noFixIsDeliveredAfterDisarm() {
        coordinator.armCoarseFallback()
        coordinator.disarmCoarseFallback()
        shadowLm.simulateLocation(fix(30f))
        idle()
        assertTrue(coarseFixes.isEmpty())
    }

    @Test
    fun destroy_disarmsCoarseAndStopsEverything() {
        coordinator.startColdStartHunt()
        coordinator.startGnssMonitoring()
        coordinator.destroy()
        assertFalse(coordinator.isColdStartActive)
        assertFalse(coordinator.isCoarseArmed)
        assertEquals(0, networkListenerCount())
        assertEquals(0, gnssCallbackCount())
    }

    // =========================================================================================
    //  GNSS monitor (L5)
    // =========================================================================================

    @Test
    fun gnss_requiresLocationPermission() {
        shadowOf(app).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        coordinator.startGnssMonitoring()
        assertEquals(0, gnssCallbackCount())
    }

    @Test
    fun gnss_statusUpdatesVisibleAndInFixCounts() {
        coordinator.startGnssMonitoring()
        assertEquals(1, gnssCallbackCount())

        shadowLm.simulateGnssStatus(gnssStatus(true, true, false, false))
        idle()
        assertEquals(4, coordinator.satellitesVisible)
        assertEquals(2, coordinator.satellitesInFix)

        shadowLm.simulateGnssStatus(gnssStatus())
        idle()
        assertEquals(0, coordinator.satellitesVisible)
        assertEquals(0, coordinator.satellitesInFix)
    }

    @Test
    fun gnss_stopUnregistersTheCallback() {
        coordinator.startGnssMonitoring()
        coordinator.stopGnssMonitoring()
        assertEquals(0, gnssCallbackCount())
        coordinator.stopGnssMonitoring() // second stop must not throw
    }

    @Ignore("BUG: GpsReliabilityCoordinator.kt:245-258 startGnssMonitoring() registers a new GnssStatus.Callback on every call without unregistering the previous one (gnssCallback is just overwritten), and LocationService.kt:951 calls it on every startLocationUpdates()/restartLocationUpdates(); 5 starts leave 5 registered callbacks, stopGnssMonitoring() removes only the last")
    @Test
    fun gnss_startingMonitoringRepeatedly_mustNotLeakCallbacks() {
        // LocationService.startLocationUpdates() calls startGnssMonitoring() on EVERY (re)start, i.e. on each
        // gear change, watchdog tier-1/2 restart and geofence wake-up. Each call registers a brand-new
        // GnssStatus.Callback; the previous one is never unregistered, and stopGnssMonitoring() only removes
        // the most recent. Expected: exactly one registered callback, and none after stop.
        repeat(5) { coordinator.startGnssMonitoring() }
        assertEquals("callbacks registered after 5 starts", 1, gnssCallbackCount())

        coordinator.stopGnssMonitoring()
        assertEquals("callbacks left after stop", 0, gnssCallbackCount())
    }
}
