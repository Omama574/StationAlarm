package com.omama.stationalarm.receiver

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import com.omama.stationalarm.geofence.FakeGeofencing
import com.omama.stationalarm.geofence.RecordingGeofencingClient
import com.omama.stationalarm.geofence.RepositoryHarness
import com.omama.stationalarm.geofence.ShadowLocationServicesGeofencing
import com.omama.stationalarm.geofence.WorkTestSupport
import com.omama.stationalarm.service.LocationService
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * "Boot completed restarts monitoring only when armed stations exist." The worker is run directly (doWork())
 * with a chosen attempt count; geofence registration goes to the recording Play services fake.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], application = android.app.Application::class, shadows = [ShadowLocationServicesGeofencing::class])
class BootRestoreWorkerTest {

    private lateinit var app: Application
    private lateinit var repo: RepositoryHarness
    private lateinit var gms: RecordingGeofencingClient

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        gms = FakeGeofencing.reset()
        repo = RepositoryHarness(app)
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        shadowOf(app).clearStartedServices()
    }

    @After
    fun tearDown() = repo.close()

    private fun worker(attempt: Int = 0) = BootRestoreWorker(app, WorkTestSupport.params(app, attempt))

    private fun registeredIds() = gms.addCalls.flatMap { it.requestIds }.toSet()

    // ------------------------------------------------------------------ nothing armed

    @Test
    fun noStationsAtAll_succeedsWithoutStartingTheServiceOrTouchingPlayServices() = runTest {
        val result = worker().doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertNull(shadowOf(app).nextStartedService)
        assertEquals(0, gms.addCalls.size)
    }

    @Test
    fun onlyPausedStations_succeedsWithoutStartingTheService() = runTest {
        repo.insert(repo.station("paused-a", status = "PAUSED"))
        repo.insert(repo.station("paused-b", status = "PAUSED"))

        val result = worker().doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertNull("no 'Monitoring...' flash for history rows", shadowOf(app).nextStartedService)
        assertEquals(0, gms.addCalls.size)
        assertEquals("PAUSED", repo.status("paused-a"))
    }

    // ------------------------------------------------------------------ armed stations

    @Test
    fun monitoringStation_isReRegistered_andTheServiceIsStartedAfterwards() = runTest {
        repo.insert(repo.station("my-stop", status = "MONITORING"))

        val result = worker().doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(
            (1..8).map { "geofence_my-stop_level$it" }.toSet() + "geofence_my-stop_alert",
            registeredIds()
        )
        val service = shadowOf(app).nextStartedService
        assertNotNull(service)
        assertEquals(LocationService.ACTION_START_FOR_ACTIVE_STATIONS, service.action)
        assertEquals(LocationService::class.java.name, service.component?.className)
        assertNull(shadowOf(app).nextStartedService)
    }

    @Test
    fun alertingStationLeftOverFromBeforeTheReboot_isResetToMonitoring_andReRegistered() = runTest {
        repo.insert(repo.station("my-stop", status = "ALERTING"))

        val result = worker().doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals("MONITORING", repo.status("my-stop"))
        assertTrue("geofence_my-stop_alert" in registeredIds())
        assertEquals(LocationService.ACTION_START_FOR_ACTIVE_STATIONS, shadowOf(app).nextStartedService.action)
    }

    @Test
    fun pausedStationsAreLeftAlone_whileMonitoringOnesAreRestored() = runTest {
        repo.insert(repo.station("live-stop", status = "MONITORING"))
        repo.insert(repo.station("paused-stop", status = "PAUSED"))

        val result = worker().doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(9, registeredIds().size)
        assertTrue(registeredIds().all { it.startsWith("geofence_live-stop_") })
        assertEquals("PAUSED", repo.status("paused-stop"))
        assertNotNull(shadowOf(app).nextStartedService)
    }

    @Test
    fun severalMonitoringStations_areAllReRegistered() = runTest {
        repo.insert(repo.station("stop-a", status = "MONITORING"))
        repo.insert(repo.station("stop-b", status = "MONITORING"))

        worker().doWork()

        assertEquals(18, registeredIds().size)
    }

    // ------------------------------------------------------------------ failure handling

    @Test
    fun whenReRegistrationFails_itRetries_andDoesNotStartTheServiceThisAttempt() = runTest {
        repo.insert(repo.station("my-stop", status = "MONITORING"))
        gms.failAddCalls = setOf(0, 1, 2)

        val result = worker(attempt = 0).doWork()

        assertEquals(ListenableWorker.Result.retry(), result)
        assertNull("no 'Monitoring...' notification over broken geofences", shadowOf(app).nextStartedService)
    }

    @Test
    fun staleAlertingRowIsStillResetEvenWhenReRegistrationFails() = runTest {
        repo.insert(repo.station("my-stop", status = "ALERTING"))
        gms.failAddCalls = setOf(0, 1, 2)

        worker().doWork()

        assertEquals("MONITORING", repo.status("my-stop"))
    }

    @Test
    fun whenTheLocationPermissionWasRevoked_itRetriesInsteadOfStartingAFalselyArmedService() = runTest {
        shadowOf(app).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        repo.insert(repo.station("my-stop", status = "MONITORING"))

        val result = worker().doWork()

        assertEquals(ListenableWorker.Result.retry(), result)
        assertNull(shadowOf(app).nextStartedService)
    }

    @Test
    fun anUnreadableDatabase_isRetried_notCrashed() = runTest {
        repo.breakDatabase()

        val result = worker().doWork()

        assertEquals(ListenableWorker.Result.retry(), result)
        assertNull(shadowOf(app).nextStartedService)
    }

    @Test
    fun afterFourFailedAttempts_itStillRetriesQuietly() = runTest {
        repo.insert(repo.station("my-stop", status = "MONITORING"))
        gms.failAddCalls = (0..20).toSet()

        val result = worker(attempt = 4).doWork()

        assertEquals(ListenableWorker.Result.retry(), result)
        assertEquals(0, shadowOf(app.getSystemService(NotificationManager::class.java)).size())
    }

    @Test
    fun onTheFifthFailedAttempt_itGivesUp_andTellsTheUserToReopenTheApp() = runTest {
        repo.insert(repo.station("my-stop", status = "MONITORING"))
        gms.failAddCalls = (0..20).toSet()

        val result = worker(attempt = 5).doWork()

        assertEquals(ListenableWorker.Result.failure(), result)
        assertNull(shadowOf(app).nextStartedService)
        val nm = app.getSystemService(NotificationManager::class.java)
        val notification = requireNotNull(shadowOf(nm).getNotification(9100))
        assertEquals("Restart needed", notification.extras.getCharSequence("android.title").toString())
        assertEquals("Open StationAlarm to re-arm your alarms.", notification.extras.getCharSequence("android.text").toString())
    }

    @Ignore(
        "BUG: BootRestoreWorker.kt:104 posts the 'Restart needed' escalation on ServiceNotifications.CHANNEL_ID " +
            "('location_channel', IMPORTANCE_LOW) even though it asks for PRIORITY_HIGH. On Android 8+ the channel's " +
            "importance wins, so the one message that tells the user their alarms are NOT armed after a reboot is " +
            "posted silently with no heads-up. Expected: a channel with importance >= IMPORTANCE_DEFAULT " +
            "(e.g. alarm_channel). Actual: IMPORTANCE_LOW."
    )
    @Test
    fun theRestartNeededNotice_isPostedOnAChannelThatCanActuallyAlertTheUser() = runTest {
        repo.insert(repo.station("my-stop", status = "MONITORING"))
        gms.failAddCalls = (0..20).toSet()

        worker(attempt = 5).doWork()

        val nm = app.getSystemService(NotificationManager::class.java)
        val notification = requireNotNull(shadowOf(nm).getNotification(9100))
        val importance = nm.getNotificationChannel(notification.channelId).importance
        assertTrue("importance was $importance", importance >= NotificationManager.IMPORTANCE_DEFAULT)
    }
}
