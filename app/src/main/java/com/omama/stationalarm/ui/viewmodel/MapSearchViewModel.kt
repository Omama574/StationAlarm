package com.omama.stationalarm.ui.viewmodel

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.location.LocationServices
import com.omama.stationalarm.R
import com.omama.stationalarm.data.StationData
import com.omama.stationalarm.network.GeoSearchResult
import com.omama.stationalarm.network.GeocodingClient
import com.omama.stationalarm.repository.StationRepository
import com.omama.stationalarm.util.GeoMath
import com.omama.stationalarm.util.StationIds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import org.osmdroid.util.GeoPoint
import retrofit2.HttpException
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.Locale

// A reverse-geocode result closer than this to the tapped point is treated as "the thing the user tapped"
// (building, station, POI) and the pin snaps to it. Anything farther keeps the tapped coordinates.
private const val MAX_PIN_SNAP_KM = 0.15

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

    // Bumped whenever the visible search state is reset (box cleared, result picked). A search that
    // started before the bump finds the number changed and discards its answer instead of resurrecting
    // stale results. Only touched on the main thread (viewModelScope runs on Dispatchers.Main.immediate).
    private var searchGeneration = 0

    // The in-flight reverse geocode for the most recent map tap, so a newer tap or a picked search
    // result can cancel it.
    private var reverseJob: Job? = null

    // ── Init ──────────────────────────────────────────────────────────────────

    init {
        // mapLatest cancels the previous performSearch when a new query
        // arrives mid-flight — without it, a slow Photon request kicked off
        // for "Mum" could resolve after the user has typed "Mumbai" and
        // overwrite the fresh result with stale data.
        //
        // distinctUntilChanged() runs BEFORE the length filter on purpose: with the filter first, the
        // blank query that follows clearing the box or picking a result never reached it, so typing the
        // same text again (e.g. "pune" after having picked a result for "pune") was dropped as a
        // duplicate and showed no results.
        _query
            .debounce(500)          // 500ms — balances UX vs LocationIQ quota
            .distinctUntilChanged()
            .filter { it.length >= 3 }
            .mapLatest { q -> performSearch(q) }
            .launchIn(viewModelScope)
        fetchAndEmitUserLocation(emitToCenter = true)
    }

    // ── Query handling ────────────────────────────────────────────────────────

    fun onQueryChanged(q: String) {
        _query.value = q
        if (q.length < 3) {
            // The pipeline above only sees queries of 3+ characters, so a shorter one cannot cancel a
            // search that is already running — invalidate it explicitly.
            searchGeneration++
            _searchResults.value = emptyList()
            _searchError.value = null
            _isSearching.value = false
        }
    }

    /**
     * Two-tier search:
     *   1. India station Room DB — instant, offline, zero API calls
     *   2. Cloudflare Worker (geocoding proxy) — live geocoding for everything else
     */
    private suspend fun performSearch(q: String) {
        val generation = searchGeneration
        var superseded = false
        _isSearching.value = true
        _searchError.value = null
        try {
            // ── Tier 1: India railway stations (offline) ──────────────────────
            val indiaResults = StationData.searchStations(q)
            if (indiaResults.isNotEmpty()) {
                val railwayLabel = getApplication<Application>()
                    .getString(R.string.map_hint_railway_station)
                if (generation == searchGeneration) {
                    _searchResults.value = indiaResults.take(8).map { station ->
                        GeoSearchResult(
                            id               = station.id,
                            name             = station.name,
                            formattedAddress = railwayLabel,
                            lat              = station.lat,
                            lon              = station.lon,
                        )
                    }
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
            if (generation == searchGeneration) {
                _searchResults.value = response.results.filter { it.hasValidCoords }
            }

        } catch (e: CancellationException) {
            // mapLatest replaced this search with a newer one. CancellationException is an Exception, so
            // without this branch the generic handler below reported every superseded search as
            // "Search unavailable", cleared the results and switched the spinner off while the newer
            // request was still running — visible whenever the user paused mid-word on a slow link.
            superseded = true
            throw e
        } catch (e: Exception) {
            if (generation == searchGeneration) {
                _searchError.value = errorMessageFor(e)
                _searchResults.value = emptyList()
            }
            android.util.Log.e("MapSearch", "Geocoding failed for '$q'", e)
        } finally {
            if (!superseded && generation == searchGeneration) _isSearching.value = false
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
    internal fun errorMessageFor(e: Throwable): String {
        val app = getApplication<Application>()
        return when (e) {
            is SocketTimeoutException -> app.getString(R.string.error_search_timed_out)
            is IOException -> app.getString(R.string.error_no_internet)
            is HttpException -> when (e.code()) {
                429 -> app.getString(R.string.error_too_many_requests)
                in 500..599 -> app.getString(R.string.error_search_down)
                else -> app.getString(R.string.error_search_unavailable_format, e.code())
            }
            else -> app.getString(R.string.error_search_unavailable)
        }
    }

    // ── Map interactions ──────────────────────────────────────────────────────

    fun selectResult(result: GeoSearchResult) {
        searchGeneration++
        reverseJob?.cancel()
        // Geocoder IDs contain '_' (liq_481312177), which corrupts the geofence request ID parse — see StationIds.
        _selectedResult.value = result.copy(id = StationIds.sanitize(result.id))
        _searchResults.value = emptyList()
        _isSearching.value = false
        _query.value = ""
    }

    /**
     * Called when user taps anywhere on the map. Immediately drops a pin with
     * raw coords, then reverse-geocodes in the background. If the geocoder has
     * nothing for that point (empty results) or the request fails, the pin keeps
     * its raw-coords label so the user can still confirm what they tapped.
     */
    fun onMapTap(lat: Double, lon: Double) {
        val app = getApplication<Application>()
        val droppedPinLabel = app.getString(R.string.map_pin_dropped_default)
        val loadingLabel = app.getString(R.string.map_pin_loading_address)

        // Identifies THIS tap. A reverse geocode that is still running for an earlier tap — common on a train
        // with a weak connection — must not overwrite the pin the user placed afterwards, nor a result they
        // picked from search, otherwise the alarm ends up at the wrong place.
        val pendingId = "manual-${System.currentTimeMillis()}"
        _selectedResult.value = GeoSearchResult(
            id               = pendingId,
            name             = droppedPinLabel,
            formattedAddress = loadingLabel,
            lat              = lat,
            lon              = lon,
        )

        reverseJob?.cancel()
        reverseJob = viewModelScope.launch {
            try {
                val response = GeocodingClient.geocodingService.reverse(
                    lat  = lat,
                    lon  = lon,
                    lang = currentLang(),
                )
                if (_selectedResult.value?.id != pendingId) return@launch
                val first = response.results.firstOrNull()
                if (first != null) {
                    // Take the geocoder's name/address, but keep the user's own point unless the geocoder's is
                    // a close snap (the building/POI that was tapped). For a tap in open country the "nearest
                    // address" can be kilometres away, and moving the pin there would silently move the alarm.
                    val snapped = first.hasValidCoords &&
                        GeoMath.distanceKm(lat, lon, first.lat, first.lon) <= MAX_PIN_SNAP_KM
                    _selectedResult.value = first.copy(
                        id  = StationIds.sanitize(first.id),
                        lat = if (snapped) first.lat else lat,
                        lon = if (snapped) first.lon else lon,
                    )
                } else {
                    // No address known here (e.g., middle of an ocean) — fall back to raw coords.
                    _selectedResult.value = _selectedResult.value?.copy(
                        name             = droppedPinLabel,
                        formattedAddress = "${String.format("%.4f", lat)}, ${String.format("%.4f", lon)}"
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("MapSearch", "Reverse geocode failed", e)
                if (_selectedResult.value?.id != pendingId) return@launch
                _selectedResult.value = _selectedResult.value?.copy(
                    name             = droppedPinLabel,
                    formattedAddress = "${String.format("%.4f", lat)}, ${String.format("%.4f", lon)}"
                )
            }
        }
    }

    fun onRadiusChanged(km: Double) {
        _radiusKm.value = km.coerceIn(3.0, 20.0)
    }

    fun clearSelection() {
        searchGeneration++
        reverseJob?.cancel()
        _selectedResult.value = null
        _radiusKm.value = 3.0
        _query.value = ""
        _searchResults.value = emptyList()
        _isSearching.value = false
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
                    _searchError.value = ctx.getString(R.string.snackbar_no_recent_fix)
                }
            }
            .addOnFailureListener { e ->
                android.util.Log.w("MapSearch", "lastLocation failed", e)
                if (!emitToCenter) {
                    _searchError.value = ctx.getString(R.string.snackbar_couldnt_read_location)
                }
            }
    }
}
