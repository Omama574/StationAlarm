package com.omama.stationalarm

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.omama.stationalarm.geofence.GeofenceManager
import com.omama.stationalarm.repository.StationRepository
import com.omama.stationalarm.ui.screens.HomeScreen
import com.omama.stationalarm.ui.theme.StationAlarmTheme

class MainActivity : ComponentActivity() {

    private val TAG = "MainActivity"

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        if (fineGranted) {
            Log.d(TAG, "Foreground permission granted")
            checkAndRequestBackgroundPermission()
        } else {
            Log.e(TAG, "User denied foreground location permission")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        StationRepository.initialize(this)

        requestLocationPermissionsIfNeeded()

        setContent {
            StationAlarmTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    HomeScreen()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val backgroundGranted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

            if (backgroundGranted) {
                Log.d(TAG, "Background permission granted – registering Vaniyambadi geofence")
                Handler(Looper.getMainLooper()).postDelayed({
                    registerVaniyambadiGeofence()
                }, 2000)
            }
        }
    }

    private fun registerVaniyambadiGeofence() {
        val stationId = "VN"
        val radiusMeters = 5000f // 5 km trigger

        val station = StationRepository.getStationById(stationId)
        if (station != null) {
            Log.d(TAG, "Registering geofence for Vaniyambadi with radius ${radiusMeters}m")
            GeofenceManager.addGeofencesForStation(this, stationId, radiusMeters)
        } else {
            Log.e(TAG, "Station VN not found in repository")
        }
    }

    private fun requestLocationPermissionsIfNeeded() {
        val fineGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (!fineGranted) {
            locationPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        } else {
            checkAndRequestBackgroundPermission()
        }
    }

    private fun checkAndRequestBackgroundPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val backgroundGranted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

            if (!backgroundGranted) {
                openAppSettingsForBackgroundPermission()
            } else {
                Log.d(TAG, "All permissions already granted")
                // Will be handled in onResume
            }
        }
    }

    private fun openAppSettingsForBackgroundPermission() {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", packageName, null)
        ).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(intent)
    }
}