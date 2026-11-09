package com.omama.stationalarm.service

import android.app.Application
import android.content.Intent
import android.view.KeyEvent
import android.view.WindowManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.omama.stationalarm.ui.screens.AlarmActivity
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
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

/**
 * The non-Compose part of AlarmActivity: how the user ends the alarm from the lock screen. The activity only
 * asks LocationService to dismiss (single dismiss path) and finishes itself.
 *
 * The activity is created but not made visible, so Compose never composes. Not verifiable under Robolectric:
 * setShowWhenLocked()/setTurnScreenOn() (no observable state) and the rendered station name.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], application = android.app.Application::class)
class AlarmActivityDismissTest {

    private lateinit var app: Application
    private var controller: ActivityController<AlarmActivity>? = null

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        shadowOf(app).clearStartedServices()
    }

    @After
    fun tearDown() {
        controller?.let { runCatching { it.destroy() } }
    }

    private fun launch(stationId: String? = "my-stop"): AlarmActivity {
        val intent = Intent(app, AlarmActivity::class.java).apply {
            stationId?.let { putExtra("stationId", it) }
            putExtra(ServiceNotifications.EXTRA_STATION_NAME, "Home Stop")
        }
        controller = Robolectric.buildActivity(AlarmActivity::class.java, intent).create()
        return controller!!.get()
    }

    private fun press(activity: AlarmActivity, keyCode: Int): Boolean =
        activity.onKeyDown(keyCode, KeyEvent(KeyEvent.ACTION_DOWN, keyCode))

    @Test
    fun theScreenStaysOnWhileTheAlarmIsShown() {
        val activity = launch()

        assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0)
    }

    @Test
    fun volumeDown_dismissesThroughTheServiceAndClosesTheScreen() {
        val activity = launch("my-stop")

        val consumed = press(activity, KeyEvent.KEYCODE_VOLUME_DOWN)

        assertTrue("the key must be consumed so the volume UI does not appear", consumed)
        val dismiss = shadowOf(app).nextStartedService
        assertNotNull(dismiss)
        assertEquals(LocationService.ACTION_DISMISS_ALARM, dismiss.action)
        assertEquals(LocationService::class.java.name, dismiss.component?.className)
        assertEquals("my-stop", dismiss.getStringExtra("stationId"))
        assertTrue(activity.isFinishing)
    }

    @Test
    fun volumeUp_alsoDismisses() {
        val activity = launch("stop-up")

        assertTrue(press(activity, KeyEvent.KEYCODE_VOLUME_UP))

        assertEquals(LocationService.ACTION_DISMISS_ALARM, shadowOf(app).nextStartedService.action)
        assertTrue(activity.isFinishing)
    }

    @Test
    fun volumeMute_alsoDismisses() {
        val activity = launch("stop-mute")

        assertTrue(press(activity, KeyEvent.KEYCODE_VOLUME_MUTE))

        assertEquals(LocationService.ACTION_DISMISS_ALARM, shadowOf(app).nextStartedService.action)
        assertTrue(activity.isFinishing)
    }

    @Test
    fun pressingTheButtonRepeatedly_sendsOnlyOneDismiss() {
        val activity = launch("my-stop")

        press(activity, KeyEvent.KEYCODE_VOLUME_DOWN)
        press(activity, KeyEvent.KEYCODE_VOLUME_DOWN)
        press(activity, KeyEvent.KEYCODE_VOLUME_UP)

        assertNotNull(shadowOf(app).nextStartedService)
        assertNull("dismiss must be sent exactly once", shadowOf(app).nextStartedService)
    }

    @Test
    fun aKeyThatIsNotAVolumeKey_doesNotDismiss() {
        val activity = launch("my-stop")

        press(activity, KeyEvent.KEYCODE_A)

        assertNull(shadowOf(app).nextStartedService)
        assertFalse(activity.isFinishing)
    }

    @Ignore(
        "BUG: AlarmActivity.kt:105-112 + strings.xml alarm_screen_press_button_hint. The alarm screen tells the " +
            "user 'Press any physical button to dismiss', but onKeyDown only handles VOLUME_UP / VOLUME_DOWN / " +
            "VOLUME_MUTE. Expected: other hardware buttons (headset hook, media keys, camera) dismiss too, as the " +
            "text promises. Actual: they are ignored. Low severity (UX mismatch)."
    )
    @Test
    fun anyOtherHardwareButtonDismissesToo_asTheOnScreenHintPromises() {
        val activity = launch("my-stop")

        press(activity, KeyEvent.KEYCODE_HEADSETHOOK)

        assertNotNull(shadowOf(app).nextStartedService)
        assertTrue(activity.isFinishing)
    }

    @Ignore(
        "BUG: AlarmActivity.kt:91-94 sets `dismissed = true` BEFORE checking stationId. Opened without a " +
            "stationId extra (never produced by ServiceNotifications today, so latent) the first press " +
            "swallows the dismissal: nothing is sent, the screen does not close, and every later press is " +
            "ignored by the `dismissed` guard, so the user is stuck on the alarm screen. " +
            "Expected: the screen still closes. Actual: it stays open."
    )
    @Test
    fun withoutAStationId_theScreenStillCloses() {
        val activity = launch(stationId = null)

        press(activity, KeyEvent.KEYCODE_VOLUME_DOWN)

        assertTrue("user is trapped on the alarm screen", activity.isFinishing)
    }
}
