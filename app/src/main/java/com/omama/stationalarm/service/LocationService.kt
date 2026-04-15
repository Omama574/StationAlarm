package com.omama.stationalarm.service

import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.ActivityCompat
import com.google.android.gms.location.*
import com.omama.stationalarm.data.ActiveStation
import com.omama.stationalarm.data.UserPreferences
import com.omama.stationalarm.repository.StationRepository
import com.omama.stationalarm.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
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
    }

    // --- In-memory tracking (only MONITORING stations) ---
    private val monitoringStations = ConcurrentHashMap.newKeySet<ActiveStation>()
    // --- In-memory alerting (only ALERTING stations) ---
    private val alertingStationIds = ConcurrentHashMap.newKeySet<String>()

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var locationRequest: LocationRequest? = null
    private lateinit var locationCallback: LocationCallback

    private var isPolling = false
    private var currentPollingIntervalMs = 10_000L // Start fast for initial lock
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
    @Volatile private var cachedAlarmSoundUri: String = ""

    private var lastLocationTimeMs = 0L
    private val watchdogHandler = Handler(Looper.getMainLooper())
    private val watchdogRunnable = object : Runnable {
        override fun run() {
            if (isPolling && monitoringStations.isNotEmpty()) {
                val timeSinceLastMs = System.currentTimeMillis() - lastLocationTimeMs
                val timeoutMs = maxOf(60_000L, currentPollingIntervalMs * 3)
                if (timeSinceLastMs > timeoutMs) {
                    Logger.log("WATCHDOG_TRIGGERED", extra = "GPS stalled for ${timeSinceLastMs / 1000}s. Restarting.")
                    notifications.showWatchdog()
                    restartLocationUpdates()
                    lastLocationTimeMs = System.currentTimeMillis()
                }
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
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                lastLocationTimeMs = System.currentTimeMillis()
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
                    processLocationUpdate(location)
                }
            }
        }

        syncWithDatabase()
        observeAlarmSoundUri()
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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DISMISS_ALARM) {
            val stationId = intent.getStringExtra("stationId") ?: return START_NOT_STICKY
            handleDismiss(stationId)
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
        audio.stopAll()
        wakeLocks.releaseAlarm()
        wakeLocks.releaseGps()
        serviceJob.cancel()
        watchdogHandler.removeCallbacks(watchdogRunnable)
        super.onDestroy()
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
            StationRepository.activeStationsFlow.collect { allFromDb ->

                // --- Partition by status ---
                val dbMonitoring = allFromDb.filter { it.status == "MONITORING" }
                val dbAlerting = allFromDb.filter { it.status == "ALERTING" }
                val dbMonitoringIds = dbMonitoring.map { it.stationId }.toSet()
                val dbAlertingIds = dbAlerting.map { it.stationId }.toSet()

                // --- 1. Sync MONITORING list ---
                val removedFromMonitoring = monitoringStations.filter { it.stationId !in dbMonitoringIds }
                if (removedFromMonitoring.isNotEmpty()) {
                    monitoringStations.removeAll { it.stationId !in dbMonitoringIds }
                    Logger.log("SYNC_MONITORING_PRUNED", extra = "Removed ${removedFromMonitoring.size}")
                }
                val currentMonitoringIds = monitoringStations.map { it.stationId }.toSet()
                val newMonitoring = dbMonitoring.filter { it.stationId !in currentMonitoringIds }
                if (newMonitoring.isNotEmpty()) {
                    monitoringStations.addAll(newMonitoring)
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
        serviceScope.launch(Dispatchers.Default) {
            if (monitoringStations.isEmpty()) return@launch

            var minDistance = Double.MAX_VALUE
            var nearestStationName = "None"
            var nearestStationId: String? = null

            val toAlert = mutableListOf<ActiveStation>()
            val newDistances = mutableMapOf<String, Double>()

            for (active in monitoringStations.toList()) {
                val station = active.getStation() ?: continue
                val distance = calculateDistance(location.latitude, location.longitude, station.lat, station.lon)
                active.currentDistanceKm = distance
                newDistances[active.stationId] = distance

                if (distance < minDistance) {
                    minDistance = distance
                    nearestStationName = station.name
                    nearestStationId = active.stationId
                }

                // Accuracy buffer: if (distance - accuracy) is within alert range, fire
                val accuracyKm = location.accuracy / 1000.0
                val effectiveDistance = distance - accuracyKm

                if (effectiveDistance <= active.alertDistanceKm && !alertingStationIds.contains(active.stationId)) {
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

            com.omama.stationalarm.util.GpsLogger.logLocation(location, currentPollingIntervalMs)

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
    private fun fireAlert(active: ActiveStation) {
        Logger.log("ALERT_FIRED", active.stationId, "distance=${active.currentDistanceKm}")

        wakeLocks.acquireAlarm()

        val startedNewAlarm = (!audio.isAlarmRinging && active.sound) || (!audio.isVibrating && active.vibrate)

        if (active.sound && !audio.isAlarmRinging) {
            // Use the cached URI (populated by observeAlarmSoundUri). Also
            // validates readability — persisted SAF permissions are sometimes
            // revoked by the OS after reboot, so a URI that worked at pick
            // time may no longer resolve. Null → default sound.
            val customUri: android.net.Uri? = resolveValidatedAlarmUri(cachedAlarmSoundUri)
            audio.playAlarmSound(customUri)
        }

        if (active.vibrate && !audio.isVibrating) {
            audio.startVibrator()
        }

        if (startedNewAlarm) {
            audio.startTimeout(serviceScope) {
                Logger.log("ALARM_TIMEOUT", extra = "Auto-dismissing all alerting stations after 5m")
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
        monitoringStations.removeAll { it.stationId == active.stationId }

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

        // 1. Stop sound if this was the alerting station
        alertingStationIds.remove(stationId)
        if (alertingStationIds.isEmpty()) {
            audio.stopAll()
            wakeLocks.releaseAlarm()
        }

        // 2. Cancel this station's notification
        notifications.cancelAlert(stationId)

        // 3. Delete from DB (this triggers sync which handles cleanup)
        StationRepository.dismissStation(stationId)

        // 4. Wait natively for DB Sync to handle service teardown
        // As soon as the Flow updates, syncWithDatabase will invoke stopSelf() if empty.
    }

    // ============================
    //    NOTIFICATION ENGINE
    // ============================

    private fun updateForegroundNotification(forceStartForeground: Boolean = false) {
        val text = when {
            monitoringStations.isNotEmpty() -> {
                val nearest = monitoringStations.minByOrNull { it.currentDistanceKm ?: Double.MAX_VALUE }
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

        val minDistance = monitoringStations.minOfOrNull { it.currentDistanceKm ?: Double.MAX_VALUE } ?: return

        val newInterval = when {
            minDistance <= 5.0 -> 10_000L       // <= 5 km -> 10s (Fast-track)
            minDistance <= 15.0 -> 30_000L      // 5 - 15 km -> 30s
            minDistance <= 30.0 -> 60_000L      // 15 - 30 km -> 1m
            minDistance <= 60.0 -> 300_000L     // 30 - 60 km -> 5m
            else -> 600_000L                   // > 60 km -> 10m
        }

        if (newInterval != currentPollingIntervalMs) {
            currentPollingIntervalMs = newInterval
            Logger.log("MODE_CHANGED", extra = "interval=${newInterval}ms, minDistance=$minDistance")
            restartLocationUpdates()
        }
    }

    private fun startLocationUpdates() {
        if (monitoringStations.isEmpty()) return
        if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return
        }
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, currentPollingIntervalMs)
            .setMinUpdateIntervalMillis(currentPollingIntervalMs / 2)
            .build()
        locationRequest = request
        fusedLocationClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
        isPolling = true
        wakeLocks.acquireGps()
        Logger.log("GPS_STARTED", extra = "interval=$currentPollingIntervalMs")
    }

    private fun restartLocationUpdates() {
        if (!isPolling) return
        fusedLocationClient.removeLocationUpdates(locationCallback)
        startLocationUpdates()
    }

    private fun stopLocationUpdates() {
        if (isPolling) {
            fusedLocationClient.removeLocationUpdates(locationCallback)
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
}
