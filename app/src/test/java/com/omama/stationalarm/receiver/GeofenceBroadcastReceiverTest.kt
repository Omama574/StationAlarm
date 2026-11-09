package com.omama.stationalarm.receiver

import android.Manifest
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.PowerManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.omama.stationalarm.geofence.FakeGeofencing
import com.omama.stationalarm.geofence.GeofenceIntents
import com.omama.stationalarm.geofence.ReceiverDelivery
import com.omama.stationalarm.geofence.RecordingGeofencingClient
import com.omama.stationalarm.geofence.RepositoryHarness
import com.omama.stationalarm.geofence.ShadowLocationServicesGeofencing
import com.omama.stationalarm.geofence.TestReflection
import com.omama.stationalarm.repository.StationRepository
import com.omama.stationalarm.service.LocationService
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowPowerManager
import java.util.concurrent.ConcurrentHashMap

/**
 * GeofenceBroadcastReceiver: GMS geofence event -> (maybe) mark the station ALERTING -> start LocationService.
 *
 * Request ids are `geofence_<stationId>_<layer>`; layers `level1`..`level8` wake the service
 * (ACTION_GEOFENCE_TRIGGERED), `alert` additionally flips the row to ALERTING and uses
 * ACTION_ALERT_GEOFENCE_TRIGGERED. Real Room (in memory) is used; Play services is the recording fake.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], application = android.app.Application::class, shadows = [ShadowLocationServicesGeofencing::class])
class GeofenceBroadcastReceiverTest {

    private lateinit var app: Application
    private lateinit var repo: RepositoryHarness
    private lateinit var gms: RecordingGeofencingClient
    private val receiver = GeofenceBroadcastReceiver()
    private val heldMutexes = mutableListOf<Pair<String, Mutex>>()

    private val levels = (1..8).map { "level$it" }

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        gms = FakeGeofencing.reset()
        repo = RepositoryHarness(app)
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        shadowOf(app).clearStartedServices()
        GeofenceBroadcastReceiver.releaseStartupLock()
    }

    @After
    fun tearDown() {
        // Free any station mutex a test deliberately kept locked, then let the receiver finish.
        locks().let { map -> heldMutexes.forEach { (id, m) -> if (m.isLocked) m.unlock(); map.remove(id) } }
        GeofenceBroadcastReceiver.releaseStartupLock()
        repo.close()
    }

    // ------------------------------------------------------------------ helpers

    private fun locks(): ConcurrentHashMap<String, Mutex> =
        TestReflection.getStatic(StationRepository::class.java, "stationLocks")

    private fun requestId(station: String, layer: String) = "geofence_${station}_$layer"

    private class Sent(val delivery: ReceiverDelivery, val startupLock: PowerManager.WakeLock?)

    private fun send(intent: Intent, context: Context = app): Sent {
        val delivery = ReceiverDelivery.deliver(receiver, context, intent)
        return Sent(delivery, ShadowPowerManager.getLatestWakeLock())
    }

    private fun sendEvent(vararg requestIds: String, context: Context = app): Sent =
        send(GeofenceIntents.enter(*requestIds), context)

    /** For events that reach goAsync(): wait for pendingResult.finish() and for the startup lock to be released. */
    private fun Sent.awaitDone() {
        delivery.awaitFinish()
        awaitLockReleased()
    }

    private fun Sent.awaitLockReleased() {
        val lock = requireNotNull(startupLock)
        val deadline = System.currentTimeMillis() + 10_000
        while (lock.isHeld && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertFalse("startup wake lock must be released once the event is handled", lock.isHeld)
    }

    private fun startedServices(): List<Intent> {
        val all = mutableListOf<Intent>()
        while (true) all += shadowOf(app).nextStartedService ?: break
        return all
    }

    private fun armed(id: String, status: String = "MONITORING") = repo.insert(repo.station(id, status = status))

    // ------------------------------------------------------------------ events that never reach the service

    @Test
    fun intentWithoutGeofencingEvent_isDropped_andReleasesTheStartupLockImmediately() {
        armed("my-stop")

        val sent = send(GeofenceIntents.empty())

        assertEquals("StationAlarm::GeofenceStartup", shadowOf(sent.startupLock!!).tag)
        assertFalse(sent.startupLock!!.isHeld)
        assertNull(shadowOf(app).nextStartedService)
        assertFalse("goAsync() must not be taken for a dropped event", sent.delivery.finishedFuture!!.isDone)
    }

    @Test
    fun errorEvent_isLoggedAndDropped_withoutStartingTheService() {
        armed("my-stop")

        val sent = send(GeofenceIntents.error(1000)) // GEOFENCE_NOT_AVAILABLE

        assertFalse(sent.startupLock!!.isHeld)
        assertNull(shadowOf(app).nextStartedService)
        assertEquals("MONITORING", repo.status("my-stop"))
    }

    @Test
    fun eventWithNoTriggeringGeofences_isDropped() {
        armed("my-stop")

        val sent = sendEvent() // valid event, empty geofence list

        assertFalse(sent.startupLock!!.isHeld)
        assertNull(shadowOf(app).nextStartedService)
    }

    @Test
    fun malformedRequestIds_areSkipped_withoutCrashingOrStartingAnything() {
        armed("my-stop")

        val sent = sendEvent("totally-unrelated", "geofence_only", "geofence-no-underscores-at-all", "_", "__")
        sent.awaitDone()

        assertNull(shadowOf(app).nextStartedService)
        assertEquals("MONITORING", repo.status("my-stop"))
    }

    @Test
    fun malformedIdInTheMiddleOfAnEvent_doesNotStopLaterValidGeofencesFromBeingHandled() {
        armed("my-stop")

        sendEvent("garbage", requestId("my-stop", "level2")).awaitDone()

        val services = startedServices()
        assertEquals(1, services.size)
        assertEquals("level2", services.single().getStringExtra("layer"))
    }

    @Test
    fun geofenceForUnknownStation_isIgnored() {
        sendEvent(requestId("deleted-stop", "alert"), requestId("deleted-stop", "level3")).awaitDone()

        assertNull(shadowOf(app).nextStartedService)
        assertEquals("no geofence work for a station that no longer exists", 0, gms.removeByIdCalls.size)
    }

    @Test
    fun geofenceForPausedStation_isIgnored_soADismissedAlarmCannotRefire() {
        armed("my-stop", status = "PAUSED")

        sendEvent(requestId("my-stop", "alert"), requestId("my-stop", "level1")).awaitDone()

        assertNull(shadowOf(app).nextStartedService)
        assertEquals("PAUSED", repo.status("my-stop"))
        assertEquals(0, gms.removeByIdCalls.size)
        assertNull("a late event must not stamp 'last triggered'", repo.lastTriggeredAt("my-stop"))
    }

    // ------------------------------------------------------------------ level layers

    @Test
    fun everyLevelLayer_startsTheServiceWithGeofenceTriggered_andLeavesTheStationMonitoring() {
        armed("my-stop")

        for (layer in levels) {
            sendEvent(requestId("my-stop", layer)).awaitDone()

            val service = shadowOf(app).nextStartedService
            assertNotNull("no service started for $layer", service)
            assertEquals(LocationService.ACTION_GEOFENCE_TRIGGERED, service.action)
            assertEquals(LocationService::class.java.name, service.component?.className)
            assertEquals("my-stop", service.getStringExtra("stationId"))
            assertEquals(layer, service.getStringExtra("layer"))
        }
        assertEquals("level rings only wake GPS; they must not fire the alarm", "MONITORING", repo.status("my-stop"))
        assertEquals(0, gms.removeByIdCalls.size)
        assertNull(repo.lastTriggeredAt("my-stop"))
    }

    @Test
    fun levelLayer_forAnAlertingStation_isStillForwardedToTheService() {
        armed("my-stop", status = "ALERTING")

        sendEvent(requestId("my-stop", "level1")).awaitDone()

        assertEquals(LocationService.ACTION_GEOFENCE_TRIGGERED, shadowOf(app).nextStartedService.action)
    }

    // ------------------------------------------------------------------ alert layer (inner entry)

    @Test
    fun alertLayer_marksStationAlerting_removesItsGeofences_andStartsTheServiceWithTheAlertAction() {
        armed("my-stop")

        sendEvent(requestId("my-stop", "alert")).awaitDone()

        val service = shadowOf(app).nextStartedService
        assertNotNull(service)
        assertEquals(LocationService.ACTION_ALERT_GEOFENCE_TRIGGERED, service.action)
        assertEquals("my-stop", service.getStringExtra("stationId"))
        assertEquals("alert", service.getStringExtra("layer"))
        assertEquals("ALERTING", repo.status("my-stop"))
        assertNotNull("'last triggered' is stamped", repo.lastTriggeredAt("my-stop"))
        assertEquals(1, gms.removeByIdCalls.size)
        assertEquals(
            (1..8).map { "geofence_my-stop_level$it" }.toSet() + "geofence_my-stop_alert",
            gms.removeByIdCalls.single().toSet()
        )
    }

    @Test
    fun alertLayer_onlyAffectsTheStationInTheRequestId() {
        armed("stop-a")
        armed("stop-b")

        sendEvent(requestId("stop-a", "alert")).awaitDone()

        assertEquals("ALERTING", repo.status("stop-a"))
        assertEquals("MONITORING", repo.status("stop-b"))
    }

    @Test
    fun duplicateAlertEvent_isIdempotent_theStationStaysAlertingAndNothingThrows() {
        armed("my-stop")

        sendEvent(requestId("my-stop", "alert")).awaitDone()
        sendEvent(requestId("my-stop", "alert")).awaitDone()

        assertEquals("ALERTING", repo.status("my-stop"))
        val services = startedServices()
        assertTrue(services.isNotEmpty())
        assertTrue(services.all { it.action == LocationService.ACTION_ALERT_GEOFENCE_TRIGGERED })
    }

    @Test
    fun oneEventCarryingSeveralGeofences_handlesEachOneInOrder() {
        armed("stop-a")
        armed("stop-b")

        sendEvent(
            requestId("stop-a", "level3"),
            requestId("stop-a", "alert"),
            requestId("stop-b", "level1")
        ).awaitDone()

        val services = startedServices()
        assertEquals(
            listOf(
                LocationService.ACTION_GEOFENCE_TRIGGERED to "stop-a",
                LocationService.ACTION_ALERT_GEOFENCE_TRIGGERED to "stop-a",
                LocationService.ACTION_GEOFENCE_TRIGGERED to "stop-b"
            ),
            services.map { it.action to it.getStringExtra("stationId") }
        )
        assertEquals("ALERTING", repo.status("stop-a"))
        assertEquals("MONITORING", repo.status("stop-b"))
    }

    // ------------------------------------------------------------------ wake lock

    @Test
    fun startupWakeLock_isHeldWhileTheEventIsStillBeingProcessed_andReleasedWhenItFinishes() {
        armed("slow-stop")
        val blocker = blockStation("slow-stop") // markAlertingSync() will wait for this lock

        val sent = sendEvent(requestId("slow-stop", "alert"))

        val lock = requireNotNull(sent.startupLock)
        assertEquals("StationAlarm::GeofenceStartup", shadowOf(lock).tag)
        assertTrue("CPU must stay awake between onReceive and startForegroundService", lock.isHeld)
        assertFalse(sent.delivery.finishedFuture!!.isDone)
        assertNull("service must not start before the DB write completed", shadowOf(app).nextStartedService)

        runBlocking { blocker.unlock() }
        sent.awaitDone()

        assertFalse(lock.isHeld)
        assertEquals(LocationService.ACTION_ALERT_GEOFENCE_TRIGGERED, shadowOf(app).nextStartedService.action)
    }

    @Test
    fun acquireAndReleaseStartupLock_areSafeToCallRepeatedly() {
        GeofenceBroadcastReceiver.acquireStartupLock(app)
        val first = ShadowPowerManager.getLatestWakeLock()
        assertTrue(first.isHeld)

        GeofenceBroadcastReceiver.acquireStartupLock(app) // replaces, does not stack
        val second = ShadowPowerManager.getLatestWakeLock()
        assertFalse("previous lock released when replaced", first.isHeld)
        assertTrue(second.isHeld)

        GeofenceBroadcastReceiver.releaseStartupLock()
        GeofenceBroadcastReceiver.releaseStartupLock()
        assertFalse(second.isHeld)
    }

    @Test
    fun startupWakeLock_hasASelfReleasingTimeout() {
        GeofenceBroadcastReceiver.acquireStartupLock(app)
        val lock = ShadowPowerManager.getLatestWakeLock()

        shadowOf(android.os.Looper.getMainLooper()).idleFor(11, java.util.concurrent.TimeUnit.SECONDS)

        assertFalse("must release by itself after 10s even if the coroutine died", lock.isHeld)
    }

    @Ignore(
        "BUG: GeofenceBroadcastReceiver.kt:31-42,87,153-154 keeps ONE static startupWakeLock slot. " +
            "A second broadcast arriving while the first is still being processed releases the first's lock " +
            "(acquireStartupLock line 37), and the second event's finally block then releases its own - so the " +
            "first event, still in flight, runs with no bridge wake lock at all. Expected: some startup lock " +
            "held until the LAST in-flight event finishes. Actual: none held."
    )
    @Test
    fun overlappingEvents_keepAStartupLockHeldWhileEitherIsStillInFlight() {
        armed("slow-stop")
        armed("fast-stop")
        val blocker = blockStation("slow-stop")

        val slow = sendEvent(requestId("slow-stop", "alert")) // stays in flight
        val fast = sendEvent(requestId("fast-stop", "level1"))
        fast.awaitDone()

        assertFalse("slow event is still being processed", slow.delivery.finishedFuture!!.isDone)
        assertTrue(
            "an event is still in flight, so a startup wake lock must still be held",
            slow.startupLock!!.isHeld || fast.startupLock!!.isHeld
        )
        runBlocking { blocker.unlock() }
        slow.delivery.awaitFinish()
    }

    @Test
    fun whenTheRepositoryFailsMidEvent_theBroadcastStillFinishes_andTheStartupLockIsReleased() {
        armed("my-stop")
        repo.breakDatabase()

        // awaitDone() times out if pendingResult.finish() is skipped, and fails if the lock is left held.
        sendEvent(requestId("my-stop", "alert")).awaitDone()
    }

    // ------------------------------------------------------------------ startForegroundService failure

    private class RefusesToStartServices(base: Context) : ContextWrapper(base) {
        override fun startForegroundService(service: Intent?): ComponentName? =
            throw IllegalStateException("BackgroundServiceStartNotAllowed (test)")

        override fun startService(service: Intent?): ComponentName? =
            throw IllegalStateException("BackgroundServiceStartNotAllowed (test)")
    }

    @Test
    fun serviceStartRefused_forALevelLayer_leavesTheStationMonitoring_andTheReceiverStillFinishes() {
        armed("my-stop")

        sendEvent(requestId("my-stop", "level1"), context = RefusesToStartServices(app)).awaitDone()

        assertEquals("MONITORING", repo.status("my-stop"))
    }

    @Test
    fun serviceStartRefused_forTheAlertLayer_rollsTheStationBackToMonitoring() {
        armed("my-stop")

        sendEvent(requestId("my-stop", "alert"), context = RefusesToStartServices(app)).awaitDone()

        assertEquals(
            "a stale ALERTING row with no service behind it would never ring",
            "MONITORING", repo.status("my-stop")
        )
    }

    @Ignore(
        "BUG: GeofenceBroadcastReceiver.kt:120,144-146. On the alert layer markAlertingSync() REMOVES all nine " +
            "geofences (StationRepository.kt markAlertingSync -> removeGeofencesForStation) before the service is " +
            "started. If startForegroundService then fails, the catch block only calls resetToMonitoring(); the " +
            "comment promises 'a later trigger can retry cleanly' but no geofence is left to trigger. " +
            "Expected: station MONITORING AND its geofences registered again. Actual: MONITORING with zero " +
            "live geofences until the next app start / reboot (reconcileOrphans / BootRestoreWorker)."
    )
    @Test
    fun serviceStartRefused_forTheAlertLayer_geofencesAreRegisteredAgain() {
        armed("my-stop")

        sendEvent(requestId("my-stop", "alert"), context = RefusesToStartServices(app)).awaitDone()

        assertEquals("MONITORING", repo.status("my-stop"))
        assertTrue("station is armed again but nothing re-registered its geofences", gms.addCalls.isNotEmpty())
    }

    // ------------------------------------------------------------------ GEOFENCE_NOT_AVAILABLE

    @Ignore(
        "BUG: GeofenceBroadcastReceiver.kt:66-74. A GMS error event (e.g. GEOFENCE_NOT_AVAILABLE = 1000, which " +
            "Android's geofencing guide says to answer by re-registering the geofences) is only logged and " +
            "counted in Analytics. Expected: armed stations get their geofences registered again. " +
            "Actual: nothing is re-registered until the next app start or reboot."
    )
    @Test
    fun geofenceNotAvailableError_triggersReRegistrationOfArmedStations() {
        armed("my-stop")

        send(GeofenceIntents.error(1000))
        Thread.sleep(500) // re-registration (if it existed) would run asynchronously

        assertTrue(gms.addCalls.isNotEmpty())
    }

    // ------------------------------------------------------------------ legacy ids

    @Ignore(
        "BUG: GeofenceBroadcastReceiver.kt:92-98 splits `geofence_<id>_<layer>` with split('_', limit = 3), so a " +
            "station id that contains '_' is mis-parsed (id 'liq' + layer '481312177_alert') and every hardware " +
            "geofence event for it is dropped. StationIds.sanitize() only protects rows created from now on " +
            "(StationRepository.addActiveStation); there is no migration for rows saved earlier with ids such as " +
            "'liq_481312177', which StationDatabase v6 still holds and GeofenceManager/BootRestoreWorker keep " +
            "re-registering under the unparseable ids. Expected: alarm for that station is processed. " +
            "Actual: ignored (GPS polling in LocationService is then the only trigger)."
    )
    @Test
    fun stationSavedBeforeIdSanitising_withUnderscoreInItsId_stillTriggers() {
        armed("liq_481312177")

        sendEvent(requestId("liq_481312177", "alert")).awaitDone()

        assertEquals("ALERTING", repo.status("liq_481312177"))
    }

    // ------------------------------------------------------------------ helpers needing the repository internals

    /** Locks the per-station mutex StationRepository uses, so work for that station parks until [Mutex.unlock]. */
    private fun blockStation(id: String): Mutex {
        val mutex = Mutex()
        runBlocking { mutex.lock() }
        locks()[id] = mutex
        heldMutexes += id to mutex
        return mutex
    }
}
