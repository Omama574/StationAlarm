package com.omama.stationalarm

import android.app.Application
import android.os.Build
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.google.firebase.FirebaseApp
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import com.google.firebase.remoteconfig.ktx.remoteConfigSettings
import com.omama.stationalarm.network.GeocodingClient
import com.omama.stationalarm.repository.StationRepository
import com.omama.stationalarm.util.Analytics
import com.omama.stationalarm.util.BatteryOptimizationHelper
import com.omama.stationalarm.util.Logger
import com.omama.stationalarm.data.UserPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.osmdroid.config.Configuration
import java.io.File

class StationAlarmApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        StationRepository.initialize(this)
        Logger.initialize(this)
        com.omama.stationalarm.util.GpsLogger.initialize(this)
        UserPreferences.initialize(this)

        // Apply the per-app locale BEFORE any Activity is created. Reading from
        // DataStore is normally async, but app cold-start happens before the
        // first Activity attaches its base context, so a brief runBlocking on
        // disk I/O here is the standard pattern. Typical cost ~5–20 ms.
        applyPersistedLocale()

        // osmdroid must be configured before any MapView is created.
        // OSM tile servers require a proper User-Agent string — without it your app
        // can get IP-banned from the tile CDN.
        Configuration.getInstance().apply {
            userAgentValue = "StationAlarm/2.0 (android)"
            osmdroidBasePath = File(cacheDir, "osmdroid")
            osmdroidTileCache = File(osmdroidBasePath, "tile")
            // Most users set an alarm once and never revisit the map. A 50 MB
            // permanent cache for a tab that's rarely opened is wasteful;
            // 5 MB still covers the most-recent session and gets evicted to
            // 4 MB on trim so we don't grow back uncontrollably.
            tileFileSystemCacheMaxBytes  = 5L * 1024 * 1024   // 5 MB cap
            tileFileSystemCacheTrimBytes = 4L * 1024 * 1024   // trim to 4 MB
            tileDownloadThreads          = 4   // default 2 → fetch 4 tiles in parallel
            tileFileSystemThreads        = 4   // default 2 → read cache 4 tiles in parallel
            tileDownloadMaxQueueSize     = 60  // default 40 → more tiles queued when panning
        }

        // ── Firebase ──────────────────────────────────────────────────────────
        // Wrapped in try-catch: a malformed google-services.json, a stale GMS
        // build on the device, or a missing default FirebaseApp can throw at
        // init time and take the whole process down — losing every alarm.
        // Better to log and run without Crashlytics/RemoteConfig than to crash.
        try {
            FirebaseApp.initializeApp(this)

            // Crashlytics: disable in debug so stack traces go to Logcat, not the dashboard
            FirebaseCrashlytics.getInstance().setCrashlyticsCollectionEnabled(!BuildConfig.DEBUG)

            // Analytics: separate util so call sites don't import Firebase types directly
            Analytics.initialize(this)

            // Always-on Crashlytics context. Set once at process start so EVERY
            // crash report includes device + permission state — the slice
            // dashboards typically need ("crashes on MIUI without battery
            // exemption", etc.). Cheap; runs off the main thread implicitly
            // since Crashlytics persists keys async.
            setBaselineCrashlyticsKeys()
            Analytics.setUserProperty("manufacturer", Build.MANUFACTURER)
            Analytics.setUserProperty("android_sdk", Build.VERSION.SDK_INT.toString())

            // Remote Config: fetch geocoding_backend_url so we can swap backends without an update
            val remoteConfig = FirebaseRemoteConfig.getInstance()
            remoteConfig.setConfigSettingsAsync(remoteConfigSettings {
                // In debug, refresh every 30s so changes in the dashboard are visible instantly.
                // In release, Firebase uses the default 12h minimum fetch interval.
                minimumFetchIntervalInSeconds = if (BuildConfig.DEBUG) 30 else 43200
            })
            remoteConfig.setDefaultsAsync(
                mapOf("geocoding_backend_url" to "https://stationalarm-geo.mohammedomama2005.workers.dev/")
            )
            remoteConfig.fetchAndActivate().addOnCompleteListener { task ->
                val url = remoteConfig.getString("geocoding_backend_url")
                if (url.isNotBlank()) GeocodingClient.setWorkerUrl(url)
                if (!task.isSuccessful) {
                    android.util.Log.w("RemoteConfig", "Fetch failed — using default Worker URL")
                }
            }
        } catch (e: Exception) {
            android.util.Log.e(
                "Firebase",
                "Firebase init failed — running without Crashlytics/RemoteConfig",
                e
            )
        }

        // Reconcile any orphan active_stations rows whose geofences may have been
        // lost to process death between DB insert and GMS registration. Idempotent
        // and cheap. Off the main thread so cold start isn't blocked.
        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            try {
                StationRepository.reconcileOrphans()
            } catch (e: Exception) {
                android.util.Log.w("Reconcile", "reconcileOrphans failed", e)
            }
            // Once the DB is reachable, publish the current alarm count to the
            // crash context (banded so it stays useful as an Audience filter).
            try {
                val count = StationRepository.getAllActiveStationsList().size
                FirebaseCrashlytics.getInstance().setCustomKey("alarms_count", count)
                Analytics.setUserProperty("alarms_count_band", bandAlarms(count))
            } catch (_: Exception) { /* Firebase may not be initialized */ }
        }
    }

    /** Read the persisted per-app locale and hand it to AppCompatDelegate.
     *  No-op if the user hasn't picked anything (or picked "System default") —
     *  Android falls back to the device locale automatically in that case. */
    private fun applyPersistedLocale() {
        try {
            val tag = runBlocking { UserPreferences.appLocaleFlow.first() }
            val locales = if (tag == UserPreferences.LOCALE_SYSTEM || tag.isBlank()) {
                LocaleListCompat.getEmptyLocaleList()
            } else {
                LocaleListCompat.forLanguageTags(tag)
            }
            AppCompatDelegate.setApplicationLocales(locales)
        } catch (e: Exception) {
            // DataStore disk read failed, or AppCompatDelegate threw — never let
            // localization break app startup. App will run in device locale.
            android.util.Log.w("Locale", "applyPersistedLocale failed", e)
        }
    }

    private fun setBaselineCrashlyticsKeys() {
        try {
            FirebaseCrashlytics.getInstance().apply {
                setCustomKey("manufacturer", Build.MANUFACTURER ?: "unknown")
                setCustomKey("device_model", Build.MODEL ?: "unknown")
                setCustomKey("android_sdk", Build.VERSION.SDK_INT)
                setCustomKey("battery_exempt", BatteryOptimizationHelper.isIgnoringBatteryOptimizations(this@StationAlarmApplication))
            }
        } catch (_: Exception) { /* Firebase may not be initialized */ }
    }

    private fun bandAlarms(count: Int): String = when {
        count <= 0 -> "0"
        count == 1 -> "1"
        count <= 5 -> "2-5"
        count <= 9 -> "6-9"
        else -> "10+"
    }
}