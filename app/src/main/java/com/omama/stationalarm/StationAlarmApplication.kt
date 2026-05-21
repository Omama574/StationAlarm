package com.omama.stationalarm

import android.app.Application
import com.google.firebase.FirebaseApp
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import com.google.firebase.remoteconfig.ktx.remoteConfigSettings
import com.omama.stationalarm.network.GeocodingClient
import com.omama.stationalarm.repository.StationRepository
import com.omama.stationalarm.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import java.io.File

class StationAlarmApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        StationRepository.initialize(this)
        Logger.initialize(this)
        com.omama.stationalarm.util.GpsLogger.initialize(this)
        com.omama.stationalarm.data.UserPreferences.initialize(this)

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
        }
    }
}