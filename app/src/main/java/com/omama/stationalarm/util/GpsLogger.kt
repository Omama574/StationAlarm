package com.omama.stationalarm.util

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.location.Location
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors

object GpsLogger {

    private const val TAG = "GpsLogger"
    private const val LOG_DIR = "TrainAlarmLogs"
    private const val FILE_NAME = "GpsThrottle_TestLog.csv"

    private val executor = Executors.newSingleThreadExecutor()
    private var contextRef: Context? = null
    private var currentBufferedWriter: java.io.BufferedWriter? = null
    private var currentOutputStream: java.io.OutputStream? = null

    fun initialize(context: Context) {
        contextRef = context.applicationContext
        openOrCreateLogFile()
    }

    private fun openOrCreateLogFile() {
        try {
            var fileUri: Uri? = null
            var isNewFile = false

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = contextRef?.contentResolver ?: return
                
                val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
                val projection = arrayOf(MediaStore.MediaColumns._ID)
                val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} = ?"
                val selectionArgs = arrayOf(FILE_NAME)
                
                resolver.query(collection, projection, selection, selectionArgs, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val id = cursor.getLong(0)
                        fileUri = Uri.withAppendedPath(collection, id.toString())
                    }
                }

                if (fileUri == null) {
                    val contentValues = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, FILE_NAME)
                        put(MediaStore.MediaColumns.MIME_TYPE, "text/csv")
                        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + LOG_DIR)
                    }
                    fileUri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                    isNewFile = true
                }
                
                if (fileUri != null) {
                    currentOutputStream = resolver.openOutputStream(fileUri!!, "wa")
                }
            } else {
                @Suppress("DEPRECATION")
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val logDir = File(downloadsDir, LOG_DIR)
                if (!logDir.exists()) logDir.mkdirs()
                val file = File(logDir, FILE_NAME)
                isNewFile = !file.exists()
                currentOutputStream = java.io.FileOutputStream(file, true)
            }
            currentBufferedWriter = currentOutputStream?.bufferedWriter()
            if (isNewFile) {
                writeHeader()
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to create/open GPS log file", e)
        }
    }

    private fun writeHeader() {
        val header = "timestamp,provider,latitude,longitude,accuracy_meters,speed_ms,current_polling_interval_ms,battery_percent\n"
        currentBufferedWriter?.write(header)
        currentBufferedWriter?.flush()
    }

    fun logLocation(location: Location, currentIntervalMs: Long) {
        executor.execute {
            ensureWriter()
            val context = contextRef ?: return@execute
            
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply {
                timeZone = TimeZone.getDefault()
            }
            val timestamp = sdf.format(Date(location.time))
            
            val batteryIntent = context.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
            val level = batteryIntent?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = batteryIntent?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1) ?: -1
            val batteryPct = if (level >= 0 && scale > 0) (level * 100f / scale).toInt() else -1

            val line = "$timestamp,${location.provider},${location.latitude},${location.longitude},${location.accuracy},${location.speed},$currentIntervalMs,$batteryPct\n"
            
            try {
                currentBufferedWriter?.write(line)
                currentBufferedWriter?.flush()
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Failed to write GPS log", e)
            }
        }
    }

    private fun ensureWriter() {
        if (currentBufferedWriter == null) {
            openOrCreateLogFile()
        }
    }

    fun getCurrentLogUri(): Uri? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = contextRef?.contentResolver ?: return null
            val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            val projection = arrayOf(MediaStore.MediaColumns._ID)
            val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} = ?"
            val selectionArgs = arrayOf(FILE_NAME)
            resolver.query(collection, projection, selection, selectionArgs, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val id = cursor.getLong(0)
                    return Uri.withAppendedPath(collection, id.toString())
                }
            }
            null
        } else {
            @Suppress("DEPRECATION")
            val file = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "$LOG_DIR/$FILE_NAME"
            )
            if (file.exists()) FileProvider.getUriForFile(contextRef!!, "${contextRef!!.packageName}.fileprovider", file) else null
        }
    }

    fun shareLog(context: Context): Intent? {
        val uri = getCurrentLogUri() ?: return null
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(intent, "Share GPS Log")
    }
}
