package com.omama.stationalarm.service

import android.content.Context
import android.os.Looper
import android.os.PowerManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.omama.stationalarm.data.UserPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowPowerManager
import java.util.concurrent.TimeUnit

/**
 * [ServiceWakeLocks] holds the CPU awake while an alarm rings (alarm lock) and while GPS polling runs
 * (gps lock). Robolectric's PowerManager shadow keeps the tag, the held state and the acquire() timeout
 * (against SystemClock), which is everything needed here.
 *
 * Not verifiable: the wake-lock *level*. ShadowPowerManager.newWakeLock() drops the `flags` argument, so
 * "PARTIAL_WAKE_LOCK" cannot be asserted under Robolectric.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], application = android.app.Application::class)
class ServiceWakeLocksTest {

    private lateinit var locks: ServiceWakeLocks

    @Before
    fun setUp() {
        ShadowPowerManager.reset()
        locks = ServiceWakeLocks(ApplicationProvider.getApplicationContext<Context>())
    }

    private fun latest(): PowerManager.WakeLock = requireNotNull(ShadowPowerManager.getLatestWakeLock())

    private fun advance(amount: Long, unit: TimeUnit) =
        shadowOf(Looper.getMainLooper()).idleFor(amount, unit)

    // ------------------------------------------------------------------ alarm lock

    @Test
    fun acquireAlarm_holdsTheNamedAlarmWakeLock() {
        locks.acquireAlarm()

        val lock = latest()
        assertTrue(lock.isHeld)
        assertEquals("StationAlarm::AlarmWakeLock", shadowOf(lock).tag)
    }

    @Test
    fun alarmLock_isStillHeldWhileTheAlarmRings_andOnlyExplicitReleaseDropsIt() {
        locks.acquireAlarm()
        val lock = latest()

        advance(9, TimeUnit.MINUTES)
        assertTrue("alarm wake lock must survive a long-ringing alarm", lock.isHeld)

        locks.releaseAlarm()
        assertFalse(lock.isHeld)
    }

    @Test
    fun releaseAlarm_twice_isHarmless_andLeavesNothingHeld() {
        locks.acquireAlarm()
        val lock = latest()

        locks.releaseAlarm()
        locks.releaseAlarm() // would throw "WakeLock under-locked" without the isHeld guard

        assertFalse(lock.isHeld)
    }

    @Test
    fun releaseAlarm_beforeAnythingWasAcquired_isANoOp() {
        locks.releaseAlarm()

        assertNull(ShadowPowerManager.getLatestWakeLock())
    }

    @Test
    fun acquireAlarm_afterRelease_reusesTheSameLockInstance_soNothingLeaks() {
        locks.acquireAlarm()
        val first = latest()
        locks.releaseAlarm()

        locks.acquireAlarm()

        assertSame(first, latest())
        assertTrue(first.isHeld)
        assertEquals(2, shadowOf(first).timesHeld)
    }

    @Test
    fun alarmLock_survivesUntilJustBeforeItsTenMinuteCap() {
        locks.acquireAlarm()
        val lock = latest()

        advance(9 * 60 + 59, TimeUnit.SECONDS)

        assertTrue(lock.isHeld)
    }

    @Ignore(
        "BUG: ServiceWakeLocks.kt:33 acquires the alarm lock with a hard 10-minute timeout, but the user can " +
            "configure the alarm to ring for up to UserPreferences.MAX_ALARM_DURATION_SECS = 900 s (15 min; " +
            "Settings slider 60..900 s) and CLAUDE.md requires the lock to be held until the user dismisses. " +
            "Expected: still held at 14 min. Actual: expires at 10 min while sound/vibration continue."
    )
    @Test
    fun alarmLock_isHeldForTheFullMaximumConfigurableRingDuration() {
        locks.acquireAlarm()
        val lock = latest()

        advance(UserPreferences.MAX_ALARM_DURATION_SECS - 60L, TimeUnit.SECONDS)

        assertTrue("alarm still ringing at ${UserPreferences.MAX_ALARM_DURATION_SECS - 60} s", lock.isHeld)
    }

    @Ignore(
        "BUG: ServiceWakeLocks.kt:26-34 acquireAlarm() is reference counted and unguarded. " +
            "LocationService.fireAlert (LocationService.kt:611) calls it once per alerting station while " +
            "handleDismiss/syncWithDatabase release exactly once when the LAST station goes away, so two " +
            "stations that alert together leave one count behind. Expected: nothing held after the final " +
            "release. Actual: lock stays held (until the 10 min timeout)."
    )
    @Test
    fun twoAlarmsAcquired_thenReleasedOnce_leavesNothingHeld() {
        locks.acquireAlarm() // station A alerts
        locks.acquireAlarm() // station B alerts
        val lock = latest()

        locks.releaseAlarm() // last dismissal

        assertFalse("alarm wake lock leaked after the final dismissal", lock.isHeld)
    }

    // ------------------------------------------------------------------ gps lock

    @Test
    fun acquireGps_holdsANonReferenceCountedNamedLock() {
        locks.acquireGps()

        val lock = latest()
        assertTrue(lock.isHeld)
        assertEquals("StationAlarm::GpsWakeLock", shadowOf(lock).tag)
        assertFalse(shadowOf(lock).isReferenceCounted)
    }

    @Test
    fun acquireGps_whileAlreadyHeld_doesNotAcquireAgain() {
        locks.acquireGps()
        val lock = latest()

        locks.acquireGps()
        locks.acquireGps()

        assertEquals(1, shadowOf(lock).timesHeld)
        locks.releaseGps()
        assertFalse("a single release must free a lock acquired three times", lock.isHeld)
    }

    @Test
    fun releaseGps_twice_isHarmless() {
        locks.acquireGps()
        val lock = latest()

        locks.releaseGps()
        locks.releaseGps()

        assertFalse(lock.isHeld)
    }

    @Test
    fun gpsLock_isHeldForFourHours_thenExpires_andCanBeReacquired() {
        locks.acquireGps()
        val lock = latest()

        advance(239, TimeUnit.MINUTES)
        assertTrue(lock.isHeld)

        advance(2, TimeUnit.MINUTES)
        assertFalse("gps lock has a 4 hour safety cap", lock.isHeld)

        locks.acquireGps()
        assertTrue("acquireGps() after expiry must take the lock again", lock.isHeld)
    }

    @Test
    fun alarmAndGpsLocks_areIndependent() {
        locks.acquireGps()
        val gps = latest()
        locks.acquireAlarm()
        val alarm = latest()
        assertNotNull(alarm)

        locks.releaseAlarm()

        assertFalse(alarm.isHeld)
        assertTrue("dismissing the alarm must not drop the GPS lock", gps.isHeld)
    }
}
