package com.omama.stationalarm.ui.viewmodel

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.location.LocationServices
import com.omama.stationalarm.BuildConfig
import com.omama.stationalarm.data.SavedPlace
import com.omama.stationalarm.network.GeoSearchResult
import com.omama.stationalarm.network.RetrofitClient
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

    /** Currently selected/pinned location (set by search OR long press). */
    private val _selectedResult = MutableStateFlow<GeoSearchResult?>(null)
    val selectedResult: StateFlow<GeoSearchResult?> = _selectedResult.asStateFlow()

    /** Radius slider value set by user (3–20 km). */
    private val _radiusKm = MutableStateFlow(3.0)
    val radiusKm: StateFlow<Double> = _radiusKm.asStateFlow()

    /** User's GPS location — emitted once when they tap the "My Location" button. */
    private val _userLocation = MutableSharedFlow<GeoPoint>(replay = 1)
    val userLocation: SharedFlow<GeoPoint> = _userLocation.asSharedFlow()

    /** Initial map center — set to last GPS fix on first load. */
    private val _initialCenter = MutableStateFlow<GeoPoint?>(null)
    val initialCenter: StateFlow<GeoPoint?> = _initialCenter.asStateFlow()

    // ── Saved places ──────────────────────────────────────────────────────────

    val savedPlaces: StateFlow<List<SavedPlace>> = StationRepository.savedPlacesFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // ── Init: fetch initial GPS position once ─────────────────────────────────

    init {
        // Kick off search pipeline
        viewModelScope.launch {
            _query
                .debounce(350)
                .filter { it.length >= 3 }
                .distinctUntilChanged()
                .collect { q -> performSearch(q) }
        }

        // Prime initial map center with last known GPS position
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

    private suspend fun performSearch(q: String) {
        _isSearching.value = true
        _searchError.value = null
        try {
            val response = RetrofitClient.geocodingService.search(
                query = q,
                token = BuildConfig.MAPBOX_ACCESS_TOKEN,
                limit = 5
            )
            _searchResults.value = response.features.map { it.toSearchResult() }
        } catch (e: Exception) {
            _searchError.value = "Search failed: ${e.message}"
            _searchResults.value = emptyList()
            android.util.Log.e("MapSearch", "Mapbox search failed for '$q'", e)
        } finally {
            _isSearching.value = false
        }
    }

    // ── Map interactions ──────────────────────────────────────────────────────

    /** Called when user selects a search result — pin auto-drops at result coords. */
    fun selectResult(result: GeoSearchResult) {
        _selectedResult.value = result
        _searchResults.value = emptyList()
        _query.value = ""
        // Radius stays at current value (user may already have adjusted it)
    }

    /** Called when user selects an existing saved place from Favorites. */
    fun selectSavedPlace(place: SavedPlace) {
        _selectedResult.value = GeoSearchResult(
            id = place.id,
            name = place.name,
            subtitle = "Saved • ${String.format("%.4f", place.lat)}, ${String.format("%.4f", place.lon)}",
            lat = place.lat,
            lon = place.lon,
            confidence = "exact"
        )
        _radiusKm.value = place.radiusKm
    }

    /**
     * Called when user taps (single or long-press) anywhere on the map.
     * The pin moves to the tapped coords — coords come from OSM map projection,
     * not from Mapbox (TOS-safe).
     */
    fun onMapTap(lat: Double, lon: Double) {
        // Drop an initial temporary pin with coords
        _selectedResult.value = GeoSearchResult(
            id = "manual-${System.currentTimeMillis()}",
            name = "Fetching...",
            subtitle = "Loading address...",
            lat = lat,
            lon = lon,
            confidence = "exact"
        )

        // Launch reverse geocoding to fill in the exact street name
        viewModelScope.launch {
            try {
                val response = RetrofitClient.geocodingService.reverseSearch(
                    longitude = lon,
                    latitude = lat,
                    token = BuildConfig.MAPBOX_ACCESS_TOKEN
                )

                // Pick the most specific feature from the results
                // Priority: address > street > neighborhood > locality > place
                val typePriority = listOf("address", "street", "neighborhood", "locality", "place")
                val bestFeature = response.features
                    .sortedBy { feature ->
                        val ft = feature.properties.featureType ?: ""
                        val idx = typePriority.indexOf(ft)
                        if (idx >= 0) idx else typePriority.size
                    }
                    .firstOrNull()

                if (bestFeature != null) {
                    val displayName = bestFeature.properties.displayName
                    val structuredAddr = bestFeature.properties.structuredAddress

                    _selectedResult.value = GeoSearchResult(
                        id = "manual-${System.currentTimeMillis()}",
                        name = displayName,
                        subtitle = structuredAddr,
                        lat = lat,
                        lon = lon,
                        confidence = "exact"
                    )
                } else {
                    // No results at all — show raw coordinates
                    _selectedResult.value = _selectedResult.value?.copy(
                        name = "Dropped Pin",
                        subtitle = "${String.format("%.4f", lat)}, ${String.format("%.4f", lon)}"
                    )
                }
            } catch (e: Exception) {
                // Network error — show coords fallback
                android.util.Log.e("MapSearchViewModel", "Reverse geocoding failed", e)
                _selectedResult.value = _selectedResult.value?.copy(
                    name = "Dropped Pin",
                    subtitle = "${String.format("%.4f", lat)}, ${String.format("%.4f", lon)}"
                )
            }
        }
    }

    /** Slider callback — clamps to 3–20 km. */
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

    /** Triggered when user taps the ⦿ FAB. Emits location to the map. */
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

    /**
     * Saves to Room DB using the pin's current lat/lon from the map
     * (not from Mapbox response) — this is the TOS-safe approach.
     */
    fun savePlace(name: String, notes: String?) {
        val result = _selectedResult.value ?: return
        val place = SavedPlace(
            id = "custom-${UUID.randomUUID()}",
            name = name.ifBlank { result.name },
            lat = result.lat,   // These are OSM map-interaction coords after first long-press/adjustment
            lon = result.lon,
            radiusKm = _radiusKm.value,
            notes = notes?.ifBlank { null }
        )
        StationRepository.saveFavoritePlace(place)
    }

    fun deletePlace(placeId: String) {
        StationRepository.deleteFavoritePlace(placeId)
    }
}
