package com.omama.stationalarm.geofence

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Parcel
import android.os.Parcelable
import androidx.room.Room
import com.google.android.gms.common.api.Api
import com.google.android.gms.common.api.internal.ApiKey
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingClient
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.tasks.OnFailureListener
import com.google.android.gms.tasks.OnSuccessListener
import com.google.android.gms.tasks.RuntimeExecutionException
import com.google.android.gms.tasks.Task
import com.omama.stationalarm.data.ActiveStation
import com.omama.stationalarm.data.db.StationDatabase
import com.omama.stationalarm.data.db.toEntity
import com.omama.stationalarm.repository.StationRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.lang.reflect.Field
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/** The client handed out by [ShadowLocationServicesGeofencing]. Tests reset it in @Before. */
object FakeGeofencing {
    @JvmStatic
    @Volatile
    var client: RecordingGeofencingClient = RecordingGeofencingClient()

    @JvmStatic
    fun reset(): RecordingGeofencingClient {
        client = RecordingGeofencingClient()
        return client
    }
}

/**
 * Records every GeofencingClient call and completes the returned Task synchronously, so the
 * suspendCancellableCoroutine blocks in GeofenceManager resolve without a Looper. Failures are scripted by
 * the zero-based index of the call: `failAddCalls = setOf(0, 1)` fails the first two addGeofences() calls.
 */
class RecordingGeofencingClient : GeofencingClient {

    class AddCall(val request: GeofencingRequest, val pendingIntent: PendingIntent) {
        val geofences: List<Geofence> get() = request.geofences
        val requestIds: List<String> get() = geofences.map { it.requestId }
    }

    val addCalls = CopyOnWriteArrayList<AddCall>()
    val removeByIdCalls = CopyOnWriteArrayList<List<String>>()
    val removeByIntentCalls = CopyOnWriteArrayList<PendingIntent>()

    @Volatile var failAddCalls: Set<Int> = emptySet()
    @Volatile var failRemoveCalls: Set<Int> = emptySet()
    @Volatile var failure: Exception = RuntimeException("GMS unavailable (test)")

    override fun addGeofences(request: GeofencingRequest, pendingIntent: PendingIntent): Task<Void> {
        val index = addCalls.size
        addCalls += AddCall(request, pendingIntent)
        return ImmediateTask(if (index in failAddCalls) failure else null)
    }

    override fun removeGeofences(pendingIntent: PendingIntent): Task<Void> {
        removeByIntentCalls += pendingIntent
        return ImmediateTask(null)
    }

    override fun removeGeofences(requestIds: MutableList<String>): Task<Void> {
        val index = removeByIdCalls.size
        removeByIdCalls += requestIds.toList()
        return ImmediateTask(if (index in failRemoveCalls) failure else null)
    }

    override fun getApiKey(): ApiKey<Api.ApiOptions.NoOptions> = throw UnsupportedOperationException("not used")
}

/** A Task that is already complete; listeners run inline on the calling thread. */
class ImmediateTask(private val error: Exception?) : Task<Void>() {
    override fun addOnFailureListener(l: OnFailureListener): Task<Void> {
        error?.let { l.onFailure(it) }
        return this
    }

    override fun addOnFailureListener(a: Activity, l: OnFailureListener): Task<Void> = addOnFailureListener(l)
    override fun addOnFailureListener(e: Executor, l: OnFailureListener): Task<Void> = addOnFailureListener(l)

    override fun addOnSuccessListener(l: OnSuccessListener<in Void>): Task<Void> {
        @Suppress("UNCHECKED_CAST")
        val listener = l as OnSuccessListener<Void?>
        if (error == null) listener.onSuccess(null)
        return this
    }

    override fun addOnSuccessListener(a: Activity, l: OnSuccessListener<in Void>): Task<Void> = addOnSuccessListener(l)
    override fun addOnSuccessListener(e: Executor, l: OnSuccessListener<in Void>): Task<Void> = addOnSuccessListener(l)

    override fun getException(): Exception? = error
    override fun getResult(): Void? = if (error == null) null else throw RuntimeExecutionException(error)
    override fun <X : Throwable> getResult(type: Class<X>): Void? = getResult()
    override fun isCanceled(): Boolean = false
    override fun isComplete(): Boolean = true
    override fun isSuccessful(): Boolean = error == null
}

/**
 * Real [StationRepository] on an in-memory Room database.
 *
 * StationRepository is an `object` whose `database` / `appContext` are private lateinit fields normally set by
 * StationAlarmApplication; Robolectric runs the unit tests with a plain Application, so they are injected here
 * by reflection (nothing in src/main is touched). [close] waits for repository coroutines before closing Room.
 */
class RepositoryHarness(context: Context) : AutoCloseable {

    val db: StationDatabase =
        Room.inMemoryDatabaseBuilder(context, StationDatabase::class.java).allowMainThreadQueries().build()

    init {
        TestReflection.setStatic(StationRepository::class.java, "appContext", context.applicationContext)
        TestReflection.setStatic(StationRepository::class.java, "database", db)
    }

    fun station(
        id: String,
        status: String = "MONITORING",
        radiusKm: Double = 3.0,
        lat: Double = 12.97,
        lon: Double = 77.59,
        name: String = "Station $id",
        sound: Boolean = true,
        vibrate: Boolean = true
    ) = ActiveStation(
        stationId = id,
        alertDistanceKm = radiusKm,
        notify = true,
        vibrate = vibrate,
        sound = sound,
        status = status,
        lat = lat,
        lon = lon,
        stationName = name
    )

    fun insert(station: ActiveStation): ActiveStation {
        runBlocking { db.activeStationDao().insert(station.toEntity()) }
        return station
    }

    /**
     * Makes every repository read throw, as if the database were unavailable. (Closing the Room database is not
     * enough: the framework helper silently re-opens a fresh in-memory database on the next query.)
     */
    fun breakDatabase() = TestReflection.setStatic(StationRepository::class.java, "database", null)

    fun status(id: String): String? = runBlocking { db.activeStationDao().getStatus(id) }

    fun lastTriggeredAt(id: String): Long? = runBlocking { db.activeStationDao().getStationById(id)?.lastTriggeredAt }

    /** Waits for every coroutine StationRepository launched (dismissStation, markAlerting, ...). */
    fun awaitRepositoryIdle() {
        val scope = TestReflection.getStatic<CoroutineScope>(StationRepository::class.java, "repositoryScope")
        val job = scope.coroutineContext[Job]!!
        runBlocking { withTimeout(20_000) { job.children.toList().forEach { it.join() } } }
    }

    override fun close() {
        runCatching { awaitRepositoryIdle() }
        db.close()
    }
}

internal object TestReflection {
    fun field(cls: Class<*>, name: String): Field {
        var c: Class<*>? = cls
        while (c != null) {
            try {
                return c.getDeclaredField(name).apply { isAccessible = true }
            } catch (_: NoSuchFieldException) {
                c = c.superclass
            }
        }
        throw NoSuchFieldException("$name on ${cls.name}")
    }

    fun setStatic(cls: Class<*>, name: String, value: Any?) = field(cls, name).set(null, value)

    @Suppress("UNCHECKED_CAST")
    fun <T> getStatic(cls: Class<*>, name: String): T = field(cls, name).get(null) as T

    fun set(target: Any, name: String, value: Any?) = field(target.javaClass, name).set(target, value)

    @Suppress("UNCHECKED_CAST")
    fun <T> get(target: Any, name: String): T = field(target.javaClass, name).get(target) as T
}

/** Builds the Intent Google Play services delivers to a geofence PendingIntent. */
object GeofenceIntents {
    const val ACTION = "com.omama.stationalarm.ACTION_GEOFENCE"
    private const val EXTRA_LIST = "com.google.android.location.intent.extra.geofence_list"
    private const val EXTRA_TRANSITION = "com.google.android.location.intent.extra.transition"
    private const val EXTRA_ERROR = "gms_error_code"

    fun geofence(requestId: String): Geofence =
        Geofence.Builder()
            .setRequestId(requestId)
            .setCircularRegion(12.97, 77.59, 500f)
            .setExpirationDuration(Geofence.NEVER_EXPIRE)
            .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER)
            .build()

    /** An ENTER event that triggered [requestIds] (zero ids gives a valid event with an empty list). */
    fun enter(vararg requestIds: String, transition: Int = Geofence.GEOFENCE_TRANSITION_ENTER): Intent {
        val parcels = ArrayList<ByteArray>()
        requestIds.forEach { id ->
            val parcel = Parcel.obtain()
            try {
                (geofence(id) as Parcelable).writeToParcel(parcel, 0)
                parcels += parcel.marshall()
            } finally {
                parcel.recycle()
            }
        }
        return Intent(ACTION).putExtra(EXTRA_LIST, parcels).putExtra(EXTRA_TRANSITION, transition)
    }

    /** An event GMS reports as failed (for example GEOFENCE_NOT_AVAILABLE = 1000). */
    fun error(code: Int): Intent = Intent(ACTION).putExtra(EXTRA_ERROR, code)

    /** An intent that carries neither a geofence list nor an error: GeofencingEvent.fromIntent() returns null. */
    fun empty(): Intent = Intent(ACTION)
}

/**
 * Delivers a broadcast to a receiver the way the framework does: with a PendingResult attached, so goAsync()
 * works, and with a handle that shows when finish() ran.
 */
class ReceiverDelivery private constructor(
    private val finished: Future<*>?,
) {
    fun awaitFinish(seconds: Long = 20) {
        finished!!.get(seconds, TimeUnit.SECONDS)
    }

    val finishedFuture: Future<*>? get() = finished

    companion object {
        fun deliver(receiver: BroadcastReceiver, context: Context, intent: Intent): ReceiverDelivery {
            val create = Class.forName("org.robolectric.shadows.ShadowBroadcastPendingResult")
                .getDeclaredMethod(
                    "create",
                    Int::class.javaPrimitiveType,
                    String::class.java,
                    Bundle::class.java,
                    Boolean::class.javaPrimitiveType
                ).apply { isAccessible = true }
            val pending = create.invoke(null, 0, null, null, false) as BroadcastReceiver.PendingResult
            BroadcastReceiver::class.java
                .getDeclaredMethod("setPendingResult", BroadcastReceiver.PendingResult::class.java)
                .apply { isAccessible = true }
                .invoke(receiver, pending)

            val shadow = pending.javaClass.getField("__robo_data__").get(pending)
            val future = shadow.javaClass.getMethod("getFuture").invoke(shadow) as Future<*>

            receiver.onReceive(context, intent)
            return ReceiverDelivery(future)
        }
    }
}

/**
 * Worker / WorkManager helpers.
 *
 * WorkManager is a process-wide singleton whose database belongs to the Robolectric environment that created it
 * (reusing it in a later test fails with "Illegal connection pointer"), so tests that need it rebuild it with
 * [freshParkedWorkManager]: its executor does nothing, so enqueued work is recorded but never runs. Workers that
 * are called directly get hand-built [androidx.work.WorkerParameters] ([params]) with inline executors, because
 * androidx.work:work-testing is not a test dependency.
 */
object WorkTestSupport {

    fun freshParkedWorkManager(context: Context) {
        val impl = Class.forName("androidx.work.impl.WorkManagerImpl")
        for (name in listOf("sDelegatedInstance", "sDefaultInstance")) {
            impl.getDeclaredField(name).apply { isAccessible = true }.set(null, null)
        }
        androidx.work.WorkManager.initialize(
            context.applicationContext,
            androidx.work.Configuration.Builder().setExecutor { }.build()
        )
    }

    @Suppress("UNUSED_PARAMETER")
    fun params(
        context: Context,
        runAttemptCount: Int = 0,
        input: androidx.work.Data = androidx.work.Data.EMPTY
    ): androidx.work.WorkerParameters {
        val inline = Executor { it.run() }
        return androidx.work.WorkerParameters(
            java.util.UUID.randomUUID(),
            input,
            emptyList<String>(),
            androidx.work.WorkerParameters.RuntimeExtras(),
            runAttemptCount,
            0,
            inline,
            androidx.work.impl.utils.taskexecutor.WorkManagerTaskExecutor(inline),
            androidx.work.WorkerFactory.getDefaultWorkerFactory(),
            unused(androidx.work.ProgressUpdater::class.java),
            unused(androidx.work.ForegroundUpdater::class.java)
        )
    }

    private fun <T> unused(type: Class<T>): T =
        type.cast(java.lang.reflect.Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, _, _ -> null })!!

    /** States of all work enqueued under [uniqueName] (empty if none). */
    fun states(context: Context, uniqueName: String): List<androidx.work.WorkInfo.State> =
        androidx.work.WorkManager.getInstance(context.applicationContext)
            .getWorkInfosForUniqueWork(uniqueName).get(20, TimeUnit.SECONDS).map { it.state }
}
