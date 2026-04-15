
package com.omama.stationalarm.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.omama.stationalarm.data.ActiveStation
import com.omama.stationalarm.data.Station
import com.omama.stationalarm.ui.viewmodel.StationViewModel
import com.omama.stationalarm.util.LocalDistanceUnit
import com.omama.stationalarm.util.formatDistance

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onStationSelected: (Station) -> Unit,
    onEditStation: (ActiveStation) -> Unit,
    onViewOnMap: (ActiveStation) -> Unit,
    onToggleStation: (ActiveStation, Boolean) -> Unit,
    onNavigateToMap: () -> Unit,
    viewModel: StationViewModel = viewModel()
) {
    val activeStations by viewModel.activeStations.observeAsState(emptyList())
    val haptic = LocalHapticFeedback.current

    // Sort: active (MONITORING/ALERTING) above paused
    val sortedStations = remember(activeStations) {
        activeStations.sortedBy { if (it.status == "PAUSED") 1 else 0 }
    }

    var query by remember { mutableStateOf("") }
    var stationToRemove by remember { mutableStateOf<ActiveStation?>(null) }
    var searchResults by remember { mutableStateOf<List<Station>>(emptyList()) }

    LaunchedEffect(query) {
        if (query.length >= 2) {
            kotlinx.coroutines.delay(300)
            searchResults = viewModel.searchStations(query)
        } else {
            searchResults = emptyList()
        }
    }

    Scaffold { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(paddingValues)
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search station to add alarm...") },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .shadow(4.dp, RoundedCornerShape(12.dp))
                    .animateContentSize(),
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.onBackground,
                    unfocusedBorderColor = Color.Gray,
                    focusedContainerColor = MaterialTheme.colorScheme.background,
                    unfocusedContainerColor = MaterialTheme.colorScheme.background,
                    focusedTextColor = MaterialTheme.colorScheme.onBackground,
                    unfocusedTextColor = MaterialTheme.colorScheme.onBackground
                ),
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Text("✕", fontSize = 16.sp, color = Color.Gray)
                        }
                    }
                }
            )

            Crossfade(targetState = query.isNotEmpty(), animationSpec = tween(300)) { isSearching ->
                if (isSearching) {
                    if (query.length < 2) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("Type at least 2 characters", color = Color.Gray)
                        }
                    } else if (searchResults.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("No stations found", color = Color.Gray)
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(searchResults, key = { it.id }) { station ->
                                StationSearchItem(
                                    station = station,
                                    onClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        query = ""
                                        onStationSelected(station)
                                    }
                                )
                            }
                        }
                    }
                } else {
                    if (sortedStations.isEmpty()) {
                        EmptyState(onNavigateToMap = onNavigateToMap)
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            items(sortedStations, key = { it.stationId }) { station ->
                                StationCard(
                                    station = station,
                                    onEdit = { onEditStation(station) },
                                    onViewOnMap = { onViewOnMap(station) },
                                    onRemove = {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        stationToRemove = station
                                    },
                                    onToggle = { enabled ->
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        onToggleStation(station, enabled)
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
        
        if (stationToRemove != null) {
            AlertDialog(
                onDismissRequest = { stationToRemove = null },
                title = { Text("Remove Alarm?", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold) },
                text = { Text("Are you sure you want to remove this alarm?", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            viewModel.removeActiveStation(stationToRemove!!.stationId)
                            stationToRemove = null
                        }
                    ) {
                        Text("Delete", color = Color.Red, fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { stationToRemove = null }) {
                        Text("Cancel", color = Color.Gray)
                    }
                },
                containerColor = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(16.dp)
            )
        }
    }
}

@Composable
fun StationSearchItem(station: Station, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(targetValue = if (isPressed) 0.98f else 1f, animationSpec = tween(150))
    val elevation by animateDpAsState(targetValue = if (isPressed) 2.dp else 4.dp, animationSpec = tween(150))

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .scale(scale)
            .shadow(elevation, RoundedCornerShape(12.dp))
            .clickable(interactionSource = interactionSource, indication = null) { onClick() },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = station.name, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold)
            Text(text = station.id, fontSize = 14.sp, color = Color.Gray)
        }
    }
}

@Composable
fun StationCard(
    station: ActiveStation,
    onEdit: () -> Unit,
    onViewOnMap: () -> Unit,
    onRemove: () -> Unit,
    onToggle: (Boolean) -> Unit
) {
    val stationData = station.getStation()
    val name = stationData?.name ?: station.stationName.ifEmpty { station.stationId }

    val unit = LocalDistanceUnit.current
    val isAlerting = station.status == "ALERTING"
    val isPaused = station.status == "PAUSED"
    val isActive = !isPaused

    val containerColor = when {
        isAlerting -> Color(0xFFFFEBEE)
        else -> MaterialTheme.colorScheme.surface
    }
    val cardBorder = when {
        isAlerting -> BorderStroke(2.dp, Color.Red)
        isPaused -> BorderStroke(1.dp, Color.Gray.copy(alpha = 0.4f))
        else -> null
    }
    val cardAlpha = if (isPaused) 0.65f else 1f

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(cardAlpha)
            .shadow(if (isPaused) 2.dp else 8.dp, RoundedCornerShape(16.dp)),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        border = cardBorder
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // ── Row 1: Name + badges ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = name,
                    fontSize = 18.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    // Alarm type badge
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = if (station.sound) Color(0xFF4FC3F7) else Color(0xFF81C784)
                    ) {
                        Text(
                            text = if (station.sound) "Alarm" else "Notification",
                            color = Color.Black,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                    // Status badge (PAUSED or RINGING)
                    if (isPaused) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = Color.Gray
                        ) {
                            Text(
                                text = "PAUSED",
                                color = Color.White,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                    if (isAlerting) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = Color.Red
                        ) {
                            Text(
                                text = "RINGING",
                                color = Color.White,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
            }

            // ── Row 2: Alert radius ──
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "Alert radius: ${formatDistance(station.alertDistanceKm, unit)}",
                fontSize = 13.sp,
                color = Color.Gray
            )

            Spacer(modifier = Modifier.height(10.dp))

            // ── Row 3: Proximity bar + distance (active only) or "--" ──
            if (isActive) {
                val distanceKm = station.currentDistanceKm
                // Anchor the bar to the FIRST observed distance for this card —
                // progress then fills linearly as the device closes in. Using a
                // fixed +60 km window made far-away trips look "full" from the
                // start and never move. The anchor is remembered per stationId
                // so switching journeys resets it.
                var startDistanceKm by remember(station.stationId) {
                    mutableStateOf<Double?>(null)
                }
                LaunchedEffect(station.stationId, distanceKm) {
                    val d = distanceKm ?: return@LaunchedEffect
                    val current = startDistanceKm
                    // Grow the anchor if we ever observe a larger distance (e.g.
                    // GPS fix improves after initial coarse reading). Never shrink.
                    if (current == null || d > current) startDistanceKm = d
                }

                val anchor = startDistanceKm ?: station.alertDistanceKm
                val rawProgress = if (distanceKm != null && anchor > station.alertDistanceKm) {
                    val span = anchor - station.alertDistanceKm
                    val covered = (anchor - distanceKm).coerceAtLeast(0.0)
                    (covered / span).coerceIn(0.0, 1.0).toFloat()
                } else if (distanceKm != null && distanceKm <= station.alertDistanceKm) {
                    1f
                } else 0f

                // Smooth the bar between polls so 10s/30s/5m intervals don't
                // look like teleport jumps.
                val progress by animateFloatAsState(
                    targetValue = rawProgress,
                    animationSpec = tween(durationMillis = 900, easing = LinearEasing),
                    label = "proximityProgress"
                )

                val distanceText = distanceKm?.let { formatDistance(it, unit) } ?: "--"

                val barColor = when {
                    progress > 0.85f -> Color(0xFF4CAF50) // green — very close
                    progress > 0.5f -> Color(0xFF4FC3F7)  // accent blue — mid
                    else -> Color.Gray                      // gray — far
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier
                            .weight(1f)
                            .height(6.dp),
                        color = barColor,
                        trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f),
                        strokeCap = androidx.compose.ui.graphics.StrokeCap.Round
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = distanceText,
                        fontSize = 14.sp,
                        color = if (isAlerting) Color.Black else MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Medium
                    )
                }
            } else {
                Text(
                    text = "--",
                    fontSize = 14.sp,
                    color = Color.Gray
                )
            }

            // ── Row 4: Custom reminder ──
            if (station.sendReminder && !station.customReminder.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "\"${station.customReminder}\"",
                    fontSize = 13.sp,
                    color = if (isAlerting) Color.Black else Color.DarkGray,
                    fontWeight = FontWeight.Medium
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // ── Row 5: Action buttons + toggle ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Edit button
                    FilledTonalButton(
                        onClick = onEdit,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
                        )
                    ) {
                        Icon(
                            Icons.Default.Edit,
                            contentDescription = "Edit",
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Edit", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface)
                    }

                    // View on Map button
                    FilledTonalButton(
                        onClick = onViewOnMap,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
                        )
                    ) {
                        Icon(
                            Icons.Default.LocationOn,
                            contentDescription = "View on Map",
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Map", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface)
                    }

                    // Delete button
                    IconButton(
                        onClick = onRemove,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "Delete",
                            modifier = Modifier.size(18.dp),
                            tint = Color(0xFFEF5350)
                        )
                    }
                }

                // Toggle switch
                Switch(
                    checked = isActive,
                    onCheckedChange = onToggle,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = MaterialTheme.colorScheme.surface,
                        checkedTrackColor = Color(0xFF4CAF50),
                        uncheckedThumbColor = Color.Gray,
                        uncheckedTrackColor = Color.DarkGray
                    )
                )
            }
        }
    }
}

@Composable
fun EmptyState(onNavigateToMap: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Default.LocationOn,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = Color.Gray.copy(alpha = 0.5f)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "No alarms set yet",
                fontSize = 20.sp,
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Search for a station above, or",
                fontSize = 14.sp,
                color = Color.Gray,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = onNavigateToMap,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.onSurface,
                    contentColor = MaterialTheme.colorScheme.surface
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(
                    Icons.Default.LocationOn,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Open Map to set alarm", fontSize = 14.sp)
            }
        }
    }
}