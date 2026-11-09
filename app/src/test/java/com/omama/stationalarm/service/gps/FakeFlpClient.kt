package com.omama.stationalarm.service.gps

import android.location.Location
import android.os.Looper
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.tasks.CancellationToken
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.TaskCompletionSource
import com.google.android.gms.tasks.Tasks
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Hand-written fake for [FusedLocationProviderClient].
 *
 * The real client is an interface, so a JDK dynamic proxy is enough (the project has no mocking library).
 * Everything the production code asks of the client is recorded, and the one-shot
 * `getCurrentLocation()` can be left pending, completed by the test, or made to throw.
 */
class FakeFlpClient(private val label: String = "fake") {

    data class UpdateRegistration(
        val request: LocationRequest,
        val callback: LocationCallback,
        val looper: Looper?
    )

    class CurrentLocationCall(
        val priority: Int?,
        val token: CancellationToken?,
        val source: TaskCompletionSource<Location>
    )

    /** Every method name invoked on the client, in order (Object methods excluded). */
    val calls = CopyOnWriteArrayList<String>()
    val registrations = CopyOnWriteArrayList<UpdateRegistration>()
    val removedCallbacks = CopyOnWriteArrayList<LocationCallback>()
    val currentLocationCalls = CopyOnWriteArrayList<CurrentLocationCall>()

    /** When non-null, `getCurrentLocation()` throws this instead of returning a Task. */
    @Volatile
    var getCurrentLocationThrows: Throwable? = null

    /** Optional auto-completion for each `getCurrentLocation()` Task. null leaves the Task pending forever. */
    @Volatile
    var onGetCurrentLocation: ((TaskCompletionSource<Location>) -> Unit)? = null

    val client: FusedLocationProviderClient = Proxy.newProxyInstance(
        FusedLocationProviderClient::class.java.classLoader,
        arrayOf<Class<*>>(FusedLocationProviderClient::class.java),
        InvocationHandler { proxy, method, args -> handle(proxy, method, args ?: emptyArray()) }
    ) as FusedLocationProviderClient

    /** Forget everything recorded so far (keeps the behaviour switches). */
    fun clearRecords() {
        calls.clear()
        registrations.clear()
        removedCallbacks.clear()
        currentLocationCalls.clear()
    }

    private fun handle(proxy: Any, method: Method, args: Array<Any?>): Any? {
        when (method.name) {
            "toString" -> return "FakeFlpClient($label)"
            "hashCode" -> return System.identityHashCode(proxy)
            "equals" -> return proxy === args.firstOrNull()
        }
        calls += method.name
        return when (method.name) {
            "requestLocationUpdates" -> {
                val request = args.getOrNull(0) as? LocationRequest
                val callback = args.firstOrNull { it is LocationCallback } as? LocationCallback
                val looper = args.firstOrNull { it is Looper } as? Looper
                if (request != null && callback != null) {
                    registrations += UpdateRegistration(request, callback, looper)
                }
                Tasks.forResult<Void?>(null)
            }
            "removeLocationUpdates" -> {
                (args.firstOrNull() as? LocationCallback)?.let { removedCallbacks += it }
                Tasks.forResult<Void?>(null)
            }
            "getCurrentLocation" -> {
                getCurrentLocationThrows?.let { throw it }
                val source = TaskCompletionSource<Location>()
                val priority = args.getOrNull(0) as? Int
                val token = args.getOrNull(1) as? CancellationToken
                currentLocationCalls += CurrentLocationCall(priority, token, source)
                onGetCurrentLocation?.invoke(source)
                source.task
            }
            else ->
                if (Task::class.java.isAssignableFrom(method.returnType)) Tasks.forResult<Any?>(null) else null
        }
    }
}
