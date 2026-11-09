package com.omama.stationalarm.receiver

import com.omama.stationalarm.receiver.AlarmManifest.android
import com.omama.stationalarm.service.LocationService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the manifest declarations the alarm-delivery chain depends on. A missing permission or a wrong
 * `exported` flag does not fail compilation but silently breaks alarms on a real device.
 */
class ManifestDeclarationsTest {

    private fun assertDeclared(permission: String) {
        assertTrue(
            "android.permission.$permission must be declared (declared: ${AlarmManifest.requestedPermissions})",
            "android.permission.$permission" in AlarmManifest.requestedPermissions
        )
    }

    // ------------------------------------------------------------------ permissions

    @Test
    fun locationPermissions_includingBackgroundLocation_areDeclared() {
        assertDeclared("ACCESS_FINE_LOCATION")
        assertDeclared("ACCESS_COARSE_LOCATION")
        // Geofences have to fire while the app is not in the foreground.
        assertDeclared("ACCESS_BACKGROUND_LOCATION")
    }

    @Test
    fun foregroundServicePermissions_matchTheServiceTypes() {
        assertDeclared("FOREGROUND_SERVICE")
        assertDeclared("FOREGROUND_SERVICE_LOCATION")
        // LocationService switches to the mediaPlayback type while only an alarm is ringing.
        assertDeclared("FOREGROUND_SERVICE_MEDIA_PLAYBACK")
    }

    @Test
    fun alarmDeliveryPermissions_areDeclared() {
        assertDeclared("WAKE_LOCK")
        assertDeclared("POST_NOTIFICATIONS")
        assertDeclared("USE_FULL_SCREEN_INTENT")
        assertDeclared("RECEIVE_BOOT_COMPLETED")
        assertDeclared("VIBRATE")
    }

    @Test
    fun requestIgnoreBatteryOptimizations_isDeliberatelyNotDeclared() {
        // CLAUDE.md says the app "requires REQUEST_IGNORE_BATTERY_OPTIMIZATIONS", but PLAY_STORE_READINESS.md
        // (line ~316) records the opposite decision: the app deep-links to the App Info page instead, because
        // Play restricts this permission. This test pins the *actual* decision so nobody adds it by accident.
        assertFalse(
            "android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" in AlarmManifest.requestedPermissions
        )
    }

    @Test
    fun advertisingIdPermissions_areStrippedFromTheMergedManifest() {
        assertEquals(
            setOf(
                "com.google.android.gms.permission.AD_ID",
                "android.permission.ACCESS_ADSERVICES_AD_ID",
                "android.permission.ACCESS_ADSERVICES_ATTRIBUTION"
            ),
            AlarmManifest.removedPermissions
        )
    }

    // ------------------------------------------------------------------ service

    @Test
    fun locationService_isForegroundServiceOfTypeLocationAndMediaPlayback_andNotExported() {
        val service = AlarmManifest.component("service", ".service.LocationService")
        assertEquals("false", service.android("exported"))
        val types = service.android("foregroundServiceType")!!.split("|").toSet()
        assertEquals(setOf("location", "mediaPlayback"), types)
    }

    // ------------------------------------------------------------------ receivers

    @Test
    fun geofenceReceiver_isNotExported_andListensForTheGeofenceAction() {
        val r = AlarmManifest.component("receiver", ".receiver.GeofenceBroadcastReceiver")
        // The GMS PendingIntent is explicit (component set) so it does not need to be exported.
        assertEquals("false", r.android("exported"))
        assertEquals(setOf("com.omama.stationalarm.ACTION_GEOFENCE"), AlarmManifest.intentFilterActions(r))
    }

    @Test
    fun notificationRestoreReceiver_isNotExported_andMatchesTheServiceActionConstant() {
        val r = AlarmManifest.component("receiver", ".receiver.NotificationRestoreReceiver")
        assertEquals("false", r.android("exported"))
        assertEquals(
            setOf(LocationService.ACTION_RESTORE_NOTIFICATION),
            AlarmManifest.intentFilterActions(r)
        )
    }

    @Test
    fun bootReceiver_isExportedAndEnabled_forBootAndQuickBoot() {
        val r = AlarmManifest.component("receiver", ".receiver.BootReceiver")
        // BOOT_COMPLETED is a protected system broadcast; the receiver has to be exported to get it.
        assertEquals("true", r.android("exported"))
        assertEquals("true", r.android("enabled"))
        assertEquals(
            setOf("android.intent.action.BOOT_COMPLETED", "android.intent.action.QUICKBOOT_POWERON"),
            AlarmManifest.intentFilterActions(r)
        )
    }

    // ------------------------------------------------------------------ activities

    @Test
    fun alarmActivity_isInternal_singleTop() {
        val a = AlarmManifest.component("activity", ".ui.screens.AlarmActivity")
        // Only our own full-screen PendingIntent may start it.
        assertEquals("false", a.android("exported"))
        // singleTop: a second alarm re-delivers to the visible screen instead of stacking a copy.
        assertEquals("singleTop", a.android("launchMode"))
    }

    @Test
    fun mainActivity_isTheLauncher() {
        val main = AlarmManifest.component("activity", ".MainActivity")
        assertEquals("true", main.android("exported"))
        assertEquals(setOf("android.intent.action.MAIN"), AlarmManifest.intentFilterActions(main))
    }

    @Test
    fun onlyBootReceiverAndLauncherActivity_areExported() {
        // Anything else exported would let other apps start/stop alarms or fake a geofence event.
        assertEquals(
            setOf(".receiver.BootReceiver", ".MainActivity"),
            AlarmManifest.exportedComponents
        )
    }

    // ------------------------------------------------------------------ full-screen intent

    @Test
    fun fullScreenIntentPermission_isDeclared() {
        // Reported rather than asserted as a bug: on Android 14+ USE_FULL_SCREEN_INTENT can be revoked for
        // apps that Play does not classify as alarm/calling apps, and the only API to find out is
        // NotificationManager.canUseFullScreenIntent(). Nothing in src/main calls it or links to
        // Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT (verified with a repository-wide search),
        // so a revoked permission degrades silently to a heads-up notification and AlarmActivity is not
        // shown over the lock screen. This test documents the manifest side only.
        assertDeclared("USE_FULL_SCREEN_INTENT")
    }
}
