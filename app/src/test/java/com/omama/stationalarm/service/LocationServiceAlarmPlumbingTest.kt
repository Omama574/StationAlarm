package com.omama.stationalarm.service

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Looper
import android.os.PowerManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.omama.stationalarm.data.ActiveStation
import com.omama.stationalarm.geofence.FakeGeofencing
import com.omama.stationalarm.geofence.RecordingGeofencingClient
import com.omama.stationalarm.geofence.RepositoryHarness
import com.omama.stationalarm.geofence.ShadowLocationServicesGeofencing
import com.omama.stationalarm.geofence.TestReflection
import kotlinx.coroutines.Job
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
import org.robolectric.shadows.ShadowMediaPlayer
import org.robolectric.shadows.ShadowPowerManager
import org.robolectric.shadows.util.DataSource
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext

/**
 * How LocationService (behaviour-locked, only read here) wires alarm start and dismissal to the wake lock, the
 * audio controller, the notification and the repository.
 *
 * The service is constructed but `onCreate()` is not run (it would start the DB-sync coroutines against
 * Play services). The three collaborators it normally builds are injected, the preference caches that
 * `fireAlert` reads are pre-filled, and `fireAlert` - private, driven by syncWithDatabase in production -
 * is invoked by reflection. Dismissal goes through the public `onStartCommand(ACTION_DISMISS_ALARM)`.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], application = android.app.Application::class, shadows = [ShadowLocationServicesGeofencing::class])
class LocationServiceAlarmPlumbingTest {

    private lateinit var app: Application
    private lateinit var repo: RepositoryHarness
    private lateinit var gms: RecordingGeofencingClient
    private lateinit var service: LocationService
    private lateinit var audio: AlarmAudioController
    private lateinit var nm: NotificationManager
    private val players = CopyOnWriteArrayList<MediaPlayer>()

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        gms = FakeGeofencing.reset()
        repo = RepositoryHarness(app)
        ShadowPowerManager.reset()
        nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        service = LocationService()
        ContextWrapper::class.java.getDeclaredMethod("attachBaseContext", Context::class.java)
            .apply { isAccessible = true }
            .invoke(service, app)

        audio = AlarmAudioController(service)
        val notifications = ServiceNotifications(service).also { it.createChannels() }
        TestReflection.set(service, "audio", audio)
        TestReflection.set(service, "wakeLocks", ServiceWakeLocks(service))
        TestReflection.set(service, "notifications", notifications)

        // Values LocationService would have read from DataStore (so fireAlert never has to suspend).
        TestReflection.set(service, "cachedAlarmSoundUri", "")
        TestReflection.set(service, "cachedRingSpeakerWithHeadphones", true)
        TestReflection.set(service, "cachedEscalatingAlarm", false)
        TestReflection.set(service, "cachedRampSecs", 30)
        TestReflection.set(service, "cachedAlarmDurationSecs", 60)

        val defaultAlarm = requireNotNull(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM))
        ShadowMediaPlayer.addMediaInfo(
            DataSource.toDataSource(app, defaultAlarm), ShadowMediaPlayer.MediaInfo(60_000, 0)
        )
        ShadowMediaPlayer.setCreateListener { player, _ -> players += player }
        shadowOf(app.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager)
            .setOutputDevices(emptyList<AudioDeviceInfo>())
    }

    @After
    fun tearDown() {
        audio.stopAll()
        (TestReflection.get<Job>(service, "serviceJob")).cancel()
        ShadowMediaPlayer.setCreateListener(null)
        repo.close()
    }

    // ------------------------------------------------------------------ helpers

    private fun alerting(id: String, sound: Boolean = true, vibrate: Boolean = true): ActiveStation =
        repo.insert(repo.station(id, status = "ALERTING", name = "Stop $id", sound = sound, vibrate = vibrate))

    /** What syncWithDatabase does for a newly ALERTING row: remember it, then fireAlert(). */
    private fun fire(station: ActiveStation) {
        TestReflection.get<MutableSet<String>>(service, "alertingStationIds").add(station.stationId)
        val method = LocationService::class.java
            .getDeclaredMethod("fireAlert", ActiveStation::class.java, Continuation::class.java)
            .apply { isAccessible = true }
        val completion = object : Continuation<Unit> {
            override val context = EmptyCoroutineContext
            override fun resumeWith(result: Result<Unit>) {
                result.getOrThrow()
            }
        }
        val outcome = try {
            method.invoke(service, station, completion)
        } catch (e: java.lang.reflect.InvocationTargetException) {
            throw e.targetException
        }
        // fireAlert only suspends when a preference cache is empty; all of them are filled in setUp().
        assertEquals(Unit, outcome)
        idle()
    }

    private fun dismiss(id: String): Int =
        service.onStartCommand(
            Intent(LocationService.ACTION_DISMISS_ALARM).putExtra("stationId", id), 0, 1
        ).also { idle() }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun advanceSeconds(s: Long) = shadowOf(Looper.getMainLooper()).idleFor(s, TimeUnit.SECONDS)

    // Only the alarm lock is created in these tests (the GPS lock belongs to onCreate/polling, which is not run).
    private fun alarmLock(): PowerManager.WakeLock? =
        ShadowPowerManager.getLatestWakeLock()?.takeIf { shadowOf(it).tag == "StationAlarm::AlarmWakeLock" }

    private fun alivePlayers() = players.filter { shadowOf(it).state != ShadowMediaPlayer.State.END }

    private fun postedAlert(id: String) = shadowOf(nm).getNotification(id.hashCode())

    // ------------------------------------------------------------------ firing

    @Test
    fun fireAlert_holdsTheAlarmWakeLock_playsSound_vibrates_andShowsTheFullScreenAlarm() {
        val station = alerting("my-stop")

        fire(station)

        assertTrue("alarm wake lock must be held", alarmLock()!!.isHeld)
        assertTrue(audio.isAlarmRinging)
        assertEquals(1, alivePlayers().size)
        assertEquals(ShadowMediaPlayer.State.STARTED, shadowOf(alivePlayers().single()).state)
        assertTrue(audio.isVibrating)
        val notification = requireNotNull(postedAlert("my-stop"))
        assertEquals("alarm_channel", notification.channelId)
        assertNotNull(notification.fullScreenIntent)
        assertEquals("Station Reached: Stop my-stop", notification.extras.getCharSequence("android.title").toString())
    }

    @Test
    fun fireAlert_forAVibrateOnlyStation_startsNoPlayer_butStillHoldsTheWakeLock() {
        val station = alerting("quiet-stop", sound = false, vibrate = true)

        fire(station)

        assertEquals(0, players.size)
        assertFalse(audio.isAlarmRinging)
        assertTrue(audio.isVibrating)
        assertTrue(alarmLock()!!.isHeld)
    }

    @Test
    fun alarmWakeLock_staysHeldWhileTheAlarmRings_untilTheUserDismisses() {
        val station = alerting("my-stop")
        fire(station)

        advanceSeconds(45) // still inside the configured 60 s ring time

        assertTrue("alarm is still ringing, lock must still be held", alarmLock()!!.isHeld)
        assertTrue(audio.isAlarmRinging)
    }

    // ------------------------------------------------------------------ explicit dismissal

    @Test
    fun dismiss_releasesTheWakeLock_stopsAudioAndVibration_cancelsTheNotification_andPausesTheStation() {
        val station = alerting("my-stop")
        fire(station)

        val result = dismiss("my-stop")
        repo.awaitRepositoryIdle()

        assertEquals(android.app.Service.START_NOT_STICKY, result)
        assertFalse("wake lock must be released on dismiss", alarmLock()!!.isHeld)
        assertFalse(audio.isAlarmRinging)
        assertFalse(audio.isVibrating)
        assertEquals(0, alivePlayers().size)
        assertNull(postedAlert("my-stop"))
        assertEquals("PAUSED", repo.status("my-stop"))
        assertEquals("geofences removed so the alarm cannot refire", 1, gms.removeByIdCalls.size)
    }

    @Test
    fun dismissingTwice_isHarmless() {
        fire(alerting("my-stop"))

        dismiss("my-stop")
        dismiss("my-stop")
        repo.awaitRepositoryIdle()

        assertFalse(alarmLock()!!.isHeld)
        assertEquals("PAUSED", repo.status("my-stop"))
    }

    @Test
    fun dismissWithoutAStationIdExtra_changesNothing() {
        fire(alerting("my-stop"))

        val result = service.onStartCommand(Intent(LocationService.ACTION_DISMISS_ALARM), 0, 1)

        assertEquals(android.app.Service.START_NOT_STICKY, result)
        assertTrue(alarmLock()!!.isHeld)
        assertTrue(audio.isAlarmRinging)
    }

    @Test
    fun withTwoAlarms_dismissingTheFirstKeepsTheOtherRingingAndTheLockHeld() {
        fire(alerting("stop-a"))
        fire(alerting("stop-b"))

        dismiss("stop-a")
        repo.awaitRepositoryIdle()

        assertTrue("stop-b is still ringing", audio.isAlarmRinging)
        assertTrue(alarmLock()!!.isHeld)
        assertNull(postedAlert("stop-a"))
        assertNotNull(postedAlert("stop-b"))
    }

    @Ignore(
        "BUG: LocationService.kt:611 fireAlert() calls wakeLocks.acquireAlarm() once PER ALERTING STATION, " +
            "while handleDismiss (LocationService.kt:751-755) releases once, when the LAST station goes away. " +
            "ServiceWakeLocks.acquireAlarm() uses a reference-counted WakeLock, so two simultaneous alarms leave " +
            "one count behind and the CPU lock stays held after every alarm is dismissed (until its 10 min " +
            "timeout). Expected: no alarm wake lock held once all alarms are dismissed. Actual: still held."
    )
    @Test
    fun withTwoAlarms_dismissingBoth_leavesNoWakeLockHeld() {
        fire(alerting("stop-a"))
        fire(alerting("stop-b"))

        dismiss("stop-a")
        dismiss("stop-b")
        repo.awaitRepositoryIdle()

        assertFalse("alarm wake lock leaked after the last dismissal", alarmLock()!!.isHeld)
    }

    // ------------------------------------------------------------------ automatic timeout

    @Test
    fun afterTheConfiguredRingTime_theAlarmStopsOnItsOwn_andReleasesEverything() {
        val station = alerting("my-stop")
        fire(station)

        advanceSeconds(61)
        repo.awaitRepositoryIdle()

        assertFalse(audio.isAlarmRinging)
        assertFalse(audio.isVibrating)
        assertFalse("wake lock must not outlive the alarm", alarmLock()!!.isHeld)
        assertNull(postedAlert("my-stop"))
        assertEquals("PAUSED", repo.status("my-stop"))
    }
}
