package com.omama.stationalarm

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.omama.stationalarm.data.Station
import com.omama.stationalarm.repository.StationRepository
import com.omama.stationalarm.service.LocationService
import com.omama.stationalarm.ui.screens.HomeScreen
import com.omama.stationalarm.ui.screens.MapSearchScreen
import com.omama.stationalarm.ui.screens.StationConfigBottomSheet
import com.omama.stationalarm.ui.theme.StationAlarmTheme
import com.omama.stationalarm.ui.viewmodel.StationViewModel
import com.omama.stationalarm.util.Logger
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    // Launcher removed from Activity level, handled in Compose AppRoot

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Initial permission launching moved entirely to AppRoot sequential workflow

        setContent {
            StationAlarmTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AppRoot()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (hasAllLocationPermissions(this)) {
            StationRepository.reRegisterAllGeofences()
            Intent(this, LocationService::class.java).apply {
                action = LocationService.ACTION_START_FOR_ACTIVE_STATIONS
            }.also { startForegroundService(it) }
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

@Composable
fun AppRoot() {
    val context = LocalContext.current
    var hasFineLocation by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) }
    var hasBackgroundLocation by remember { mutableStateOf(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED else true) }
    
    var showPermissionRationale by remember { mutableStateOf(false) }

    val lifecycleOwner = LocalLifecycleOwner.current

    val fineLocationLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        hasFineLocation = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        if (hasFineLocation && !hasBackgroundLocation) {
            showPermissionRationale = true
        }
    }

    val backgroundLocationLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasBackgroundLocation = isGranted
        showPermissionRationale = !isGranted
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasFineLocation = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                hasBackgroundLocation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
                } else true

                if (!hasFineLocation) {
                    val initialPermissions = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        initialPermissions.add(Manifest.permission.POST_NOTIFICATIONS)
                    }
                    fineLocationLauncher.launch(initialPermissions.toTypedArray())
                } else if (!hasBackgroundLocation) {
                    showPermissionRationale = true
                } else {
                    showPermissionRationale = false
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AppNavigation(
            isGpsEnabled = { MainActivity.isGpsEnabled(context) }
        )

        if (showPermissionRationale) {
            PermissionRationaleDialog {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    backgroundLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                } else {
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", context.packageName, null)
                    }
                    context.startActivity(intent)
                }
            }
        }
    }
}

@Composable
fun PermissionRationaleDialog(onOpenSettings: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.85f)),
        contentAlignment = Alignment.Center
    ) {
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            modifier = Modifier
                .fillMaxWidth()
                .padding(32.dp)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Location Access Required",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "StationAlarm requires 'Allow all the time' location access to wake up and sound the alarm when you arrive near your destination while the app is closed or your phone is locked.",
                    fontSize = 16.sp,
                    color = Color.DarkGray,
                    textAlign = TextAlign.Center,
                    lineHeight = 22.sp
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "• Tap 'Settings' below\n• Tap 'Permissions'\n• Select 'Location'\n• Choose 'Allow all the time'",
                    fontSize = 14.sp,
                    color = Color.Black,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(24.dp))
                Button(
                    onClick = onOpenSettings,
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Black, contentColor = Color.White),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Open Settings", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun AppNavigation(isGpsEnabled: () -> Boolean) {
    var selectedStation by remember { mutableStateOf<Station?>(null) }
    var showGpsDialog by remember { mutableStateOf(false) }
    val viewModel: StationViewModel = viewModel()
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val tabs = listOf("My Stations", "Map")
    val pagerState = rememberPagerState(pageCount = { tabs.size })
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(pagerState.currentPage) {
        if (pagerState.currentPage == 1 && !isGpsEnabled()) {
            showGpsDialog = true
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // ── Tab Row ───────────────────────────────────────────────────────────
        TabRow(
            selectedTabIndex = pagerState.currentPage,
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface
        ) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    selected = pagerState.currentPage == index,
                    onClick = { coroutineScope.launch { pagerState.animateScrollToPage(index) } },
                    text = {
                        Text(
                            title,
                            fontWeight = if (pagerState.currentPage == index) FontWeight.Bold else FontWeight.Normal,
                            fontSize = 14.sp
                        )
                    }
                )
            }
        }

        // ── Pages ─────────────────────────────────────────────────────────────
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .weight(1f)
                .clipToBounds(),
            // Disable swipe gestures — tabs only.
            // This prevents the pager from stealing horizontal drags
            // that the osmdroid MapView needs for panning.
            userScrollEnabled = false
        ) { page ->
            when (page) {
                0 -> HomeScreen(
                    onStationSelected = { station ->
                        if (isGpsEnabled()) {
                            selectedStation = station
                        } else {
                            showGpsDialog = true
                        }
                    },
                    onShareLogs = {
                        val intent = Logger.shareLog(context)
                        if (intent != null) context.startActivity(intent)
                    },
                    onShareGpsLogs = {
                        val intent = com.omama.stationalarm.util.GpsLogger.shareLog(context)
                        if (intent != null) context.startActivity(intent)
                    },
                    viewModel = viewModel
                )
                1 -> MapSearchScreen(
                    onStartTrip = { station, alertDistanceKm ->
                        if (isGpsEnabled()) {
                            selectedStation = station
                        } else {
                            showGpsDialog = true
                        }
                    },
                    isGpsEnabled = isGpsEnabled,
                    onRequestGps = { showGpsDialog = true }
                )
            }
        }
    }
        
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 32.dp)
        )
    }

    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartIntentSenderForResult()
    ) { }

    fun promptGps() {
        val locationRequest = com.google.android.gms.location.LocationRequest.Builder(
            com.google.android.gms.location.Priority.PRIORITY_HIGH_ACCURACY, 1000
        ).build()
        val builder = com.google.android.gms.location.LocationSettingsRequest.Builder().addLocationRequest(locationRequest)
        val client = com.google.android.gms.location.LocationServices.getSettingsClient(context)
        val task = client.checkLocationSettings(builder.build())
        task.addOnFailureListener { exception ->
            if (exception is com.google.android.gms.common.api.ResolvableApiException) {
                try {
                    val intentSenderRequest = androidx.activity.result.IntentSenderRequest.Builder(exception.resolution).build()
                    launcher.launch(intentSenderRequest)
                } catch (e: Exception) {}
            }
        }
    }

    if (showGpsDialog) {
        GpsDisabledDialog(
            onDismiss = { showGpsDialog = false },
            onTurnOn = { showGpsDialog = false; promptGps() }
        )
    }

    if (selectedStation != null) {
        StationConfigBottomSheet(
            station = selectedStation!!,
            onDismiss = { selectedStation = null },
            onConfirm = { activeStation ->
                val isRailway = com.omama.stationalarm.data.StationData.getStationById(selectedStation!!.id) != null
                viewModel.addActiveStation(activeStation, if (!isRailway) selectedStation else null)
                
                val stationName = selectedStation!!.name
                selectedStation = null
                
                coroutineScope.launch {
                    snackbarHostState.showSnackbar("✅ Alarm set for $stationName")
                }
                
                Intent(context, LocationService::class.java).apply {
                    action = LocationService.ACTION_START_FOR_ACTIVE_STATIONS
                }.also { context.startForegroundService(it) }
            }
        )
    }
}

@Composable
fun GpsDisabledDialog(onDismiss: () -> Unit, onTurnOn: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("GPS Disabled", color = Color.Black, fontWeight = FontWeight.Bold) },
        text = { Text("Please allow StationAlarm to turn on location services for accurate tracking.", color = Color.DarkGray) },
        confirmButton = {
            TextButton(onClick = onTurnOn) {
                Text("Turn On GPS", color = Color.Black, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = Color.Gray)
            }
        },
        containerColor = Color.White,
        shape = RoundedCornerShape(16.dp)
    )
}
