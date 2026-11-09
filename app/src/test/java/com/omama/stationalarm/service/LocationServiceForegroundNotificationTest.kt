package com.omama.stationalarm.service

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.omama.stationalarm.data.UserPreferences
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * The text of the ongoing foreground notification, built by LocationService.updateForegroundNotification()
 * from the live distances: "Nearest station: <name> - <km> km", a station count while no distance is known
 * yet, or the "alarm ringing" count when only alerting stations remain.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class LocationServiceForegroundNotificationTest {

    private lateinit var rig: LocationServiceRig
    private var savedLocale: Locale? = null

    @Before
    fun setUp() {
        savedLocale = Locale.getDefault()
        Locale.setDefault(Locale.US) // "%.1f".format(...) in the service is locale sensitive
        rig = LocationServiceRig()
    }

    @After
    fun tearDown() {
        rig.close()
        savedLocale?.let { Locale.setDefault(it) }
    }

    private fun text(n: Notification) = n.extras.getCharSequence(Notification.EXTRA_TEXT).toString()

    /** Notification posted through NotificationManager.notify (monitoring stations present, type unchanged). */
    private fun postedForegroundText(): String {
        val nm = rig.app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val n = shadowOf(nm).getNotification(ServiceNotifications.FOREGROUND_NOTIFICATION_ID)
        return text(n!!)
    }

    private fun add(id: String, distanceKm: Double?) {
        rig.monitoringStations[id] = rig.station(id, distanceKm = distanceKm)
    }

    @Test
    fun showsTheNearestStationWithOneDecimalOfKilometres() {
        add("zz_fg_far", 12.345)
        add("zz_fg_near", 4.04)

        rig.updateForegroundNotification()

        assertEquals("Nearest station: Test zz_fg_near — 4.0 km", postedForegroundText())
    }

    @Test
    fun aStationWithAKnownDistanceBeatsOnesWithoutOne() {
        add("zz_fg_unknown", null)
        add("zz_fg_known", 250.0)

        rig.updateForegroundNotification()

        assertEquals("Nearest station: Test zz_fg_known — 250.0 km", postedForegroundText())
    }

    @Test
    fun withoutAnyMeasuredDistance_showsTheMonitoredCount() {
        add("zz_fg_a", null)
        add("zz_fg_b", null)

        rig.updateForegroundNotification()

        assertEquals("Monitoring 2 station(s)...", postedForegroundText())
    }

    @Test
    fun whenOnlyAlarmsRemain_showsTheRingingCount() {
        rig.alertingStationIds.add("zz_fg_ringing")

        rig.updateForegroundNotification()

        // monitoring set empty -> the foreground type flips, so the notification goes through startForeground()
        val n = shadowOf(rig.service).lastForegroundNotification
        assertTrue(n != null)
        assertEquals("Alarm ringing — 1 station(s) reached", text(n!!))
    }

    @Ignore("BUG (UX): LocationService.kt:835-836 formats the nearest distance as \"%.1f\" kilometres and R.string.notif_nearest_format hard-codes \" km\", so the ongoing notification ignores the user's miles preference that every Compose screen honours via formatDistance()/LocalDistanceUnit")
    @Test
    fun theForegroundNotificationShouldHonourTheMilesPreference() {
        // DistanceUnit says "only the display layer converts" and every Compose screen uses formatDistance() with
        // the user's unit. The ongoing notification does not: it hard-codes "%.1f" + the literal " km" from
        // R.string.notif_nearest_format, so a user who chose miles still sees kilometres here.
        val context = rig.app
        context.filesDir.resolve("datastore").deleteRecursively()
        UserPreferences.initialize(context)
        runBlocking { UserPreferences.setDistanceUnit(UserPreferences.UNIT_MILES) }
        try {
            add("zz_fg_miles", 16.09344) // exactly 10 miles

            rig.updateForegroundNotification()

            val shown = postedForegroundText()
            assertTrue("expected miles but got: $shown", shown.contains("10.0") && shown.contains("mi") && !shown.contains("km"))
        } finally {
            runBlocking { UserPreferences.setDistanceUnit(UserPreferences.UNIT_KM) }
        }
    }
}
