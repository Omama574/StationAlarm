package com.omama.stationalarm.service

import android.app.*
import android.content.Context
import android.media.AudioManager
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.*
import com.omama.stationalarm.MainActivity
import com.omama.stationalarm.data.ActiveStation
import com.omama.stationalarm.repository.StationRepository
import com.omama.stationalarm.util.Logger
import java.util.concurrent.CopyOnWriteArraySet

class LocationService : Service() {

    private val TAG = "LocationService"
    private val NOTIFICATION_ID = 12345
    private val CHANNEL_ID = "location_channel"
    private val CHANNEL_NAME = "Station Alarm Service"

    companion object {
        const val ACTION_GEOFENCE_TRIGGERED = "com.omama.stationalarm.ACTION_GEOFENCE_TRIGGERED"
        const val ACTION_START_FOR_ACTIVE_STATIONS = "START_FOR_ACTIVE_STATIONS"
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
                if (distance <= active.outerRadiusKm) {
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
        for (active in trackedStations) {
            val station = active.getStation() ?: continue
            val distance = calculateDistance(location.latitude, location.longitude, station.lat, station.lon)
            active.currentDistanceKm = distance
            StationRepository.updateStationDistance(active.stationId, distance)

            if (distance <= active.alertDistanceKm && !alertingStations.contains(active.stationId)) {
                triggerAlert(active)
            }
        }

        val toRemove = mutableListOf<ActiveStation>()
        for (active in trackedStations) {
            val station = active.getStation() ?: continue
            val distance = active.currentDistanceKm ?: continue
            if (distance > active.outerRadiusKm) {
                toRemove.add(active)
                Logger.log("STATION_REMOVED_FROM_TRACKING", active.stationId, "distance=$distance > outer")
            }
        }
        trackedStations.removeAll(toRemove)

        adjustPollingInterval()

        if (trackedStations.isEmpty()) {
            Logger.log("SERVICE_STOPPED", extra = "No stations tracked")
            stopSelf()
        }
    }

    private fun adjustPollingInterval() {
        if (trackedStations.isEmpty()) return

        val minDistance = trackedStations.minOfOrNull { it.currentDistanceKm ?: Double.MAX_VALUE } ?: return
        val newInterval = when {
            minDistance <= (trackedStations.minOf { it.innerRadiusKm }) -> 30_000L
            minDistance <= (trackedStations.minOf { it.midRadiusKm }) -> 120_000L
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

        StationRepository.removeActiveStation(active.stationId)

        if (active.sound) {
            playAlarmSound()
        }

        showAlertNotification(active)
        trackedStations.remove(active)
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
                    // Set volume to max
                    val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
                    val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
                    setVolume(maxVolume.toFloat(), maxVolume.toFloat())
                    start()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error playing alarm", e)
        }
    }

    private fun stopAlarm() {
        if (alarmRinging) {
            mediaPlayer?.stop()
            mediaPlayer?.release()
            mediaPlayer = null
            alarmRinging = false
        }
    }

    private fun showAlertNotification(active: ActiveStation) {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Station Reached: ${active.getStation()?.name ?: active.stationId}")
            .setContentText("You have arrived. Alarm is ringing.")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)

        if (active.vibrate) {
            builder.setVibrate(longArrayOf(0, 500, 200, 500))
        }

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
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
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
        stopAlarm()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}