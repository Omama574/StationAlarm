package com.omama.stationalarm.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.PowerManager
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.*
import java.util.concurrent.ConcurrentHashMap
import com.omama.stationalarm.MainActivity
import com.omama.stationalarm.data.ActiveStation
import com.omama.stationalarm.repository.StationRepository
import com.omama.stationalarm.ui.screens.AlarmActivity
import com.omama.stationalarm.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

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
 */
class LocationService : Service() {

    private val TAG = "LocationService"
    private val NOTIFICATION_ID = 12345
    private val CHANNEL_ID = "location_channel"
    private val CHANNEL_NAME = "Station Alarm Service"
    private val ALARM_CHANNEL_ID = "alarm_channel"
    private val ALARM_CHANNEL_NAME = "Station Alarm Alerts"

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

    private var mediaPlayer: MediaPlayer? = null
    private var alarmRinging = false
    private var isVibrating = false
    private var alarmTimeoutJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var gpsWakeLock: PowerManager.WakeLock? = null

    private var serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)

    private var lastLocationTimeMs = 0L
    private val watchdogHandler = Handler(Looper.getMainLooper())
    private val watchdogRunnable = object : Runnable {
        override fun run() {
            if (isPolling && monitoringStations.isNotEmpty()) {
                val timeSinceLastMs = System.currentTimeMillis() - lastLocationTimeMs
                val timeoutMs = maxOf(60_000L, currentPollingIntervalMs * 3)
                if (timeSinceLastMs > timeoutMs) {
                    Logger.log("WATCHDOG_TRIGGERED", extra = "GPS stalled for ${timeSinceLastMs / 1000}s. Restarting.")
                    showWatchdogNotification()
                    restartLocationUpdates()
                    lastLocationTimeMs = System.currentTimeMillis()
                }
            }
            watchdogHandler.postDelayed(this, 30_000L)
        }
    }

    private var audioFocusRequest: AudioFocusRequest? = null
    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                // Don't stop alarm on focus loss — user needs this alarm
                Log.d(TAG, "Audio focus lost, keeping alarm alive")
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                Log.d(TAG, "Audio focus lost transiently")
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                Log.d(TAG, "Audio focus gained")
                if (alarmRinging && mediaPlayer?.isPlaying == false) {
                    mediaPlayer?.start()
                }
            }
        }
    }

    // ============================
    //         LIFECYCLE
    // ============================

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
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
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Always ensure we are in the foreground first
        startForeground(NOTIFICATION_ID, createNotification("Monitoring stations..."))

        when (intent?.action) {
            ACTION_GEOFENCE_TRIGGERED -> {
                val stationId = intent.getStringExtra("stationId") ?: return START_STICKY
                val layer = intent.getStringExtra("layer")
                Logger.log("SERVICE_GEOFENCE_RECEIVED", stationId, layer)
                // Wake-up: immediately force a location update to react to proximity change
                restartLocationUpdates()
            }
            ACTION_ALERT_GEOFENCE_TRIGGERED -> {
                val stationId = intent.getStringExtra("stationId") ?: return START_STICKY
                Logger.log("SERVICE_ALERT_GEOFENCE_RECEIVED", stationId)
                // Transition to ALERTING in the DB; sync will handle the rest
                StationRepository.markAlerting(stationId)
            }
            ACTION_START_FOR_ACTIVE_STATIONS -> {
                // Boot / re-initialization: sync handles everything
                Logger.log("SERVICE_INIT_REQUESTED")
            }
            ACTION_DISMISS_ALARM -> {
                val stationId = intent.getStringExtra("stationId") ?: return START_STICKY
                handleDismiss(stationId)
                return START_STICKY
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
        stopAlarms()
        releaseWakeLock()
        releaseGpsWakeLock()
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
                // Remove stations no longer MONITORING
                val removedFromMonitoring = monitoringStations.filter { it.stationId !in dbMonitoringIds }
                if (removedFromMonitoring.isNotEmpty()) {
                    monitoringStations.removeAll { it.stationId !in dbMonitoringIds }
                    Logger.log("SYNC_MONITORING_PRUNED", extra = "Removed ${removedFromMonitoring.size}")
                }
                // Add new MONITORING stations
                val currentMonitoringIds = monitoringStations.map { it.stationId }.toSet()
                val newMonitoring = dbMonitoring.filter { it.stationId !in currentMonitoringIds }
                if (newMonitoring.isNotEmpty()) {
                    monitoringStations.addAll(newMonitoring)
                    Logger.log("SYNC_MONITORING_ADDED", extra = "Added ${newMonitoring.size}")
                }

                // --- 2. Sync ALERTING list ---
                val newlyAlerting = dbAlertingIds - alertingStationIds
                val noLongerAlerting = alertingStationIds - dbAlertingIds

                // Handle newly alerting stations
                for (id in newlyAlerting) {
                    val station = dbAlerting.find { it.stationId == id } ?: continue
                    alertingStationIds.add(id)
                    fireAlert(station)
                }

                // Handle stations that were alerting but are now gone (user deleted from UI)
                if (noLongerAlerting.isNotEmpty()) {
                    alertingStationIds.removeAll(noLongerAlerting)
                    // If no more alerting stations, stop the sound
                    if (alertingStationIds.isEmpty()) {
                        stopAlarms()
                        releaseWakeLock()
                    }
                    // Cancel their specific notifications
                    val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    for (id in noLongerAlerting) {
                        nm.cancel(id.hashCode())
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
                    // Immediate location check for newly added stations
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
                    // No monitoring stations: stop GPS
                    stopLocationUpdates()
                }

                // --- 4. Update foreground notification ---
                updateForegroundNotification()

                // --- 5. Service shutdown check ---
                if (monitoringStations.isEmpty() && alertingStationIds.isEmpty()) {
                    Logger.log("SERVICE_STOPPING", extra = "No monitoring, no alerting stations")
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
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

            // Re-post alert notifications for any ALERTING stations (handles swipe-away scenario)
            if (alertingStationIds.isNotEmpty()) {
                val allActive = StationRepository.getAllActiveStationsList()
                for (id in alertingStationIds) {
                    val station = allActive.find { it.stationId == id } ?: continue
                    showAlertNotification(station)
                }
            }

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

            // Transition matched stations to ALERTING (via DB, sync will handle the rest)
            for (active in toAlert) {
                StationRepository.markAlerting(active.stationId)
            }

            // Log the poll
            val distToLog = if (minDistance == Double.MAX_VALUE) null else minDistance
            Logger.log(
                eventType = "LOCATION_POLL",
                stationId = nearestStationId,
                latitude = location.latitude,
                longitude = location.longitude,
                distanceKm = distToLog
            )

            // Update ongoing notification
            updateForegroundNotification()

            // Adjust polling interval based on nearest distance
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

        acquireWakeLock()

        val startedNewAlarm = (!alarmRinging && active.sound) || (!isVibrating && active.vibrate)

        if (active.sound && !alarmRinging) {
            playAlarmSound()
        }
        
        if (active.vibrate && !isVibrating) {
            startVibrator()
        }

        if (startedNewAlarm) {
            startAlarmTimeout()
        }

        // Remove from monitoring set (sync already did this, but be safe)
        monitoringStations.removeAll { it.stationId == active.stationId }

        // Remove the foreground "monitoring" notification for this station
        // and replace with a proper alarm notification
        showAlertNotification(active)
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
            stopAlarms()
            releaseWakeLock()
        }

        // 2. Cancel this station's notification
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(stationId.hashCode())

        // 3. Delete from DB (this triggers sync which handles cleanup)
        StationRepository.dismissStation(stationId)
    }

    // ============================
    //    NOTIFICATION ENGINE
    // ============================

    private fun updateForegroundNotification() {
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
        val notif = createNotification(text)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notif)
    }

    private fun showAlertNotification(active: ActiveStation) {
        val fullscreenIntent = Intent(this, AlarmActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("stationId", active.stationId)
        }
        val pendingIntent = PendingIntent.getActivity(
            this, active.stationId.hashCode(), fullscreenIntent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_UPDATE_CURRENT
        )

        val dismissIntent = Intent(this, LocationService::class.java).apply {
            action = ACTION_DISMISS_ALARM
            putExtra("stationId", active.stationId)
        }
        val dismissPendingIntent = PendingIntent.getService(
            this, active.stationId.hashCode(), dismissIntent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_UPDATE_CURRENT
        )

        val customText = active.customReminder?.takeIf { active.sendReminder }
        val bodyText = if (customText.isNullOrBlank()) "You have arrived at your destination." else "Reminder: $customText"

        val builder = NotificationCompat.Builder(this, ALARM_CHANNEL_ID)
            .setContentTitle("Station Reached: ${active.getStation()?.name ?: active.stationId}")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setFullScreenIntent(pendingIntent, true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setOngoing(true) // Prevent swipe-away from dismissing
            .setAutoCancel(false)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Dismiss", dismissPendingIntent)
            .setStyle(NotificationCompat.BigTextStyle().bigText(bodyText))

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(active.stationId.hashCode(), builder.build())
    }

    // ============================
    //     GPS ENGINE
    // ============================

    private fun adjustPollingInterval() {
        if (monitoringStations.isEmpty()) return

        val minDistance = monitoringStations.minOfOrNull { it.currentDistanceKm ?: Double.MAX_VALUE } ?: return

        val newInterval = when {
            minDistance <= 5.0 -> 10_000L       // <= 5 km -> 10s (Fast-track)
            minDistance <= 15.0 -> 30_000L      // 5 - 15 km -> 30s (was 60s)
            minDistance <= 30.0 -> 60_000L      // 15 - 30 km -> 1m (was 2m)
            minDistance <= 60.0 -> 300_000L     // 30 - 60 km -> 2m (was 5m)
            else -> 600_000L                   // > 60 km -> 3m (was 10m)
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
        acquireGpsWakeLock()
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
            releaseGpsWakeLock()
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
    //      AUDIO ENGINE
    // ============================

    private fun playAlarmSound() {
        if (alarmRinging) return

        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                .setAudioAttributes(audioAttributes)
                .setAcceptsDelayedFocusGain(true)
                .setOnAudioFocusChangeListener(audioFocusChangeListener)
                .build()
            audioFocusRequest = request
            audioManager.requestAudioFocus(request)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                audioFocusChangeListener,
                AudioManager.STREAM_ALARM,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE
            )
        }

        alarmRinging = true
        try {
            val alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            if (alarmUri != null) {
                mediaPlayer = MediaPlayer().apply {
                    setDataSource(this@LocationService, alarmUri)
                    setAudioAttributes(audioAttributes)
                    isLooping = true
                    setOnPreparedListener {
                        setVolume(1.0f, 1.0f)
                        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
                        audioManager.setStreamVolume(AudioManager.STREAM_ALARM, maxVolume, 0)
                        start()
                    }
                    prepareAsync()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error playing alarm", e)
        }
    }

    private fun stopAlarmSound() {
        if (alarmRinging) {
            mediaPlayer?.stop()
            mediaPlayer?.release()
            mediaPlayer = null
            alarmRinging = false

            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            } else {
                @Suppress("DEPRECATION")
                audioManager.abandonAudioFocus(audioFocusChangeListener)
            }
        }
    }

    private fun startVibrator() {
        if (isVibrating) return
        isVibrating = true
        val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 1000, 1000), 1))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(longArrayOf(0, 1000, 1000), 1)
        }
    }

    private fun stopVibrator() {
        if (isVibrating) {
            val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            vibrator.cancel()
            isVibrating = false
        }
    }

    private fun startAlarmTimeout() {
        alarmTimeoutJob?.cancel()
        alarmTimeoutJob = serviceScope.launch {
            kotlinx.coroutines.delay(5 * 60 * 1000L) // 5 minutes max duration
            Logger.log("ALARM_TIMEOUT", extra = "Stopping sound/vibration after 5m")
            stopAlarms()
        }
    }

    private fun stopAlarms() {
        stopAlarmSound()
        stopVibrator()
        alarmTimeoutJob?.cancel()
    }

    // ============================
    //     WAKE LOCK
    // ============================

    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "StationAlarm::AlarmWakeLock"
            )
        }
        wakeLock?.acquire(10 * 60 * 1000L)
    }

    private fun releaseWakeLock() {
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
    }

    /**
     * Prevents the CPU from entering sleep states (Light Doze) between GPS polls.
     * Essential for high-speed travel (trains) where 5-10 minutes of "napping"
     * would result in missing the destination by 10-15 kilometers.
     */
    private fun acquireGpsWakeLock() {
        if (gpsWakeLock == null) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            gpsWakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "StationAlarm::GpsWakeLock"
            )
            gpsWakeLock?.setReferenceCounted(false)
        }
        if (gpsWakeLock?.isHeld == false) {
            gpsWakeLock?.acquire(4 * 60 * 60 * 1000L) // 4 hours maximum
            Logger.log("WAKELOCK_ACQUIRED", extra = "GpsWakeLock")
        }
    }

    private fun releaseGpsWakeLock() {
        if (gpsWakeLock?.isHeld == true) {
            gpsWakeLock?.release()
            Logger.log("WAKELOCK_RELEASED", extra = "GpsWakeLock")
        }
    }

    // ============================
    //    NOTIFICATION CHANNELS
    // ============================

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows tracking status"
            }
            val alarmChannel = NotificationChannel(
                ALARM_CHANNEL_ID,
                ALARM_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "High priority alarm alerts"
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
            manager.createNotificationChannel(alarmChannel)
        }
    }

    private fun showWatchdogNotification() {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Location Stalled")
            .setContentText("GPS signal lost. Tap to open app and restart tracking.")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(9999, builder.build())
    }

    private fun createNotification(text: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Station Alarm")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    // ============================
    //       UTILITIES
    // ============================

    private fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val results = FloatArray(1)
        Location.distanceBetween(lat1, lon1, lat2, lon2, results)
        return (results[0] / 1000.0)
    }
}