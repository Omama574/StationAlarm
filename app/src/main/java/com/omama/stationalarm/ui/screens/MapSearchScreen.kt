package com.omama.stationalarm.ui.screens

import android.view.ViewGroup
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import com.omama.stationalarm.R
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import com.omama.stationalarm.data.SavedPlace
import com.omama.stationalarm.data.Station
import com.omama.stationalarm.network.GeoSearchResult
import com.omama.stationalarm.ui.viewmodel.MapSearchViewModel
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon

// ── Colours specific to Map tab ───────────────────────────────────────────────
private val MapAccentBlue    = Color(0xFF4FC3F7)
private val MapAccentBlueDim = Color(0x334FC3F7)
private val SheetBg          = Color(0xFF1A1A2E)
private val SheetBgLight     = Color(0xFFF5F5F5)
private val ConfirmGreen     = Color(0xFF00C853)

@Composable
fun MapSearchScreen(
    onStartTrip: (Station, Double) -> Unit,
    isGpsEnabled: () -> Boolean,
    onRequestGps: () -> Unit,
    viewModel: MapSearchViewModel = viewModel()
) {
    val query           by viewModel.query.collectAsState()
    val searchResults   by viewModel.searchResults.collectAsState()
    val isSearching     by viewModel.isSearching.collectAsState()
    val selectedResult  by viewModel.selectedResult.collectAsState()
    val radiusKm        by viewModel.radiusKm.collectAsState()
    val savedPlaces     by viewModel.savedPlaces.collectAsState()
    val searchError     by viewModel.searchError.collectAsState()
    val initialCenter   by viewModel.initialCenter.collectAsState()

    // Controls whether the "Name & Save" dialog is shown
    var showNameDialog by remember { mutableStateOf(false) }

    // Shared MapView reference so the FAB can trigger animateToCenter
    var mapViewRef by remember { mutableStateOf<MapView?>(null) }

    // Collect location events from FAB
    LaunchedEffect(Unit) {
        viewModel.userLocation.collect { geoPoint ->
            mapViewRef?.controller?.animateTo(geoPoint)
        }
    }

    // Pan to initial GPS location once we get it
    LaunchedEffect(initialCenter) {
        val center = initialCenter
        if (center != null && selectedResult == null) {
            mapViewRef?.controller?.setCenter(center)
        }
    }

    // ── Root: map fills everything, UI overlays on top ────────────────────────
    Box(modifier = Modifier.fillMaxSize().clipToBounds()) {

        // ── 1. Full-screen map ────────────────────────────────────────────────
        OsmMapView(
            initialCenter = initialCenter,
            selectedLat   = selectedResult?.lat,
            selectedLon   = selectedResult?.lon,
            radiusKm      = radiusKm,
            onLongPress   = { lat, lon -> 
                if (isGpsEnabled()) viewModel.onMapLongPress(lat, lon) else onRequestGps()
            },
            onMapReady    = { mv -> mapViewRef = mv },
            modifier      = Modifier.fillMaxSize()
        )

        // ── 2. Top overlay: search bar + results dropdown ─────────────────────
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .padding(top = 12.dp, start = 12.dp, end = 12.dp)
        ) {
            // Search bar card
            Surface(
                modifier  = Modifier
                    .fillMaxWidth()
                    .shadow(12.dp, RoundedCornerShape(16.dp)),
                shape     = RoundedCornerShape(16.dp),
                color     = MaterialTheme.colorScheme.surface,
                tonalElevation = 8.dp
            ) {
                Row(
                    modifier           = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 4.dp),
                    verticalAlignment  = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Search,
                        contentDescription = null,
                        tint   = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        modifier = Modifier.size(20.dp)
                    )
                    TextField(
                        value            = query,
                        onValueChange    = viewModel::onQueryChanged,
                        placeholder      = {
                            Text(
                                "Search a place, landmark, address…",
                                color    = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                                fontSize = 14.sp
                            )
                        },
                        singleLine       = true,
                        colors           = TextFieldDefaults.colors(
                            focusedContainerColor   = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor   = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                        ),
                        modifier         = Modifier.weight(1f),
                        textStyle        = LocalTextStyle.current.copy(fontSize = 15.sp)
                    )
                    AnimatedVisibility(visible = query.isNotEmpty()) {
                        IconButton(onClick = { viewModel.onQueryChanged("") }) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Clear",
                                tint   = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }

            // Thin loading strip
            AnimatedVisibility(visible = isSearching) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                    color = MapAccentBlue
                )
            }

            // Error text
            AnimatedVisibility(visible = searchError != null) {
                Text(
                    searchError ?: "",
                    color    = MaterialTheme.colorScheme.error,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                )
            }

            // Results dropdown
            AnimatedVisibility(
                visible = searchResults.isNotEmpty(),
                enter   = fadeIn() + expandVertically(),
                exit    = fadeOut() + shrinkVertically()
            ) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp)
                        .shadow(8.dp, RoundedCornerShape(14.dp)),
                    shape    = RoundedCornerShape(14.dp),
                    color    = MaterialTheme.colorScheme.surface
                ) {
                    LazyColumn(contentPadding = PaddingValues(vertical = 6.dp)) {
                        items(searchResults) { result ->
                            SearchResultRow(result = result) {
                                if (isGpsEnabled()) {
                                    viewModel.selectResult(result)
                                } else {
                                    onRequestGps()
                                }
                            }
                        }
                    }
                }
            }
        }

        // ── 3. My Location FAB ────────────────────────────────────────────────
        val fabBottomPad = if (selectedResult != null) 256.dp else 12.dp
        FloatingActionButton(
            onClick              = {
                if (isGpsEnabled()) viewModel.onMyLocationRequested() else onRequestGps()
            },
            modifier             = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 16.dp, bottom = fabBottomPad)
                .size(48.dp),
            shape                = CircleShape,
            containerColor       = MaterialTheme.colorScheme.surface,
            contentColor         = MapAccentBlue,
            elevation            = FloatingActionButtonDefaults.elevation(6.dp)
        ) {
            Icon(
                ImageVector.vectorResource(R.drawable.ic_gps_fixed),
                contentDescription = "My location",
                modifier = Modifier.size(22.dp)
            )
        }

        // ── 4. Bottom sheet (visible when a pin is set) ───────────────────────
        AnimatedVisibility(
            visible = selectedResult != null,
            enter   = slideInVertically { it },
            exit    = slideOutVertically { it },
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            val pin = selectedResult
            if (pin != null) {
                PinBottomSheet(
                    result      = pin,
                    radiusKm    = radiusKm,
                    onRadius    = viewModel::onRadiusChanged,
                    onSaveClick = { showNameDialog = true },
                    onAlarmClick = {
                        onStartTrip(
                            Station(id = pin.id, name = pin.name, lat = pin.lat, lon = pin.lon),
                            radiusKm
                        )
                    },
                    onBack      = viewModel::clearSelection
                )
            }
        }

        // ── 5. Saved places panel (when no pin and no search active) ──────────
        AnimatedVisibility(
            visible  = selectedResult == null && searchResults.isEmpty() && !isSearching,
            enter    = fadeIn(),
            exit     = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            SavedPlacesPanel(
                savedPlaces = savedPlaces,
                onSelect    = viewModel::selectSavedPlace,
                onDelete    = viewModel::deletePlace
            )
        }
    }

    // ── Save dialog ───────────────────────────────────────────────────────────
    if (showNameDialog) {
        SaveFavoriteDialog(
            initialName = selectedResult?.name ?: "",
            onSave      = { name, notes ->
                viewModel.savePlace(name, notes)
                showNameDialog = false
            },
            onDismiss   = { showNameDialog = false }
        )
    }
}

// ── Sub-composables ───────────────────────────────────────────────────────────

@Composable
private fun SearchResultRow(result: GeoSearchResult, onClick: () -> Unit) {
    val (chipColor, chipText) = when (result.confidence) {
        "exact"  -> Pair(Color(0xFF00C853), "Exact")
        "high"   -> Pair(Color(0xFF00BCD4), "High")
        "medium" -> Pair(Color(0xFFFFA726), "Medium")
        else     -> Pair(Color(0xFFEF5350), "Low")
    }
    Row(
        modifier           = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment  = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Location icon with accent background circle
        Box(
            modifier        = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(MapAccentBlueDim),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                ImageVector.vectorResource(R.drawable.ic_location_on),
                contentDescription = null,
                tint     = MapAccentBlue,
                modifier = Modifier.size(18.dp)
            )
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                result.name,
                fontWeight   = FontWeight.SemiBold,
                fontSize     = 14.sp,
                maxLines     = 1,
                overflow     = TextOverflow.Ellipsis,
                color        = MaterialTheme.colorScheme.onSurface
            )
            if (result.subtitle.isNotBlank()) {
                Text(
                    result.subtitle,
                    fontSize = 12.sp,
                    color    = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Surface(
            shape = RoundedCornerShape(20.dp),
            color = chipColor.copy(alpha = 0.13f)
        ) {
            Text(
                chipText,
                color      = chipColor,
                fontSize   = 10.sp,
                fontWeight = FontWeight.Bold,
                modifier   = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
            )
        }
    }
    HorizontalDivider(
        color     = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f),
        thickness = 0.5.dp,
        modifier  = Modifier.padding(horizontal = 14.dp)
    )
}

@Composable
private fun PinBottomSheet(
    result     : GeoSearchResult,
    radiusKm   : Double,
    onRadius   : (Double) -> Unit,
    onSaveClick: () -> Unit,
    onAlarmClick: () -> Unit,
    onBack     : () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape    = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        color    = MaterialTheme.colorScheme.surface,
        tonalElevation = 12.dp,
        shadowElevation = 16.dp
    ) {
        Column(modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 20.dp)) {

            // Handle pill
            Box(
                Modifier
                    .width(40.dp)
                    .height(4.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f))
                    .align(Alignment.CenterHorizontally)
            )
            Spacer(Modifier.height(12.dp))

            // Location name + coords
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(MapAccentBlueDim),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        ImageVector.vectorResource(R.drawable.ic_location_on),
                        null,
                        tint = MapAccentBlue,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        result.name,
                        fontWeight = FontWeight.Bold,
                        fontSize   = 16.sp,
                        maxLines   = 1,
                        overflow   = TextOverflow.Ellipsis,
                        color      = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        "${String.format("%.4f", result.lat)}, ${String.format("%.4f", result.lon)}",
                        fontSize = 11.sp,
                        color    = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            // Radius label
            Row(
                modifier              = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment     = Alignment.CenterVertically
            ) {
                Text("Alert Radius", fontWeight = FontWeight.Medium, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    "${String.format("%.1f", radiusKm)} km",
                    fontWeight = FontWeight.Bold,
                    fontSize   = 14.sp,
                    color      = MapAccentBlue
                )
            }

            // Slider
            Slider(
                value        = radiusKm.toFloat(),
                onValueChange = { onRadius(it.toDouble()) },
                valueRange   = 2f..20f,
                modifier     = Modifier.fillMaxWidth(),
                colors       = SliderDefaults.colors(
                    thumbColor       = MapAccentBlue,
                    activeTrackColor = MapAccentBlue,
                    inactiveTrackColor = MapAccentBlueDim
                )
            )

            Row(
                modifier              = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("2 km", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
                Text("20 km", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
            }

            Spacer(Modifier.height(16.dp))

            // Action buttons
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier              = Modifier.fillMaxWidth()
            ) {
                OutlinedButton(
                    onClick  = onSaveClick,
                    modifier = Modifier.weight(1f),
                    shape    = RoundedCornerShape(12.dp),
                    border   = ButtonDefaults.outlinedButtonBorder(true).copy(width = 1.dp)
                ) {
                    Icon(
                        ImageVector.vectorResource(R.drawable.ic_star),
                        null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("Save Place", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
                Button(
                    onClick  = onAlarmClick,
                    modifier = Modifier.weight(1f),
                    shape    = RoundedCornerShape(12.dp),
                    colors   = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Text("Set Alarm 🔔", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }

            TextButton(
                onClick  = onBack,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp)
            ) {
                Text(
                    "← Back to Search",
                    fontSize = 13.sp,
                    color    = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                )
            }
        }
    }
}

@Composable
private fun SavedPlacesPanel(
    savedPlaces: List<SavedPlace>,
    onSelect   : (SavedPlace) -> Unit,
    onDelete   : (String) -> Unit
) {
    if (savedPlaces.isEmpty()) return

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 260.dp),
        shape    = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        color    = MaterialTheme.colorScheme.surface,
        tonalElevation  = 6.dp,
        shadowElevation = 10.dp
    ) {
        Column(modifier = Modifier.padding(top = 12.dp)) {
            // Handle pill
            Box(
                Modifier
                    .width(40.dp)
                    .height(4.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f))
                    .align(Alignment.CenterHorizontally)
            )
            Spacer(Modifier.height(10.dp))
            Text(
                "⭐  Saved Places",
                fontWeight = FontWeight.Bold,
                fontSize   = 14.sp,
                modifier   = Modifier.padding(horizontal = 18.dp),
                color      = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(6.dp))
            LazyColumn(
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(savedPlaces, key = { it.id }) { place ->
                    SavedPlaceRow(place = place, onSelect = { onSelect(place) }, onDelete = { onDelete(place.id) })
                }
            }
        }
    }
}

@Composable
private fun SavedPlaceRow(place: SavedPlace, onSelect: () -> Unit, onDelete: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect),
        shape  = RoundedCornerShape(12.dp),
        color  = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
    ) {
        Row(
            modifier           = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment  = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(MapAccentBlueDim),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    ImageVector.vectorResource(R.drawable.ic_star),
                    null,
                    tint = MapAccentBlue,
                    modifier = Modifier.size(16.dp)
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    place.name,
                    fontWeight = FontWeight.SemiBold,
                    fontSize   = 14.sp,
                    maxLines   = 1,
                    overflow   = TextOverflow.Ellipsis,
                    color      = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    "${String.format("%.1f", place.radiusKm)} km radius  •  ${String.format("%.3f", place.lat)}, ${String.format("%.3f", place.lon)}",
                    fontSize = 11.sp,
                    color    = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.Close, null, tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f), modifier = Modifier.size(16.dp))
            }
        }
    }
}

@Composable
private fun OsmMapView(
    initialCenter: GeoPoint?,
    selectedLat  : Double?,
    selectedLon  : Double?,
    radiusKm     : Double,
    onLongPress  : (Double, Double) -> Unit,
    onMapReady   : (MapView) -> Unit,
    modifier     : Modifier = Modifier
) {
    // Default center: India if GPS unavailable
    val startLat = initialCenter?.latitude  ?: 20.5937
    val startLon = initialCenter?.longitude ?: 78.9629

    AndroidView(
        factory = { ctx ->
            MapView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                setTileSource(TileSourceFactory.MAPNIK)
                setMultiTouchControls(true)
                controller.setZoom(if (initialCenter != null) 17.5 else 5.0)
                controller.setCenter(GeoPoint(startLat, startLon))

                // Long press listener
                val receiver = object : MapEventsReceiver {
                    override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean = false
                    override fun longPressHelper(p: GeoPoint?): Boolean {
                        p?.let { onLongPress(it.latitude, it.longitude) }
                        return true
                    }
                }
                overlays.add(MapEventsOverlay(receiver))
                onMapReady(this)
            }
        },
        update = { mapView ->
            // Remove old pin/circle but keep MapEventsOverlay
            mapView.overlays.removeAll { it is Marker || it is Polygon }

            if (selectedLat != null && selectedLon != null) {
                val center = GeoPoint(selectedLat, selectedLon)

                // Animate to the selected location
                mapView.controller.animateTo(center)
                mapView.controller.setZoom(14.0)

                // Pin marker
                val marker = Marker(mapView).apply {
                    position = center
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    title = "Selected location"
                }
                mapView.overlays.add(marker)

                // Geofence circle
                val circlePoints = (0..360 step 4).map { angle ->
                    val rad  = Math.toRadians(angle.toDouble())
                    val dLat = (radiusKm / 111.0) * Math.cos(rad)
                    val dLon = (radiusKm / (111.0 * Math.cos(Math.toRadians(selectedLat)))) * Math.sin(rad)
                    GeoPoint(selectedLat + dLat, selectedLon + dLon)
                }
                val polygon = Polygon(mapView).apply {
                    points = circlePoints
                    fillPaint.color    = android.graphics.Color.argb(45, 79, 195, 247)
                    outlinePaint.color = android.graphics.Color.argb(210, 79, 195, 247)
                    outlinePaint.strokeWidth = 3.5f
                }
                mapView.overlays.add(polygon)

            } else if (initialCenter != null) {
                // No pin selected — show a "You are here" blue dot at GPS location
                val userMarker = Marker(mapView).apply {
                    position = initialCenter
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    title = "You are here"
                    // Use a simple blue circle drawable for the "my location" dot
                    icon = android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.OVAL
                        setSize(48, 48)
                        setColor(android.graphics.Color.argb(220, 79, 195, 247))
                        setStroke(4, android.graphics.Color.WHITE)
                    }
                }
                mapView.overlays.add(userMarker)
                mapView.controller.setCenter(initialCenter)
                mapView.controller.setZoom(17.5)
            }
            mapView.invalidate()
        },
        modifier = modifier
    )
}

// ── Save dialog ───────────────────────────────────────────────────────────────

@Composable
private fun SaveFavoriteDialog(
    initialName: String,
    onSave     : (name: String, notes: String?) -> Unit,
    onDismiss  : () -> Unit
) {
    var name  by remember { mutableStateOf(initialName) }
    var notes by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape            = RoundedCornerShape(20.dp),
        title = {
            Text(
                "Name this place",
                fontWeight = FontWeight.Bold,
                fontSize   = 18.sp
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value         = name,
                    onValueChange = { name = it },
                    label         = { Text("Name *") },
                    singleLine    = true,
                    modifier      = Modifier.fillMaxWidth(),
                    shape         = RoundedCornerShape(12.dp)
                )
                OutlinedTextField(
                    value         = notes,
                    onValueChange = { notes = it },
                    label         = { Text("Notes (optional)") },
                    singleLine    = true,
                    modifier      = Modifier.fillMaxWidth(),
                    shape         = RoundedCornerShape(12.dp)
                )
            }
        },
        confirmButton = {
            Button(
                onClick  = { if (name.isNotBlank()) onSave(name, notes.ifBlank { null }) },
                shape    = RoundedCornerShape(12.dp),
                enabled  = name.isNotBlank()
            ) {
                Text("Save ⭐", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
