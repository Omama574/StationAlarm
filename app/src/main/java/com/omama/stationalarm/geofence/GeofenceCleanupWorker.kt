package com.omama.stationalarm.geofence

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.omama.stationalarm.util.Logger
import java.util.concurrent.TimeUnit

/**
 * Persistent fallback for [GeofenceManager.removeGeofencesForStation]. The
 * in-process retry chain there only covers ~3.5s; if GMS is genuinely down
 * (no network, Play Services updating, etc.) those leaked geofences would
 * count against the 100/app cap forever. This worker keeps trying with
 * exponential backoff until either GMS accepts the removal or we give up
 * after ~10 attempts (~24h of total backoff).
 */
class GeofenceCleanupWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val stationId = inputData.getString(KEY_STATION_ID) ?: return Result.failure()
        val result = GeofenceManager.attemptRemoveGeofencesForStation(applicationContext, stationId)
        return if (result.isSuccess) {
            Logger.log("GEOFENCE_CLEANUP_OK", stationId, "via worker (attempt=$runAttemptCount)")
            Result.success()
        } else {
            if (runAttemptCount >= MAX_ATTEMPTS) {
                Logger.breadcrumb("GEOFENCE_CLEANUP_GIVE_UP", stationId, "after $runAttemptCount attempts")
                Result.failure()
            } else {
                Logger.log("GEOFENCE_CLEANUP_RETRY", stationId, "attempt=$runAttemptCount")
                Result.retry()
            }
        }
    }

    companion object {
        private const val KEY_STATION_ID = "stationId"
        private const val WORK_PREFIX = "geofence_cleanup_"
        private const val MAX_ATTEMPTS = 10

        fun enqueue(context: Context, stationId: String) {
            val req = OneTimeWorkRequestBuilder<GeofenceCleanupWorker>()
                .setInputData(workDataOf(KEY_STATION_ID to stationId))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "$WORK_PREFIX$stationId",
                ExistingWorkPolicy.REPLACE,
                req
            )
        }
    }
}
