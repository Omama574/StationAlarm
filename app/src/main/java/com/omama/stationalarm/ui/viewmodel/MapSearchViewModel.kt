package com.omama.stationalarm.ui.viewmodel

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.location.LocationServices
import com.omama.stationalarm.data.SavedPlace
import com.omama.stationalarm.data.StationData
import com.omama.stationalarm.network.GeoSearchResult
import com.omama.stationalarm.network.GeocodingClient
import com.omama.stationalarm.network.toSearchResult
import com.omama.stationalarm.repository.StationRepository
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import org.osmdroid.util.GeoPoint
import java.util.UUID

@OptIn(FlowPreview::class)
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

    // ── Saved places ──────────────────────────────────────────────────────────

    val savedPlaces: StateFlow<List<SavedPlace>> = StationRepository.savedPlacesFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // ── Init ──────────────────────────────────────────────────────────────────

    init {
        viewModelScope.launch {
            _query
                .debounce(500)          // 500ms — balances UX vs LocationIQ quota
                .filter { it.length >= 3 }
                .distinctUntilChanged()
                .collect { q -> performSearch(q) }
        }
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
     * Three-tier search:
     *   1. India station Room DB — instant, offline, zero API calls
     *   2. LocationIQ via Cloudflare Worker — primary live geocoding
     *   3. Photon by Komoot — fallback (direct from device, no key needed)
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
                        id         = station.id,
                        name       = station.name,
                        subtitle   = "India · Railway Station",
                        lat        = station.lat,
                        lon        = station.lon,
                        confidence = "exact"
                    )
                }
                return
            }

            // ── Tier 2: LocationIQ via Cloudflare Worker ──────────────────────
            try {
                val results = GeocodingClient.locationIqService.autocomplete(query = q)
                if (results.isNotEmpty()) {
                    _searchResults.value = results.map { it.toSearchResult() }
                    return
                }
            } catch (e: Exception) {
                android.util.Log.w("MapSearch", "LocationIQ unavailable, falling back to Photon", e)
            }

            // ── Tier 3: Photon by Komoot (direct from device) ─────────────────
            val bias = _initialCenter.value
            val photonResponse = GeocodingClient.photonService.search(
                query = q,
                lat   = bias?.latitude,
                lon   = bias?.longitude
            )
            _searchResults.value = photonResponse.features.map { it.toSearchResult() }

        } catch (e: Exception) {
            _searchError.value = "Search unavailable"
            _searchResults.value = emptyList()
            android.util.Log.e("MapSearch", "All geocoding failed for '$q'", e)
        } finally {
            _isSearching.value = false
        }
    }

    // ── Map interactions ──────────────────────────────────────────────────────

    fun selectResult(result: GeoSearchResult) {
        _selectedResult.value = result
        _searchResults.value = emptyList()
        _query.value = ""
    }

    fun selectSavedPlace(place: SavedPlace) {
        _selectedResult.value = GeoSearchResult(
            id         = place.id,
            name       = place.name,
            subtitle   = "Saved · ${String.format("%.4f", place.lat)}, ${String.format("%.4f", place.lon)}",
            lat        = place.lat,
            lon        = place.lon,
            confidence = "exact"
        )
        _radiusKm.value = place.radiusKm
    }

    /**
     * Called when user taps anywhere on the map.
     * Immediately drops a pin with coordinates, then reverse geocodes in background.
     * Two-tier: LocationIQ → Photon fallback.
     */
    fun onMapTap(lat: Double, lon: Double) {
        _selectedResult.value = GeoSearchResult(
            id         = "manual-${System.currentTimeMillis()}",
            name       = "Dropped Pin",
            subtitle   = "Loading address...",
            lat        = lat,
            lon        = lon,
            confidence = "exact"
        )

        viewModelScope.launch {
            // ── Tier 1: LocationIQ reverse via Worker ─────────────────────────
            try {
                val result = GeocodingClient.locationIqService.reverse(lat = lat, lon = lon)
                _selectedResult.value = result.toSearchResult(lat, lon)
                return@launch
            } catch (e: Exception) {
                android.util.Log.w("MapSearch", "LocationIQ reverse failed, trying Photon", e)
            }

            // ── Tier 2: Photon reverse (direct from device) ───────────────────
            try {
                val photonResponse = GeocodingClient.photonService.reverse(lat = lat, lon = lon)
                val feature = photonResponse.features.firstOrNull()
                if (feature != null) {
                    _selectedResult.value = feature.toSearchResult().copy(lat = lat, lon = lon)
                    return@launch
                }
            } catch (e: Exception) {
                android.util.Log.w("MapSearch", "Photon reverse also failed", e)
            }

            // ── Fallback: raw coordinates ─────────────────────────────────────
            _selectedResult.value = _selectedResult.value?.copy(
                name     = "Dropped Pin",
                subtitle = "${String.format("%.4f", lat)}, ${String.format("%.4f", lon)}"
            )
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
                }
            }
    }

    // ── Favorites CRUD ────────────────────────────────────────────────────────

    fun savePlace(name: String, notes: String?) {
        val result = _selectedResult.value ?: return
        val place = SavedPlace(
            id      = "custom-${UUID.randomUUID()}",
            name    = name.ifBlank { result.name },
            lat     = result.lat,
            lon     = result.lon,
            radiusKm = _radiusKm.value,
            notes   = notes?.ifBlank { null }
        )
        StationRepository.saveFavoritePlace(place)
    }

    fun deletePlace(placeId: String) {
        StationRepository.deleteFavoritePlace(placeId)
    }
}
