package com.omama.stationalarm.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.media.AudioAttributes
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.*
import com.omama.stationalarm.MainActivity

class LocationService : Service() {

    private val TAG = "LocationService"
    private val NOTIFICATION_ID = 12345
    private val CHANNEL_ID = "location_channel"
    private val CHANNEL_NAME = "Location Alarm Channel"

    // Vaniyambadi coordinates
    private val targetLat = 12.6787
    private val targetLon = 78.6219
    private val alarmThresholdMeters = 2000f // ring alarm when <= 2km

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationRequest: LocationRequest
    private lateinit var locationCallback: LocationCallback

    private var mediaPlayer: MediaPlayer? = null
    private var alarmRinging = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5000) // 5 seconds
            .setMinUpdateIntervalMillis(2000)
            .build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                locationResult.lastLocation?.let { location ->
                    checkProximityAndAlert(location)
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, createNotification("Monitoring distance to Vaniyambadi..."))
        startLocationUpdates()
        return START_STICKY
    }

    private fun startLocationUpdates() {
        if (ActivityCompat.checkSelfPermission(
                this,
                android.Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            stopSelf()
            return
        }
        fusedLocationClient.requestLocationUpdates(
            locationRequest,
            locationCallback,
            Looper.getMainLooper()
        )
    }

    private fun stopLocationUpdates() {
        fusedLocationClient.removeLocationUpdates(locationCallback)
    }

    private fun checkProximityAndAlert(location: Location) {
        val results = FloatArray(1)
        Location.distanceBetween(
            location.latitude, location.longitude,
            targetLat, targetLon,
            results
        )
        val distance = results[0]

        Log.d(TAG, "Distance to Vaniyambadi: $distance meters")
        updateNotification("Distance: ${distance.toInt()}m")

        if (distance <= alarmThresholdMeters && !alarmRinging) {
            ringAlarm()
        } else if (distance > alarmThresholdMeters && alarmRinging) {
            stopAlarm()
        }
    }

    private fun ringAlarm() {
        Log.d(TAG, "RING ALARM!")
        alarmRinging = true

        try {
            // Get default alarm sound
            var alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            if (alarmUri == null) {
                // Fallback to notification sound
                alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            }

            if (alarmUri != null) {
                mediaPlayer = MediaPlayer.create(this, alarmUri).apply {
                    isLooping = true
                    // Set audio stream type to alarm for proper behavior
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    start()
                }
            } else {
                Log.e(TAG, "No default alarm/notification sound found")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to play alarm", e)
        }

        updateNotification("ARRIVED! Alarm ringing")
    }

    private fun stopAlarm() {
        Log.d(TAG, "Stop alarm")
        alarmRinging = false
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping alarm", e)
        }
        mediaPlayer = null
        updateNotification("Monitoring distance...")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows distance to Vaniyambadi"
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
            .setContentTitle("Vaniyambadi Alarm")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info) // replace with your own icon
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(text: String) {
        val notification = createNotification(text)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
    }

    override fun onDestroy() {
        stopLocationUpdates()
        stopAlarm()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}