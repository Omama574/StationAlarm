package com.omama.stationalarm

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.omama.stationalarm.data.UserPreferences
import com.omama.stationalarm.repository.StationRepository
import com.omama.stationalarm.service.LocationService
import com.omama.stationalarm.ui.AppRoot
import com.omama.stationalarm.ui.theme.StationAlarmTheme
import com.omama.stationalarm.util.DistanceUnit
import com.omama.stationalarm.util.LocalDistanceUnit
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val themeMode by UserPreferences.themeModeFlow.collectAsState(initial = UserPreferences.THEME_SYSTEM)
            val distanceUnitPref by UserPreferences.distanceUnitFlow.collectAsState(initial = UserPreferences.UNIT_KM)

            val darkTheme = when (themeMode) {
                UserPreferences.THEME_LIGHT -> false
                UserPreferences.THEME_DARK -> true
                else -> isSystemInDarkTheme()
            }
            val unit = DistanceUnit.fromPrefString(distanceUnitPref)

            StationAlarmTheme(darkTheme = darkTheme) {
                CompositionLocalProvider(LocalDistanceUnit provides unit) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        AppRoot()
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (hasAllLocationPermissions(this)) {
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                val active = StationRepository.getAllActiveStationsList()
                if (active.isNotEmpty()) {
                    StationRepository.reRegisterAllGeofences()
                    val intent = Intent(this@MainActivity, LocationService::class.java).apply {
                        action = LocationService.ACTION_START_FOR_ACTIVE_STATIONS
                    }
                    startForegroundService(intent)
                }
            }
        }
    }

    companion object {
        fun hasAllLocationPermissions(context: Context): Boolean {
            val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            val bg = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
            } else true
            return fine && bg
        }

        fun isGpsEnabled(context: Context): Boolean {
            val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            return locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                   locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        }
    }
}
