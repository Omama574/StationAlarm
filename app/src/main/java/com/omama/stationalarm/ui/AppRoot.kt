package com.omama.stationalarm.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
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
import com.omama.stationalarm.ui.screens.AboutScreen
import com.omama.stationalarm.ui.screens.HomeScreen
import com.omama.stationalarm.ui.screens.MapSearchScreen
import com.omama.stationalarm.ui.screens.SettingsScreen
import com.omama.stationalarm.ui.screens.StationConfigBottomSheet
import com.omama.stationalarm.ui.viewmodel.StationViewModel
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

    // Track POST_NOTIFICATIONS separately on Android 13+. Without it, the
    // foreground-service notification and alarm alerts won't appear on a locked
    // screen — users routinely blame the app for "missed alarms" without
    // realising they denied notifications.
    val notificationsRequired = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    var hasNotificationsPermission by remember {
        mutableStateOf(
            if (notificationsRequired) {
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            } else true
        )
    }
    var showNotificationRationale by remember { mutableStateOf(false) }

    val lifecycleOwner = LocalLifecycleOwner.current

    val fineLocationLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        hasFineLocation = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        if (notificationsRequired) {
            val granted = permissions[Manifest.permission.POST_NOTIFICATIONS]
            if (granted != null) {
                hasNotificationsPermission = granted
                if (!granted) showNotificationRationale = true
            }
        }
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
                if (notificationsRequired) {
                    hasNotificationsPermission = ContextCompat.checkSelfPermission(
                        context, Manifest.permission.POST_NOTIFICATIONS
                    ) == PackageManager.PERMISSION_GRANTED
                    // User came back from Settings and granted — dismiss rationale.
                    if (hasNotificationsPermission) showNotificationRationale = false
                }

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

        // On Android 13+, POST_NOTIFICATIONS is runtime. Denying it silently
        // breaks foreground-service visibility and alarm alerts on a locked
        // screen, so we explain the consequence and deep-link to the app's
        // notification settings.
        if (showNotificationRationale && notificationsRequired && !hasNotificationsPermission) {
            NotificationPermissionDialog(
                onOpenSettings = {
                    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                        putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    }
                    try {
                        context.startActivity(intent)
                    } catch (_: Exception) {
                        // Fallback: open the generic app details page.
                        context.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = Uri.fromParts("package", context.packageName, null)
                            }
                        )
                    }
                },
                onDismiss = { showNotificationRationale = false }
            )
        }
    }
}

@Composable
fun NotificationPermissionDialog(onOpenSettings: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                "Enable Notifications",
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Text(
                "StationAlarm needs notification permission to show alarms while your " +
                        "phone is locked. Without it, the alarm may ring silently in the " +
                        "background and you'll miss your stop.",
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
            )
        },
        confirmButton = {
            TextButton(onClick = onOpenSettings) {
                Text("Open Settings", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Not Now", color = Color.Gray)
            }
        },
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(16.dp)
    )
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

/** Top-level screens reachable from the navigation drawer. */
private enum class Screen { Main, Settings, About }

@OptIn(ExperimentalMaterial3Api::class)
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

    var currentScreen by remember { mutableStateOf(Screen.Main) }
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)

    // Back handling for sub-screens and open drawer
    BackHandler(enabled = currentScreen != Screen.Main) {
        currentScreen = Screen.Main
    }
    BackHandler(enabled = currentScreen == Screen.Main && drawerState.isOpen) {
        coroutineScope.launch { drawerState.close() }
    }

    LaunchedEffect(pagerState.currentPage) {
        if (pagerState.currentPage == 1 && !isGpsEnabled()) {
            showGpsDialog = true
        }
    }

    // Surface one-shot errors from StationRepository (e.g. max-alarms reached,
    // geofence registration failures) to the user via the existing snackbar host.
    LaunchedEffect(Unit) {
        viewModel.errorEvents.collect { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = currentScreen == Screen.Main,
        drawerContent = {
            AppDrawerContent(
                onSettings = {
                    coroutineScope.launch { drawerState.close() }
                    currentScreen = Screen.Settings
                },
                onAbout = {
                    coroutineScope.launch { drawerState.close() }
                    currentScreen = Screen.About
                },
                onRateUs = {
                    coroutineScope.launch { drawerState.close() }
                    val pkg = context.packageName
                    val marketIntent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg")).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
                    }
                    try {
                        context.startActivity(marketIntent)
                    } catch (_: android.content.ActivityNotFoundException) {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$pkg"))
                        )
                    }
                },
                onShareApp = {
                    coroutineScope.launch { drawerState.close() }
                    val pkg = context.packageName
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, "StationAlarm")
                        putExtra(
                            Intent.EXTRA_TEXT,
                            "Check out StationAlarm — a location-based train/place alarm app.\nhttps://play.google.com/store/apps/details?id=$pkg"
                        )
                    }
                    context.startActivity(Intent.createChooser(shareIntent, "Share StationAlarm"))
                }
            )
        }
    ) {
        when (currentScreen) {
            Screen.Settings -> SettingsScreen(onBack = { currentScreen = Screen.Main })
            Screen.About -> AboutScreen(onBack = { currentScreen = Screen.Main })
            Screen.Main -> {
                Box(modifier = Modifier.fillMaxSize()) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        CenterAlignedTopAppBar(
                            title = {
                                Text(
                                    "StationAlarm",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 18.sp
                                )
                            },
                            navigationIcon = {
                                IconButton(onClick = { coroutineScope.launch { drawerState.open() } }) {
                                    Icon(Icons.Default.Menu, contentDescription = "Menu")
                                }
                            },
                            colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                                containerColor = MaterialTheme.colorScheme.surface,
                                titleContentColor = MaterialTheme.colorScheme.onSurface,
                                navigationIconContentColor = MaterialTheme.colorScheme.onSurface
                            )
                        )

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
                                            // Editing re-registers the geofence stack on save;
                                            // gate on GPS so the saved alarm is actually live.
                                            if (!isGpsEnabled()) {
                                                showGpsDialog = true
                                            } else {
                                                editingStation = activeStation
                                                selectedStation = station
                                            }
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
                                            // Re-arming requires live GPS — without this check the
                                            // toggle would flip ON, the foreground service would
                                            // start, and no location updates would ever arrive.
                                            if (!isGpsEnabled()) {
                                                showGpsDialog = true
                                            } else {
                                                viewModel.rearmStation(activeStation.stationId)
                                                Intent(context, LocationService::class.java).apply {
                                                    action = LocationService.ACTION_START_FOR_ACTIVE_STATIONS
                                                }.also { context.startForegroundService(it) }
                                            }
                                        } else {
                                            viewModel.pauseStation(activeStation.stationId)
                                        }
                                    },
                                    onNavigateToMap = {
                                        coroutineScope.launch {
                                            pagerState.animateScrollToPage(1)
                                        }
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
            }
        }
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
private fun AppDrawerContent(
    onSettings: () -> Unit,
    onAbout: () -> Unit,
    onRateUs: () -> Unit,
    onShareApp: () -> Unit
) {
    ModalDrawerSheet(
        drawerContainerColor = MaterialTheme.colorScheme.surface,
        drawerContentColor = MaterialTheme.colorScheme.onSurface
    ) {
        // Header
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp)
        ) {
            Text(
                "StationAlarm",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "v${com.omama.stationalarm.BuildConfig.VERSION_NAME}",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f))

        Spacer(Modifier.height(8.dp))

        NavigationDrawerItem(
            icon = { Icon(Icons.Default.Settings, contentDescription = null) },
            label = { Text("Settings") },
            selected = false,
            onClick = onSettings,
            modifier = Modifier.padding(horizontal = 12.dp)
        )
        NavigationDrawerItem(
            icon = { Icon(Icons.Default.Info, contentDescription = null) },
            label = { Text("About") },
            selected = false,
            onClick = onAbout,
            modifier = Modifier.padding(horizontal = 12.dp)
        )
        NavigationDrawerItem(
            icon = { Icon(Icons.Default.Star, contentDescription = null) },
            label = { Text("Rate Us") },
            selected = false,
            onClick = onRateUs,
            modifier = Modifier.padding(horizontal = 12.dp)
        )
        NavigationDrawerItem(
            icon = { Icon(Icons.Default.Share, contentDescription = null) },
            label = { Text("Share App") },
            selected = false,
            onClick = onShareApp,
            modifier = Modifier.padding(horizontal = 12.dp)
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
