package com.omama.stationalarm.repository

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker.Result
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.omama.stationalarm.geofence.GeofenceCleanupWorker
import com.omama.stationalarm.repository.RepositoryTestEnv.app
import com.omama.stationalarm.repository.RepositoryTestEnv.entity
import com.omama.stationalarm.repository.RepositoryTestEnv.geofenceIds
import com.omama.stationalarm.repository.RepositoryTestEnv.hasNoGeofences
import com.omama.stationalarm.repository.RepositoryTestEnv.isRegistered
import com.omama.stationalarm.repository.RepositoryTestEnv.newId
import com.omama.stationalarm.repository.RepositoryTestEnv.seed
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/** [GeofenceCleanupWorker.doWork] decision table, driven against the real repository and the fake GMS. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], application = Application::class, shadows = [ShadowLocationServices::class])
class GeofenceCleanupWorkerTest {

    @Before fun setUp() = RepositoryTestEnv.setUp()
    @After fun tearDown() = RepositoryTestEnv.tearDown()

    private fun run(stationId: String?, attempt: Int = 0): Result {
        val input = if (stationId == null) androidx.work.Data.EMPTY else workDataOf("stationId" to stationId)
        return runBlocking { GeofenceCleanupWorker(app, workerParams(input, attempt)).doWork() }
    }

    /** Pretends GMS still holds this station's geofences (as it would after a failed removal). */
    private fun leakGeofences(id: String) {
        FakeGms.registered.addAll(geofenceIds(id))
    }

    @Test
    fun withoutAStationId_failsPermanently() {
        assertEquals(Result.failure(), run(null))
        assertTrue(FakeGms.removeCalls.isEmpty())
    }

    @Test
    fun anArmedMonitoringStation_ownsItsGeofences_soNothingIsRemoved() {
        val id = newId(); seed(entity(id, status = "MONITORING")); leakGeofences(id)
        assertEquals(Result.success(), run(id))
        assertTrue("live geofences of a re-armed alarm must survive", isRegistered(id))
        assertTrue(FakeGms.removeCalls.isEmpty())
    }

    @Test
    fun anAlertingStation_isStillArmed_soNothingIsRemoved() {
        val id = newId(); seed(entity(id, status = "ALERTING")); leakGeofences(id)
        assertEquals(Result.success(), run(id))
        assertTrue(FakeGms.removeCalls.isEmpty())
    }

    @Test
    fun aPausedStation_hasItsLeakedGeofencesRemoved() {
        val id = newId(); seed(entity(id, status = "PAUSED")); leakGeofences(id)
        assertEquals(Result.success(), run(id))
        assertTrue(hasNoGeofences(id))
        assertEquals(geofenceIds(id), FakeGms.removeCalls.single().toSet())
    }

    @Test
    fun aDeletedStation_isNotArmed_soItsLeakedGeofencesAreRemoved() {
        val id = newId("gone"); leakGeofences(id)
        assertEquals(Result.success(), run(id))
        assertTrue(hasNoGeofences(id))
    }

    @Test
    fun whenGmsKeepsFailing_underTheAttemptLimit_asksWorkManagerToRetry() {
        val id = newId(); seed(entity(id, status = "PAUSED")); FakeGms.failRemoves = true
        assertEquals(Result.retry(), run(id, attempt = 9))
        assertEquals("one in-process removal attempt = 3 GMS calls", 3, FakeGms.removeCalls.size)
    }

    @Test
    fun whenGmsKeepsFailing_atTheAttemptLimit_givesUp() {
        val id = newId(); seed(entity(id, status = "PAUSED")); FakeGms.failRemoves = true
        assertEquals(Result.failure(), run(id, attempt = 10))
    }

    @Test
    fun whenTheStationStateCannotBeRead_retriesInsteadOfRemovingBlindly() {
        val id = newId(); leakGeofences(id)
        val restore = detachRepositoryDatabase()
        try {
            assertEquals(Result.retry(), run(id))
        } finally {
            restore()
        }
        assertTrue("must not remove geofences it could not prove are orphaned", FakeGms.removeCalls.isEmpty())
        assertTrue(isRegistered(id))
    }

    @Test
    fun enqueue_isUniquePerStation_soRepeatedFailuresDoNotStackWorkers() {
        val id = newId()
        RepositoryTestEnv.initWorkManager()
        GeofenceCleanupWorker.enqueue(app, id)
        GeofenceCleanupWorker.enqueue(app, id)
        val infos = WorkManager.getInstance(app).getWorkInfosForUniqueWork("geofence_cleanup_$id").get(10, TimeUnit.SECONDS)
        assertEquals("REPLACE keeps exactly one live job", 1, infos.count { !it.state.isFinished })
        assertTrue(infos.any { it.state == WorkInfo.State.ENQUEUED })
    }
}
