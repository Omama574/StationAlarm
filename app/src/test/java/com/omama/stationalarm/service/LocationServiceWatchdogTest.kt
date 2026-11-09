package com.omama.stationalarm.service

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.location.LocationManager
import android.os.PowerManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.gms.location.LocationCallback
import com.omama.stationalarm.R
import com.omama.stationalarm.service.gps.FakeFlpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Watchdog of the behaviour-locked LocationService ("GPS death" handling) together with the real
 * FlpClientGuardian / GpsReliabilityCoordinator it drives:
 *
 *   tick every 30 s (only while polling AND at least one station is monitored)
 *   tier 1 threshold = max(60 s, 3 x interval)   -> silent recovery (restart updates + one-shot fix)
 *   tier 2 threshold = max(180 s, 2 x interval)  -> visible "Searching for GPS" notification, 10 s floor
 *   GPS/network provider off                     -> tier 2 immediately
 *   device in Doze (and providers on)            -> silent recovery only
 *   stall >= 180 s and coarse not armed          -> arm the coarse LocationManager fallback
 *
 * `System.currentTimeMillis()` is real time here, so stalls are produced by back-dating the service's
 * `lastLocationTimeMs`; looper timers (the guardian's 15 s deadline, the 30 s tick) use the paused looper.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class LocationServiceWatchdogTest {

    private lateinit var rig: LocationServiceRig
    private lateinit var notificationManager: NotificationManager
    private lateinit var locationManager: LocationManager
    private var ids = 0


    @Before
    fun setUp() {
        rig = LocationServiceRig()
        notificationManager = rig.app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        locationManager = rig.app.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    }

    @After
    fun tearDown() {
        rig.close()
    }

    private fun watchdogNotification(): Notification? =
        shadowOf(notificationManager).getNotification(ServiceNotifications.WATCHDOG_NOTIFICATION_ID)

    /**
     * One station, polling at [gearMs], cold-start hunt skipped. [observeAlert] additionally registers the station
     * with the rig's recording lock so a fired alarm can be asserted through [LocationServiceRig.status].
     */
    private fun polling(distanceKm: Double = 4.0, gearMs: Long = 10_000L, observeAlert: Boolean = false): String {
        val id = "zz_wd_${System.nanoTime()}_${++ids}"
        val station = rig.station(id, distanceKm = distanceKm)
        if (observeAlert) rig.monitor(station) else rig.monitoringStations[id] = station
        rig.currentPollingIntervalMs = gearMs
        rig.lastLocationTimeMs = System.currentTimeMillis()
        rig.startLocationUpdates()
        assertTrue(rig.isPolling)
        rig.fakeFlp.clearRecords()
        return id
    }

    private fun stalledFor(ms: Long) {
        rig.lastLocationTimeMs = System.currentTimeMillis() - ms
    }

    private fun resetWatchdogObservations() {
        rig.fakeFlp.clearRecords()
        rig.watchdogRecoveryPending = false
        rig.watchdogNotified = false
        notificationManager.cancel(ServiceNotifications.WATCHDOG_NOTIFICATION_ID)
    }

    private fun title(n: Notification) = n.extras.getCharSequence(Notification.EXTRA_TITLE).toString()
    private fun text(n: Notification) = n.extras.getCharSequence(Notification.EXTRA_TEXT).toString()

    // =========================================================================================
    //  Thresholds
    // =========================================================================================

    private data class Gear(val intervalMs: Long, val tier1Ms: Long, val tier2Ms: Long)

    @Test
    fun noActionUpToTier1_andRecoveryJustBeyondIt_forEveryGear() {
        val gears = listOf(
            Gear(10_000, 60_000, 180_000),
            Gear(30_000, 90_000, 180_000),
            Gear(60_000, 180_000, 180_000),
            Gear(300_000, 900_000, 600_000),
            Gear(600_000, 1_800_000, 1_200_000)
        )
        polling()
        for (g in gears) {
            // 5 s of margin: the service reads the real clock a few ms after we back-date it.
            resetWatchdogObservations()
            rig.currentPollingIntervalMs = g.intervalMs
            stalledFor(g.tier1Ms - 5_000)
            rig.handleWatchdogTick()
            assertTrue("gear=${g.intervalMs}: nothing below tier 1", rig.fakeFlp.currentLocationCalls.isEmpty())
            assertTrue("gear=${g.intervalMs}: no restart below tier 1", rig.fakeFlp.registrations.isEmpty())
            assertFalse("gear=${g.intervalMs}", rig.watchdogRecoveryPending)
            assertNull("gear=${g.intervalMs}", watchdogNotification())

            resetWatchdogObservations()
            rig.currentPollingIntervalMs = g.intervalMs
            stalledFor(g.tier1Ms + 5_000)
            rig.handleWatchdogTick()
            assertEquals("gear=${g.intervalMs}: one-shot recovery kicked", 1, rig.fakeFlp.currentLocationCalls.size)
            assertTrue("gear=${g.intervalMs}", rig.watchdogRecoveryPending)
            val visible = g.tier1Ms + 5_000 >= g.tier2Ms
            assertEquals("gear=${g.intervalMs}: visible escalation", visible, rig.watchdogNotified)
            assertEquals("gear=${g.intervalMs}", visible, watchdogNotification() != null)
        }
    }

    @Test
    fun atFarGears_tierTwoIsReachedBeforeTierOne_soTheSilentStageIsSkipped() {
        // Characterisation of the escalation maths: for intervals >= 90 s, tier2 = max(180 s, 2 x interval) is not
        // above tier1 = max(60 s, 3 x interval), so the first reaction at 5/10-minute gears is the visible one.
        polling(gearMs = 300_000L)
        stalledFor(900_000L + 5_000)
        rig.handleWatchdogTick()
        assertTrue(rig.watchdogNotified)
        assertNotNull(watchdogNotification())
    }

    @Test
    fun tierTwo_postsTheNotification_forcesTheTenSecondFloor_andReRegistersAtThatRate() {
        polling(gearMs = 30_000L)
        stalledFor(200_000)

        rig.handleWatchdogTick()

        assertTrue(rig.watchdogNotified)
        assertTrue(rig.watchdogRecoveryPending)
        assertEquals(10_000L, rig.currentPollingIntervalMs)
        assertEquals(10_000L, rig.fakeFlp.registrations.last().request.intervalMillis)
        val n = watchdogNotification()!!
        assertEquals(rig.app.getString(R.string.notif_watchdog_title), title(n))
        assertTrue(text(n), text(n).contains("3m")) // 200 s -> "last fix 3m ago"
    }

    @Test
    fun tierOne_isSilent_noNotification_butRestartsUpdatesAndKicksOneShotRecovery() {
        polling()
        stalledFor(75_000)

        rig.handleWatchdogTick()

        assertTrue(rig.watchdogRecoveryPending)
        assertFalse(rig.watchdogNotified)
        assertNull(watchdogNotification())
        assertEquals("updates restarted", 1, rig.fakeFlp.registrations.size)
        assertEquals(1, rig.fakeFlp.currentLocationCalls.size)
    }

    @Test
    fun aFixAfterAVisibleStall_clearsTheNotificationAndTheFlags() {
        polling(distanceKm = 40.0)
        stalledFor(200_000)
        rig.handleWatchdogTick()
        assertNotNull(watchdogNotification())

        rig.deliverFlpFix(rig.fixAtKm(45.0))

        assertNull("notification must disappear once a fix arrives", watchdogNotification())
        assertFalse(rig.watchdogNotified)
        assertFalse(rig.watchdogRecoveryPending)
    }

    // =========================================================================================
    //  Location services off / Doze / coarse fallback
    // =========================================================================================

    @Test
    fun locationServicesOff_escalatesImmediately_withTheLocationOffMessage() {
        polling()
        shadowOf(locationManager).setProviderEnabled(LocationManager.GPS_PROVIDER, false)
        shadowOf(locationManager).setProviderEnabled(LocationManager.NETWORK_PROVIDER, false)
        stalledFor(65_000) // just past tier 1, far below tier 2

        rig.handleWatchdogTick()

        assertTrue(rig.watchdogNotified)
        val n = watchdogNotification()!!
        assertEquals(rig.app.getString(R.string.notif_watchdog_location_off_title), title(n))
        assertEquals(rig.app.getString(R.string.notif_watchdog_location_off_body), text(n))
    }

    @Test
    fun networkProviderAlone_countsAsLocationAvailable() {
        polling()
        shadowOf(locationManager).setProviderEnabled(LocationManager.GPS_PROVIDER, false)
        shadowOf(locationManager).setProviderEnabled(LocationManager.NETWORK_PROVIDER, true)
        stalledFor(65_000)

        rig.handleWatchdogTick()

        assertFalse("GPS off but network on: not the 'location is off' escalation", rig.watchdogNotified)
        assertNull(watchdogNotification())
    }

    @Test
    fun dozing_suppressesTheVisibleEscalation_butStillKicksSilentRecovery() {
        polling()
        shadowOf(rig.app.getSystemService(Context.POWER_SERVICE) as PowerManager).setIsDeviceIdleMode(true)
        stalledFor(400_000) // would be tier 2 on an awake device

        rig.handleWatchdogTick()

        assertNull(watchdogNotification())
        assertFalse(rig.watchdogNotified)
        assertEquals("silent recovery still runs", 1, rig.fakeFlp.currentLocationCalls.size)
        assertTrue("and updates are not restarted in the Doze branch", rig.fakeFlp.registrations.isEmpty())
    }

    @Test
    fun dozingWithLocationOff_isNotSuppressed() {
        polling()
        shadowOf(rig.app.getSystemService(Context.POWER_SERVICE) as PowerManager).setIsDeviceIdleMode(true)
        shadowOf(locationManager).setProviderEnabled(LocationManager.GPS_PROVIDER, false)
        shadowOf(locationManager).setProviderEnabled(LocationManager.NETWORK_PROVIDER, false)
        stalledFor(65_000)

        rig.handleWatchdogTick()

        assertTrue(rig.watchdogNotified)
        assertNotNull(watchdogNotification())
    }

    @Test
    fun stallOfThreeMinutes_armsTheCoarseFallback_shorterStallsDoNot() {
        polling()
        assertFalse(rig.coordinator.isCoarseArmed)

        stalledFor(100_000) // tier 1 (>60 s) but below 180 s
        rig.handleWatchdogTick()
        assertFalse(rig.coordinator.isCoarseArmed)

        stalledFor(200_000)
        rig.handleWatchdogTick()
        assertTrue("FLP silent for 3+ minutes: raw NETWORK provider takes over", rig.coordinator.isCoarseArmed)
    }

    // =========================================================================================
    //  The 30 s tick guard
    // =========================================================================================

    @Test
    fun tick_actsOnlyWhilePollingAndMonitoring_andAlwaysReschedulesItself() {
        val id = polling()
        stalledFor(10 * 60_000L)
        val tick = rig.watchdogRunnable()

        // (a) monitoring but not polling
        rig.isPolling = false
        tick.run()
        assertTrue("not polling: no recovery", rig.fakeFlp.currentLocationCalls.isEmpty())
        assertTrue(rig.watchdogHandler().hasCallbacks(tick))
        rig.watchdogHandler().removeCallbacks(tick)

        // (b) polling but nothing monitored
        rig.isPolling = true
        rig.monitoringStations.clear()
        tick.run()
        assertTrue("nothing monitored: no recovery", rig.fakeFlp.currentLocationCalls.isEmpty())
        assertNull(watchdogNotification())
        assertTrue(rig.watchdogHandler().hasCallbacks(tick))
        rig.watchdogHandler().removeCallbacks(tick)

        // (c) polling and monitoring: acts
        rig.monitoringStations[id] = rig.station(id, distanceKm = 4.0)
        tick.run()
        assertEquals(1, rig.fakeFlp.currentLocationCalls.size)
        assertTrue(rig.watchdogHandler().hasCallbacks(tick))
    }

    // =========================================================================================
    //  GPS death end to end: stall -> watchdog -> guardian -> new FLP client
    // =========================================================================================

    @Test
    fun gpsSilentWhileAnAlarmIsArmed_theGuardianRecreatesTheFlpClient_afterTheFifteenSecondDeadline() {
        polling(distanceKm = 4.0) // armed alarm: one station in MONITORING
        val originalClient = rig.fakeFlp.client
        stalledFor(90_000) // FLP has been silent for 90 s (> tier 1 at the 10 s gear)

        rig.watchdogRunnable().run() // the 30 s tick

        assertEquals("tier-1 recovery started", 1, rig.fakeFlp.currentLocationCalls.size)
        assertSame(originalClient, rig.guardian.client)
        assertTrue(rig.recreatedClients.isEmpty())

        rig.advanceMs(14_999)
        assertSame("deadline not reached yet", originalClient, rig.guardian.client)

        rig.advanceMs(1)
        assertEquals(listOf("15s timeout"), rig.recoveryFailures)
        assertNotSame("dead IPC handle replaced by a fresh client", originalClient, rig.guardian.client)
        assertEquals(1, rig.recreatedClients.size)
        assertSame(rig.guardian.client, rig.recreatedClients.single())
    }

    @Ignore("BUG: LocationService.kt:938-942 calls flpGuardian.configure(..., callback = locationCallback) on every (re)start, and the locationCallback field is never replaced after a nuclear reset (onClientRecreated at 152 is a no-op). So the first restart after a reset (a gear change, or the very next 30 s watchdog tick while still stalled) re-registers the pre-reset callback instance on the new client and discards the fresh callback the guardian built, although LocationService.kt:189-193 and FlpClientGuardian.kt:207 state that GMS needs a fresh callback object")
    @Test
    fun afterAClientReset_aLaterRestartMustKeepRegisteringTheFreshCallback() {
        polling(distanceKm = 4.0)
        val original = Reflect.get<LocationCallback>(rig.service, "locationCallback")
        stalledFor(90_000)
        rig.watchdogRunnable().run()
        rig.advanceMs(15_000) // 15 s deadline -> nuclear reset
        assertEquals(1, rig.recreatedClients.size)
        val fresh = Reflect.get<LocationCallback?>(rig.guardian, "currentCallback")
        assertNotSame("sanity: the reset built a new callback object", original, fresh)

        // Observe the NEXT registration on the new client (a gear change or the next 30 s watchdog tick does this).
        val postReset = FakeFlpClient("post-reset")
        LocationServiceRig.injectClient(rig.guardian, postReset.client)
        rig.restartLocationUpdates()

        assertNotSame(
            "the pre-reset callback instance was registered on the new client again",
            original,
            postReset.registrations.last().callback
        )
    }

    @Test
    fun noAlarmArmed_neverRecreatesTheFlpClient_evenIfLocationsNeverArrive() {
        polling()
        rig.monitoringStations.clear() // nothing armed any more
        val originalClient = rig.fakeFlp.client
        stalledFor(2 * 3_600_000L) // "silent" for two hours

        rig.watchdogRunnable().run()
        rig.advanceMs(10 * 60_000L) // twenty 30 s ticks

        assertTrue("no recovery attempts at all", rig.fakeFlp.currentLocationCalls.isEmpty())
        assertTrue(rig.recreatedClients.isEmpty())
        assertTrue(rig.recoveryFailures.isEmpty())
        assertSame(originalClient, rig.guardian.client)
        assertNull(watchdogNotification())
        assertFalse(rig.watchdogNotified)
    }

    @Test
    fun locationsResumeBeforeTheDeadline_noClientIsRecreated() {
        polling(distanceKm = 40.0)
        val originalClient = rig.fakeFlp.client
        stalledFor(90_000)
        rig.watchdogRunnable().run()
        assertTrue(rig.watchdogRecoveryPending)
        rig.advanceMs(5_000)

        rig.deliverFlpFix(rig.fixAtKm(45.0)) // FLP wakes up on its own

        assertFalse(rig.watchdogRecoveryPending)
        rig.advanceMs(120_000) // well past the 15 s deadline and several ticks
        assertTrue("a healthy pipe is never nuked", rig.recreatedClients.isEmpty())
        assertTrue(rig.recoveryFailures.isEmpty())
        assertSame(originalClient, rig.guardian.client)
    }

    @Test
    fun threeAnsweredButEmptyRecoveries_alsoEndInANewClient() {
        polling(distanceKm = 4.0)
        val originalClient = rig.fakeFlp.client
        rig.fakeFlp.onGetCurrentLocation = { it.setResult(null) } // GMS answers, but never with a location

        repeat(3) {
            stalledFor(90_000)
            rig.handleWatchdogTick()
            rig.idleMainLooper()
        }

        assertEquals(listOf("null location", "null location", "null location"), rig.recoveryFailures)
        assertNotSame(originalClient, rig.guardian.client)
        assertEquals(1, rig.recreatedClients.size)
    }

    @Test
    fun theGuardianOneShotFix_stillFiresTheAlarm_whenTheRegularUpdatesAreDead() {
        val id = polling(distanceKm = 4.0, observeAlert = true)
        rig.fakeFlp.onGetCurrentLocation = { it.setResult(rig.fixAtKm(2.0)) } // recovery finds the user inside the radius
        stalledFor(90_000)

        rig.settling {
            rig.watchdogRunnable().run()
            rig.idleMainLooper()
        }

        assertEquals("ALERTING", rig.status(id))
        assertEquals(1, rig.recoveryFixes.size)
        assertFalse("recovery fix clears the watchdog state", rig.watchdogRecoveryPending)
        assertTrue(System.currentTimeMillis() - rig.lastLocationTimeMs < 10_000)
    }

    @Test
    fun theSameStallOnAGearThatToleratesIt_doesNothing() {
        // 20 minutes of silence at the 10 min gear is inside tier 1 (30 min): the engine waits for the batch window.
        polling(distanceKm = 150.0, gearMs = 600_000L)
        stalledFor(20 * 60_000L)

        rig.watchdogRunnable().run()
        rig.advanceMs(14_999)

        assertTrue(rig.fakeFlp.currentLocationCalls.isEmpty())
        assertTrue(rig.recreatedClients.isEmpty())
    }
}
