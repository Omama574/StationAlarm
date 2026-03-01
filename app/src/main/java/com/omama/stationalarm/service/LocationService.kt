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
import android.os.PowerManager
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.*
import com.omama.stationalarm.MainActivity
import com.omama.stationalarm.data.ActiveStation
import com.omama.stationalarm.repository.StationRepository
import com.omama.stationalarm.ui.screens.AlarmActivity
import com.omama.stationalarm.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.util.concurrent.CopyOnWriteArraySet

class LocationService : Service() {

    private val TAG = "LocationService"
    private val NOTIFICATION_ID = 12345
    private val CHANNEL_ID = "location_channel"
    private val CHANNEL_NAME = "Station Alarm Service"
    private val ALARM_CHANNEL_ID = "alarm_channel"
    private val ALARM_CHANNEL_NAME = "Station Alarm Alerts"

    private var wakeLock: PowerManager.WakeLock? = null

    companion object {
        const val ACTION_GEOFENCE_TRIGGERED = "com.omama.stationalarm.ACTION_GEOFENCE_TRIGGERED"
        const val ACTION_START_FOR_ACTIVE_STATIONS = "START_FOR_ACTIVE_STATIONS"
        const val ACTION_DISMISS_ALARM = "com.omama.stationalarm.ACTION_DISMISS_ALARM"
    }

    private val trackedStations = CopyOnWriteArraySet<ActiveStation>()

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var locationRequest: LocationRequest? = null
    private lateinit var locationCallback: LocationCallback

    private var isPolling = false
    private var currentPollingIntervalMs = 300_000L

    private var mediaPlayer: MediaPlayer? = null
    private var alarmRinging = false
    private val alertingStations = mutableSetOf<String>()
    
    private var serviceJob = kotlinx.coroutines.Job()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)

    private var lastLocationTimeMs = 0L
    private val watchdogHandler = Handler(Looper.getMainLooper())
    private val watchdogRunnable = object : Runnable {
        override fun run() {
            if (isPolling && trackedStations.isNotEmpty()) {
                val timeSinceLastMs = System.currentTimeMillis() - lastLocationTimeMs
                if (timeSinceLastMs > 10 * 60 * 1000L) {
                    Logger.log("WATCHDOG_TRIGGERED", extra = "GPS stalled for ${timeSinceLastMs / 1000}s. Restarting.")
                    showWatchdogNotification()
                    restartLocationUpdates()
                }
            }
            watchdogHandler.postDelayed(this, 5 * 60 * 1000L) // Check every 5 minutes
        }
    }

    private var audioFocusRequest: AudioFocusRequest? = null
    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                Log.d(TAG, "Audio focus lost completely")
                stopAlarmSound()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                Log.d(TAG, "Audio focus lost transiently")
                if (mediaPlayer?.isPlaying == true) {
                    mediaPlayer?.pause()
                }
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                Log.d(TAG, "Audio focus gained")
                if (alarmRinging && mediaPlayer?.isPlaying == false) {
                    mediaPlayer?.start()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                lastLocationTimeMs = System.currentTimeMillis()
                // Check permission using the service instance
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

    private fun syncWithDatabase() {
        serviceScope.launch {
            StationRepository.activeStationsFlow.collect { activeFromDb ->
                val activeIds = activeFromDb.map { it.stationId }.toSet()
                
                // 1. Prune trackedStations
                val toRemoveFromTracking = trackedStations.filter { it.stationId !in activeIds }
                if (toRemoveFromTracking.isNotEmpty()) {
                    trackedStations.removeAll(toRemoveFromTracking.toSet())
                    Logger.log("SYNC_PRUNED_TRACKING", extra = "Removed ${toRemoveFromTracking.size} stations")
                }

                // 2. Prune alertingStations (crucial if user deletes while ringing)
                val toRemoveFromAlerting = alertingStations.filter { it !in activeIds }
                if (toRemoveFromAlerting.isNotEmpty()) {
                    alertingStations.removeAll(toRemoveFromAlerting.toSet())
                    Logger.log("SYNC_PRUNED_ALERTING", extra = "Cleaned up ${toRemoveFromAlerting.size} dead alerts")
                }

                // 3. Update Notification or Stop Service
                if (trackedStations.isEmpty() && !alarmRinging) {
                    Logger.log("SERVICE_NUMBED", extra = "Stopping as 0 stations remain active")
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                } else if (trackedStations.isNotEmpty()) {
                    // Force refresh notification to clear any stale text
                    updateForegroundNotification()
                }
            }
        }
    }

    private fun updateForegroundNotification() {
        val minDistance = trackedStations.minOfOrNull { it.currentDistanceKm ?: Double.MAX_VALUE }
        if (minDistance != null && minDistance != Double.MAX_VALUE) {
            val nearest = trackedStations.minByOrNull { it.currentDistanceKm ?: Double.MAX_VALUE }
            val distStr = "%.1f".format(minDistance)
            val notif = createNotification("Nearest station: ${nearest?.getStation()?.name ?: "..."} — $distStr km")
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(NOTIFICATION_ID, notif)
        } else {
            val notif = createNotification("Monitoring ${trackedStations.size} stations...")
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(NOTIFICATION_ID, notif)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_GEOFENCE_TRIGGERED -> {
                val stationId = intent.getStringExtra("stationId") ?: return START_STICKY
                val layer = intent.getStringExtra("layer")
                Logger.log("SERVICE_GEOFENCE_RECEIVED", stationId, layer)
                addStationToTracking(stationId)
            }
            ACTION_START_FOR_ACTIVE_STATIONS -> {
                initializeTrackingFromLastKnownLocation()
            }
            ACTION_DISMISS_ALARM -> {
                val stationId = intent.getStringExtra("stationId") ?: return START_STICKY
                dismissAlarm(stationId)
                return START_STICKY
            }
        }

        startForeground(NOTIFICATION_ID, createNotification("Monitoring stations..."))

        if (!isPolling && trackedStations.isNotEmpty()) {
            startLocationUpdates()
            lastLocationTimeMs = System.currentTimeMillis()
            watchdogHandler.postDelayed(watchdogRunnable, 5 * 60 * 1000L)
        }

        return START_STICKY
    }

    private fun addStationToTracking(stationId: String) {
        val station = StationRepository.getStationById(stationId) ?: return
        CoroutineScope(Dispatchers.Main).launch {
            val allActive = StationRepository.getAllActiveStationsList()
            val active = allActive.find { it.stationId == stationId } ?: return@launch
            if (trackedStations.none { it.stationId == stationId }) {
                trackedStations.add(active)
                Logger.log("STATION_ADDED_TO_TRACKING", stationId)
                getLastKnownLocation { loc ->
                    if (loc != null) {
                        val distance = calculateDistance(loc.latitude, loc.longitude, station.lat, station.lon)
                        active.currentDistanceKm = distance
                        adjustPollingInterval()
                    }
                }
            }
        }
    }

    private fun initializeTrackingFromLastKnownLocation() {
        getLastKnownLocation { location ->
            if (location == null) return@getLastKnownLocation
            CoroutineScope(Dispatchers.Main).launch {
                val allActive = StationRepository.getAllActiveStationsList()
                for (active in allActive) {
                    val station = active.getStation() ?: continue
                    val distance = calculateDistance(location.latitude, location.longitude, station.lat, station.lon)
                    if (distance <= active.radiusLevel5Km) {
                        trackedStations.add(active)
                        active.currentDistanceKm = distance
                        Logger.log("STATION_ADDED_TO_TRACKING", active.stationId, "initial distance=$distance")
                    }
                }
                if (trackedStations.isNotEmpty()) {
                    adjustPollingInterval()
                    if (!isPolling) startLocationUpdates()
                }
            }
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

    private fun processLocationUpdate(location: Location) {
        var minDistance = Double.MAX_VALUE
        var nearestStationName = "None"
        var nearestStationId: String? = null

        for (active in trackedStations) {
            val station = active.getStation() ?: continue
            val distance = calculateDistance(location.latitude, location.longitude, station.lat, station.lon)
            active.currentDistanceKm = distance
            StationRepository.updateStationDistance(active.stationId, distance)
            
            if (distance < minDistance) {
                minDistance = distance
                nearestStationName = station.name
                nearestStationId = active.stationId
            }

            if (distance <= active.alertDistanceKm && !alertingStations.contains(active.stationId)) {
                triggerAlert(active)
            }
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

        // Update ongoing notification silently if we have a nearest station
        if (trackedStations.isNotEmpty() && minDistance != Double.MAX_VALUE) {
            val distStr = "%.1f".format(minDistance)
            val notif = createNotification("Nearest station: $nearestStationName — $distStr km")
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(NOTIFICATION_ID, notif)
        }

        val toRemove = mutableListOf<ActiveStation>()
        for (active in trackedStations) {
            val distance = active.currentDistanceKm ?: continue
            if (distance > active.radiusLevel5Km) {
                toRemove.add(active)
                Logger.log("STATION_REMOVED_FROM_TRACKING", active.stationId, "distance=$distance > outer")
            }
        }
        trackedStations.removeAll(toRemove)

        adjustPollingInterval()

        if (trackedStations.isEmpty() && !alarmRinging) {
            Logger.log("SERVICE_STOPPED", extra = "No stations tracked")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun adjustPollingInterval() {
        if (trackedStations.isEmpty()) return

        val minDistance = trackedStations.minOfOrNull { it.currentDistanceKm ?: Double.MAX_VALUE } ?: return
        
        val newInterval = when {
            minDistance <= 5.0 -> 30_000L      // <= 5 km -> 30s
            minDistance <= 15.0 -> 60_000L     // 5 - 15 km -> 60s
            minDistance <= 30.0 -> 120_000L    // 15 - 30 km -> 2m
            minDistance <= 60.0 -> 300_000L    // 30 - 60 km -> 5m
            else -> 600_000L                   // > 60 km -> 10m
        }

        if (newInterval != currentPollingIntervalMs) {
            currentPollingIntervalMs = newInterval
            Logger.log("MODE_CHANGED", extra = "interval=${newInterval}ms, minDistance=$minDistance")
            restartLocationUpdates()
        }
    }

    private fun startLocationUpdates() {
        if (trackedStations.isEmpty()) return
        if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return
        }
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, currentPollingIntervalMs)
            .setMinUpdateIntervalMillis(currentPollingIntervalMs / 2)
            .build()
        locationRequest = request
        fusedLocationClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
        isPolling = true
        Logger.log("SERVICE_STARTED", extra = "interval=$currentPollingIntervalMs")
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
        }
    }

    private fun triggerAlert(active: ActiveStation) {
        if (alertingStations.contains(active.stationId)) return
        
        // Final sanity check: is it still in the DB?
        serviceScope.launch {
            if (!StationRepository.isActive(active.stationId)) {
                Logger.log("ALERT_ABORTED", active.stationId, "Station was deleted just before trigger")
                return@launch
            }

            alertingStations.add(active.stationId)
            Logger.log("ALERT_TRIGGERED", active.stationId, "distance=${active.currentDistanceKm}")

            acquireWakeLock()

            if (active.sound) {
                playAlarmSound()
            }

            showAlertNotification(active)
            trackedStations.remove(active)
            StationRepository.removeActiveStation(active.stationId)

            if (trackedStations.isEmpty()) {
                stopLocationUpdates()
            }
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "StationAlarm::AlarmWakeLock"
            )
        }
        wakeLock?.acquire(10 * 60 * 1000L /*10 minutes*/)
    }

    private fun releaseWakeLock() {
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
    }

    private fun playAlarmSound() {
        if (alarmRinging) return
        
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
            
        val focusResult = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
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

        if (focusResult != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            Logger.log("ERROR", extra = "Audio focus denied for alarm")
            // Even if denied, we can proceed to show heads up UI, but sound may conflict.
        }

        alarmRinging = true
        try {
            val alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            if (alarmUri != null) {
                mediaPlayer = MediaPlayer.create(this, alarmUri).apply {
                    isLooping = true
                    setAudioAttributes(audioAttributes)
                    setVolume(1.0f, 1.0f)
                    val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
                    audioManager.setStreamVolume(AudioManager.STREAM_ALARM, maxVolume, 0)
                    start()
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

    private fun dismissAlarm(stationId: String) {
        Logger.log("ALERT_DISMISSED", stationId)
        stopAlarmSound()
        releaseWakeLock()
        
        // Remove from memory immediately so numbing logic triggers
        trackedStations.removeAll { it.stationId == stationId }
        alertingStations.remove(stationId)
        
        StationRepository.removeActiveStation(stationId)
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(stationId.hashCode())

        if (trackedStations.isEmpty()) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
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
            .setContentText(bodyText)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setFullScreenIntent(pendingIntent, true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Dismiss", dismissPendingIntent)
            .setDeleteIntent(dismissPendingIntent)

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(active.stationId.hashCode(), builder.build())
    }

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

    private fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val results = FloatArray(1)
        Location.distanceBetween(lat1, lon1, lat2, lon2, results)
        return (results[0] / 1000.0)
    }

    override fun onDestroy() {
        serviceJob.cancel()
        stopLocationUpdates()
        stopAlarmSound()
        releaseWakeLock()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}