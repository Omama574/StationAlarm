package com.omama.stationalarm.service

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.location.Location
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationResult
import com.omama.stationalarm.data.ActiveStation
import com.omama.stationalarm.repository.StationRepository
import com.omama.stationalarm.service.gps.FakeFlpClient
import com.omama.stationalarm.service.gps.FlpClientGuardian
import com.omama.stationalarm.service.gps.GpsReliabilityCoordinator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.selects.SelectClause2
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeout
import org.junit.Assert.fail
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * Test rig for the behaviour-locked [LocationService].
 *
 * It builds a service instance WITHOUT running `onCreate()` (which would start the DB-sync coroutines
 * and make state non-deterministic), injects the real collaborators the service normally builds itself
 * ([FlpClientGuardian], [GpsReliabilityCoordinator], notifications, wake locks) and then drives / observes
 * the private decision logic through reflection. Nothing under `app/src/main` is modified.
 *
 * What is faked:
 *  - the FLP client inside the guardian (a recording [FakeFlpClient]);
 *  - the per-station lock that `StationRepository.markAlerting()` takes as its very first action. For every
 *    station registered through [monitor] the lock is a [RecordingMutex]: it records "markAlerting was
 *    requested for this station" and then cancels the coroutine, so no database, no GMS geofencing client and
 *    no exception is ever involved. That record is how the tests observe "the alarm fired" ([status]).
 *
 * The callbacks handed to the guardian / coordinator replicate, line for line, the wiring in
 * `LocationService.onCreate()` (those lambdas are not reachable from outside).
 */
internal class LocationServiceRig : AutoCloseable {

    val app: Application = ApplicationProvider.getApplicationContext()
    val service = LocationService()
    val fakeFlp = FakeFlpClient("rig-original")

    val recoveryFixes = CopyOnWriteArrayList<Location>()
    val recoveryFailures = CopyOnWriteArrayList<String>()
    val recreatedClients = CopyOnWriteArrayList<FusedLocationProviderClient>()

    val guardian: FlpClientGuardian
    val coordinator: GpsReliabilityCoordinator
    val notifications: ServiceNotifications

    /** Station ids for which StationRepository.markAlerting() was invoked (one entry per invocation). */
    val alertRequests = CopyOnWriteArrayList<String>()

    private val stationLocks: ConcurrentHashMap<String, Mutex> =
        Reflect.get(StationRepository, "stationLocks")
    private val installedLocks = CopyOnWriteArrayList<String>()

    /**
     * Exceptions that escaped a background coroutine. In this test environment an uncaught exception on a
     * pool thread can terminate the whole test JVM, so a recording handler is installed for the lifetime of
     * the rig and any unexpected exception is turned into a test failure in [close].
     */
    private val uncaught = CopyOnWriteArrayList<Throwable>()
    private val previousUncaughtHandler: Thread.UncaughtExceptionHandler? =
        Thread.getDefaultUncaughtExceptionHandler()

    init {
        Thread.setDefaultUncaughtExceptionHandler { _, error -> uncaught += error }

        // A Service has no base context until the framework attaches one; without it getString()/getSystemService() fail.
        val attach = ContextWrapper::class.java.getDeclaredMethod("attachBaseContext", Context::class.java)
        attach.isAccessible = true
        attach.invoke(service, app)

        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        val lm = app.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        shadowOf(lm).setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        shadowOf(lm).setProviderEnabled(LocationManager.NETWORK_PROVIDER, true)

        notifications = ServiceNotifications(service)
        notifications.createChannels()
        Reflect.set(service, "notifications", notifications)
        Reflect.set(service, "wakeLocks", ServiceWakeLocks(service))
        Reflect.set(service, "audio", AlarmAudioController(service))

        guardian = FlpClientGuardian(
            context = service,
            onClientRecreated = { recreatedClients += it },
            onRecoveryFix = { loc ->
                // == LocationService.onCreate: onRecoveryFix
                Reflect.set(service, "lastLocationTimeMs", System.currentTimeMillis())
                Reflect.call(service, "clearWatchdogState", "guardian one-shot fix")
                Reflect.call(service, "processLocationUpdate", loc)
                recoveryFixes += loc
            },
            onRecoveryFailed = { reason -> recoveryFailures += reason }
        )
        injectClient(guardian, fakeFlp.client)

        coordinator = GpsReliabilityCoordinator(
            context = service,
            onCoarseFix = { coarse ->
                // == LocationService.onCreate: onCoarseFix
                Reflect.set(service, "lastLocationTimeMs", System.currentTimeMillis())
                Reflect.call(service, "processLocationUpdate", coarse)
            },
            onColdStartComplete = {
                // == LocationService.onCreate: onColdStartComplete
                Reflect.call(service, "restartLocationUpdates")
                Reflect.call(service, "adjustPollingInterval")
            }
        )

        Reflect.set(service, "flpGuardian", guardian)
        Reflect.set(service, "gpsCoordinator", coordinator)
        Reflect.set(service, "locationCallback", Reflect.call(service, "createLocationCallback"))
    }

    // ---------------------------------------------------------------------------------------------
    //  Service state (private fields)
    // ---------------------------------------------------------------------------------------------

    var currentPollingIntervalMs: Long
        get() = Reflect.get(service, "currentPollingIntervalMs")
        set(v) = Reflect.set(service, "currentPollingIntervalMs", v)

    var currentLocationPriority: Int
        get() = Reflect.get(service, "currentLocationPriority")
        set(v) = Reflect.set(service, "currentLocationPriority", v)

    var isPolling: Boolean
        get() = Reflect.get(service, "isPolling")
        set(v) = Reflect.set(service, "isPolling", v)

    var lastLocationTimeMs: Long
        get() = Reflect.get(service, "lastLocationTimeMs")
        set(v) = Reflect.set(service, "lastLocationTimeMs", v)

    var watchdogRecoveryPending: Boolean
        get() = Reflect.get(service, "watchdogRecoveryPending")
        set(v) = Reflect.set(service, "watchdogRecoveryPending", v)

    var watchdogNotified: Boolean
        get() = Reflect.get(service, "watchdogNotified")
        set(v) = Reflect.set(service, "watchdogNotified", v)

    val monitoringStations: ConcurrentHashMap<String, ActiveStation>
        get() = Reflect.get(service, "monitoringStations")

    val alertingStationIds: MutableSet<String>
        get() = Reflect.get(service, "alertingStationIds")

    val needsInitialEvaluation: MutableSet<String>
        get() = Reflect.get(service, "needsInitialEvaluation")

    val waitingForExit: MutableSet<String>
        get() = Reflect.get(service, "waitingForExit")

    // ---------------------------------------------------------------------------------------------
    //  Private method invocations
    // ---------------------------------------------------------------------------------------------

    fun adjustPollingInterval() { Reflect.call(service, "adjustPollingInterval") }
    fun startLocationUpdates() { Reflect.call(service, "startLocationUpdates") }
    fun restartLocationUpdates() { Reflect.call(service, "restartLocationUpdates") }
    fun stopLocationUpdates() { Reflect.call(service, "stopLocationUpdates") }
    fun handleWatchdogTick() { Reflect.call(service, "handleWatchdogTick") }
    fun updateForegroundNotification(force: Boolean = false) {
        Reflect.call(service, "updateForegroundNotification", force)
    }

    fun watchdogRunnable(): Runnable = Reflect.get(service, "watchdogRunnable")
    fun watchdogHandler(): Handler = Reflect.get(service, "watchdogHandler")

    // ---------------------------------------------------------------------------------------------
    //  Stations
    // ---------------------------------------------------------------------------------------------

    fun station(
        id: String,
        lat: Double = STATION_LAT,
        lon: Double = STATION_LON,
        radiusKm: Double = 3.0,
        distanceKm: Double? = null
    ) = ActiveStation(
        stationId = id,
        alertDistanceKm = radiusKm,
        notify = true,
        vibrate = false,
        sound = false,
        currentDistanceKm = distanceKm,
        lat = lat,
        lon = lon,
        stationName = "Test $id"
    )

    /**
     * Puts the station in the service's in-memory monitoring set and installs the recording lock, so that a
     * later StationRepository.markAlerting(id) shows up in [alertRequests] / [status].
     */
    fun monitor(station: ActiveStation): ActiveStation {
        monitoringStations[station.stationId] = station
        stationLocks[station.stationId] = RecordingMutex(station.stationId, alertRequests)
        installedLocks += station.stationId
        return station
    }

    /** "ALERTING" once StationRepository.markAlerting() was requested for [id], otherwise "MONITORING". */
    fun status(id: String): String = if (alertRequests.contains(id)) "ALERTING" else "MONITORING"

    fun publishedDistance(id: String): Double? = StationRepository.distancesFlow.value[id]

    // ---------------------------------------------------------------------------------------------
    //  Driving locations through the real pipeline
    // ---------------------------------------------------------------------------------------------

    /** Runs [block], then waits for every coroutine it spawned (service + repository) to finish. */
    fun <T> settling(block: () -> T): T {
        val repoBefore = repositoryChildren()
        val result = block()
        awaitServiceWork()
        val fresh = repositoryChildren() - repoBefore
        runBlocking { withTimeout(20_000) { fresh.forEach { it.join() } } }
        return result
    }

    /** processLocationUpdate(location) and wait until the alarm decision is final. */
    fun process(location: Location) {
        settling { Reflect.call(service, "processLocationUpdate", location) }
    }

    /** Delivers [location] through the FLP callback that the service registered on the (fake) client. */
    fun deliverFlpFix(location: Location) {
        val callback = fakeFlp.registrations.last().callback
        settling { callback.onLocationResult(LocationResult.create(listOf(location))) }
    }

    fun awaitServiceWork() {
        val job = Reflect.get<Job>(service, "serviceJob")
        runBlocking { withTimeout(20_000) { job.children.toList().forEach { it.join() } } }
        if (job.isCancelled) {
            fail("serviceJob was cancelled: a coroutine inside LocationService threw: $uncaught")
        }
    }

    private fun repositoryChildren(): Set<Job> {
        val scope = Reflect.get<CoroutineScope>(StationRepository, "repositoryScope")
        return scope.coroutineContext[Job]!!.children.toSet()
    }

    fun idleMainLooper() = shadowOf(Looper.getMainLooper()).idle()

    fun advanceMs(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(ms, TimeUnit.MILLISECONDS)

    // ---------------------------------------------------------------------------------------------
    //  Geometry helpers. The test station sits on the equator, so "N km east" is a plain longitude offset.
    // ---------------------------------------------------------------------------------------------

    fun fixAtKm(
        distanceKm: Double,
        accuracyM: Float = 10f,
        provider: String = "fused",
        stationLon: Double = STATION_LON,
        stationLat: Double = STATION_LAT
    ): Location = Location(provider).apply {
        latitude = stationLat
        longitude = stationLon + distanceKm / KM_PER_DEGREE_AT_EQUATOR
        accuracy = accuracyM
        time = System.currentTimeMillis()
        elapsedRealtimeNanos = android.os.SystemClock.elapsedRealtimeNanos()
    }

    /** Exactly what LocationService.calculateDistance does. */
    fun distanceKm(fix: Location, stationLat: Double = STATION_LAT, stationLon: Double = STATION_LON): Double {
        val results = FloatArray(1)
        Location.distanceBetween(fix.latitude, fix.longitude, stationLat, stationLon, results)
        return results[0] / 1000.0
    }

    override fun close() {
        runCatching { Reflect.get<Job>(service, "serviceJob").cancel() }
        runCatching { watchdogHandler().removeCallbacks(watchdogRunnable()) }
        runCatching { guardian.destroy() }
        runCatching { coordinator.destroy() }
        installedLocks.forEach { stationLocks.remove(it) }
        Thread.setDefaultUncaughtExceptionHandler(previousUncaughtHandler)

        if (uncaught.isNotEmpty()) {
            throw AssertionError("Uncaught exception(s) on a background thread: $uncaught", uncaught.first())
        }
    }

    /**
     * Stand-in for the per-station Mutex in StationRepository. markAlerting() does
     * `repositoryScope.launch { lockFor(id).withLock { <DB writes + geofence removal> } }`; recording the
     * request and cancelling the coroutine at the lock keeps the (unrelated) DB and GMS parts out of the test.
     */
    private class RecordingMutex(
        private val stationId: String,
        private val sink: MutableList<String>
    ) : Mutex {
        override val isLocked: Boolean get() = true
        @Suppress("OVERRIDE_DEPRECATION")
        override val onLock: SelectClause2<Any?, Mutex> get() = throw UnsupportedOperationException()
        override fun tryLock(owner: Any?): Boolean = false
        override suspend fun lock(owner: Any?) {
            sink += stationId
            throw CancellationException("markAlerting($stationId) recorded by the test rig")
        }
        override fun holdsLock(owner: Any): Boolean = false
        override fun unlock(owner: Any?) {}
    }

    companion object {
        const val STATION_LAT = 0.0
        const val STATION_LON = 10.0

        /** WGS-84 equatorial degree length: a * pi / 180. */
        const val KM_PER_DEGREE_AT_EQUATOR = 111.31949079327357

        fun injectClient(guardian: FlpClientGuardian, client: FusedLocationProviderClient) {
            Reflect.field(FlpClientGuardian::class.java, "client").set(guardian, client)
        }
    }
}
