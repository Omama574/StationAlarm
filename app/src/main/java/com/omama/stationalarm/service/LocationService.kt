package com.omama.stationalarm.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
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

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
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

        if (!isPolling) {
            startLocationUpdates()
        }

        return START_STICKY
    }

    private fun addStationToTracking(stationId: String) {
        val station = StationRepository.getStationById(stationId) ?: return
        val active = StationRepository.getAllActiveStations().find { it.stationId == stationId } ?: return
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

    private fun initializeTrackingFromLastKnownLocation() {
        getLastKnownLocation { location ->
            if (location == null) return@getLastKnownLocation
            val allActive = StationRepository.getAllActiveStations()
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            stopSelf()
        }
    }

    private fun adjustPollingInterval() {
        if (trackedStations.isEmpty()) return

        val minDistance = trackedStations.minOfOrNull { it.currentDistanceKm ?: Double.MAX_VALUE } ?: return
        val newInterval = when {
            minDistance <= (trackedStations.minOf { it.radiusLevel2Km }) -> 30_000L
            minDistance <= (trackedStations.minOf { it.radiusLevel4Km }) -> 120_000L
            else -> 300_000L
        }

        if (newInterval != currentPollingIntervalMs) {
            currentPollingIntervalMs = newInterval
            Logger.log("MODE_CHANGED", extra = "interval=${newInterval}ms, minDistance=$minDistance")
            restartLocationUpdates()
        }
    }

    private fun startLocationUpdates() {
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
        }
    }

    private fun triggerAlert(active: ActiveStation) {
        if (alertingStations.contains(active.stationId)) return
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
        alarmRinging = true
        try {
            val alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            if (alarmUri != null) {
                mediaPlayer = MediaPlayer.create(this, alarmUri).apply {
                    isLooping = true
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
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
        }
    }

    private fun dismissAlarm(stationId: String) {
        Logger.log("ALERT_DISMISSED", stationId)
        stopAlarmSound()
        releaseWakeLock()
        
        StationRepository.removeActiveStation(stationId)
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(stationId.hashCode())

        if (trackedStations.isEmpty()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
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
        stopLocationUpdates()
        stopAlarmSound()
        releaseWakeLock()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}