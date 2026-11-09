package com.omama.stationalarm.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.omama.stationalarm.MainActivity
import com.omama.stationalarm.data.ActiveStation
import com.omama.stationalarm.ui.screens.AlarmActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Channels and notifications the alarm engine relies on. The channel importance matters: a LOW alarm
 * channel would never wake a locked phone.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], application = android.app.Application::class)
class ServiceNotificationsTest {

    private lateinit var context: Context
    private lateinit var nm: NotificationManager
    private lateinit var notifications: ServiceNotifications

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notifications = ServiceNotifications(context)
        notifications.createChannels()
    }

    /** Station ids that can never be railway codes, so ActiveStation.getStation() uses its own lat/lon. */
    private fun active(
        id: String = "my-place-1",
        name: String = "Home Stop",
        reminder: String? = null,
        sendReminder: Boolean = false,
        withCoordinates: Boolean = true
    ) = ActiveStation(
        stationId = id,
        alertDistanceKm = 3.0,
        notify = true,
        vibrate = true,
        sound = true,
        customReminder = reminder,
        sendReminder = sendReminder,
        lat = if (withCoordinates) 12.97 else 0.0,
        lon = if (withCoordinates) 77.59 else 0.0,
        stationName = name
    )

    private fun posted(id: Int): Notification? = shadowOf(nm).getNotification(id)

    private fun title(n: Notification) = n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
    private fun text(n: Notification) = n.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
    private fun bigText(n: Notification) = n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()

    // ------------------------------------------------------------------ channels

    @Test
    fun createChannels_createsTheMonitoringChannelAsLowImportance() {
        val channel = nm.getNotificationChannel("location_channel")

        assertNotNull(channel)
        assertEquals(NotificationManager.IMPORTANCE_LOW, channel.importance)
        assertEquals(ServiceNotifications.CHANNEL_ID, channel.id)
        assertEquals(false, channel.canBypassDnd())
    }

    @Test
    fun createChannels_createsTheAlarmChannelAsHighImportanceThatBypassesDnd() {
        val channel = nm.getNotificationChannel("alarm_channel")

        assertNotNull(channel)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, channel.importance)
        assertEquals(ServiceNotifications.ALARM_CHANNEL_ID, channel.id)
        assertTrue("Do Not Disturb must not swallow the full-screen alarm", channel.canBypassDnd())
    }

    @Test
    fun createChannels_isIdempotent_andCreatesExactlyTwoChannels() {
        notifications.createChannels()
        notifications.createChannels()

        assertEquals(setOf("location_channel", "alarm_channel"), nm.notificationChannels.map { it.id }.toSet())
        assertEquals(2, nm.notificationChannels.size)
    }

    @Test
    fun channels_haveUserVisibleNamesAndDescriptions() {
        val service = nm.getNotificationChannel("location_channel")
        val alerts = nm.getNotificationChannel("alarm_channel")

        assertEquals("Station Alarm Service", service.name.toString())
        assertEquals("Shows tracking status", service.description)
        assertEquals("Station Alarm Alerts", alerts.name.toString())
        assertEquals("High priority alarm alerts", alerts.description)
    }

    // ------------------------------------------------------------------ foreground notification

    @Test
    fun buildForeground_isOngoingLowPriorityNotificationOnTheMonitoringChannel() {
        val n = notifications.buildForeground("Nearest station: X - 4.2 km")

        assertEquals("location_channel", n.channelId)
        assertEquals("Station Alarm", title(n))
        assertEquals("Nearest station: X - 4.2 km", text(n))
        assertTrue(n.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertTrue(n.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
        @Suppress("DEPRECATION")
        assertEquals(Notification.PRIORITY_LOW, n.priority)
    }

    @Test
    fun buildForeground_tappingOpensTheMainActivity() {
        val n = notifications.buildForeground("x")

        val pi = shadowOf(n.contentIntent)
        assertTrue(pi.isActivityIntent)
        assertEquals(MainActivity::class.java.name, pi.savedIntent.component?.className)
    }

    @Test
    fun buildForeground_swipeAway_broadcastsRestoreActionInsideThisPackage() {
        val n = notifications.buildForeground("x")

        val delete = shadowOf(n.deleteIntent)
        assertTrue(delete.isBroadcastIntent)
        assertEquals(LocationService.ACTION_RESTORE_NOTIFICATION, delete.savedIntent.action)
        assertEquals(context.packageName, delete.savedIntent.`package`)
        assertTrue("PendingIntent must be immutable", delete.flags and PendingIntent.FLAG_IMMUTABLE != 0)
    }

    @Test
    fun buildForeground_hasAStopAllActionThatStartsTheServiceWithStopAllAction() {
        val n = notifications.buildForeground("x")

        assertEquals(1, n.actions.size)
        assertEquals("Stop all", n.actions[0].title.toString())
        val pi = shadowOf(n.actions[0].actionIntent)
        assertTrue(pi.isServiceIntent)
        assertEquals(LocationService.ACTION_STOP_ALL_MONITORING, pi.savedIntent.action)
        assertEquals(LocationService::class.java.name, pi.savedIntent.component?.className)
    }

    @Test
    fun notifyForeground_postsUnderTheForegroundId_andCancelForegroundRemovesIt() {
        val n = notifications.buildForeground("x")

        notifications.notifyForeground(n)
        assertNotNull(posted(ServiceNotifications.FOREGROUND_NOTIFICATION_ID))

        notifications.cancelForeground()
        assertNull(posted(ServiceNotifications.FOREGROUND_NOTIFICATION_ID))
    }

    // ------------------------------------------------------------------ alarm notification

    @Test
    fun showAlert_postsAHighPriorityOngoingAlarmCategoryNotificationOnTheAlarmChannel() {
        val station = active()

        notifications.showAlert(station)

        val n = requireNotNull(posted(station.stationId.hashCode()))
        assertEquals("alarm_channel", n.channelId)
        assertEquals(Notification.CATEGORY_ALARM, n.category)
        @Suppress("DEPRECATION")
        assertEquals(Notification.PRIORITY_HIGH, n.priority)
        assertTrue("alarm must not be swipe-dismissable", n.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertEquals("must not auto-cancel on tap", 0, n.flags and Notification.FLAG_AUTO_CANCEL)
        assertEquals("Station Reached: Home Stop", title(n))
    }

    @Test
    fun showAlert_fullScreenIntentOpensAlarmActivityWithStationIdAndNameOnFirstFrame() {
        val station = active()

        notifications.showAlert(station)

        val n = requireNotNull(posted(station.stationId.hashCode()))
        val pi = shadowOf(requireNotNull(n.fullScreenIntent))
        assertTrue(pi.isActivityIntent)
        val intent = pi.savedIntent
        assertEquals(AlarmActivity::class.java.name, intent.component?.className)
        assertEquals("my-place-1", intent.getStringExtra("stationId"))
        assertEquals("Home Stop", intent.getStringExtra(ServiceNotifications.EXTRA_STATION_NAME))
        assertEquals(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP,
            intent.flags and (Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        )
        assertEquals("one PendingIntent per station", station.stationId.hashCode(), pi.requestCode)
    }

    @Test
    fun showAlert_dismissActionStartsTheServiceWithDismissActionAndStationId() {
        val station = active()

        notifications.showAlert(station)

        val n = requireNotNull(posted(station.stationId.hashCode()))
        assertEquals(1, n.actions.size)
        assertEquals("Dismiss", n.actions[0].title.toString())
        val pi = shadowOf(n.actions[0].actionIntent)
        assertTrue(pi.isServiceIntent)
        assertEquals(LocationService.ACTION_DISMISS_ALARM, pi.savedIntent.action)
        assertEquals("my-place-1", pi.savedIntent.getStringExtra("stationId"))
        assertEquals(station.stationId.hashCode(), pi.requestCode)
    }

    @Test
    fun showAlert_usesDefaultBody_whenNoReminder() {
        notifications.showAlert(active())

        assertEquals("You have arrived at your destination.", bigText(requireNotNull(posted("my-place-1".hashCode()))))
    }

    @Test
    fun showAlert_includesTheCustomReminder_onlyWhenSendReminderIsOn() {
        notifications.showAlert(active(id = "with-reminder", reminder = "Buy tickets", sendReminder = true))
        notifications.showAlert(active(id = "muted-reminder", reminder = "Buy tickets", sendReminder = false))

        assertEquals("Reminder: Buy tickets", bigText(requireNotNull(posted("with-reminder".hashCode()))))
        assertEquals(
            "You have arrived at your destination.",
            bigText(requireNotNull(posted("muted-reminder".hashCode())))
        )
    }

    @Test
    fun showAlert_blankReminder_fallsBackToDefaultBody() {
        notifications.showAlert(active(id = "blank-reminder", reminder = "   ", sendReminder = true))

        assertEquals(
            "You have arrived at your destination.",
            bigText(requireNotNull(posted("blank-reminder".hashCode())))
        )
    }

    @Test
    fun showAlert_unknownStationWithoutCoordinates_showsTheRawStationId() {
        notifications.showAlert(active(id = "zz-unknown", withCoordinates = false))

        val n = requireNotNull(posted("zz-unknown".hashCode()))
        assertEquals("Station Reached: zz-unknown", title(n))
        assertEquals(
            "zz-unknown",
            shadowOf(n.fullScreenIntent).savedIntent.getStringExtra(ServiceNotifications.EXTRA_STATION_NAME)
        )
    }

    @Test
    fun showAlert_twoStations_getTwoIndependentNotifications_andCancelAlertRemovesOnlyOne() {
        notifications.showAlert(active(id = "stop-a", name = "Stop A"))
        notifications.showAlert(active(id = "stop-b", name = "Stop B"))
        assertEquals(2, shadowOf(nm).size())

        notifications.cancelAlert("stop-a")

        assertNull(posted("stop-a".hashCode()))
        assertNotNull(posted("stop-b".hashCode()))
    }

    // ------------------------------------------------------------------ watchdog + audio failure

    @Test
    fun showWatchdog_postsAnOngoingLowPriorityNoticeOnTheMonitoringChannel_andCancelWatchdogRemovesIt() {
        notifications.showWatchdog("Searching for GPS", "Still tracking")

        val n = requireNotNull(posted(ServiceNotifications.WATCHDOG_NOTIFICATION_ID))
        assertEquals("location_channel", n.channelId)
        assertEquals("Searching for GPS", title(n))
        assertEquals("Still tracking", text(n))
        assertTrue(n.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertEquals(MainActivity::class.java.name, shadowOf(n.contentIntent).savedIntent.component?.className)

        notifications.cancelWatchdog()
        assertNull(posted(ServiceNotifications.WATCHDOG_NOTIFICATION_ID))
    }

    @Test
    fun showWatchdog_defaults_useTheStringResources() {
        notifications.showWatchdog()

        val n = requireNotNull(posted(ServiceNotifications.WATCHDOG_NOTIFICATION_ID))
        assertEquals("Searching for GPS…", title(n))
        assertEquals("Still tracking — will resume when a fix returns.", text(n))
    }

    @Test
    fun showAudioFailureNotification_isAnAlarmCategoryNoticeOnTheAlarmChannel_andIsCancellable() {
        notifications.showAudioFailureNotification("Pune")

        val n = requireNotNull(posted(ServiceNotifications.AUDIO_FAILURE_NOTIFICATION_ID))
        assertEquals("alarm_channel", n.channelId)
        assertEquals(Notification.CATEGORY_ALARM, n.category)
        assertEquals("Alarm fired silently", title(n))
        assertEquals("Audio system error at Pune — vibration only.", text(n))

        notifications.cancelAudioFailureNotification()
        assertNull(posted(ServiceNotifications.AUDIO_FAILURE_NOTIFICATION_ID))
    }

    @Test
    fun fixedNotificationIds_doNotCollideWithEachOther() {
        val ids = listOf(
            ServiceNotifications.FOREGROUND_NOTIFICATION_ID,
            ServiceNotifications.WATCHDOG_NOTIFICATION_ID,
            ServiceNotifications.AUDIO_FAILURE_NOTIFICATION_ID
        )
        assertEquals(ids.size, ids.toSet().size)
    }
}
