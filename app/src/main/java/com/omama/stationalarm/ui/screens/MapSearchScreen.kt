package com.omama.stationalarm.ui.screens

import android.view.ViewGroup
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import com.omama.stationalarm.data.SavedPlace
import com.omama.stationalarm.data.Station
import com.omama.stationalarm.network.GeoSearchResult
import com.omama.stationalarm.ui.viewmodel.MapSearchViewModel
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon

@Composable
fun MapSearchScreen(
    onStartTrip: (Station, Double) -> Unit,  // Station + alertDistanceKm
    viewModel: MapSearchViewModel = viewModel()
) {
    val query by viewModel.query.collectAsState()
    val searchResults by viewModel.searchResults.collectAsState()
    val isSearching by viewModel.isSearching.collectAsState()
    val selectedResult by viewModel.selectedResult.collectAsState()
    val radiusKm by viewModel.radiusKm.collectAsState()
    val savedPlaces by viewModel.savedPlaces.collectAsState()
    val searchError by viewModel.searchError.collectAsState()
    var showSaveDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // ── Search Bar ────────────────────────────────────────────────────────
        OutlinedTextField(
            value = query,
            onValueChange = viewModel::onQueryChanged,
            placeholder = { Text("Search any place, bus stand, temple…") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { viewModel.onQueryChanged("") }) {
                        Icon(Icons.Default.Close, contentDescription = "Clear")
                    }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .shadow(4.dp, RoundedCornerShape(14.dp)),
            shape = RoundedCornerShape(14.dp),
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = Color.Transparent,
                focusedContainerColor = MaterialTheme.colorScheme.surface,
                unfocusedContainerColor = MaterialTheme.colorScheme.surface,
            )
        )

        // ── Search Error ──────────────────────────────────────────────────────
        AnimatedVisibility(visible = searchError != null) {
            Text(
                text = searchError ?: "",
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp),
                fontSize = 13.sp
            )
        }

        // ── Loading indicator ─────────────────────────────────────────────────
        AnimatedVisibility(visible = isSearching) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        // ── Search Results (overlaid above map) ───────────────────────────────
        AnimatedVisibility(
            visible = searchResults.isNotEmpty(),
            enter = fadeIn() + slideInVertically(),
            exit = fadeOut()
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 280.dp)
                    .padding(horizontal = 12.dp)
                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp)),
                contentPadding = PaddingValues(vertical = 4.dp)
            ) {
                items(searchResults) { result ->
                    SearchResultItem(result) {
                        viewModel.selectResult(result)
                    }
                    HorizontalDivider(color = Color.Gray.copy(alpha = 0.15f))
                }
            }
        }

        // ── Map + Saved Places (main content) ─────────────────────────────────
        if (selectedResult != null) {
            // Full map view when a result is selected
            Box(modifier = Modifier.weight(1f)) {
                OsmMapView(
                    lat = selectedResult!!.lat,
                    lon = selectedResult!!.lon,
                    radiusKm = radiusKm,
                    onLongPress = { lat, lon -> viewModel.onMapLongPress(lat, lon) },
                    modifier = Modifier.fillMaxSize()
                )
                // Semi-opaque bottom sheet for controls
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
                            RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
                        )
                        .padding(16.dp)
                ) {
                    Text(
                        text = selectedResult!!.name,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (selectedResult!!.subtitle.isNotBlank()) {
                        Text(
                            text = selectedResult!!.subtitle,
                            fontSize = 13.sp,
                            color = Color.Gray
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))

                    // Radius slider
                    Text(
                        text = "Alert radius: ${"%.1f".format(radiusKm)} km",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Slider(
                        value = radiusKm.toFloat(),
                        onValueChange = { viewModel.onRadiusChanged(it.toDouble()) },
                        valueRange = 1f..10f,
                        steps = 17,  // 0.5 km steps
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        OutlinedButton(
                            onClick = { showSaveDialog = true },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("⭐ Save Place")
                        }
                        Button(
                            onClick = {
                                val result = selectedResult ?: return@Button
                                val station = Station(
                                    id = result.id,
                                    name = result.name,
                                    lat = result.lat,
                                    lon = result.lon
                                )
                                onStartTrip(station, radiusKm)
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Set Alarm")
                        }
                    }
                    OutlinedButton(
                        onClick = { viewModel.clearSelection() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("← Back to Search")
                    }
                }
            }
        } else {
            // Saved places list when no pin is selected
            if (savedPlaces.isEmpty()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("🗺️", fontSize = 48.sp)
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            "Search for a place above",
                            fontSize = 16.sp,
                            color = Color.Gray
                        )
                        Text(
                            "Saved places will appear here",
                            fontSize = 13.sp,
                            color = Color.Gray.copy(alpha = 0.7f)
                        )
                    }
                }
            } else {
                Text(
                    "Saved Places",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    color = MaterialTheme.colorScheme.onBackground
                )
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(savedPlaces, key = { it.id }) { place ->
                        SavedPlaceCard(
                            place = place,
                            onSelect = { viewModel.selectSavedPlace(place) },
                            onDelete = { viewModel.deletePlace(place.id) }
                        )
                    }
                }
            }
        }
    }

    // ── Save Favorite Dialog ──────────────────────────────────────────────────
    if (showSaveDialog) {
        SaveFavoriteDialog(
            initialName = selectedResult?.name ?: "",
            onSave = { name, notes ->
                viewModel.savePlace(name, notes)
                showSaveDialog = false
            },
            onDismiss = { showSaveDialog = false }
        )
    }
}

// ── Sub-composables ───────────────────────────────────────────────────────────

@Composable
private fun SearchResultItem(result: GeoSearchResult, onClick: () -> Unit) {
    val confidenceColor = when (result.confidence) {
        "exact", "high" -> Color(0xFF2E7D32)
        "medium"        -> Color(0xFFF57F17)
        else            -> Color(0xFFC62828)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(result.name, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            if (result.subtitle.isNotBlank()) {
                Text(result.subtitle, fontSize = 12.sp, color = Color.Gray)
            }
        }
        Surface(
            shape = RoundedCornerShape(4.dp),
            color = confidenceColor.copy(alpha = 0.15f)
        ) {
            Text(
                text = result.confidence,
                color = confidenceColor,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
            )
        }
    }
}

@Composable
private fun SavedPlaceCard(
    place: SavedPlace,
    onSelect: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(3.dp, RoundedCornerShape(12.dp))
            .clickable(onClick = onSelect),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(place.name, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Text(
                    "Radius: ${"%.1f".format(place.radiusKm)} km",
                    fontSize = 12.sp,
                    color = Color.Gray
                )
                if (!place.notes.isNullOrBlank()) {
                    Text(place.notes, fontSize = 12.sp, color = Color.Gray)
                }
            }
            IconButton(onClick = onDelete) {
                Text("✕", color = Color.Gray, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun OsmMapView(
    lat: Double,
    lon: Double,
    radiusKm: Double,
    onLongPress: (Double, Double) -> Unit,
    modifier: Modifier = Modifier
) {
    AndroidView(
        factory = { ctx ->
            MapView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                setTileSource(TileSourceFactory.MAPNIK)
                setMultiTouchControls(true)
                controller.setZoom(14.0)
                controller.setCenter(GeoPoint(lat, lon))

                // Long press listener
                val mReceive = object : MapEventsReceiver {
                    override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean = false
                    override fun longPressHelper(p: GeoPoint?): Boolean {
                        p?.let { onLongPress(it.latitude, it.longitude) }
                        return true
                    }
                }
                overlays.add(MapEventsOverlay(mReceive))
            }
        },
        update = { mapView ->
            // Keep existing pin/circle logic but update center if result changes
            val center = GeoPoint(lat, lon)
            
            // Clear only markers and polygons, keep the MapEventsOverlay
            mapView.overlays.removeAll { it is Marker || it is Polygon }

            // Pin marker
            val marker = Marker(mapView).apply {
                position = center
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            }
            mapView.overlays.add(marker)

            // Radius circle — approximate using polygon points
            val circlePoints = (0..360 step 5).map { angle ->
                val angleRad = Math.toRadians(angle.toDouble())
                val dLat = (radiusKm / 111.0) * Math.cos(angleRad)
                val dLon = (radiusKm / (111.0 * Math.cos(Math.toRadians(lat)))) * Math.sin(angleRad)
                GeoPoint(lat + dLat, lon + dLon)
            }
            val polygon = Polygon(mapView).apply {
                points = circlePoints
                fillPaint.color = android.graphics.Color.argb(40, 33, 150, 243)
                outlinePaint.color = android.graphics.Color.argb(200, 33, 150, 243)
                outlinePaint.strokeWidth = 3f
            }
            mapView.overlays.add(polygon)
            mapView.invalidate()
        },
        modifier = modifier
    )
}

@Composable
private fun SaveFavoriteDialog(
    initialName: String,
    onSave: (name: String, notes: String?) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(initialName) }
    var notes by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Save this place?", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("Notes (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(onClick = { onSave(name, notes.ifBlank { null }) }) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
