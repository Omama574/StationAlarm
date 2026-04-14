package com.omama.stationalarm.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.omama.stationalarm.util.Logger
import java.util.concurrent.TimeUnit

/**
 * Handles BOOT_COMPLETED (and Samsung/HTC QUICKBOOT_POWERON). Instead of doing
 * the DB + geofence restoration inline — which can fail if the repository
 * hasn't initialised yet or if Play Services is still starting — we enqueue a
 * [BootRestoreWorker]. That worker is reliable: WorkManager retries it with
 * exponential backoff until it succeeds.
 */
class BootReceiver : BroadcastReceiver() {
    private val TAG = "BootReceiver"

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != "android.intent.action.QUICKBOOT_POWERON") {
            return
        }
        Log.d(TAG, "Device booted, scheduling alarm restoration worker...")
        Logger.log("SYSTEM_BOOTED", extra = "Enqueueing BootRestoreWorker")

        val request = OneTimeWorkRequestBuilder<BootRestoreWorker>()
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
            .build()

        // KEEP policy: if BOOT_COMPLETED fires twice or the worker is already
        // queued/running, don't duplicate it.
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(BootRestoreWorker.WORK_NAME, ExistingWorkPolicy.KEEP, request)
    }
}
