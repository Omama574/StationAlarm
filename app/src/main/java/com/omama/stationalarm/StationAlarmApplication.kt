package com.omama.stationalarm

import android.app.Application
import com.google.firebase.FirebaseApp
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import com.google.firebase.remoteconfig.ktx.remoteConfigSettings
import com.omama.stationalarm.network.GeocodingClient
import com.omama.stationalarm.repository.StationRepository
import com.omama.stationalarm.util.Logger
import org.osmdroid.config.Configuration
import java.io.File

class StationAlarmApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        StationRepository.initialize(this)
        Logger.initialize(this)
        com.omama.stationalarm.util.GpsLogger.initialize(this)

        // osmdroid must be configured before any MapView is created.
        // OSM tile servers require a proper User-Agent string — without it your app
        // can get IP-banned from the tile CDN.
        Configuration.getInstance().apply {
            userAgentValue = "StationAlarm/2.0 (android)"
            osmdroidBasePath = File(cacheDir, "osmdroid")
            osmdroidTileCache = File(osmdroidBasePath, "tile")
            tileFileSystemCacheMaxBytes  = 50L * 1024 * 1024  // 50 MB cap
            tileFileSystemCacheTrimBytes = 40L * 1024 * 1024  // trim to 40 MB
            tileDownloadThreads          = 4   // default 2 → fetch 4 tiles in parallel
            tileFileSystemThreads        = 4   // default 2 → read cache 4 tiles in parallel
            tileDownloadMaxQueueSize     = 60  // default 40 → more tiles queued when panning
        }

        // ── Firebase ──────────────────────────────────────────────────────────
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
    }
}