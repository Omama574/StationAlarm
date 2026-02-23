package com.omama.stationalarm

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.omama.stationalarm.data.Station
import com.omama.stationalarm.repository.StationRepository
import com.omama.stationalarm.service.LocationService
import com.omama.stationalarm.ui.screens.HomeScreen
import com.omama.stationalarm.ui.screens.SearchScreen
import com.omama.stationalarm.ui.screens.StationConfigBottomSheet
import com.omama.stationalarm.ui.theme.StationAlarmTheme
import com.omama.stationalarm.ui.viewmodel.StationViewModel
import com.omama.stationalarm.util.Logger

class MainActivity : ComponentActivity() {

    // Permission launcher for multiple permissions
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        val backgroundGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            permissions[Manifest.permission.ACCESS_BACKGROUND_LOCATION] ?: false
        } else true
        val notificationGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions[Manifest.permission.POST_NOTIFICATIONS] ?: false
        } else true

        if (fineGranted) {
            if (backgroundGranted) {
                // All location permissions granted
                showAllowAllTheTimeGuidance()
            } else {
                // Need background permission
                openAppSettingsForBackgroundPermission()
            }
        } else {
            // Show rationale for location permission
            // (optional)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestAllPermissions()

        setContent {
            StationAlarmTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AppNavigation()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (checkAllPermissions()) {
            // Permissions are all granted, re-register geofences and start service
            StationRepository.reRegisterAllGeofences()
            Intent(this, LocationService::class.java).apply {
                action = LocationService.ACTION_START_FOR_ACTIVE_STATIONS
            }.also { startForegroundService(it) }
        }
    }

    private fun requestAllPermissions() {
        val permissionsToRequest = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            permissionsToRequest.add(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permissionLauncher.launch(permissionsToRequest.toTypedArray())
    }

    private fun checkAllPermissions(): Boolean {
        val fineGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val backgroundGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
        } else true
        val notificationGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else true
        return fineGranted && backgroundGranted && notificationGranted
    }

    private fun showAllowAllTheTimeGuidance() {
        // On Android 10+, even with background permission, the user might have selected "Allow only while using the app"
        // We can't detect that directly, but we can show a dialog reminding them to select "Allow all the time"
        android.app.AlertDialog.Builder(this)
            .setTitle("Location Access")
            .setMessage("For reliable geofence alerts, please ensure location access is set to 'Allow all the time' in app settings.")
            .setPositiveButton("Open Settings") { _, _ ->
                openAppSettingsForBackgroundPermission()
            }
            .setNegativeButton("Later", null)
            .show()
    }

    private fun openAppSettingsForBackgroundPermission() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", packageName, null)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(intent)
    }

    private fun checkLocationEnabled() {
        val locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        val isGpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
        val isNetworkEnabled = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)

        if (!isGpsEnabled && !isNetworkEnabled) {
            android.app.AlertDialog.Builder(this)
                .setTitle("Location Disabled")
                .setMessage("Location is turned off. Please enable location for accurate alerts.")
                .setPositiveButton("Open Settings") { _, _ ->
                    startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }
}

@Composable
fun AppNavigation() {
    var currentScreen by remember { mutableStateOf(Screen.HOME) }
    var selectedStation by remember { mutableStateOf<Station?>(null) }
    val viewModel: StationViewModel = viewModel()
    val context = LocalContext.current

    when (currentScreen) {
        Screen.HOME -> HomeScreen(
            onAddClick = { currentScreen = Screen.SEARCH },
            onShareLogs = {
                val intent = Logger.shareLog(context)
                if (intent != null) {
                    context.startActivity(intent)
                } else {
                    // Optionally show a toast
                }
            }
        )
        Screen.SEARCH -> SearchScreen(
            onBack = { currentScreen = Screen.HOME },
            onStationSelected = { station ->
                selectedStation = station
                currentScreen = Screen.CONFIG
            },
            viewModel = viewModel
        )
        Screen.CONFIG -> { /* handled by bottom sheet */ }
    }

    if (selectedStation != null) {
        StationConfigBottomSheet(
            station = selectedStation!!,
            onDismiss = {
                selectedStation = null
                currentScreen = Screen.HOME
            },
            onConfirm = { activeStation ->
                viewModel.addActiveStation(activeStation)
                selectedStation = null
                currentScreen = Screen.HOME
                // Optionally start service if within outer radius
                Intent(context, LocationService::class.java).apply {
                    action = LocationService.ACTION_START_FOR_ACTIVE_STATIONS
                }.also { context.startForegroundService(it) }
            }
        )
    }
}

enum class Screen { HOME, SEARCH, CONFIG }