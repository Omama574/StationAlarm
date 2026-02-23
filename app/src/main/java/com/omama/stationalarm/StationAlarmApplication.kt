package com.omama.stationalarm

import android.app.Application
import com.omama.stationalarm.repository.StationRepository
import com.omama.stationalarm.util.Logger

class StationAlarmApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        StationRepository.initialize(this)
        Logger.initialize(this)
    }
}