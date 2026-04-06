package com.omama.stationalarm.util

import android.content.Context
import android.os.Build
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

data class LogEntry(
    val timestamp: String,
    val eventType: String,
    val stationId: String?,
    val latitude: Double?,
    val longitude: Double?,
    val distanceKm: Double?,
    val batteryPercent: Int,
    val deviceModel: String,
    val manufacturer: String,
    val androidVersion: String,
    val extra: String?
) {
    companion object {
        private val formatter = DateTimeFormatter.ISO_INSTANT

        fun now(): String = formatter.format(Instant.now().atOffset(ZoneOffset.UTC))

        fun getBatteryLevel(context: Context): Int {
            val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager
            return batteryManager.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
        }

        fun create(
            context: Context,
            eventType: String,
            stationId: String? = null,
            latitude: Double? = null,
            longitude: Double? = null,
            distanceKm: Double? = null,
            extra: String? = null
        ): LogEntry {
            return LogEntry(
                timestamp = now(),
                eventType = eventType,
                stationId = stationId,
                latitude = latitude,
                longitude = longitude,
                distanceKm = distanceKm,
                batteryPercent = getBatteryLevel(context),
                deviceModel = Build.MODEL,
                manufacturer = Build.MANUFACTURER,
                androidVersion = Build.VERSION.SDK_INT.toString(),
                extra = extra
            )
        }
    }

    fun toCsvLine(): String {
        return listOf(
            timestamp,
            eventType,
            stationId ?: "",
            latitude?.toString() ?: "",
            longitude?.toString() ?: "",
            distanceKm?.toString() ?: "",
            batteryPercent.toString(),
            deviceModel,
            manufacturer,
            androidVersion,
            extra ?: ""
        ).joinToString(separator = ",", postfix = "\n") { field ->
            if (field.contains(",") || field.contains("\"") || field.contains("\n")) {
                "\"${field.replace("\"", "\"\"")}\""
            } else {
                field
            }
        }
    }
}