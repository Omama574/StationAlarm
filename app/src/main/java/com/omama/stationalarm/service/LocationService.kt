package com.omama.stationalarm.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.*
import com.omama.stationalarm.R
import com.omama.stationalarm.data.Station
import com.omama.stationalarm.repository.StationRepository
import kotlin.math.*

class LocationService : Service() {

    companion object {
        const val CHANNEL_ID = "station_alert_channel"
        const val ACTION_GEOFENCE_TRIGGERED =
            "com.omama.stationalarm.ACTION_GEOFENCE_TRIGGERED"
    }

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback

    override fun onCreate() {
        super.onCreate()

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        createNotificationChannel()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val location = result.lastLocation ?: return
                handleLocationUpdate(location.latitude, location.longitude)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {

        startForeground(
            1,
            buildNotification("Monitoring station proximity...")
        )

        startLocationUpdates()

        return START_STICKY
    }

    private fun startLocationUpdates() {
        val request = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            60_000L
        ).build()

        try {
            fusedLocationClient.requestLocationUpdates(
                request,
                locationCallback,
                mainLooper
            )
        } catch (e: SecurityException) {
            Log.e("LocationService", "Location permission missing")
        }
    }

    private fun handleLocationUpdate(lat: Double, lon: Double) {
        val activeStations = StationRepository.getAllActiveStations()

        if (activeStations.isEmpty()) {
            stopSelf()
            return
        }

        for (active in activeStations) {

            val station: Station? =
                StationRepository.getStationById(active.stationId)

            if (station == null) continue

            val distance = haversineDistance(
                lat,
                lon,
                station.lat,
                station.lon
            )

            Log.d(
                "LocationService",
                "Distance to ${station.name} = $distance km"
            )

            if (distance <= active.alertDistanceKm) {
                triggerAlert(station.name)
                StationRepository.removeStation(active.stationId)
            }
        }

        if (StationRepository.getAllActiveStations().isEmpty()) {
            stopSelf()
        }
    }

    private fun triggerAlert(stationName: String) {
        val notification = buildNotification(
            "You are near $stationName"
        )

        val manager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        manager.notify(
            System.currentTimeMillis().toInt(),
            notification
        )
    }

    private fun haversineDistance(
        lat1: Double,
        lon1: Double,
        lat2: Double,
        lon2: Double
    ): Double {

        val R = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)

        val a =
            sin(dLat / 2).pow(2) +
                    cos(Math.toRadians(lat1)) *
                    cos(Math.toRadians(lat2)) *
                    sin(dLon / 2).pow(2)

        val c = 2 * atan2(sqrt(a), sqrt(1 - a))

        return R * c
    }

    override fun onDestroy() {
        fusedLocationClient.removeLocationUpdates(locationCallback)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("StationAlarm")
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Station Monitoring",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager =
                getSystemService(Context.NOTIFICATION_SERVICE)
                        as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }
}
