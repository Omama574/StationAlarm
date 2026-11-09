package com.omama.stationalarm.ui.viewmodel

import com.omama.stationalarm.network.GeoSearchResult
import com.omama.stationalarm.network.GeocodingClient
import com.omama.stationalarm.network.GeocodingResponse
import com.omama.stationalarm.network.GeocodingService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import java.util.concurrent.CopyOnWriteArrayList

/** Makes viewModelScope (Dispatchers.Main.immediate) run on a virtual-time dispatcher. */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchMainDispatcherRule(
    val dispatcher: TestDispatcher = StandardTestDispatcher(),
) : TestWatcher() {
    override fun starting(description: Description) = Dispatchers.setMain(dispatcher)
    override fun finished(description: Description) = Dispatchers.resetMain()
}

/** Hand-written stand-in for the Retrofit service; records every call and how each one ended. */
internal class FakeGeocodingService : GeocodingService {

    data class SearchCall(val query: String, val limit: Int, val biasLat: Double?, val biasLon: Double?, val lang: String?)
    data class ReverseCall(val lat: Double, val lon: Double, val lang: String?)

    val searchCalls = CopyOnWriteArrayList<SearchCall>()
    val searchCompleted = CopyOnWriteArrayList<String>()
    val searchCancelled = CopyOnWriteArrayList<String>()
    val reverseCalls = CopyOnWriteArrayList<ReverseCall>()
    val reverseCancelled = CopyOnWriteArrayList<ReverseCall>()

    var onSearch: suspend (SearchCall) -> GeocodingResponse = { GeocodingResponse(emptyList()) }
    var onReverse: suspend (ReverseCall) -> GeocodingResponse = { GeocodingResponse(emptyList()) }

    override suspend fun search(query: String, limit: Int, biasLat: Double?, biasLon: Double?, lang: String?): GeocodingResponse {
        val call = SearchCall(query, limit, biasLat, biasLon, lang)
        searchCalls += call
        try {
            return onSearch(call).also { searchCompleted += query }
        } catch (e: CancellationException) {
            searchCancelled += query
            throw e
        }
    }

    override suspend fun reverse(lat: Double, lon: Double, lang: String?): GeocodingResponse {
        val call = ReverseCall(lat, lon, lang)
        reverseCalls += call
        try {
            return onReverse(call)
        } catch (e: CancellationException) {
            reverseCancelled += call
            throw e
        }
    }
}

/** Swaps GeocodingClient's cached Retrofit service for a fake (the ViewModel has no injection point). */
internal object GeocodingClientHook {
    private val field = GeocodingClient::class.java.getDeclaredField("_geocodingService").apply { isAccessible = true }
    fun install(fake: GeocodingService) = field.set(GeocodingClient, fake)
    fun clear() = field.set(GeocodingClient, null)
}

internal fun place(id: String, lat: Double = 18.5, lon: Double = 73.8, name: String = "Place $id", addr: String = "Addr $id") =
    GeoSearchResult(id = id, name = name, formattedAddress = addr, lat = lat, lon = lon)

internal fun response(vararg r: GeoSearchResult) = GeocodingResponse(r.toList())
