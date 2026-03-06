package com.omama.stationalarm.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.location.LocationServices
import com.omama.stationalarm.data.SavedPlace
import com.omama.stationalarm.network.GeoSearchResult
import com.omama.stationalarm.network.RetrofitClient
import com.omama.stationalarm.network.toSearchResult
import com.omama.stationalarm.repository.StationRepository
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.UUID

@OptIn(FlowPreview::class)
class MapSearchViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        // Replace with your token from local.properties in production
        // For now, set it here or inject via BuildConfig
        const val MAPBOX_TOKEN = "YOUR_MAPBOX_ACCESS_TOKEN"
    }

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

    /** Currently selected/pinned location. */
    private val _selectedResult = MutableStateFlow<GeoSearchResult?>(null)
    val selectedResult: StateFlow<GeoSearchResult?> = _selectedResult.asStateFlow()

    /** Radius slider value set by user (1–10 km). */
    private val _radiusKm = MutableStateFlow(5.0)
    val radiusKm: StateFlow<Double> = _radiusKm.asStateFlow()

    // ── Saved places ──────────────────────────────────────────────────────────

    val savedPlaces: StateFlow<List<SavedPlace>> = StationRepository.savedPlacesFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // ── Debounced search pipeline ─────────────────────────────────────────────

    init {
        viewModelScope.launch {
            _query
                .debounce(350)
                .filter { it.length >= 3 }
                .distinctUntilChanged()
                .collect { q ->
                    performSearch(q)
                }
        }
    }

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
            val proximity = getUserProximityString()
            val response = RetrofitClient.geocodingService.search(
                query = q,
                token = MAPBOX_TOKEN,
                proximity = proximity
            )
            _searchResults.value = response.features.map { it.toSearchResult() }
        } catch (e: Exception) {
            _searchError.value = "Search failed. Check your connection."
            _searchResults.value = emptyList()
        } finally {
            _isSearching.value = false
        }
    }

    /** Try to get last known location for proximity bias in search. */
    private fun getUserProximityString(): String? {
        return try {
            // Best-effort: use last known location synchronously
            // In production, integrate with FusedLocationClient flow
            null
        } catch (e: Exception) {
            null
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
            id = place.id,
            name = place.name,
            subtitle = "Saved place • ${place.radiusKm} km",
            lat = place.lat,
            lon = place.lon,
            confidence = "exact"
        )
        _radiusKm.value = place.radiusKm
    }

    fun onRadiusChanged(km: Double) {
        _radiusKm.value = km
    }

    fun clearSelection() {
        _selectedResult.value = null
        _radiusKm.value = 5.0
    }

    // ── Favorites management ──────────────────────────────────────────────────

    fun savePlace(name: String, notes: String?) {
        val result = _selectedResult.value ?: return
        val place = SavedPlace(
            id = "custom-${UUID.randomUUID()}",
            name = name.ifBlank { result.name },
            lat = result.lat,
            lon = result.lon,
            radiusKm = _radiusKm.value,
            notes = notes?.ifBlank { null }
        )
        StationRepository.saveFavoritePlace(place)
    }

    fun onMapLongPress(lat: Double, lon: Double) {
        _selectedResult.value = GeoSearchResult(
            id = "manual-${System.currentTimeMillis()}",
            name = "Dropped Pin",
            subtitle = "${"%.4f".format(lat)}, ${"%.4f".format(lon)}",
            lat = lat,
            lon = lon,
            confidence = "exact"
        )
    }

    fun deletePlace(placeId: String) {
        StationRepository.deleteFavoritePlace(placeId)
    }
}
