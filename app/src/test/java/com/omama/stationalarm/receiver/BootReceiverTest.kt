package com.omama.stationalarm.receiver

import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkInfo
import com.omama.stationalarm.geofence.FakeGeofencing
import com.omama.stationalarm.geofence.ShadowLocationServicesGeofencing
import com.omama.stationalarm.geofence.WorkTestSupport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * BootReceiver only schedules [BootRestoreWorker] (unique work, KEEP policy); what the worker does is covered
 * in [BootRestoreWorkerTest]. WorkManager is rebuilt per test with a parked executor, so the enqueued work stays
 * pending and cannot race the assertions.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], application = android.app.Application::class, shadows = [ShadowLocationServicesGeofencing::class])
class BootReceiverTest {

    private lateinit var app: Application
    private val receiver = BootReceiver()

    private val pending = setOf(WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING, WorkInfo.State.BLOCKED)

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        FakeGeofencing.reset()
        WorkTestSupport.freshParkedWorkManager(app)
    }

    private fun boot(action: String?) {
        receiver.onReceive(app, Intent().apply { this.action = action })
    }

    private fun states() = WorkTestSupport.states(app, BootRestoreWorker.WORK_NAME)

    @Test
    fun bootCompleted_schedulesTheRestoreWorker() {
        boot(Intent.ACTION_BOOT_COMPLETED)

        assertEquals(1, states().size)
        assertTrue("restore work must be pending, was ${states()}", states().single() in pending)
    }

    @Test
    fun quickBootPowerOn_schedulesTheRestoreWorker() {
        boot("android.intent.action.QUICKBOOT_POWERON")

        assertEquals(1, states().size)
    }

    @Test
    fun bootCompletedTwice_keepsASingleRestoreJob() {
        boot(Intent.ACTION_BOOT_COMPLETED)
        boot(Intent.ACTION_BOOT_COMPLETED)

        assertEquals("KEEP policy: no stacked duplicate restores", 1, states().size)
    }

    @Test
    fun bootThenQuickBoot_keepsASingleRestoreJob() {
        boot(Intent.ACTION_BOOT_COMPLETED)
        boot("android.intent.action.QUICKBOOT_POWERON")

        assertEquals(1, states().size)
    }

    @Test
    fun unrelatedBroadcasts_scheduleNothing() {
        boot(Intent.ACTION_TIME_CHANGED)
        boot(Intent.ACTION_LOCKED_BOOT_COMPLETED)
        boot(null)

        assertTrue(states().isEmpty())
    }

    @Test
    fun theWorkIsUniquelyNamedAndNotCancelledByTheReceiver() {
        boot(Intent.ACTION_BOOT_COMPLETED)

        assertEquals("boot_restore_alarms", BootRestoreWorker.WORK_NAME)
        assertFalse(states().contains(WorkInfo.State.CANCELLED))
    }
}
