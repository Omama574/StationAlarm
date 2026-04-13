package com.omama.stationalarm.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.livedata.observeAsState
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
import com.omama.stationalarm.MainActivity
import com.omama.stationalarm.data.Station
import com.omama.stationalarm.service.LocationService
import com.omama.stationalarm.ui.screens.HomeScreen
import com.omama.stationalarm.ui.screens.MapSearchScreen
import com.omama.stationalarm.ui.screens.StationConfigBottomSheet
import com.omama.stationalarm.ui.viewmodel.StationViewModel
import com.omama.stationalarm.util.Logger
import kotlinx.coroutines.launch

/**
 * Top-level Compose root: drives the runtime permission flow and hosts
 * [AppNavigation]. Extracted from MainActivity.kt during the modularity
 * refactor; behaviour is preserved verbatim.
 */
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

    val activeStations by viewModel.activeStations.observeAsState(initial = emptyList())
    val tabs = listOf("Alarms", "Map")
    val pagerState = rememberPagerState(pageCount = { tabs.size })
    val snackbarHostState = remember { SnackbarHostState() }

    var selectedRadius by remember { mutableStateOf<Double?>(null) }
    var editingStation by remember { mutableStateOf<com.omama.stationalarm.data.ActiveStation?>(null) }
    var isFromMap by remember { mutableStateOf(false) }
    // For "View on Map" — station to center on when switching to map tab
    var viewOnMapStation by remember { mutableStateOf<com.omama.stationalarm.data.ActiveStation?>(null) }

    LaunchedEffect(pagerState.currentPage) {
        if (pagerState.currentPage == 1 && !isGpsEnabled()) {
            showGpsDialog = true
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
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
                        onEditStation = { activeStation ->
                            val station = activeStation.getStation()
                            if (station != null) {
                                editingStation = activeStation
                                selectedStation = station
                            }
                        },
                        onViewOnMap = { activeStation ->
                            viewOnMapStation = activeStation
                            coroutineScope.launch {
                                pagerState.animateScrollToPage(1)
                            }
                        },
                        onToggleStation = { activeStation, enabled ->
                            if (enabled) {
                                viewModel.rearmStation(activeStation.stationId)
                                // Start LocationService for re-armed station
                                Intent(context, LocationService::class.java).apply {
                                    action = LocationService.ACTION_START_FOR_ACTIVE_STATIONS
                                }.also { context.startForegroundService(it) }
                            } else {
                                viewModel.pauseStation(activeStation.stationId)
                            }
                        },
                        onNavigateToMap = {
                            coroutineScope.launch {
                                pagerState.animateScrollToPage(1)
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
                                isFromMap = true
                                selectedStation = station
                                selectedRadius = alertDistanceKm
                            } else {
                                showGpsDialog = true
                            }
                        },
                        isGpsEnabled = isGpsEnabled,
                        onRequestGps = { showGpsDialog = true },
                        focusStation = viewOnMapStation,
                        onFocusHandled = { viewOnMapStation = null }
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
        val isEditing = editingStation != null
        val isActive = isEditing || activeStations.any { it.stationId == selectedStation!!.id }
        StationConfigBottomSheet(
            station = selectedStation!!,
            isActive = isActive,
            initialRadius = if (isEditing) editingStation!!.alertDistanceKm else (selectedRadius ?: 5.0),
            initialNotify = if (isEditing) editingStation!!.notify else true,
            initialVibrate = if (isEditing) editingStation!!.vibrate else true,
            initialSound = if (isEditing) editingStation!!.sound else true,
            initialNotes = if (isEditing) editingStation!!.customReminder else null,
            onDismiss = {
                selectedStation = null
                selectedRadius = null
                editingStation = null
            },
            onConfirm = { activeStation ->
                if (isEditing) {
                    viewModel.updateActiveStationSettings(
                        stationId = activeStation.stationId,
                        radius = activeStation.alertDistanceKm,
                        notify = activeStation.notify,
                        vibrate = activeStation.vibrate,
                        sound = activeStation.sound,
                        reminder = activeStation.customReminder,
                        sendReminder = activeStation.sendReminder
                    )
                    val stationName = selectedStation!!.name
                    selectedStation = null
                    selectedRadius = null
                    editingStation = null
                    isFromMap = false
                    coroutineScope.launch {
                        snackbarHostState.showSnackbar("Alarm updated for $stationName")
                    }
                } else {
                    val isRailway = com.omama.stationalarm.data.StationData.getStationById(selectedStation!!.id) != null
                    viewModel.addActiveStation(activeStation, if (!isRailway) selectedStation else null)

                    val stationName = selectedStation!!.name
                    val wasFromMap = isFromMap
                    selectedStation = null
                    selectedRadius = null
                    editingStation = null
                    isFromMap = false

                    Intent(context, LocationService::class.java).apply {
                        action = LocationService.ACTION_START_FOR_ACTIVE_STATIONS
                    }.also { context.startForegroundService(it) }

                    coroutineScope.launch {
                        if (wasFromMap) {
                            pagerState.animateScrollToPage(0)
                        }
                        snackbarHostState.showSnackbar("Alarm set for $stationName")
                    }
                }
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
