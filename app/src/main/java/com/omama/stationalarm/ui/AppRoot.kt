package com.omama.stationalarm.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.omama.stationalarm.R
import com.omama.stationalarm.ui.theme.LocalAppHazeState
import com.omama.stationalarm.ui.theme.appGradient
import com.omama.stationalarm.ui.theme.glassTopBarStyle
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.omama.stationalarm.BuildConfig
import com.omama.stationalarm.MainActivity
import com.omama.stationalarm.data.Station
import com.omama.stationalarm.data.UserPreferences
import com.omama.stationalarm.service.LocationService
import com.omama.stationalarm.util.AppRemoteConfig
import com.omama.stationalarm.ui.screens.AboutScreen
import com.omama.stationalarm.ui.screens.HomeScreen
import com.omama.stationalarm.ui.screens.MapSearchScreen
import com.omama.stationalarm.ui.screens.SettingsScreen
import com.omama.stationalarm.ui.screens.BatteryOptimizationSheet
import com.omama.stationalarm.ui.screens.StationConfigBottomSheet
import com.omama.stationalarm.ui.viewmodel.StationViewModel
import com.omama.stationalarm.util.BatteryOptimizationHelper
import kotlinx.coroutines.launch

private fun isAlarmVolumeLow(context: Context): Boolean {
    val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    val max = am.getStreamMaxVolume(AudioManager.STREAM_ALARM)
    return am.getStreamVolume(AudioManager.STREAM_ALARM) < (max * 0.5f)
}

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

    // Remote-config-driven app-wide gates. Read once per recomposition.
    val minVersion by AppRemoteConfig.minSupportedAppVersionFlow.collectAsState()
    val maintenanceOn by AppRemoteConfig.maintenanceModeFlow.collectAsState()
    val maintenanceMessage by AppRemoteConfig.maintenanceMessageFlow.collectAsState()
    val forceUpdate = AppRemoteConfig.isForceUpdateRequired(
        currentVersionCode = BuildConfig.VERSION_CODE.toLong(),
        isDebug = BuildConfig.DEBUG,
    )

    Box(modifier = Modifier.fillMaxSize()) {
        if (forceUpdate) {
            // Hard-block the entire app behind an update prompt. The rest of
            // AppNavigation is not composed — no map, no alarms, no settings.
            ForceUpdateDialog(
                onUpdate = { openPlayStore(context) }
            )
            return@Box
        }

        Column(modifier = Modifier.fillMaxSize()) {
            if (maintenanceOn) {
                MaintenanceBanner(
                    message = maintenanceMessage.ifBlank { stringResource(R.string.maintenance_default_body) }
                )
            }
            Box(modifier = Modifier.fillMaxSize().weight(1f)) {
                AppNavigation(
                    isGpsEnabled = { MainActivity.isGpsEnabled(context) }
                )
            }
        }

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

/**
 * Non-dismissable amber banner shown at the top of the main screen when the
 * server flips `maintenance_mode` on. Keep it small enough to leave the rest
 * of the UI usable — users can still browse alarms, just can't trust new
 * geocoding lookups to succeed.
 */
@Composable
fun MaintenanceBanner(message: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Color(0xFFFFE082),       // Material amber 200
        contentColor = Color(0xFF5D4037), // Brown 700 — readable on amber
    ) {
        Text(
            text = message,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

/**
 * Blocking dialog shown when the running build is below the Remote-Config
 * `min_supported_app_version` floor. There is no dismiss button on purpose —
 * the only escape is to update. (Use this rarely: bump the floor only when
 * an older build has a critical bug that can't be hot-fixed via the Worker.)
 */
@Composable
fun ForceUpdateDialog(onUpdate: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.85f)),
        contentAlignment = Alignment.Center,
    ) {
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            modifier = Modifier.fillMaxWidth().padding(32.dp),
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = stringResource(R.string.force_update_title),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.force_update_body),
                    fontSize = 15.sp,
                    color = Color.DarkGray,
                    textAlign = TextAlign.Center,
                    lineHeight = 22.sp,
                )
                Spacer(Modifier.height(24.dp))
                Button(
                    onClick = onUpdate,
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.Black,
                        contentColor = Color.White,
                    ),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text(
                        text = stringResource(R.string.force_update_button),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

/** Same launch pattern as the drawer's "Rate Us" — market URI first, web
 *  fallback if no Play Store app is installed. */
private fun openPlayStore(context: Context) {
    val pkg = context.packageName
    val marketIntent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg")).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    try {
        context.startActivity(marketIntent)
    } catch (_: android.content.ActivityNotFoundException) {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$pkg"))
        )
    }
}

@Composable
fun NotificationPermissionDialog(onOpenSettings: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(R.string.permission_notifications_title),
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Text(
                stringResource(R.string.permission_notifications_body),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
            )
        },
        confirmButton = {
            TextButton(onClick = onOpenSettings) {
                Text(stringResource(R.string.common_open_settings), fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_not_now), color = Color.Gray)
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
                    text = stringResource(R.string.permission_location_title),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.permission_location_body),
                    fontSize = 16.sp,
                    color = Color.DarkGray,
                    textAlign = TextAlign.Center,
                    lineHeight = 22.sp
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.permission_location_steps),
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
                    Text(stringResource(R.string.common_open_settings), fontSize = 16.sp, fontWeight = FontWeight.Bold)
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
    val tabs = listOf(stringResource(R.string.tab_alarms), stringResource(R.string.tab_map))
    val pagerState = rememberPagerState(pageCount = { tabs.size })
    val snackbarHostState = remember { SnackbarHostState() }

    var selectedRadius by remember { mutableStateOf<Double?>(null) }
    var editingStation by remember { mutableStateOf<com.omama.stationalarm.data.ActiveStation?>(null) }
    var isFromMap by remember { mutableStateOf(false) }
    // For "View on Map" — station to center on when switching to map tab
    var viewOnMapStation by remember { mutableStateOf<com.omama.stationalarm.data.ActiveStation?>(null) }

    var currentScreen by remember { mutableStateOf(Screen.Main) }
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    var showBatteryOptSheet by remember { mutableStateOf(false) }
    var showVolumeWarning by remember { mutableStateOf(false) }
    var checkVolumeAfterBattery by remember { mutableStateOf(false) }
    // Read once per recomposition; the sheet is gated on this so users who
    // hit "Don't ask again" never see it again from the post-confirm path.
    val batteryOptDismissed by UserPreferences.batteryOptDismissedFlow.collectAsState(initial = false)

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

    // On the Map tab the drawer would steal horizontal drags meant for panning
    // the osmdroid MapView, so swipe-to-open is disabled there — the menu icon
    // is the only way in. Swipe-to-close still works once the drawer is open.
    val drawerGesturesEnabled = currentScreen == Screen.Main &&
        (pagerState.currentPage != 1 || drawerState.isOpen)

    val hazeState = rememberHazeState()
    val gradient = appGradient()
    val topBarGlassStyle = glassTopBarStyle()

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = drawerGesturesEnabled,
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
                        putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.share_app_subject))
                        putExtra(
                            Intent.EXTRA_TEXT,
                            context.getString(R.string.share_app_text_format, pkg)
                        )
                    }
                    context.startActivity(Intent.createChooser(shareIntent, context.getString(R.string.share_app_chooser_title)))
                }
            )
        }
    ) {
        when (currentScreen) {
            Screen.Settings -> SettingsScreen(onBack = { currentScreen = Screen.Main })
            Screen.About -> AboutScreen(onBack = { currentScreen = Screen.Main })
            Screen.Main -> {
                CompositionLocalProvider(LocalAppHazeState provides hazeState) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(gradient)
                        .hazeSource(hazeState)
                ) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        CenterAlignedTopAppBar(
                            title = {
                                Text(
                                    stringResource(R.string.app_name),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 18.sp
                                )
                            },
                            navigationIcon = {
                                IconButton(onClick = { coroutineScope.launch { drawerState.open() } }) {
                                    Icon(Icons.Default.Menu, contentDescription = stringResource(R.string.nav_menu))
                                }
                            },
                            colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                                containerColor = Color.Transparent,
                                titleContentColor = MaterialTheme.colorScheme.onSurface,
                                navigationIconContentColor = MaterialTheme.colorScheme.onSurface
                            ),
                            modifier = Modifier.hazeEffect(hazeState, topBarGlassStyle)
                        )

                        TabRow(
                            selectedTabIndex = pagerState.currentPage,
                            containerColor = Color.Transparent,
                            contentColor = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.hazeEffect(hazeState, topBarGlassStyle)
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
                } // CompositionLocalProvider
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

    // Capture selectedStation into a local val so all subsequent reads work
    // off the same snapshot — without this, a recomposition between the null
    // check and an `!!` deref could throw NPE.
    val station = selectedStation
    val editing = editingStation
    if (station != null) {
        val isEditing = editing != null
        val isActive = isEditing || activeStations.any { it.stationId == station.id }
        // Pre-fill name: edit → existing alarm name; new → station name
        // (railway result or geocoded address). Raw "Dropped Pin" with no
        // reverse-geocode becomes a plain "Alarm" placeholder.
        val initialAlarmName = if (isEditing) {
            editing!!.stationName.ifBlank { "Alarm" }
        } else {
            val raw = station.name
            if (raw.isBlank() || raw == "Dropped Pin") "Alarm" else raw
        }
        StationConfigBottomSheet(
            station = station,
            isActive = isActive,
            initialRadius = if (isEditing) editing!!.alertDistanceKm else (selectedRadius ?: 5.0),
            initialNotify = if (isEditing) editing!!.notify else true,
            initialVibrate = if (isEditing) editing!!.vibrate else true,
            initialSound = if (isEditing) editing!!.sound else true,
            initialNotes = if (isEditing) editing!!.customReminder else null,
            initialName = initialAlarmName,
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
                    // updateActiveStationSettings doesn't touch stationName —
                    // persist a rename made inside the bottom sheet here.
                    if (activeStation.stationName.isNotBlank() &&
                        activeStation.stationName != editing!!.stationName) {
                        viewModel.updateStationName(activeStation.stationId, activeStation.stationName)
                    }
                    val stationName = activeStation.stationName.ifBlank { station.name }
                    selectedStation = null
                    selectedRadius = null
                    editingStation = null
                    isFromMap = false
                    coroutineScope.launch {
                        snackbarHostState.showSnackbar(context.getString(R.string.snackbar_alarm_updated_format, stationName))
                    }
                    val soundOn = activeStation.sound
                    val needsBatterySheet = !batteryOptDismissed
                        && !BatteryOptimizationHelper.isIgnoringBatteryOptimizations(context)
                    if (needsBatterySheet) {
                        checkVolumeAfterBattery = soundOn
                        showBatteryOptSheet = true
                    } else if (soundOn && isAlarmVolumeLow(context)) {
                        showVolumeWarning = true
                    }
                } else {
                    val isRailway = com.omama.stationalarm.data.StationData.getStationById(station.id) != null
                    viewModel.addActiveStation(activeStation, if (!isRailway) station else null)

                    val stationName = activeStation.stationName.ifBlank { station.name }
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
                        snackbarHostState.showSnackbar(context.getString(R.string.snackbar_alarm_set_format, stationName))
                    }
                    val soundOn = activeStation.sound
                    val needsBatterySheet = !batteryOptDismissed
                        && !BatteryOptimizationHelper.isIgnoringBatteryOptimizations(context)
                    if (needsBatterySheet) {
                        checkVolumeAfterBattery = soundOn
                        showBatteryOptSheet = true
                    } else if (soundOn && isAlarmVolumeLow(context)) {
                        showVolumeWarning = true
                    }
                }
            }
        )
    }

    if (showBatteryOptSheet) {
        BatteryOptimizationSheet(
            onDismiss = {
                showBatteryOptSheet = false
                if (checkVolumeAfterBattery && isAlarmVolumeLow(context)) {
                    showVolumeWarning = true
                }
                checkVolumeAfterBattery = false
            },
            onDontAskAgain = {
                coroutineScope.launch { UserPreferences.setBatteryOptDismissed(true) }
                showBatteryOptSheet = false
                if (checkVolumeAfterBattery && isAlarmVolumeLow(context)) {
                    showVolumeWarning = true
                }
                checkVolumeAfterBattery = false
            }
        )
    }

    if (showVolumeWarning) {
        AlarmVolumeWarningDialog(
            onIncreaseToMax = {
                val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
                showVolumeWarning = false
            },
            onDismiss = { showVolumeWarning = false }
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
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    val drawerBg = if (dark) Color(0xFF0E1514).copy(alpha = 0.93f) else Color(0xFFFAFDFB).copy(alpha = 0.95f)

    ModalDrawerSheet(
        drawerContainerColor = drawerBg,
        drawerContentColor = MaterialTheme.colorScheme.onSurface
    ) {
        // Header
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp)
        ) {
            Text(
                stringResource(R.string.app_name),
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(2.dp))
            Text(
                stringResource(R.string.nav_drawer_app_version_format, com.omama.stationalarm.BuildConfig.VERSION_NAME),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f))

        Spacer(Modifier.height(8.dp))

        NavigationDrawerItem(
            icon = { Icon(Icons.Default.Settings, contentDescription = null) },
            label = { Text(stringResource(R.string.nav_settings)) },
            selected = false,
            onClick = onSettings,
            modifier = Modifier.padding(horizontal = 12.dp)
        )
        NavigationDrawerItem(
            icon = { Icon(Icons.Default.Info, contentDescription = null) },
            label = { Text(stringResource(R.string.nav_about)) },
            selected = false,
            onClick = onAbout,
            modifier = Modifier.padding(horizontal = 12.dp)
        )
        NavigationDrawerItem(
            icon = { Icon(Icons.Default.Star, contentDescription = null) },
            label = { Text(stringResource(R.string.nav_rate_us)) },
            selected = false,
            onClick = onRateUs,
            modifier = Modifier.padding(horizontal = 12.dp)
        )
        NavigationDrawerItem(
            icon = { Icon(Icons.Default.Share, contentDescription = null) },
            label = { Text(stringResource(R.string.nav_share_app)) },
            selected = false,
            onClick = onShareApp,
            modifier = Modifier.padding(horizontal = 12.dp)
        )
    }
}

@Composable
fun AlarmVolumeWarningDialog(onIncreaseToMax: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(R.string.volume_low_title),
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Text(
                stringResource(R.string.volume_low_body),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
            )
        },
        confirmButton = {
            TextButton(onClick = onIncreaseToMax) {
                Text(stringResource(R.string.volume_low_increase), fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.volume_low_keep), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(16.dp)
    )
}

@Composable
fun GpsDisabledDialog(onDismiss: () -> Unit, onTurnOn: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.gps_disabled_title), color = Color.Black, fontWeight = FontWeight.Bold) },
        text = { Text(stringResource(R.string.gps_disabled_body), color = Color.DarkGray) },
        confirmButton = {
            TextButton(onClick = onTurnOn) {
                Text(stringResource(R.string.gps_disabled_turn_on), color = Color.Black, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel), color = Color.Gray)
            }
        },
        containerColor = Color.White,
        shape = RoundedCornerShape(16.dp)
    )
}
