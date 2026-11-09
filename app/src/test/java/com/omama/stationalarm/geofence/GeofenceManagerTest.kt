package com.omama.stationalarm.geofence

import android.Manifest
import android.app.Application
import android.app.PendingIntent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingRequest
import com.omama.stationalarm.data.StationData
import com.omama.stationalarm.receiver.AlarmManifest
import com.omama.stationalarm.receiver.GeofenceBroadcastReceiver
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * GeofenceManager registers 9 geofences per station: level1..level8 plus "alert", all ENTER-only, all with the
 * request id `geofence_<stationId>_<layer>` that GeofenceBroadcastReceiver later parses. (CLAUDE.md still says
 * "6 geofences, Outer/Mid/Inner x Entry/Exit"; the code, and these tests, say 9 ENTER-only ones.)
 *
 * Google Play services is replaced by [RecordingGeofencingClient] through [ShadowLocationServicesGeofencing].
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], application = android.app.Application::class, shadows = [ShadowLocationServicesGeofencing::class])
class GeofenceManagerTest {

    private lateinit var app: Application
    private lateinit var repo: RepositoryHarness
    private lateinit var gms: RecordingGeofencingClient

    private val layers = listOf("level1", "level2", "level3", "level4", "level5", "level6", "level7", "level8", "alert")

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        gms = FakeGeofencing.reset()
        repo = RepositoryHarness(app)
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    @After
    fun tearDown() {
        repo.close()
    }

    private suspend fun register(
        stationId: String,
        alertM: Float = 3000f,
    ): Result<Unit> = GeofenceManager.addGeofencesForStation(
        context = app,
        stationId = stationId,
        radiusLevel8M = 203_000f,
        radiusLevel7M = 153_000f,
        radiusLevel6M = 103_000f,
        radiusLevel5M = 63_000f,
        radiusLevel4M = 43_000f,
        radiusLevel3M = 33_000f,
        radiusLevel2M = 8_000f,
        radiusLevel1M = 3_000f,
        alertDistanceM = alertM
    )

    private fun allRegistered(): List<Geofence> = gms.addCalls.flatMap { it.geofences }

    // ------------------------------------------------------------------ registration shape

    @Test
    fun addGeofences_registersNineEnterOnlyGeofences_withTheIdsTheReceiverParses() = runTest {
        repo.insert(repo.station("my-stop"))

        val result = register("my-stop")

        assertTrue(result.isSuccess)
        assertEquals(
            layers.map { "geofence_my-stop_$it" }.toSet(),
            allRegistered().map { it.requestId }.toSet()
        )
        assertEquals("exactly 9, no duplicates", 9, allRegistered().size)
        allRegistered().forEach {
            assertEquals(Geofence.GEOFENCE_TRANSITION_ENTER, it.transitionTypes)
            assertEquals(Geofence.NEVER_EXPIRE, it.expirationTime)
            assertEquals(0, it.notificationResponsiveness)
            assertEquals(12.97, it.latitude, 1e-9)
            assertEquals(77.59, it.longitude, 1e-9)
        }
    }

    @Test
    fun addGeofences_usesTheGivenRadiusPerLayer() = runTest {
        repo.insert(repo.station("my-stop"))

        register("my-stop", alertM = 3000f)

        val radius = allRegistered().associate { it.requestId to it.radius }
        assertEquals(3_000f, radius["geofence_my-stop_level1"])
        assertEquals(8_000f, radius["geofence_my-stop_level2"])
        assertEquals(33_000f, radius["geofence_my-stop_level3"])
        assertEquals(43_000f, radius["geofence_my-stop_level4"])
        assertEquals(63_000f, radius["geofence_my-stop_level5"])
        assertEquals(103_000f, radius["geofence_my-stop_level6"])
        assertEquals(153_000f, radius["geofence_my-stop_level7"])
        assertEquals(203_000f, radius["geofence_my-stop_level8"])
        assertEquals(3_000f, radius["geofence_my-stop_alert"])
    }

    @Test
    fun addGeofences_usesTwoRequests_levelsFirstWithEnterInitialTrigger_thenAlertWithNoInitialTrigger() = runTest {
        repo.insert(repo.station("my-stop"))

        register("my-stop")

        assertEquals(2, gms.addCalls.size)
        val levels = gms.addCalls[0]
        val alert = gms.addCalls[1]
        assertEquals(8, levels.geofences.size)
        assertEquals(GeofencingRequest.INITIAL_TRIGGER_ENTER, levels.request.initialTrigger)
        assertEquals(listOf("geofence_my-stop_alert"), alert.requestIds)
        // Registering while already inside the alert circle must NOT fire the alarm immediately.
        assertEquals(0, alert.request.initialTrigger)
    }

    @Test
    fun addGeofences_alertRadiusBelowAndroidMinimum_isClampedTo100m() = runTest {
        repo.insert(repo.station("my-stop"))

        register("my-stop", alertM = 20f)

        val alert = allRegistered().single { it.requestId.endsWith("_alert") }
        assertEquals(100f, alert.radius)
        // The level rings are not clamped.
        assertEquals(3_000f, allRegistered().single { it.requestId.endsWith("_level1") }.radius)
    }

    @Test
    fun addGeofences_alertRadiusAbove100m_isKept() = runTest {
        repo.insert(repo.station("my-stop"))

        register("my-stop", alertM = 250f)

        assertEquals(250f, allRegistered().single { it.requestId.endsWith("_alert") }.radius)
    }

    @Test
    fun addGeofences_pendingIntentIsAMutableBroadcastToTheReceiver_withTheManifestAction() = runTest {
        repo.insert(repo.station("my-stop"))

        register("my-stop")

        val pi = shadowOf(gms.addCalls.first().pendingIntent)
        assertTrue(pi.isBroadcastIntent)
        assertEquals(GeofenceBroadcastReceiver::class.java.name, pi.savedIntent.component?.className)
        assertEquals(GeofenceIntents.ACTION, pi.savedIntent.action)
        assertTrue(
            "GMS needs FLAG_MUTABLE to attach the geofencing event extras",
            pi.flags and PendingIntent.FLAG_MUTABLE != 0
        )
        assertEquals(
            setOf(pi.savedIntent.action),
            AlarmManifest.intentFilterActions(AlarmManifest.component("receiver", ".receiver.GeofenceBroadcastReceiver"))
        )
        // The same PendingIntent identity is used for both requests and for later removal-by-intent.
        assertEquals(gms.addCalls[0].pendingIntent, gms.addCalls[1].pendingIntent)
    }

    @Test
    fun requestIds_roundTripThroughTheReceiversParsing_forSafeStationIds() = runTest {
        // GeofenceBroadcastReceiver does requestId.split("_", limit = 3) and takes parts[1] / parts[2].
        for (id in listOf("NDLS", "PUNE", "place-1730000000000", "liq-481312177", "a-b-c")) {
            gms = FakeGeofencing.reset()
            repo.insert(repo.station(id))

            register(id)

            allRegistered().forEach {
                val parts = it.requestId.split("_", limit = 3)
                assertEquals("geofence", parts[0])
                assertEquals("station id must survive the receiver's split for '$id'", id, parts[1])
                assertTrue(parts[2] in layers)
            }
        }
    }

    // ------------------------------------------------------------------ preconditions

    @Test
    fun addGeofences_withoutFineLocationPermission_failsWithMissingPermission_andTouchesNothing() = runTest {
        shadowOf(app).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        repo.insert(repo.station("my-stop"))

        val result = register("my-stop")

        assertTrue(result.exceptionOrNull() is GeofenceManager.GeofenceRegistrationException.MissingPermission)
        assertEquals(0, gms.addCalls.size)
    }

    @Test
    fun addGeofences_forUnknownStation_failsWithStationNotFound() = runTest {
        val result = register("ghost-station")

        val error = result.exceptionOrNull()
        assertTrue(error is GeofenceManager.GeofenceRegistrationException.StationNotFound)
        assertEquals("Station ghost-station not found.", error!!.message)
        assertEquals(0, gms.addCalls.size)
    }

    @Test
    fun addGeofences_forStationWithoutCoordinates_isTreatedAsNotFound() = runTest {
        repo.insert(repo.station("no-coords", lat = 0.0, lon = 0.0))

        val result = register("no-coords")

        assertTrue(result.exceptionOrNull() is GeofenceManager.GeofenceRegistrationException.StationNotFound)
    }

    @Test
    fun addGeofences_forARailwayStationWithoutARow_whenTenOthersAreActive_failsWithMaxStationsReached() = runTest {
        // "NDLS" resolves from the offline list, so it needs no active_stations row of its own. (A station whose
        // row already exists is exempt from the cap - see the next test - which is why StationRepository
        // pre-flights the cap before inserting.)
        StationData.initialize(app)
        repeat(GeofenceManager.MAX_ACTIVE_STATIONS) { repo.insert(repo.station("existing-$it")) }

        val result = register("NDLS")

        assertTrue(result.exceptionOrNull() is GeofenceManager.GeofenceRegistrationException.MaxStationsReached)
        assertEquals(0, gms.addCalls.size)
    }

    @Test
    fun addGeofences_forAnAlreadyActiveStation_isAllowedAtTheLimit_soEditAndBootReRegistrationWork() = runTest {
        repeat(GeofenceManager.MAX_ACTIVE_STATIONS) { repo.insert(repo.station("existing-$it")) }

        val result = register("existing-3")

        assertTrue(result.isSuccess)
        assertEquals(9, allRegistered().size)
    }

    // ------------------------------------------------------------------ retry behaviour

    @Test
    fun addGeofences_retriesAfterATransientFailure_withBackoffOf2s() = runTest {
        repo.insert(repo.station("my-stop"))
        gms.failAddCalls = setOf(0) // the first levels request fails

        val result = register("my-stop")

        assertTrue(result.isSuccess)
        assertEquals("failed levels + retried levels + alert", 3, gms.addCalls.size)
        assertEquals(2_000L, currentTime)
    }

    @Test
    fun addGeofences_whenTheAlertRequestFails_theWholeSequenceIsRetried() = runTest {
        repo.insert(repo.station("my-stop"))
        gms.failAddCalls = setOf(1) // levels ok, alert fails

        val result = register("my-stop")

        assertTrue(result.isSuccess)
        assertEquals(4, gms.addCalls.size)
        assertEquals(listOf("geofence_my-stop_alert"), gms.addCalls[3].requestIds)
    }

    @Test
    fun addGeofences_givesUpAfterThreeAttempts_withExponentialBackoff_andReportsRegistrationFailed() = runTest {
        repo.insert(repo.station("my-stop"))
        gms.failAddCalls = setOf(0, 1, 2)

        val result = register("my-stop")

        val error = result.exceptionOrNull()
        assertTrue(error is GeofenceManager.GeofenceRegistrationException.RegistrationFailed)
        assertEquals("GMS unavailable (test)", error!!.message)
        assertEquals(3, gms.addCalls.size)
        assertEquals("2s + 4s of backoff, no sleep after the last attempt", 6_000L, currentTime)
    }

    // ------------------------------------------------------------------ removal

    @Test
    fun removeGeofences_removesExactlyTheNineIdsOfThatStation_inOneCall() = runTest {
        GeofenceManager.removeGeofencesForStation(app, "my-stop")

        assertEquals(1, gms.removeByIdCalls.size)
        assertEquals(
            (1..8).map { "geofence_my-stop_level$it" }.toSet() + "geofence_my-stop_alert",
            gms.removeByIdCalls.single().toSet()
        )
        assertEquals(9, gms.removeByIdCalls.single().size)
    }

    @Test
    fun removeGeofences_neverUsesRemoveByPendingIntent_soOtherStationsKeepTheirGeofences() = runTest {
        GeofenceManager.removeGeofencesForStation(app, "my-stop")

        assertEquals(0, gms.removeByIntentCalls.size)
    }

    @Test
    fun attemptRemove_retriesTwiceWithBackoff_thenReportsFailureInsteadOfThrowing() = runTest {
        gms.failRemoveCalls = setOf(0, 1, 2)

        val result = GeofenceManager.attemptRemoveGeofencesForStation(app, "my-stop")

        assertTrue(result.isFailure)
        assertEquals(3, gms.removeByIdCalls.size)
        assertEquals("500ms + 1s of backoff", 1_500L, currentTime)
    }

    @Test
    fun attemptRemove_recoversFromATransientFailure() = runTest {
        gms.failRemoveCalls = setOf(0)

        val result = GeofenceManager.attemptRemoveGeofencesForStation(app, "my-stop")

        assertTrue(result.isSuccess)
        assertEquals(2, gms.removeByIdCalls.size)
        assertEquals(500L, currentTime)
    }

    @Test
    fun removalIdsMatchRegistrationIds_soNothingIsLeakedAgainstTheGmsCap() = runTest {
        repo.insert(repo.station("my-stop"))
        register("my-stop")

        GeofenceManager.removeGeofencesForStation(app, "my-stop")

        assertEquals(
            allRegistered().map { it.requestId }.toSet(),
            gms.removeByIdCalls.single().toSet()
        )
        assertFalse(gms.removeByIdCalls.single().isEmpty())
    }
}
