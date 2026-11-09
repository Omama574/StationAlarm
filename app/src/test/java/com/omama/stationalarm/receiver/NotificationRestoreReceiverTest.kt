package com.omama.stationalarm.receiver

import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.omama.stationalarm.geofence.FakeGeofencing
import com.omama.stationalarm.geofence.ReceiverDelivery
import com.omama.stationalarm.geofence.RepositoryHarness
import com.omama.stationalarm.geofence.ShadowLocationServicesGeofencing
import com.omama.stationalarm.service.LocationService
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Fires when the user swipes away the ongoing "monitoring" notification. It must put the notification back
 * (by re-starting the foreground service) while anything is still armed, and must do nothing once every
 * alarm is PAUSED, otherwise teardown would loop.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], application = android.app.Application::class, shadows = [ShadowLocationServicesGeofencing::class])
class NotificationRestoreReceiverTest {

    private lateinit var app: Application
    private lateinit var repo: RepositoryHarness
    private val receiver = NotificationRestoreReceiver()

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        FakeGeofencing.reset()
        repo = RepositoryHarness(app)
        shadowOf(app).clearStartedServices()
    }

    @After
    fun tearDown() = repo.close()

    private fun swipeAway() {
        val delivery = ReceiverDelivery.deliver(
            receiver, app, Intent(LocationService.ACTION_RESTORE_NOTIFICATION).setPackage(app.packageName)
        )
        delivery.awaitFinish()
    }

    @Test
    fun withAMonitoringStation_restartsTheForegroundServiceWithTheRestoreAction() {
        repo.insert(repo.station("my-stop", status = "MONITORING"))

        swipeAway()

        val service = shadowOf(app).nextStartedService
        assertNotNull(service)
        assertEquals(LocationService.ACTION_RESTORE_NOTIFICATION, service.action)
        assertEquals(LocationService::class.java.name, service.component?.className)
        assertNull("exactly one restart", shadowOf(app).nextStartedService)
    }

    @Test
    fun withOnlyAnAlertingStation_stillRestoresTheNotification() {
        repo.insert(repo.station("my-stop", status = "ALERTING"))

        swipeAway()

        assertEquals(LocationService.ACTION_RESTORE_NOTIFICATION, shadowOf(app).nextStartedService.action)
    }

    @Test
    fun withAPausedAndAMonitoringStation_restoresOnce() {
        repo.insert(repo.station("paused-stop", status = "PAUSED"))
        repo.insert(repo.station("live-stop", status = "MONITORING"))

        swipeAway()

        assertNotNull(shadowOf(app).nextStartedService)
        assertNull(shadowOf(app).nextStartedService)
    }

    @Test
    fun withOnlyPausedStations_doesNothing_soTeardownCannotLoop() {
        repo.insert(repo.station("paused-a", status = "PAUSED"))
        repo.insert(repo.station("paused-b", status = "PAUSED"))

        swipeAway()

        assertNull(shadowOf(app).nextStartedService)
    }

    @Test
    fun withNoStations_doesNothing() {
        swipeAway()

        assertNull(shadowOf(app).nextStartedService)
    }

    @Test
    fun whenTheDatabaseIsUnreadable_theBroadcastStillFinishes_andNothingIsStarted() {
        repo.insert(repo.station("my-stop", status = "MONITORING"))
        repo.breakDatabase()

        swipeAway() // awaitFinish() would time out if finish() were skipped

        assertNull(shadowOf(app).nextStartedService)
    }
}
