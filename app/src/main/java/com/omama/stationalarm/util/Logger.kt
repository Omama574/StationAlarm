package com.omama.stationalarm.util

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors

object Logger {

    private const val TAG = "Logger"
    private const val LOG_DIR = "TrainAlarmLogs"
    private const val FILE_PREFIX = "TrainAlert_"
    private const val FILE_SUFFIX = ".csv"

    private val executor = Executors.newSingleThreadExecutor()
    private var contextRef: Context? = null
    private var sessionStartTime: String = ""
    private var currentBufferedWriter: java.io.BufferedWriter? = null
    private var currentOutputStream: java.io.OutputStream? = null

    fun initialize(context: Context) {
        contextRef = context.applicationContext
        createNewLogFile()
    }

    private fun createNewLogFile() {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        sessionStartTime = timeStamp
        val fileName = "$FILE_PREFIX$timeStamp$FILE_SUFFIX"

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = contextRef?.contentResolver ?: return
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/csv")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + LOG_DIR)
                }
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                if (uri != null) {
                    currentOutputStream = resolver.openOutputStream(uri)
                }
            } else {
                @Suppress("DEPRECATION")
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val logDir = File(downloadsDir, LOG_DIR)
                if (!logDir.exists()) logDir.mkdirs()
                val file = File(logDir, fileName)
                currentOutputStream = file.outputStream()
            }
            currentBufferedWriter = currentOutputStream?.bufferedWriter()
            writeHeader()
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to create log file", e)
        }
    }

    private fun writeHeader() {
        val header = "timestamp,eventType,stationId,latitude,longitude,distanceKm,batteryPercent,deviceModel,manufacturer,androidVersion,extra\n"
        currentBufferedWriter?.write(header)
        currentBufferedWriter?.flush()
    }

    fun log(
        eventType: String,
        stationId: String? = null,
        extra: String? = null,
        latitude: Double? = null,
        longitude: Double? = null,
        distanceKm: Double? = null
    ) {
        executor.execute {
            ensureWriter()
            val context = contextRef ?: return@execute
            val entry = LogEntry.create(
                context = context,
                eventType = eventType,
                stationId = stationId,
                latitude = latitude,
                longitude = longitude,
                distanceKm = distanceKm,
                extra = extra
            )
            try {
                currentBufferedWriter?.write(entry.toCsvLine())
                currentBufferedWriter?.flush()
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Failed to write log", e)
            }
        }
    }

    private fun ensureWriter() {
        if (currentBufferedWriter == null) {
            createNewLogFile()
        }
    }

    fun flushAndClose() {
        executor.execute {
            try {
                currentBufferedWriter?.flush()
                currentBufferedWriter?.close()
            } catch (e: Exception) {
                // ignore
            }
            currentBufferedWriter = null
            currentOutputStream = null
        }
    }

    fun getCurrentLogUri(): Uri? {
        val fileName = "$FILE_PREFIX${sessionStartTime}$FILE_SUFFIX"
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = contextRef?.contentResolver ?: return null
            val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            val projection = arrayOf(MediaStore.MediaColumns._ID)
            val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} = ?"
            val selectionArgs = arrayOf(fileName)
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
                "$LOG_DIR/$fileName"
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
        return Intent.createChooser(intent, "Share Log")
    }
}