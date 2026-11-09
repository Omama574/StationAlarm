package com.omama.stationalarm.geofence

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.workDataOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The persistent fallback for a failed geofence removal. The key rule: a station that is armed again owns its
 * geofences, so the cleanup must not delete the live ones.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], application = android.app.Application::class, shadows = [ShadowLocationServicesGeofencing::class])
class GeofenceCleanupWorkerTest {

    private lateinit var app: Application
    private lateinit var repo: RepositoryHarness
    private lateinit var gms: RecordingGeofencingClient

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        gms = FakeGeofencing.reset()
        repo = RepositoryHarness(app)
    }

    @After
    fun tearDown() {
        repo.close()
    }

    private fun worker(stationId: String?, attempt: Int = 0) = GeofenceCleanupWorker(
        app,
        WorkTestSupport.params(
            app, attempt,
            if (stationId == null) androidx.work.Data.EMPTY else workDataOf("stationId" to stationId)
        )
    )

    @Test
    fun withoutAStationIdInTheInput_itFails_andTouchesNothing() = runTest {
        assertEquals(ListenableWorker.Result.failure(), worker(null).doWork())
        assertEquals(0, gms.removeByIdCalls.size)
    }

    @Test
    fun forAPausedStation_itRemovesTheNineGeofences_andSucceeds() = runTest {
        repo.insert(repo.station("my-stop", status = "PAUSED"))

        val result = worker("my-stop").doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(
            (1..8).map { "geofence_my-stop_level$it" }.toSet() + "geofence_my-stop_alert",
            gms.removeByIdCalls.single().toSet()
        )
    }

    @Test
    fun forAStationThatNoLongerExists_itStillRemovesTheGeofences() = runTest {
        val result = worker("deleted-stop").doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(1, gms.removeByIdCalls.size)
    }

    @Test
    fun forAStationThatIsArmedAgain_itSkipsTheCleanup_soLiveGeofencesSurvive() = runTest {
        repo.insert(repo.station("my-stop", status = "MONITORING"))
        repo.insert(repo.station("ringing-stop", status = "ALERTING"))

        assertEquals(ListenableWorker.Result.success(), worker("my-stop").doWork())
        assertEquals(ListenableWorker.Result.success(), worker("ringing-stop").doWork())

        assertEquals("armed stations keep their geofences", 0, gms.removeByIdCalls.size)
    }

    @Test
    fun whenPlayServicesStillFails_itRetries_untilTheTenthAttempt_thenGivesUp() = runTest {
        gms.failRemoveCalls = (0..100).toSet()

        assertEquals(ListenableWorker.Result.retry(), worker("my-stop", attempt = 0).doWork())
        assertEquals(ListenableWorker.Result.retry(), worker("my-stop", attempt = 9).doWork())
        assertEquals(ListenableWorker.Result.failure(), worker("my-stop", attempt = 10).doWork())
    }

    @Test
    fun whenTheAlarmStateCannotBeRead_itRetriesInsteadOfRiskingALiveGeofenceDeletion() = runTest {
        repo.breakDatabase()

        val result = worker("my-stop").doWork()

        assertEquals(ListenableWorker.Result.retry(), result)
        assertEquals("must not remove anything when armed-ness is unknown", 0, gms.removeByIdCalls.size)
    }

    // ------------------------------------------------------------------ the fallback that schedules it

    @Test
    fun aFailedRemoval_schedulesTheCleanupWorkerUnderAPerStationName() = runTest {
        WorkTestSupport.freshParkedWorkManager(app)
        gms.failRemoveCalls = setOf(0, 1, 2)

        GeofenceManager.removeGeofencesForStation(app, "my-stop")

        assertEquals(3, gms.removeByIdCalls.size)
        assertEquals(1, WorkTestSupport.states(app, "geofence_cleanup_my-stop").size)
    }

    @Test
    fun aSuccessfulRemoval_schedulesNoCleanup() = runTest {
        WorkTestSupport.freshParkedWorkManager(app)
        GeofenceManager.removeGeofencesForStation(app, "my-stop")

        assertTrue(WorkTestSupport.states(app, "geofence_cleanup_my-stop").isEmpty())
    }
}
