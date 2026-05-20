package com.omama.stationalarm.service

import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.ActivityCompat
import com.google.android.gms.location.*
import com.omama.stationalarm.service.gps.FixQualityGate
import com.omama.stationalarm.service.gps.FlpClientGuardian
import com.omama.stationalarm.service.gps.GpsReliabilityCoordinator
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.omama.stationalarm.BuildConfig
import com.omama.stationalarm.data.ActiveStation
import com.omama.stationalarm.data.UserPreferences
import com.omama.stationalarm.repository.StationRepository
import com.omama.stationalarm.util.BatteryOptimizationHelper
import com.omama.stationalarm.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import java.util.concurrent.ConcurrentHashMap

/**
 * V2 LocationService — fully reactive to the Database state machine.
 *
 * States per station:
 *   MONITORING  → GPS tracking active, geofences registered
 *   ALERTING    → Alarm ringing, fullscreen notification shown, GPS tracking stopped for this station
 *   DISMISSED   → (row deleted from DB) All cleanup done
 *
 * The Service observes the DB via [syncWithDatabase]. All state transitions
 * happen by writing to the DB first; the Service reacts.
 *
 * Behaviour-preserving modular refactor delegates audio, wake locks and
 * notifications to dedicated helpers in this package — see
 * [AlarmAudioController], [ServiceWakeLocks] and [ServiceNotifications].
 */
class LocationService : Service() {

    companion object {
        const val ACTION_GEOFENCE_TRIGGERED = "com.omama.stationalarm.ACTION_GEOFENCE_TRIGGERED"
        const val ACTION_ALERT_GEOFENCE_TRIGGERED = "com.omama.stationalarm.ACTION_ALERT_GEOFENCE_TRIGGERED"
        const val ACTION_START_FOR_ACTIVE_STATIONS = "START_FOR_ACTIVE_STATIONS"
        const val ACTION_DISMISS_ALARM = "com.omama.stationalarm.ACTION_DISMISS_ALARM"
        const val ACTION_RESTORE_NOTIFICATION = "com.omama.stationalarm.ACTION_RESTORE_NOTIFICATION"
        const val ACTION_STOP_ALL_MONITORING = "com.omama.stationalarm.ACTION_STOP_ALL_MONITORING"
        const val ACTION_TEST_ALARM = "com.omama.stationalarm.ACTION_TEST_ALARM"
    }

    // --- In-memory tracking (only MONITORING stations) ---
    private val monitoringStations = ConcurrentHashMap<String, ActiveStation>()
    // --- In-memory alerting (only ALERTING stations) ---
    private val alertingStationIds = ConcurrentHashMap.newKeySet<String>()

    // --- Wait for Exit pattern ---
    private val needsInitialEvaluation = ConcurrentHashMap.newKeySet<String>()
    private val waitingForExit = ConcurrentHashMap.newKeySet<String>()

    // GPS reliability layers (Phase 2). Guardian wraps the FLP client;
    // Coordinator manages cold-start, coarse fallback, and GNSS monitoring.
    private lateinit var flpGuardian: FlpClientGuardian
    private lateinit var gpsCoordinator: GpsReliabilityCoordinator

    // Convenience accessor — always routes through the guardian's current client.
    private val fusedLocationClient: FusedLocationProviderClient
        get() = flpGuardian.client

    private var locationRequest: LocationRequest? = null
    private lateinit var locationCallback: LocationCallback

    private var isPolling = false
    private var currentPollingIntervalMs = 10_000L // Start fast for initial lock
    // Tracks the current FLP priority. BALANCED at >100km (cell/WiFi, saves GPS
    // antenna power), HIGH_ACCURACY at <=100km (satellite GPS for precision).
    private var currentLocationPriority = Priority.PRIORITY_HIGH_ACCURACY
    // Tracks whether the foreground service is running with LOCATION type.
    // Flipped to false when all monitoring stops (alarm-only mode) so the GPS
    // indicator disappears from the status bar while the alarm is ringing.
    private var locationForegroundActive = true

    private lateinit var audio: AlarmAudioController
    private lateinit var wakeLocks: ServiceWakeLocks
    private lateinit var notifications: ServiceNotifications

    private var serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)

    // Cached alarm URI string — updated whenever UserPreferences.alarmSoundUriFlow
    // emits. Reading this in fireAlert() avoids blocking the Main dispatcher on
    // DataStore disk I/O (was an ANR risk with runBlocking). Blank = system default.
    @Volatile private var cachedAlarmSoundUri: String? = null
    @Volatile private var cachedRingSpeakerWithHeadphones: Boolean? = null
    @Volatile private var cachedEscalatingAlarm: Boolean? = null
    @Volatile private var cachedRampSecs: Int? = null
    @Volatile private var cachedAlarmDurationSecs: Int? = null

    private var lastLocationTimeMs = 0L
    // Last reported accuracy in metres; used as a Crashlytics custom key when
    // an alarm fires so a Doze/MIUI sleep-induced bad fix can be diagnosed.
    @Volatile private var lastKnownAccuracyM: Float = -1f
    // Self-heal state. Set when silent recovery is in flight; cleared on first
    // fix arrival (→ cancels any visible watchdog notification). Also drives
    // whether we've already escalated to the user-visible Tier 2 state.
    private var watchdogRecoveryPending = false
    private var watchdogNotified = false
    private var watchdogRecoveryToken: CancellationTokenSource? = null
    private val watchdogHandler = Handler(Looper.getMainLooper())
    private val watchdogRunnable = object : Runnable {
        override fun run() {
            if (isPolling && monitoringStations.isNotEmpty()) {
                handleWatchdogTick()
            }
            watchdogHandler.postDelayed(this, 30_000L)
        }
    }

    // ============================
    //         LIFECYCLE
    // ============================

    override fun onCreate() {
        super.onCreate()
        audio = AlarmAudioController(this)
        wakeLocks = ServiceWakeLocks(this)
        notifications = ServiceNotifications(this)
        notifications.createChannels()
        // Sweep any orphan watchdog notification left by a previously killed process.
        // Canceling a non-existent notification is a harmless no-op.
        notifications.cancelWatchdog()

        // --- Phase 2: Construct reliability layers ---
        flpGuardian = FlpClientGuardian(
            context = this,
            onClientRecreated = { /* fusedLocationClient getter auto-delegates */ },
            onRecoveryFix = { loc ->
                lastLocationTimeMs = System.currentTimeMillis()
                clearWatchdogState("guardian one-shot fix")
                processLocationUpdate(loc)
            },
            onRecoveryFailed = { reason ->
                Logger.log("GUARDIAN_RECOVERY_FAILED", extra = reason)
            }
        )

        gpsCoordinator = GpsReliabilityCoordinator(
            context = this,
            onCoarseFix = { coarseLoc ->
                // Coarse fixes (cell tower) go through the normal pipeline.
                // The FixQualityGate will inflate their accuracy conservatively.
                lastLocationTimeMs = System.currentTimeMillis()
                processLocationUpdate(coarseLoc)
            },
            onColdStartComplete = {
                Logger.log("COLD_START_SETTLED", extra = "switching to normal gearbox")
                // Settle into normal gearbox based on actual distance.
                // We MUST force a restart because the cached currentPollingIntervalMs might 
                // match the distance gear perfectly, causing adjustPollingInterval() to skip 
                // the rebuild and leaving us stuck on the 5-second cold-start request forever.
                restartLocationUpdates()
                adjustPollingInterval()
            }
        )

        locationCallback = createLocationCallback()

        syncWithDatabase()
        observeAlarmSoundUri()
        observeAudioRoutingPreference()
    }

    /**
     * Factory for the FLP LocationCallback. Extracted so the FlpClientGuardian
     * can create a fresh callback object with the same body during nuclear reset
     * (the GMS IPC pipe needs a fresh object reference, not the same instance).
     */
    private fun createLocationCallback(): LocationCallback {
        return object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                lastLocationTimeMs = System.currentTimeMillis()
                flpGuardian.onFixReceived()
                clearWatchdogState("fix arrived, clearing watchdog state")

                if (ActivityCompat.checkSelfPermission(
                        this@LocationService,
                        android.Manifest.permission.ACCESS_FINE_LOCATION
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    Logger.log("ERROR", extra = "Location permission lost, stopping service")
                    stopSelf()
                    return
                }
                locationResult.lastLocation?.let { location ->
                    // Notify coordinator of good FLP fix (for coarse disarm logic)
                    gpsCoordinator.onGoodFlpFix(location)

                    // End cold-start on first fix
                    if (gpsCoordinator.isColdStartActive) {
                        gpsCoordinator.onFirstFixReceived()
                    }

                    processLocationUpdate(location)
                }
            }
        }
    }

    /** Shared helper: clears watchdog recovery state and cancels notifications. */
    private fun clearWatchdogState(logReason: String) {
        if (watchdogRecoveryPending || watchdogNotified) {
            Logger.log("WATCHDOG_RECOVERED", extra = logReason)
            watchdogRecoveryPending = false
            watchdogNotified = false
            watchdogRecoveryToken?.cancel()
            watchdogRecoveryToken = null
            notifications.cancelWatchdog()
        }
    }

    /**
     * Keep [cachedAlarmSoundUri] in sync with DataStore so [fireAlert] never has
     * to block for disk I/O. DataStore emits the current value on subscribe and
     * then on every update.
     */
    private fun observeAlarmSoundUri() {
        serviceScope.launch {
            UserPreferences.alarmSoundUriFlow.collect { uri ->
                cachedAlarmSoundUri = uri
            }
        }
    }

    private fun observeAudioRoutingPreference() {
        serviceScope.launch {
            UserPreferences.ringSpeakerWithHeadphonesFlow.collect { enabled ->
                cachedRingSpeakerWithHeadphones = enabled
            }
        }
        serviceScope.launch {
            UserPreferences.escalatingAlarmFlow.collect { enabled ->
                cachedEscalatingAlarm = enabled
            }
        }
        serviceScope.launch {
            UserPreferences.escalatingAlarmRampSecsFlow.collect { secs ->
                cachedRampSecs = secs
            }
        }
        serviceScope.launch {
            UserPreferences.alarmDurationSecsFlow.collect { secs ->
                cachedAlarmDurationSecs = secs
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DISMISS_ALARM) {
            val stationId = intent.getStringExtra("stationId") ?: return START_NOT_STICKY
            handleDismiss(stationId)
            return START_NOT_STICKY
        }

        if (intent?.action == ACTION_STOP_ALL_MONITORING) {
            handleStopAll()
            return START_NOT_STICKY
        }

        if (intent?.action == ACTION_TEST_ALARM) {
            // Required by Android: must call startForeground() within 5s of
            // startForegroundService. The notification disappears with stopSelf()
            // after the 3s test if no real alarms are armed.
            updateForegroundNotification(forceStartForeground = true)
            handleTestAlarm()
            return START_NOT_STICKY
        }

        // For all other intents, ensure we hit Android's foreground service requirement immediately.
        updateForegroundNotification(forceStartForeground = true)

        when (intent?.action) {
            ACTION_GEOFENCE_TRIGGERED -> {
                val stationId = intent.getStringExtra("stationId") ?: return START_STICKY
                val layer = intent.getStringExtra("layer")
                Logger.log("SERVICE_GEOFENCE_RECEIVED", stationId, layer)
                // Wake-up: immediately force a location update
                restartLocationUpdates()
            }
            ACTION_ALERT_GEOFENCE_TRIGGERED -> {
                val stationId = intent.getStringExtra("stationId") ?: return START_STICKY
                Logger.log("SERVICE_ALERT_GEOFENCE_RECEIVED", stationId)
                StationRepository.markAlerting(stationId)
            }
            ACTION_START_FOR_ACTIVE_STATIONS -> {
                // Boot / re-initialization: sync handles everything
                Logger.log("SERVICE_INIT_REQUESTED")
            }
            ACTION_RESTORE_NOTIFICATION -> {
                // User swiped the ongoing notification away. forceStartForeground
                // above already re-posted it; syncWithDatabase keeps driving state.
                Logger.log("SERVICE_NOTIFICATION_RESTORED")
            }
        }

        // Ensure polling is running if we have monitoring stations
        if (!isPolling && monitoringStations.isNotEmpty()) {
            startLocationUpdates()
            lastLocationTimeMs = System.currentTimeMillis()
            watchdogHandler.postDelayed(watchdogRunnable, 30_000L)
        }

        return START_STICKY
    }

    override fun onDestroy() {
        stopLocationUpdates()
        flpGuardian.destroy()
        gpsCoordinator.destroy()
        audio.stopAll()
        wakeLocks.releaseAlarm()
        wakeLocks.releaseGps()
        serviceJob.cancel()
        watchdogHandler.removeCallbacks(watchdogRunnable)
        super.onDestroy()
    }

    /**
     * Called when the user swipes the app from Recents. Default Android
     * behaviour is to kill the service process right after this returns, which
     * also removes the ongoing foreground notification — making the user think
     * they've cancelled the trip when geofences are still armed at the OS
     * level. Restart ourselves immediately so monitoring (GPS + notification)
     * stays continuous, matching Strava's pattern.
     *
     * Gated on in-memory state (already synced from DB) so we never respawn
     * when there's nothing to monitor.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val hasWork = monitoringStations.isNotEmpty() || alertingStationIds.isNotEmpty()
        Logger.log("SERVICE_TASK_REMOVED", extra = "hasWork=$hasWork")
        if (hasWork) {
            val restartIntent = Intent(applicationContext, LocationService::class.java).apply {
                action = ACTION_START_FOR_ACTIVE_STATIONS
            }
            androidx.core.content.ContextCompat.startForegroundService(applicationContext, restartIntent)
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ============================
    //    DATABASE SYNC ENGINE
    // ============================

    /**
     * The heart of the V2 architecture. Observes the database and reacts
     * to state changes. This is the ONLY place that drives service behavior.
     */
    private fun syncWithDatabase() {
        serviceScope.launch {
            // Process kill can leave a row stuck in ALERTING (alertingStationIds
            // is in-memory only). Without this reset, the first flow emission
            // below would diff dbAlertingIds - alertingStationIds (= dbAlertingIds
            // on cold start) and re-fire the alarm without user interaction.
            // Awaited before subscribing so the first emission reflects the
            // cleared state. Geofences were already removed at markAlerting()
            // time, so the user must enter the radius again to re-fire.
            try {
                StationRepository.resetAllAlertingToMonitoring()
            } catch (e: Exception) {
                // Hard-fail: if we can't clear ALERTING rows on cold start, the
                // diff in the first emission below will treat every ALERTING
                // row as "newly alerting" and re-fire the alarm with no user
                // intent. Better to stop the service and let WorkManager / the
                // next user action retry than to wake users with ghost alarms.
                Logger.log("ALERTING_RESET_FAILED", extra = "stopping service: ${e.message}")
                stopSelf()
                return@launch
            }
            StationRepository.activeStationsFlow.collect { allFromDb ->

                // --- Partition by status ---
                val dbMonitoring = allFromDb.filter { it.status == "MONITORING" }
                val dbAlerting = allFromDb.filter { it.status == "ALERTING" }
                val dbMonitoringIds = dbMonitoring.map { it.stationId }.toSet()
                val dbAlertingIds = dbAlerting.map { it.stationId }.toSet()

                // --- 1. Sync MONITORING list ---
                val removedFromMonitoring = monitoringStations.keys.filter { it !in dbMonitoringIds }
                if (removedFromMonitoring.isNotEmpty()) {
                    for (id in removedFromMonitoring) monitoringStations.remove(id)
                    Logger.log("SYNC_MONITORING_PRUNED", extra = "Removed ${removedFromMonitoring.size}")
                }
                val currentMonitoringIds = monitoringStations.keys
                val newMonitoring = dbMonitoring.filter { it.stationId !in currentMonitoringIds }
                if (newMonitoring.isNotEmpty()) {
                    for (station in newMonitoring) monitoringStations[station.stationId] = station
                    needsInitialEvaluation.addAll(newMonitoring.map { it.stationId })
                    Logger.log("SYNC_MONITORING_ADDED", extra = "Added ${newMonitoring.size}")
                }

                // --- 2. Sync ALERTING list ---
                val newlyAlerting = dbAlertingIds - alertingStationIds
                val noLongerAlerting = alertingStationIds - dbAlertingIds

                for (id in newlyAlerting) {
                    val station = dbAlerting.find { it.stationId == id } ?: continue
                    alertingStationIds.add(id)
                    fireAlert(station)
                }

                if (noLongerAlerting.isNotEmpty()) {
                    alertingStationIds.removeAll(noLongerAlerting)
                    if (alertingStationIds.isEmpty()) {
                         audio.stopAll()
                        wakeLocks.releaseAlarm()
                        notifications.cancelAudioFailureNotification()
                    }
                    for (id in noLongerAlerting) {
                        notifications.cancelAlert(id)
                    }
                    Logger.log("SYNC_ALERTING_PRUNED", extra = "Cleaned up ${noLongerAlerting.size}")
                }

                // --- 3. GPS Management ---
                if (monitoringStations.isNotEmpty()) {
                    if (!isPolling) {
                        startLocationUpdates()
                        lastLocationTimeMs = System.currentTimeMillis()
                        watchdogHandler.postDelayed(watchdogRunnable, 30_000L)
                    }
                    if (newMonitoring.isNotEmpty()) {
                        getLastKnownLocation { loc ->
                            if (loc != null) {
                                for (s in newMonitoring) {
                                    val station = s.getStation() ?: continue
                                    s.currentDistanceKm = calculateDistance(loc.latitude, loc.longitude, station.lat, station.lon)
                                }
                                adjustPollingInterval()
                                processLocationUpdate(loc)
                            }
                        }
                    }
                } else {
                    stopLocationUpdates()
                }

                // --- 4. Update foreground notification / shutdown ---
                if (monitoringStations.isEmpty() && alertingStationIds.isEmpty()) {
                    // Full teardown: no monitoring, no alerting. Release GPS,
                    // drop the foreground notification explicitly (stopForeground
                    // REMOVE can race with a prior notifyForeground and leave a
                    // stale "Monitoring stations..." visible), and stop the
                    // service so the OS frees it instead of keeping it alive.
                    Logger.log("SERVICE_STOPPING", extra = "No monitoring, no alerting stations")
                    stopLocationUpdates()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    notifications.cancelForeground()
                    stopSelf()
                } else {
                    updateForegroundNotification()
                }
            }
        }
    }

    // ============================
    //    LOCATION PROCESSING
    // ============================

    private fun processLocationUpdate(location: Location) {
        lastKnownAccuracyM = location.accuracy
        serviceScope.launch(Dispatchers.Default) {
            if (monitoringStations.isEmpty()) return@launch

            var minDistance = Double.MAX_VALUE
            var nearestStationName = "None"
            var nearestStationId: String? = null

            val toAlert = mutableListOf<ActiveStation>()
            val newDistances = mutableMapOf<String, Double>()

            for (active in monitoringStations.values.toList()) {
                val station = active.getStation() ?: continue
                val distance = calculateDistance(location.latitude, location.longitude, station.lat, station.lon)
                active.currentDistanceKm = distance
                newDistances[active.stationId] = distance

                if (distance < minDistance) {
                    minDistance = distance
                    nearestStationName = station.name
                    nearestStationId = active.stationId
                }

                // Quality gate: inflate accuracy for low-quality fixes based on
                // distance band. A demoted fix still updates UI and gearbox but
                // the inflated accuracy makes the alert buffer more conservative.
                val gateDecision = FixQualityGate.evaluate(location, distance)
                val accuracyKm = when (gateDecision) {
                    is FixQualityGate.Decision.Accept -> gateDecision.effectiveAccuracyMeters / 1000.0
                    is FixQualityGate.Decision.DemoteToHint -> {
                        Logger.log("GPS_GATE_DEMOTED", extra = gateDecision.reason)
                        gateDecision.effectiveAccuracyMeters / 1000.0
                    }
                }
                val effectiveDistance = distance - accuracyKm

                if (needsInitialEvaluation.contains(active.stationId)) {
                    needsInitialEvaluation.remove(active.stationId)
                    if (effectiveDistance <= active.alertDistanceKm) {
                        waitingForExit.add(active.stationId)
                        Logger.log("WAITING_FOR_EXIT", active.stationId, "Re-armed while inside radius")
                    }
                }

                if (effectiveDistance > active.alertDistanceKm) {
                    waitingForExit.remove(active.stationId)
                }

                if (effectiveDistance <= active.alertDistanceKm 
                    && !alertingStationIds.contains(active.stationId)
                    && !waitingForExit.contains(active.stationId)) {
                    toAlert.add(active)
                }
            }

            StationRepository.updateStationDistances(newDistances)

            for (active in toAlert) {
                StationRepository.markAlerting(active.stationId)
            }

            val distToLog = if (minDistance == Double.MAX_VALUE) null else minDistance
            Logger.log(
                eventType = "LOCATION_POLL",
                stationId = nearestStationId,
                latitude = location.latitude,
                longitude = location.longitude,
                distanceKm = distToLog
            )

            val priorityLabel = if (currentLocationPriority == Priority.PRIORITY_HIGH_ACCURACY) "HIGH" else "BALANCED"
            // Log the gate decision for the nearest station (the one that drives the gearbox)
            val nearestGateDecision = if (minDistance < Double.MAX_VALUE) {
                val d = FixQualityGate.evaluate(location, minDistance)
                if (d is FixQualityGate.Decision.Accept) "accept" else "demote"
            } else ""
            com.omama.stationalarm.util.GpsLogger.logLocation(
                location = location,
                currentIntervalMs = currentPollingIntervalMs,
                gateDecision = nearestGateDecision,
                priorityUsed = priorityLabel,
                satsVisible = gpsCoordinator.satellitesVisible,
                satsInFix = gpsCoordinator.satellitesInFix,
                isMock = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) location.isMock else @Suppress("DEPRECATION") location.isFromMockProvider,
                flpFailures = flpGuardian.getConsecutiveFailures(),
                flpClientAgeSec = flpGuardian.getClientAgeSeconds(),
                coarseArmed = gpsCoordinator.isCoarseArmed,
                coldStartActive = gpsCoordinator.isColdStartActive
            )

            updateForegroundNotification()
            adjustPollingInterval()
        }
    }

    // ============================
    //     ALERT ENGINE
    // ============================

    /**
     * Called by syncWithDatabase when a station transitions to ALERTING.
     * Plays sound, shows fullscreen notification. Does NOT delete from DB.
     */
    private suspend fun fireAlert(active: ActiveStation) {
        Logger.log("ALERT_FIRED", active.stationId, "distance=${active.currentDistanceKm}")

        wakeLocks.acquireAlarm()

        // Crashlytics context: if anything in the alarm path throws after this,
        // the dashboard report includes the active station + reliability-relevant
        // state. Cached pref reads (vs .first()) so we never block on disk here;
        // the DataStore default mirrors the cache-miss value.
        if (!BuildConfig.DEBUG) {
            try {
                FirebaseCrashlytics.getInstance().apply {
                    setCustomKey("alarm_station_id", active.stationId)
                    setCustomKey(
                        "alarm_battery_exempt",
                        BatteryOptimizationHelper.isIgnoringBatteryOptimizations(this@LocationService)
                    )
                    setCustomKey("alarm_gps_accuracy_m", lastKnownAccuracyM.toDouble())
                    setCustomKey(
                        "alarm_audio_route",
                        if (cachedRingSpeakerWithHeadphones != false) "speaker+ext" else "ext_only"
                    )
                    setCustomKey("alarm_escalating", cachedEscalatingAlarm != false)
                    setCustomKey(
                        "alarm_duration_secs",
                        cachedAlarmDurationSecs ?: UserPreferences.DEFAULT_ALARM_DURATION_SECS
                    )
                }
            } catch (_: Exception) {
                // Firebase may have failed to init at process start — see
                // StationAlarmApplication. Alarm logic must not depend on it.
            }
        }

        val startedNewAlarm = (!audio.isAlarmRinging && active.sound) || (!audio.isVibrating && active.vibrate)

        if (active.sound && !audio.isAlarmRinging) {
            // Use the cached URI (populated by observeAlarmSoundUri). Also
            // validates readability — persisted SAF permissions are sometimes
            // revoked by the OS after reboot, so a URI that worked at pick
            // time may no longer resolve. Null → default sound.
            // DataStore .first() can throw on corrupted/unreadable prefs file.
            // Without these guards, an IOException here cancels fireAlert mid-
            // execution: wake lock is already held, DB still ALERTING, alarm
            // never starts — a silent failure. Defaults match DataStore's
            // fresh-install defaults, so behaviour is identical to a new user.
            val customUriStr = cachedAlarmSoundUri ?: try {
                UserPreferences.alarmSoundUriFlow.first().also { cachedAlarmSoundUri = it }
            } catch (e: Exception) {
                Logger.log("PREF_READ_FAILED", extra = "alarmSoundUri: ${e.message}")
                ""
            }
            val customUri: android.net.Uri? = resolveValidatedAlarmUri(customUriStr)
            val stationLabel = active.getStation()?.name ?: active.stationId
            val ringSpeaker = cachedRingSpeakerWithHeadphones ?: try {
                UserPreferences.ringSpeakerWithHeadphonesFlow.first().also { cachedRingSpeakerWithHeadphones = it }
            } catch (e: Exception) {
                Logger.log("PREF_READ_FAILED", extra = "ringSpeaker: ${e.message}")
                true
            }
            val escalating = cachedEscalatingAlarm ?: try {
                UserPreferences.escalatingAlarmFlow.first().also { cachedEscalatingAlarm = it }
            } catch (e: Exception) {
                Logger.log("PREF_READ_FAILED", extra = "escalating: ${e.message}")
                true
            }
            val rampSecs = cachedRampSecs ?: try {
                UserPreferences.escalatingAlarmRampSecsFlow.first().also { cachedRampSecs = it }
            } catch (e: Exception) {
                Logger.log("PREF_READ_FAILED", extra = "rampSecs: ${e.message}")
                UserPreferences.DEFAULT_RAMP_SECS
            }
            audio.playAlarmSound(customUri, ringSpeaker, escalating, rampSecs.takeIf { escalating } ?: 0) {
                // Both custom and default URIs failed — escalate so the user
                // doesn't think the alarm silently failed. The full-screen
                // alert still fires; this just adds vibration + a notification.
                if (!audio.isVibrating) audio.startVibrator()
                notifications.showAudioFailureNotification(stationLabel)
                Logger.log("AUDIO_TERMINAL_FAILURE", active.stationId, "fell back to vibration only")
            }
        }

        if (active.vibrate && !audio.isVibrating) {
            audio.startVibrator()
        }

        if (startedNewAlarm) {
            val durationSecs = cachedAlarmDurationSecs ?: try {
                UserPreferences.alarmDurationSecsFlow.first().also { cachedAlarmDurationSecs = it }
            } catch (e: Exception) {
                Logger.log("PREF_READ_FAILED", extra = "alarmDurationSecs: ${e.message}")
                UserPreferences.DEFAULT_ALARM_DURATION_SECS
            }
            val durationMs = durationSecs * 1000L
            audio.startTimeout(serviceScope, durationMs) {
                Logger.log("ALARM_TIMEOUT", extra = "Auto-dismissing after ${durationSecs}s")
                // Auto-dismiss all alerting stations to prevent zombie state
                val ids = alertingStationIds.toList()
                for (id in ids) {
                    notifications.cancelAlert(id)
                    StationRepository.dismissStation(id)
                }
                alertingStationIds.clear()
                wakeLocks.releaseAlarm()
            }
        }

        // Remove from monitoring set (sync already did this, but be safe)
        monitoringStations.remove(active.stationId)

        // Replace the foreground "monitoring" notification for this station
        // with a proper alarm notification
        notifications.showAlert(active)
    }

    /**
     * Called when user explicitly presses "Dismiss" (from notification button or AlarmActivity).
     * This is the ONLY code path that ends an alarm.
     */
    private fun handleDismiss(stationId: String) {
        Logger.log("ALERT_DISMISSED", stationId)

        // Stop audio immediately for UX — but do NOT remove from alertingStationIds yet.
        // Race window: DB still shows ALERTING until dismissStation's async write lands.
        // Clearing alertingStationIds here lets any syncWithDatabase re-emission during
        // that window see the station as "newly alerting" and call fireAlert again.
        // The noLongerAlerting path in syncWithDatabase removes it once DB confirms PAUSED.
        val isLast = alertingStationIds.size == 1 && alertingStationIds.contains(stationId)
        if (isLast) {
            audio.stopAll()
            wakeLocks.releaseAlarm()
            notifications.cancelAudioFailureNotification()
            // Reset Crashlytics context so crashes after dismissal don't get
            // mis-attributed to a stale alarm. Best-effort; never throws.
            if (!BuildConfig.DEBUG) {
                try {
                    FirebaseCrashlytics.getInstance().setCustomKey("alarm_station_id", "none")
                } catch (_: Exception) { /* Firebase may not be initialized */ }
            }
        }

        notifications.cancelAlert(stationId)
        StationRepository.dismissStation(stationId)
    }

    /**
     * Plays the user's configured alarm sound for ~3 seconds, then stops.
     * Triggered from Settings > Alarm Sound > "Test alarm" so the user can
     * verify what the alarm will actually sound like before relying on it.
     * Stops the service afterwards if there's no real work pending — we don't
     * want a stray foreground notification lingering after a quick test.
     */
    private fun handleTestAlarm() {
        serviceScope.launch {
            val uriStr = cachedAlarmSoundUri ?: try {
                UserPreferences.alarmSoundUriFlow.first()
            } catch (_: Exception) { "" }
            val ringSpeaker = cachedRingSpeakerWithHeadphones ?: try {
                UserPreferences.ringSpeakerWithHeadphonesFlow.first()
            } catch (_: Exception) { true }
            val customUri = resolveValidatedAlarmUri(uriStr)
            Logger.log("TEST_ALARM_STARTED")
            audio.playAlarmSound(customUri, ringSpeaker, false, 0, null)
            delay(3_000L)
            audio.stopAll()
            Logger.log("TEST_ALARM_STOPPED")
            if (monitoringStations.isEmpty() && alertingStationIds.isEmpty()) {
                stopSelf()
            }
        }
    }

    /**
     * Fired by the "Stop all" action on the ongoing foreground notification.
     * Dismisses every monitoring and alerting station via the existing
     * [StationRepository.dismissStation] path — which flips each row to PAUSED
     * and removes its geofences. syncWithDatabase then drives teardown and
     * calls stopSelf() when the service has nothing left to do.
     */
    private fun handleStopAll() {
        val monitoringIds = monitoringStations.keys
        val alertingIds = alertingStationIds.toList()
        val allIds = (monitoringIds + alertingIds).distinct()
        Logger.log("SERVICE_STOP_ALL_REQUESTED", extra = "count=${allIds.size}")

        // Stop any ringing audio immediately so the user gets instant feedback
        // even before the Flow-driven teardown completes.
        if (alertingIds.isNotEmpty()) {
            audio.stopAll()
            wakeLocks.releaseAlarm()
            notifications.cancelAudioFailureNotification()
            for (id in alertingIds) notifications.cancelAlert(id)
        }

        for (id in allIds) {
            StationRepository.dismissStation(id)
        }
    }

    // ============================
    //    NOTIFICATION ENGINE
    // ============================

    private fun updateForegroundNotification(forceStartForeground: Boolean = false) {
        val text = when {
            monitoringStations.isNotEmpty() -> {
                val nearest = monitoringStations.values.minByOrNull { it.currentDistanceKm ?: Double.MAX_VALUE }
                val dist = nearest?.currentDistanceKm
                if (dist != null) {
                    val distStr = "%.1f".format(dist)
                    "Nearest station: ${nearest.getStation()?.name ?: "..."} — $distStr km"
                } else {
                    "Monitoring ${monitoringStations.size} station(s)..."
                }
            }
            alertingStationIds.isNotEmpty() -> {
                "Alarm ringing — ${alertingStationIds.size} station(s) reached"
            }
            else -> "Monitoring stations..."
        }
        val notif = notifications.buildForeground(text)

        val newIsLocationActive = monitoringStations.isNotEmpty()
        val typeChanged = locationForegroundActive != newIsLocationActive
        locationForegroundActive = newIsLocationActive

        if (forceStartForeground || typeChanged) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val fgType = if (locationForegroundActive)
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                else
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                startForeground(ServiceNotifications.FOREGROUND_NOTIFICATION_ID, notif, fgType)
            } else {
                startForeground(ServiceNotifications.FOREGROUND_NOTIFICATION_ID, notif)
            }
        } else {
            notifications.notifyForeground(notif)
        }
    }

    // ============================
    //     GPS ENGINE
    // ============================

    private fun adjustPollingInterval() {
        if (monitoringStations.isEmpty()) return

        val minDistance = monitoringStations.values.minOfOrNull { it.currentDistanceKm ?: Double.MAX_VALUE } ?: return

        val newInterval = when {
            minDistance <= 5.0 -> 10_000L       // <= 5 km -> 10s (Fast-track)
            minDistance <= 15.0 -> 30_000L      // 5 - 15 km -> 30s
            minDistance <= 30.0 -> 60_000L      // 15 - 30 km -> 1m
            minDistance <= 60.0 -> 300_000L     // 30 - 60 km -> 5m
            else -> 600_000L                   // > 60 km -> 10m
        }

        // Determine optimal FLP priority based on distance.
        // At >100km, cell/WiFi triangulation (balanced power) is accurate enough
        // for gearbox decisions and avoids powering up the GPS antenna entirely.
        val newPriority = if (minDistance > 100.0)
            Priority.PRIORITY_BALANCED_POWER_ACCURACY
        else
            Priority.PRIORITY_HIGH_ACCURACY

        val intervalChanged = newInterval != currentPollingIntervalMs
        val priorityChanged = newPriority != currentLocationPriority

        if (intervalChanged || priorityChanged) {
            currentPollingIntervalMs = newInterval
            currentLocationPriority = newPriority
            val priorityLabel = if (newPriority == Priority.PRIORITY_HIGH_ACCURACY) "HIGH" else "BALANCED"
            
            // FIX: If we are entering a long-interval gear (>= 1 minute), the 30s Emergency Coarse 
            // Fallback is a battery killer. Disarm it. The watchdog will dynamically re-arm it 
            // later if FLP actually stalls for 3x the long interval.
            if (newInterval >= 60_000L) {
                gpsCoordinator.disarmCoarseFallback()
            }
            
            Logger.log("MODE_CHANGED", extra = "interval=${newInterval}ms, priority=$priorityLabel, minDistance=$minDistance")
            restartLocationUpdates()
        }
    }

    private fun startLocationUpdates() {
        if (monitoringStations.isEmpty()) return
        if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return
        }

        // During cold-start, poll aggressively at 5s HIGH_ACCURACY regardless
        // of distance gear — we need a real position to determine which gear.
        val effectivePriority = if (gpsCoordinator.isColdStartActive)
            Priority.PRIORITY_HIGH_ACCURACY
        else
            currentLocationPriority

        val effectiveInterval = if (gpsCoordinator.isColdStartActive)
            5_000L
        else
            currentPollingIntervalMs

        val request = LocationRequest.Builder(effectivePriority, effectiveInterval)
            .setMinUpdateIntervalMillis(effectiveInterval / 2)
            .setMaxUpdateDelayMillis(effectiveInterval * 2)   // Bounds staleness; lets OS batch at long gears
            .setWaitForAccurateLocation(false)                // Don't withhold rough fixes — something > nothing
            .build()
        locationRequest = request

        // Configure the guardian so it can replay request+callback after nuclear reset
        flpGuardian.configure(
            request = request,
            callbackFactory = { createLocationCallback() },
            callback = locationCallback
        )
        flpGuardian.startLocationUpdates()

        isPolling = true
        wakeLocks.acquireGps()
        val priorityLabel = if (effectivePriority == Priority.PRIORITY_HIGH_ACCURACY) "HIGH" else "BALANCED"
        Logger.log("GPS_STARTED", extra = "interval=$effectiveInterval, priority=$priorityLabel, coldStart=${gpsCoordinator.isColdStartActive}")

        // Start GNSS satellite monitoring for telemetry
        gpsCoordinator.startGnssMonitoring()

        // If this is the very first poll (no fix yet), start cold-start hunt
        if (lastLocationTimeMs == 0L && !gpsCoordinator.isColdStartActive) {
            gpsCoordinator.startColdStartHunt()
        }
    }

    private fun restartLocationUpdates() {
        if (!isPolling) return
        flpGuardian.stopLocationUpdates()
        startLocationUpdates()
    }

    private fun stopLocationUpdates() {
        if (isPolling) {
            flpGuardian.stopLocationUpdates()
            gpsCoordinator.disarmCoarseFallback()
            gpsCoordinator.stopGnssMonitoring()
            isPolling = false
            watchdogHandler.removeCallbacks(watchdogRunnable)
            wakeLocks.releaseGps()
            Logger.log("GPS_STOPPED")
        }
    }

    private fun getLastKnownLocation(callback: (Location?) -> Unit) {
        if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            callback(null)
            return
        }
        fusedLocationClient.lastLocation.addOnSuccessListener { location ->
            callback(location)
        }.addOnFailureListener {
            callback(null)
        }
    }

    // ============================
    //       UTILITIES
    // ============================

    /**
     * Parses and validates the cached custom alarm URI. Returns null (→ default
     * sound) when the URI string is blank, malformed, or no longer readable
     * (e.g., SAF persistable permission revoked after reboot, local file
     * deleted). Errors are logged but never thrown — the alarm MUST fire.
     */
    private fun resolveValidatedAlarmUri(uriStr: String): android.net.Uri? {
        if (uriStr.isBlank()) return null
        return try {
            val uri = android.net.Uri.parse(uriStr)
            // Cheap read probe — opens and immediately closes the stream. If the
            // URI is unreadable, this throws and we fall back to default.
            contentResolver.openInputStream(uri)?.use { /* probe only */ }
                ?: run {
                    Logger.log("ALARM_URI_UNREADABLE", extra = "null stream for $uri")
                    return null
                }
            uri
        } catch (e: Exception) {
            Logger.log("ALARM_URI_INVALID", extra = e.message ?: "parse/read failed")
            null
        }
    }

    private fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val results = FloatArray(1)
        Location.distanceBetween(lat1, lon1, lat2, lon2, results)
        return (results[0] / 1000.0)
    }

    // ============================
    //     WATCHDOG ENGINE
    // ============================

    private fun handleWatchdogTick() {
        val now = System.currentTimeMillis()
        val stallMs = now - lastLocationTimeMs
        val tier1Threshold = maxOf(60_000L, currentPollingIntervalMs * 3)
        if (stallMs <= tier1Threshold) return

        val tier2Threshold = maxOf(180_000L, currentPollingIntervalMs * 2)
        val gpsOff = !isGpsProviderEnabled()
        val dozing = isDozingWithScreenOff()

        // Ask the coordinator what escalation action to take
        val stallAction = gpsCoordinator.classifyStall(stallMs, flpGuardian.getConsecutiveFailures())

        // Suppress user-visible escalation while the device is idle with screen
        // off — stalls there are expected behaviour, not a fault. Silent retry
        // only; any arriving fix will clear state via onLocationResult.
        if (dozing && !gpsOff) {
            Logger.log("WATCHDOG_SELF_HEAL", extra = "dozing stall=${stallMs / 1000}s (suppressed)")
            kickSilentRecovery()
            return
        }

        // Act on coordinator's escalation recommendation
        when (stallAction) {
            GpsReliabilityCoordinator.StallAction.ARM_COARSE -> {
                Logger.log("WATCHDOG_ARM_COARSE", extra = "stall=${stallMs / 1000}s")
                gpsCoordinator.armCoarseFallback()
            }
            GpsReliabilityCoordinator.StallAction.NUKE_FLP -> {
                // Guardian handles nuke internally via attemptRecovery()
                Logger.log("WATCHDOG_NUKE_RECOMMENDED", extra = "stall=${stallMs / 1000}s, guardian_failures=${flpGuardian.getConsecutiveFailures()}")
            }
            GpsReliabilityCoordinator.StallAction.NORMAL_RETRY -> { /* fall through to existing tier logic */ }
        }

        if (stallMs >= tier2Threshold || gpsOff) {
            // Tier 2 — notify (ongoing, low-priority, auto-cleared on fix) and
            // keep fighting for a fix. Force polling back to the 10s floor.
            watchdogNotified = true
            watchdogRecoveryPending = true
            if (currentPollingIntervalMs != 10_000L) {
                currentPollingIntervalMs = 10_000L
            }
            val minutes = (stallMs / 60_000L).coerceAtLeast(1)
            val (title, body) = if (gpsOff) {
                "Location is off" to "Tap to enable location — alarm is paused until then."
            } else {
                "Searching for GPS\u2026" to "Still tracking — last fix ${minutes}m ago."
            }
            Logger.log("WATCHDOG_TRIGGERED", extra = "tier2 stall=${stallMs / 1000}s gpsOff=$gpsOff stallAction=$stallAction")
            notifications.showWatchdog(title, body)
            restartLocationUpdates()
            kickSilentRecovery()
        } else {
            // Tier 1 — silent recovery.
            Logger.log("WATCHDOG_SELF_HEAL", extra = "tier1 stall=${stallMs / 1000}s stallAction=$stallAction")
            watchdogRecoveryPending = true
            restartLocationUpdates()
            kickSilentRecovery()
        }
    }

    /**
     * Delegates recovery to the FlpClientGuardian. The guardian tracks
     * consecutive failures internally and performs nuclear reset when
     * thresholds are breached.
     */
    private fun kickSilentRecovery() {
        flpGuardian.attemptRecovery()
    }

    private fun isGpsProviderEnabled(): Boolean {
        val lm = getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return true
        return lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
            lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }

    private fun isDozingWithScreenOff(): Boolean {
        // Only true system doze — prolonged stillness + screen off. Screen-off
        // alone is common on a train ride, so we do NOT suppress on that.
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && pm.isDeviceIdleMode
    }
}
