package com.omama.stationalarm.ui.viewmodel

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.location.LocationServices
import com.omama.stationalarm.data.StationData
import com.omama.stationalarm.network.GeoSearchResult
import com.omama.stationalarm.network.GeocodingClient
import com.omama.stationalarm.repository.StationRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import org.osmdroid.util.GeoPoint
import retrofit2.HttpException
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.Locale

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class MapSearchViewModel(application: Application) : AndroidViewModel(application) {

    // ── Search state ──────────────────────────────────────────────────────────

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _searchResults = MutableStateFlow<List<GeoSearchResult>>(emptyList())
    val searchResults: StateFlow<List<GeoSearchResult>> = _searchResults.asStateFlow()

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()

    private val _searchError = MutableStateFlow<String?>(null)
    val searchError: StateFlow<String?> = _searchError.asStateFlow()

    // ── Map state ─────────────────────────────────────────────────────────────

    private val _selectedResult = MutableStateFlow<GeoSearchResult?>(null)
    val selectedResult: StateFlow<GeoSearchResult?> = _selectedResult.asStateFlow()

    private val _radiusKm = MutableStateFlow(3.0)
    val radiusKm: StateFlow<Double> = _radiusKm.asStateFlow()

    private val _userLocation = MutableSharedFlow<GeoPoint>(replay = 1)
    val userLocation: SharedFlow<GeoPoint> = _userLocation.asSharedFlow()

    private val _initialCenter = MutableStateFlow<GeoPoint?>(null)
    val initialCenter: StateFlow<GeoPoint?> = _initialCenter.asStateFlow()

    // ── Init ──────────────────────────────────────────────────────────────────

    init {
        // mapLatest cancels the previous performSearch when a new query
        // arrives mid-flight — without it, a slow Photon request kicked off
        // for "Mum" could resolve after the user has typed "Mumbai" and
        // overwrite the fresh result with stale data.
        _query
            .debounce(500)          // 500ms — balances UX vs LocationIQ quota
            .filter { it.length >= 3 }
            .distinctUntilChanged()
            .mapLatest { q -> performSearch(q) }
            .launchIn(viewModelScope)
        fetchAndEmitUserLocation(emitToCenter = true)
    }

    // ── Query handling ────────────────────────────────────────────────────────

    fun onQueryChanged(q: String) {
        _query.value = q
        if (q.length < 3) {
            _searchResults.value = emptyList()
            _searchError.value = null
        }
    }

    /**
     * Two-tier search:
     *   1. India station Room DB — instant, offline, zero API calls
     *   2. Cloudflare Worker (geocoding proxy) — live geocoding for everything else
     */
    private suspend fun performSearch(q: String) {
        _isSearching.value = true
        _searchError.value = null
        try {
            // ── Tier 1: India railway stations (offline) ──────────────────────
            val indiaResults = StationData.searchStations(q)
            if (indiaResults.isNotEmpty()) {
                _searchResults.value = indiaResults.take(8).map { station ->
                    GeoSearchResult(
                        id               = station.id,
                        name             = station.name,
                        formattedAddress = "India · Railway Station",
                        lat              = station.lat,
                        lon              = station.lon,
                    )
                }
                return
            }

            // ── Tier 2: Cloudflare Worker geocoding ──────────────────────────
            val bias = _initialCenter.value
            val response = GeocodingClient.geocodingService.search(
                query   = q,
                biasLat = bias?.latitude,
                biasLon = bias?.longitude,
                lang    = currentLang(),
            )
            _searchResults.value = response.results.filter { it.hasValidCoords }

        } catch (e: Exception) {
            _searchError.value = errorMessageFor(e)
            _searchResults.value = emptyList()
            android.util.Log.e("MapSearch", "Geocoding failed for '$q'", e)
        } finally {
            _isSearching.value = false
        }
    }

    /** ISO 639-1 language code from the (possibly per-app-overridden) current
     *  locale. Sent to the Worker as a hint so the geocoder localizes place
     *  names when it can. Defaults to "en" if the locale somehow has a blank
     *  language tag (shouldn't happen, but defensive). */
    private fun currentLang(): String =
        Locale.getDefault().language.takeIf { it.isNotBlank() } ?: "en"

    /** Translates a network-level failure into a short user-facing message so the
     *  Map snackbar tells the user *why* search failed, not just that it did.
     *  SocketTimeoutException is treated separately from generic IOException
     *  because a timeout usually means a flaky network, not a fully-offline one,
     *  and the user-facing fix is different ("try again" vs "turn wifi on"). */
    internal fun errorMessageFor(e: Throwable): String = when (e) {
        is SocketTimeoutException -> "Search timed out — check your connection."
        is IOException -> "No internet connection."
        is HttpException -> when (e.code()) {
            429 -> "Too many requests — try again in a moment."
            in 500..599 -> "Search service is down — try again shortly."
            else -> "Search unavailable (${e.code()})."
        }
        else -> "Search unavailable."
    }

    // ── Map interactions ──────────────────────────────────────────────────────

    fun selectResult(result: GeoSearchResult) {
        _selectedResult.value = result
        _searchResults.value = emptyList()
        _query.value = ""
    }

    /**
     * Called when user taps anywhere on the map. Immediately drops a pin with
     * raw coords, then reverse-geocodes in the background. If the geocoder has
     * nothing for that point (empty results) or the request fails, the pin keeps
     * its raw-coords label so the user can still confirm what they tapped.
     */
    fun onMapTap(lat: Double, lon: Double) {
        _selectedResult.value = GeoSearchResult(
            id               = "manual-${System.currentTimeMillis()}",
            name             = "Dropped Pin",
            formattedAddress = "Loading address...",
            lat              = lat,
            lon              = lon,
        )

        viewModelScope.launch {
            try {
                val response = GeocodingClient.geocodingService.reverse(
                    lat  = lat,
                    lon  = lon,
                    lang = currentLang(),
                )
                val first = response.results.firstOrNull()
                if (first != null) {
                    // Use the Worker's lat/lon (snapped to the resolved address)
                    // only if valid; otherwise stick with the tap point.
                    _selectedResult.value = first.copy(
                        lat = if (first.hasValidCoords) first.lat else lat,
                        lon = if (first.hasValidCoords) first.lon else lon,
                    )
                } else {
                    // No address known here (e.g., middle of an ocean) — fall back to raw coords.
                    _selectedResult.value = _selectedResult.value?.copy(
                        name             = "Dropped Pin",
                        formattedAddress = "${String.format("%.4f", lat)}, ${String.format("%.4f", lon)}"
                    )
                }
            } catch (e: Exception) {
                android.util.Log.w("MapSearch", "Reverse geocode failed", e)
                _selectedResult.value = _selectedResult.value?.copy(
                    name             = "Dropped Pin",
                    formattedAddress = "${String.format("%.4f", lat)}, ${String.format("%.4f", lon)}"
                )
            }
        }
    }

    fun onRadiusChanged(km: Double) {
        _radiusKm.value = km.coerceIn(3.0, 20.0)
    }

    fun clearSelection() {
        _selectedResult.value = null
        _radiusKm.value = 3.0
        _query.value = ""
        _searchResults.value = emptyList()
    }

    // ── "My Location" FAB ─────────────────────────────────────────────────────

    fun onMyLocationRequested() {
        fetchAndEmitUserLocation(emitToCenter = false)
    }

    private fun fetchAndEmitUserLocation(emitToCenter: Boolean) {
        val ctx = getApplication<Application>()
        if (ActivityCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) return

        LocationServices.getFusedLocationProviderClient(ctx).lastLocation
            .addOnSuccessListener { loc ->
                if (loc != null) {
                    val geoPoint = GeoPoint(loc.latitude, loc.longitude)
                    viewModelScope.launch {
                        if (emitToCenter) {
                            _initialCenter.value = geoPoint
                        } else {
                            _userLocation.emit(geoPoint)
                        }
                    }
                } else if (!emitToCenter) {
                    // FAB request: lastLocation is sometimes null on first launch
                    // before any client has triggered a fix. Surface so the user
                    // doesn't tap the FAB and silently wonder why nothing happened.
                    _searchError.value = "No recent location fix — try moving to an open area."
                }
            }
            .addOnFailureListener { e ->
                android.util.Log.w("MapSearch", "lastLocation failed", e)
                if (!emitToCenter) {
                    _searchError.value = "Couldn't read your location."
                }
            }
    }
}
