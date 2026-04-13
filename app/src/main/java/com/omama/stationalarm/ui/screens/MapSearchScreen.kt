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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
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
import org.osmdroid.util.BoundingBox
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
    focusStation: com.omama.stationalarm.data.ActiveStation? = null,
    onFocusHandled: () -> Unit = {},
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

    val haptic = LocalHapticFeedback.current

    // Shared MapView reference so the FAB can trigger animateToCenter
    var mapViewRef by remember { mutableStateOf<MapView?>(null) }

    // Handle "View on Map" from Alarms tab — center on the requested station
    LaunchedEffect(focusStation) {
        val station = focusStation ?: return@LaunchedEffect
        if (station.lat != 0.0 || station.lon != 0.0) {
            viewModel.selectResult(
                com.omama.stationalarm.network.GeoSearchResult(
                    id = station.stationId,
                    name = station.stationName.ifEmpty { station.stationId },
                    subtitle = "Alert radius: ${station.alertDistanceKm} km",
                    lat = station.lat,
                    lon = station.lon,
                    confidence = "exact"
                )
            )
            viewModel.onRadiusChanged(station.alertDistanceKm)
            onFocusHandled()
        }
    }

    // Collect location events from FAB
    LaunchedEffect(Unit) {
        viewModel.userLocation.collect { geoPoint ->
            mapViewRef?.controller?.setZoom(17.5)
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

    // Helper: tap on map (single or long)
    val handleMapTap: (Double, Double) -> Unit = { lat, lon ->
        if (isGpsEnabled()) {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            viewModel.onMapTap(lat, lon)
        } else {
            onRequestGps()
        }
    }

    // ── Root: map fills everything, UI overlays on top ────────────────────────
    Box(modifier = Modifier.fillMaxSize().clipToBounds()) {

        // ── 1. Full-screen map ────────────────────────────────────────────────
        OsmMapView(
            initialCenter  = initialCenter,
            selectedResult = selectedResult,
            radiusKm       = radiusKm,
            onSingleTap    = handleMapTap,
            onLongPress    = handleMapTap,
            onMapReady     = { mv -> mapViewRef = mv },
            modifier       = Modifier
                .fillMaxSize()
                .padding(bottom = if (selectedResult != null) 140.dp else 80.dp)
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
                    // LocationIQ TOS: attribution required when showing live search results
                    Text(
                        "Search by LocationIQ.com",
                        fontSize = 10.sp,
                        color    = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 5.dp)
                    )
                }
            }
        }

        // ── 3. Zoom + Location FABs (right side) ─────────────────────────────
        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 12.dp, bottom = if (selectedResult != null) 152.dp else 92.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // My Location FAB
            FloatingActionButton(
                onClick = {
                    if (isGpsEnabled()) viewModel.onMyLocationRequested() else onRequestGps()
                },
                modifier       = Modifier.size(44.dp),
                shape          = CircleShape,
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor   = MapAccentBlue,
                elevation      = FloatingActionButtonDefaults.elevation(6.dp)
            ) {
                Icon(
                    ImageVector.vectorResource(R.drawable.ic_gps_fixed),
                    contentDescription = "My location",
                    modifier = Modifier.size(22.dp)
                )
            }

            // Zoom in
            FloatingActionButton(
                onClick = { mapViewRef?.controller?.zoomIn() },
                modifier       = Modifier.size(40.dp),
                shape          = RoundedCornerShape(10.dp),
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor   = MaterialTheme.colorScheme.onSurface,
                elevation      = FloatingActionButtonDefaults.elevation(4.dp)
            ) {
                Text("+", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }

            // Zoom out
            FloatingActionButton(
                onClick = { mapViewRef?.controller?.zoomOut() },
                modifier       = Modifier.size(40.dp),
                shape          = RoundedCornerShape(10.dp),
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor   = MaterialTheme.colorScheme.onSurface,
                elevation      = FloatingActionButtonDefaults.elevation(4.dp)
            ) {
                Text("−", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
        }

        // ── 4. Fixed bottom bar: Perimeter slider + Set Alarm ─────────────────
        BottomControlBar(
            radiusKm    = radiusKm,
            onRadius    = viewModel::onRadiusChanged,
            hasPin      = selectedResult != null,
            pinName     = selectedResult?.name ?: "",
            pinSubtitle = selectedResult?.subtitle ?: "",
            onSetAlarm  = {
                val pin = selectedResult ?: return@BottomControlBar
                onStartTrip(
                    Station(id = pin.id, name = pin.name, lat = pin.lat, lon = pin.lon),
                    radiusKm
                )
            },
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }

    // ── Save dialog (now triggered externally after alarm is set) ──────────────
    // Kept for programmatic use by MainActivity via SaveFavoriteDialog
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

// ── Fixed bottom control bar ──────────────────────────────────────────────────

@Composable
private fun BottomControlBar(
    radiusKm   : Double,
    onRadius   : (Double) -> Unit,
    hasPin     : Boolean,
    pinName    : String,
    pinSubtitle: String,
    onSetAlarm : () -> Unit,
    modifier   : Modifier = Modifier
) {
    Surface(
        modifier       = modifier.fillMaxWidth(),
        color          = MaterialTheme.colorScheme.surface,
        tonalElevation = 8.dp,
        shadowElevation = 16.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 14.dp)
        ) {
            // Selected location name + address
            if (hasPin && pinName.isNotBlank()) {
                Text(
                    pinName,
                    fontWeight = FontWeight.SemiBold,
                    fontSize   = 15.sp,
                    maxLines   = 1,
                    overflow   = TextOverflow.Ellipsis,
                    color      = MaterialTheme.colorScheme.onSurface
                )
                if (pinSubtitle.isNotBlank() && pinSubtitle != "Loading address...") {
                    Text(
                        pinSubtitle,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color    = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                    )
                }
                Spacer(Modifier.height(8.dp))
            }

            // Perimeter label + value
            Row(
                modifier              = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment     = Alignment.CenterVertically
            ) {
                Text(
                    "Perimeter",
                    fontWeight = FontWeight.Medium,
                    fontSize   = 14.sp,
                    color      = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    "${String.format("%.1f", radiusKm)} km",
                    fontWeight = FontWeight.Bold,
                    fontSize   = 15.sp,
                    color      = MapAccentBlue
                )
            }

            Spacer(Modifier.height(4.dp))

            // Slider
            Slider(
                value         = radiusKm.toFloat(),
                onValueChange = { onRadius(it.toDouble()) },
                valueRange    = 3f..20f,
                enabled       = hasPin,
                modifier      = Modifier.fillMaxWidth(),
                colors        = SliderDefaults.colors(
                    thumbColor         = if (hasPin) MapAccentBlue else Color.Gray,
                    activeTrackColor   = if (hasPin) MapAccentBlue else Color.Gray,
                    inactiveTrackColor = MapAccentBlueDim,
                    disabledThumbColor = Color.Gray.copy(alpha = 0.5f),
                    disabledActiveTrackColor = Color.Gray.copy(alpha = 0.3f),
                    disabledInactiveTrackColor = Color.Gray.copy(alpha = 0.1f)
                )
            )

            Row(
                modifier              = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("3 km", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
                Text("20 km", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
            }

            Spacer(Modifier.height(10.dp))

            // Set Alarm button
            Button(
                onClick  = onSetAlarm,
                enabled  = hasPin,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                shape    = RoundedCornerShape(14.dp),
                colors   = ButtonDefaults.buttonColors(
                    containerColor         = ConfirmGreen,
                    contentColor           = Color.White,
                    disabledContainerColor = Color.Gray.copy(alpha = 0.3f),
                    disabledContentColor   = Color.Gray
                ),
                elevation = ButtonDefaults.buttonElevation(defaultElevation = 4.dp)
            ) {
                Text("Set Alarm", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

// ── Map View ──────────────────────────────────────────────────────────────────

// ── Save dialog ───────────────────────────────────────────────────────────────

@Composable
fun SaveFavoriteDialog(
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
