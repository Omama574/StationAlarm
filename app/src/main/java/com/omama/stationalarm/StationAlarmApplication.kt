package com.omama.stationalarm

import android.app.Application
import com.omama.stationalarm.repository.StationRepository
import com.omama.stationalarm.util.Logger
import org.osmdroid.config.Configuration
import java.io.File

class StationAlarmApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        StationRepository.initialize(this)
        Logger.initialize(this)

        // osmdroid must be configured before any MapView is created.
        // OSM tile servers require a proper User-Agent string — without it your app
        // can get IP-banned from the tile CDN.
        Configuration.getInstance().apply {
            userAgentValue = "StationAlarm/2.0 (android)"
            osmdroidBasePath = File(cacheDir, "osmdroid")
            osmdroidTileCache = File(osmdroidBasePath, "tile")
            tileFileSystemCacheMaxBytes = 300L * 1024 * 1024  // 300 MB cap
        }
    }
}