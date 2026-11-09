package com.omama.stationalarm.repository

import android.Manifest
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import com.omama.stationalarm.data.ActiveStation
import com.omama.stationalarm.data.Station
import com.omama.stationalarm.data.StationData
import com.omama.stationalarm.data.db.ActiveStationDao
import com.omama.stationalarm.data.db.ActiveStationEntity
import com.omama.stationalarm.data.db.StationDatabase
import com.omama.stationalarm.data.db.resetStationDatabaseSingleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.fail
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * Shared Robolectric environment for tests of the real StationRepository + GeofenceManager.
 *
 * StationRepository and GeofenceManager are `object`s with no seams, so the tests drive the real code and
 * replace only the outermost layer: Play Services (via [ShadowLocationServices]/[FakeGms]). Every test class
 * using this must declare
 * `@Config(sdk = [33], application = Application::class, shadows = [ShadowLocationServices::class])`
 * so StationAlarmApplication.onCreate (Firebase, reconcileOrphans, ...) does not race the test.
 */
internal object RepositoryTestEnv {
    lateinit var app: Application
    private val seq = AtomicInteger()

    /** Unique, sanitiser-stable station id (letters, digits, '-') per call. */
    fun newId(prefix: String = "t") = "$prefix-${seq.incrementAndGet()}"

    val dao: ActiveStationDao get() = StationDatabase.getDatabase(app).activeStationDao()

    fun setUp(locationGranted: Boolean = true) {
        app = ApplicationProvider.getApplicationContext()
        setLocationPermission(locationGranted)
        resetStationDatabaseSingleton()
        FakeGms.reset()
        StationRepository.initialize(app)
        StationData.initialize(app) // synchronous; initialize() only loads it in the background
    }

    /**
     * Opt-in, only for tests where a failed geofence removal makes GeofenceManager enqueue a
     * GeofenceCleanupWorker. WorkManager is a process-wide singleton whose database belongs to the Robolectric
     * environment of the test that created it (reuse fails with "Illegal connection pointer"), so it is rebuilt
     * here; and every WorkManager adds background DB work that races teardown, so it is not done for all tests.
     * The executor is parked: the enqueued worker is recorded but never runs.
     */
    fun initWorkManager() {
        val impl = Class.forName("androidx.work.impl.WorkManagerImpl")
        for (name in listOf("sDelegatedInstance", "sDefaultInstance")) {
            impl.getDeclaredField(name).apply { isAccessible = true }.set(null, null)
        }
        WorkManager.initialize(app, Configuration.Builder().setExecutor { }.build())
    }

    fun tearDown() = resetStationDatabaseSingleton()

    fun setLocationPermission(granted: Boolean) {
        if (granted) shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        else shadowOf(app).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    /** The 9 request ids GeofenceManager registers for [stationId]. */
    fun geofenceIds(stationId: String): Set<String> =
        (1..8).map { "geofence_${stationId}_level$it" }.toSet() + "geofence_${stationId}_alert"

    fun isRegistered(stationId: String) = FakeGms.registered.containsAll(geofenceIds(stationId))
    fun hasNoGeofences(stationId: String) = FakeGms.registered.intersect(geofenceIds(stationId)).isEmpty()

    fun entity(
        id: String,
        status: String = "MONITORING",
        radiusKm: Double = 5.0,
        lat: Double = 12.97,
        lon: Double = 77.59,
        name: String = "Place $id",
    ) = ActiveStationEntity(
        stationId = id, alertDistanceKm = radiusKm, notify = true, vibrate = true, sound = true,
        customReminder = null, sendReminder = false, status = status, lat = lat, lon = lon, stationName = name,
    )

    fun seed(vararg rows: ActiveStationEntity) = runBlocking { rows.forEach { dao.insert(it) } }
    fun row(id: String): ActiveStationEntity? = runBlocking { dao.getStationById(id) }
    fun status(id: String): String? = runBlocking { dao.getStatus(id) }
    fun allRows(): List<ActiveStationEntity> = runBlocking { dao.getAllActiveStationsList() }

    /** Adds a map-pin style alarm through the real repository and waits until its geofences are live. */
    fun addAndAwait(id: String, radiusKm: Double = 5.0, lat: Double = 12.97, lon: Double = 77.59) {
        StationRepository.addActiveStation(
            ActiveStation(stationId = id, alertDistanceKm = radiusKm, notify = true, vibrate = true, sound = true),
            Station(id, "Pin $id", lat, lon),
        )
        eventually("geofences registered for $id") { isRegistered(id) }
    }

    /** Polls (real time) because the repository does its work on its own Dispatchers.IO scope. */
    fun eventually(what: String, timeoutMs: Long = 20_000, cond: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (!cond()) {
            if (System.nanoTime() > deadline) fail("Timed out after ${timeoutMs}ms waiting for: $what")
            Thread.sleep(20)
        }
    }

    /** Collects StationRepository.errorEvents; subscribed before the constructor returns (replay = 0). */
    class ErrorCollector : AutoCloseable {
        val events = CopyOnWriteArrayList<String>()
        private val job: Job = CoroutineScope(Dispatchers.Unconfined).launch(start = CoroutineStart.UNDISPATCHED) {
            StationRepository.errorEvents.collect { events.add(it) }
        }
        override fun close() = job.cancel()
    }
}
