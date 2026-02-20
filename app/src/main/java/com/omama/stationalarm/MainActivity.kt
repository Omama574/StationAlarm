package com.omama.stationalarm

import android.Manifest
import android.app.PendingIntent
import android.util.Log
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.os.Handler
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresPermission
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import com.omama.stationalarm.geofence.GeofenceManager
import com.omama.stationalarm.receiver.GeofenceBroadcastReceiver
import com.omama.stationalarm.repository.StationRepository
import com.omama.stationalarm.ui.screens.HomeScreen
import com.omama.stationalarm.ui.theme.StationAlarmTheme

class MainActivity : ComponentActivity() {

    private val TAG = "MainActivity"

    // 1. Define the launcher to handle the result of the permission dialog
    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        val coarseGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] ?: false

        if (fineGranted || coarseGranted) {
            Log.d(TAG, "Foreground location permissions granted: fine=$fineGranted, coarse=$coarseGranted")
            // Foreground granted, now check if we need background access
            checkAndRequestBackgroundPermission()
        } else {
            Log.e(TAG, "User denied foreground location permissions")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        StationRepository.initialize(this)

        // Run all diagnostic checks
        runDiagnosticChecks()

        // 2. Start the permission flow
        requestLocationPermissionsIfNeeded()

        setContent {
            StationAlarmTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    HomeScreen()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Re-check background permission after user returns from settings
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val backgroundGranted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

            if (!backgroundGranted) {
                Log.d(TAG, "User still hasn't granted background location")
            } else {
                Log.d(TAG, "Background location now granted! Testing fake geofence...")
                // Test with fake geofence first
                Handler(Looper.getMainLooper()).postDelayed({
                    testFakeGeofence()
                }, 2000)

                // Then test with your station
                Handler(Looper.getMainLooper()).postDelayed({
                    testGeofenceRegistration()
                }, 5000)
            }
        }

        // Re-run diagnostics when returning to app
        runDiagnosticChecks()
    }

    @RequiresPermission(Manifest.permission.ACCESS_FINE_LOCATION)
    private fun testFakeGeofence() {
        Log.d(TAG, "--- TESTING FAKE GEOFENCE ---")

        // Use random coordinates (Times Square, NYC as example)
        val fakeLat = 40.7580
        val fakeLon = -73.9855
        val fakeRadius = 500f // 500 meters

        try {
            // Create a simple geofence
            val geofence = Geofence.Builder()
                .setRequestId("test_geofence_123")
                .setCircularRegion(fakeLat, fakeLon, fakeRadius)
                .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER)
                .setExpirationDuration(Geofence.NEVER_EXPIRE)
                .build()

            // Create request
            val request = GeofencingRequest.Builder()
                .setInitialTrigger(0)
                .addGeofence(geofence)
                .build()

            // Create pending intent
            val intent = Intent(this, GeofenceBroadcastReceiver::class.java).apply {
                action = "com.omama.stationalarm.ACTION_GEOFENCE"
            }

            val pendingIntent = PendingIntent.getBroadcast(
                applicationContext, // Use applicationContext instead of 'this'
                9999,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            // Register geofence
            LocationServices.getGeofencingClient(this)
                .addGeofences(request, pendingIntent)
                .addOnSuccessListener {
                    Log.d(TAG, "✅ FAKE GEOFENCE REGISTERED SUCCESSFULLY!")
                }
                .addOnFailureListener { e ->
                    val errorCode = if (e is ApiException) e.statusCode else -1
                    val errorMessage = e.message ?: "null"
                    Log.e(TAG, "❌ FAKE GEOFENCE FAILED: code=$errorCode message=$errorMessage")

                    // Try with even simpler parameters
                    if (errorCode == 13) {
                        Log.d(TAG, "Trying with minimal geofence parameters...")
                        testMinimalGeofence()
                    }
                }

        } catch (e: Exception) {
            Log.e(TAG, "Error creating fake geofence: ${e.message}")
        }
    }

    @RequiresPermission(Manifest.permission.ACCESS_FINE_LOCATION)
    private fun testMinimalGeofence() {
        Log.d(TAG, "--- TESTING MINIMAL GEOFENCE ---")

        // Use current location or very simple coordinates
        val fakeLat = 37.4220 // Googleplex
        val fakeLon = -122.0840
        val fakeRadius = 100f // 100 meters - smaller radius

        try {
            val geofence = Geofence.Builder()
                .setRequestId("minimal_test_456")
                .setCircularRegion(fakeLat, fakeLon, fakeRadius)
                .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER)
                .setExpirationDuration(12 * 60 * 60 * 1000) // 12 hours instead of never
                .build()

            val request = GeofencingRequest.Builder()
                .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER)
                .addGeofence(geofence)
                .build()

            val intent = Intent(this, GeofenceBroadcastReceiver::class.java).apply {
                action = "com.omama.stationalarm.ACTION_GEOFENCE"
            }

            val pendingIntent = PendingIntent.getBroadcast(
                this,
                8888,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            LocationServices.getGeofencingClient(this)
                .addGeofences(request, pendingIntent)
                .addOnSuccessListener {
                    Log.d(TAG, "✅ MINIMAL GEOFENCE REGISTERED SUCCESSFULLY!")
                }
                .addOnFailureListener { e ->
                    val errorCode = if (e is ApiException) e.statusCode else -1
                    Log.e(TAG, "❌ MINIMAL GEOFENCE FAILED: code=$errorCode message=${e.message}")
                }
        } catch (e: Exception) {
            Log.e(TAG, "Error in minimal geofence: ${e.message}")
        }
    }

    private fun runDiagnosticChecks() {
        Log.d(TAG, "========== STARTING DIAGNOSTIC CHECKS ==========")
        logPermissionStatus()
        checkGooglePlayServices()
        checkGeofencingClient()
        checkLocationEnabled()
        Log.d(TAG, "========== DIAGNOSTIC CHECKS COMPLETE ==========")
    }

    private fun logPermissionStatus() {
        Log.d(TAG, "--- PERMISSION STATUS ---")

        val fineGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarseGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

        Log.d(TAG, "ACCESS_FINE_LOCATION: $fineGranted")
        Log.d(TAG, "ACCESS_COARSE_LOCATION: $coarseGranted")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val backgroundGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
            Log.d(TAG, "ACCESS_BACKGROUND_LOCATION: $backgroundGranted")
        } else {
            Log.d(TAG, "ACCESS_BACKGROUND_LOCATION: Not required (Android < 10)")
        }

        // Check if permissions are permanently denied
        if (!fineGranted && !coarseGranted) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val shouldShowRationale = shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION)
                Log.d(TAG, "Should show permission rationale: $shouldShowRationale")
                if (!shouldShowRationale) {
                    Log.e(TAG, "Permissions may be permanently denied - user needs to enable in settings")
                }
            }
        }
    }

    private fun checkGooglePlayServices() {
        Log.d(TAG, "--- GOOGLE PLAY SERVICES CHECK ---")
        try {
            val apiAvailability = GoogleApiAvailability.getInstance()
            val result = apiAvailability.isGooglePlayServicesAvailable(this)

            Log.d(TAG, "Google Play Services availability result code: $result")

            when (result) {
                ConnectionResult.SUCCESS -> {
                    Log.d(TAG, "✅ Google Play Services is available and up to date!")

                    // Get version info
                    val versionCode = apiAvailability.getApkVersion(this)
                    val versionName = try {
                        packageManager.getPackageInfo("com.google.android.gms", 0).versionName
                    } catch (e: Exception) {
                        "Unknown"
                    }
                    Log.d(TAG, "Google Play Services version code: $versionCode")
                    Log.d(TAG, "Google Play Services version name: $versionName")

                    // Simple location client check
                    try {
                        val locationClient = LocationServices.getFusedLocationProviderClient(this)
                        Log.d(TAG, "✅ Location client created successfully")
                    } catch (e: Exception) {
                        Log.e(TAG, "❌ Failed to create location client", e)
                    }
                }
                ConnectionResult.SERVICE_MISSING -> Log.e(TAG, "❌ Google Play Services is missing")
                ConnectionResult.SERVICE_UPDATING -> Log.e(TAG, "❌ Google Play Services is updating")
                ConnectionResult.SERVICE_VERSION_UPDATE_REQUIRED -> Log.e(TAG, "❌ Google Play Services version update required")
                ConnectionResult.SERVICE_DISABLED -> Log.e(TAG, "❌ Google Play Services is disabled")
                ConnectionResult.SERVICE_INVALID -> Log.e(TAG, "❌ Google Play Services is invalid")
                else -> Log.e(TAG, "❌ Google Play Services error: $result")
            }

            // Check if user can resolve the error
            if (result != ConnectionResult.SUCCESS && apiAvailability.isUserResolvableError(result)) {
                Log.d(TAG, "This error can be resolved by the user (showing dialog)")
                // Uncomment to show resolution dialog
                // apiAvailability.getErrorDialog(this, result, 1001).show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking Google Play Services", e)
        }
    }

    private fun checkGeofencingClient() {
        Log.d(TAG, "--- GEOFENCING CLIENT CHECK ---")
        try {
            val geofencingClient = LocationServices.getGeofencingClient(this)
            Log.d(TAG, "✅ GeofencingClient created successfully")

            // Check if we can get the client without throwing
            if (geofencingClient != null) {
                Log.d(TAG, "GeofencingClient is not null")

                // Try to check connection state (this is hacky but can help)
                try {
                    // This is just to trigger any initialization issues
                    val pendingIntent = GeofenceManager.getTestPendingIntent(this)
                    Log.d(TAG, "✅ Test PendingIntent created successfully")
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Error creating test PendingIntent", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to create GeofencingClient", e)
        }
    }

    private fun checkLocationEnabled() {
        Log.d(TAG, "--- LOCATION ENABLED CHECK ---")
        try {
            val locationManager = getSystemService(LOCATION_SERVICE) as LocationManager

            val isGpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
            val isNetworkEnabled = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
            val isPassiveEnabled = locationManager.isProviderEnabled(LocationManager.PASSIVE_PROVIDER)

            Log.d(TAG, "GPS provider enabled: $isGpsEnabled")
            Log.d(TAG, "Network provider enabled: $isNetworkEnabled")
            Log.d(TAG, "Passive provider enabled: $isPassiveEnabled")

            if (!isGpsEnabled && !isNetworkEnabled) {
                Log.e(TAG, "❌ No location providers are enabled! Location may be turned off.")
            } else {
                Log.d(TAG, "✅ Location is enabled (at least one provider active)")
            }

            // Check if we can get last known location (optional)
            try {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                    val lastLocation = LocationServices.getFusedLocationProviderClient(this).lastLocation
                    lastLocation.addOnSuccessListener { location ->
                        if (location != null) {
                            Log.d(TAG, "✅ Last known location available: ${location.latitude}, ${location.longitude}")
                        } else {
                            Log.d(TAG, "ℹ️ No last known location available")
                        }
                    }.addOnFailureListener { e ->
                        Log.d(TAG, "Could not get last location: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "Could not check last location: ${e.message}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking location enabled state", e)
        }
    }

    private fun testGeofenceRegistration() {
        Log.d(TAG, "--- TEST GEOFENCE REGISTRATION ---")

        // Get all active stations
        val activeStations = StationRepository.getAllActiveStations()

        if (activeStations.isNotEmpty()) {
            // Use the first active station
            val testStation = activeStations.first()
            Log.d(TAG, "Attempting to register geofence for active station: ${testStation.stationId}")
            GeofenceManager.addGeofencesForStation(this, testStation.stationId, testStation.alertDistanceKm)
        } else {
            // If no active stations, try the station ID from your error log
            val testStationId = "KPD"
            Log.d(TAG, "No active stations found. Attempting to register geofence for station: $testStationId")

            // Check if this station exists in the master data
            val station = StationRepository.getStationById(testStationId)
            if (station != null) {
                Log.d(TAG, "Station $testStationId found in master data")
                GeofenceManager.addGeofencesForStation(this, testStationId, 1.0)
            } else {
                Log.e(TAG, "Station $testStationId not found in master data")

                // Try to get any station from master data
                val allStations = StationRepository.getAllStations()
                if (allStations.isNotEmpty()) {
                    val anyStation = allStations.first()
                    Log.d(TAG, "Using first available station: ${anyStation.id}")
                    GeofenceManager.addGeofencesForStation(this, anyStation.id, 1.0)
                } else {
                    Log.e(TAG, "No stations available in master data")
                }
            }
        }
    }

    private fun requestLocationPermissionsIfNeeded() {
        val fineGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        Log.d(TAG, "Initial permission check - FINE_LOCATION granted: $fineGranted")

        if (!fineGranted) {
            Log.d(TAG, "Requesting foreground location permissions")
            // Request foreground permissions first
            locationPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        } else {
            Log.d(TAG, "Foreground location already granted")
            // Foreground already granted, check background
            checkAndRequestBackgroundPermission()
        }
    }

    private fun checkAndRequestBackgroundPermission() {
        // Background location only exists on API 29 (Q) and above
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val backgroundGranted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

            Log.d(TAG, "Background location permission granted: $backgroundGranted")

            if (!backgroundGranted) {
                Log.d(TAG, "Opening app settings for background location permission")
                // On Android 11+ (API 30), you MUST send the user to settings
                // for background location; you cannot show a system popup for it.
                openAppSettingsForBackgroundPermission()
            } else {
                Log.d(TAG, "✅ All permissions are granted! Attempting test registration...")
                // Give Google Play Services a moment to initialize
                Handler(Looper.getMainLooper()).postDelayed({
                    testFakeGeofence()
                }, 3000)
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