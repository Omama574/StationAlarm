package com.omama.stationalarm.repository

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker.Result
import com.omama.stationalarm.receiver.BootRestoreWorker
import com.omama.stationalarm.repository.RepositoryTestEnv.app
import com.omama.stationalarm.repository.RepositoryTestEnv.entity
import com.omama.stationalarm.repository.RepositoryTestEnv.hasNoGeofences
import com.omama.stationalarm.repository.RepositoryTestEnv.isRegistered
import com.omama.stationalarm.repository.RepositoryTestEnv.newId
import com.omama.stationalarm.repository.RepositoryTestEnv.seed
import com.omama.stationalarm.repository.RepositoryTestEnv.setLocationPermission
import com.omama.stationalarm.repository.RepositoryTestEnv.status
import com.omama.stationalarm.service.LocationService
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** [BootRestoreWorker.doWork] against the real repository and the fake GMS. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], application = Application::class, shadows = [ShadowLocationServices::class])
class BootRestoreWorkerTest {

    private val failureNotificationId = 9100 // BootRestoreWorker.BOOT_RESTORE_FAILED_NOTIF_ID (private)

    @Before fun setUp() = RepositoryTestEnv.setUp()
    @After fun tearDown() = RepositoryTestEnv.tearDown()

    private fun run(attempt: Int = 0): Result =
        runBlocking { BootRestoreWorker(app, workerParams(runAttemptCount = attempt)).doWork() }

    private fun startedService() = shadowOf(app).nextStartedService

    private fun postedNotification(): Notification? =
        shadowOf(app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).getNotification(failureNotificationId)

    @Test
    fun withNoAlarms_succeedsWithoutStartingTheServiceOrTouchingGms() {
        assertEquals(Result.success(), run())
        assertNull(startedService())
        assertTrue(FakeGms.addCalls.isEmpty())
    }

    @Test
    fun withOnlyPausedAlarms_isAnInstantNoOp() {
        val id = newId(); seed(entity(id, status = "PAUSED"))
        assertEquals(Result.success(), run())
        assertNull("a PAUSED-only user must not get a foreground service at boot", startedService())
        assertTrue(hasNoGeofences(id))
        assertEquals("PAUSED", status(id))
    }

    @Test
    fun withAMonitoringAlarm_reRegistersGeofences_thenStartsTheLocationService() {
        val id = newId(); seed(entity(id, status = "MONITORING"))
        assertEquals(Result.success(), run())

        assertTrue(isRegistered(id))
        val intent = startedService()
        assertNotNull(intent)
        assertEquals(LocationService.ACTION_START_FOR_ACTIVE_STATIONS, intent!!.action)
        assertEquals(LocationService::class.java.name, intent.component!!.className)
    }

    @Test
    fun anAlertingAlarmLeftOverFromBeforeTheReboot_isResetToMonitoring_andReArmed() {
        val (alerting, monitoring, paused) = List(3) { newId() }
        seed(entity(alerting, status = "ALERTING"), entity(monitoring, status = "MONITORING"), entity(paused, status = "PAUSED"))

        assertEquals(Result.success(), run())

        assertEquals("MONITORING", status(alerting))
        assertEquals("MONITORING", status(monitoring))
        assertEquals("PAUSED", status(paused))
        assertTrue(isRegistered(alerting))
        assertTrue(isRegistered(monitoring))
        assertTrue(hasNoGeofences(paused))
        assertNotNull(startedService())
    }

    @Test
    fun anAlertingOnlyUser_counts_asArmed_andIsRestored() {
        val id = newId(); seed(entity(id, status = "ALERTING"))
        assertEquals(Result.success(), run())
        assertEquals("MONITORING", status(id))
        assertNotNull(startedService())
    }

    @Test
    fun whenRegistrationFails_retries_andDoesNotStartTheServiceOverBrokenGeofences() {
        val id = newId(); seed(entity(id))
        setLocationPermission(false)

        assertEquals(Result.retry(), run(attempt = 0))
        assertNull(startedService())
        assertNull(postedNotification())
        assertTrue(hasNoGeofences(id))
    }

    @Test
    fun aPartialFailure_stillRetriesTheWholeRestore_andHoldsBackTheService() {
        val ok = newId("ok"); val bad = newId("bad")
        seed(entity(ok), entity(bad, lat = 0.0, lon = 0.0)) // (0,0) custom row cannot be resolved
        assertEquals(Result.retry(), run())
        assertNull(startedService())
        assertTrue("the resolvable alarm was still registered", isRegistered(ok))
    }

    @Test
    fun theFourthRetry_isStillSilent() {
        seed(entity(newId())); setLocationPermission(false)
        assertEquals(Result.retry(), run(attempt = 4))
        assertNull(postedNotification())
    }

    @Test
    fun afterFiveFailedAttempts_givesUp_andTellsTheUserToReopenTheApp() {
        seed(entity(newId())); setLocationPermission(false)

        assertEquals(Result.failure(), run(attempt = 5))

        val n = postedNotification()
        assertNotNull("user must be told their alarms are not restored", n)
        assertEquals("Restart needed", n!!.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertNull(startedService())
    }

    @Test
    fun whenTheRepositoryIsNotInitialisedYet_retriesInsteadOfCrashing() {
        val restore = detachRepositoryDatabase()
        try {
            assertEquals(Result.retry(), run())
        } finally {
            restore()
        }
        assertNull(startedService())
    }
}
