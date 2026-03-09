
package com.omama.stationalarm.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.omama.stationalarm.data.ActiveStation
import com.omama.stationalarm.data.Station
import com.omama.stationalarm.ui.viewmodel.StationViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onStationSelected: (Station) -> Unit,
    onShareLogs: () -> Unit,
    onShareGpsLogs: () -> Unit,
    viewModel: StationViewModel = viewModel()
) {
    val activeStations by viewModel.activeStations.observeAsState(emptyList())
    val haptic = LocalHapticFeedback.current

    var query by remember { mutableStateOf("") }
    var stationToRemove by remember { mutableStateOf<ActiveStation?>(null) }
    val searchResults = remember(query) {
        if (query.length >= 2) viewModel.searchStations(query) else emptyList()
    }

    Scaffold { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(paddingValues)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "My Stations",
                    fontSize = 28.sp,
                    color = MaterialTheme.colorScheme.onBackground,
                    style = MaterialTheme.typography.headlineMedium
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IconButton(
                        onClick = onShareGpsLogs,
                        modifier = Modifier
                            .shadow(4.dp, RoundedCornerShape(12.dp))
                            .background(Color(0xFF4FC3F7), RoundedCornerShape(12.dp)) // MapAccentBlue
                    ) {
                        Icon(Icons.Default.LocationOn, contentDescription = "Share GPS Logs", tint = Color.Black)
                    }
                    IconButton(
                        onClick = onShareLogs,
                        modifier = Modifier
                            .shadow(4.dp, RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.onBackground, RoundedCornerShape(12.dp))
                    ) {
                        Icon(Icons.Default.Share, contentDescription = "Share App Logs", tint = MaterialTheme.colorScheme.background)
                    }
                }
            }

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
                            items(searchResults) { station ->
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
                    if (activeStations.isEmpty()) {
                        EmptyState()
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            items(activeStations, key = { it.stationId }) { station ->
                                StationCard(
                                    station = station,
                                    onRemove = {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        stationToRemove = station
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
                title = { Text("Remove Alarm?", color = Color.Black, fontWeight = FontWeight.Bold) },
                text = { Text("Are you sure you want to delete this trip?", color = Color.DarkGray) },
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
                containerColor = Color.White,
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
fun StationCard(station: ActiveStation, onRemove: () -> Unit) {
    val stationData = station.getStation()
    val name = stationData?.name ?: station.stationId
    val distanceText = station.currentDistanceKm?.let { "%.1f km".format(it) } ?: "—"

    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(targetValue = if (isPressed) 0.95f else 1f, animationSpec = tween(150))
    val elevation by animateDpAsState(targetValue = if (isPressed) 2.dp else 8.dp, animationSpec = tween(150))

    val isAlerting = station.status == "ALERTING"
    val containerColor = if (isAlerting) Color(0xFFFFEBEE) else MaterialTheme.colorScheme.surface

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .scale(scale)
            .shadow(elevation, RoundedCornerShape(16.dp))
            .clickable(interactionSource = interactionSource, indication = null) { },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        border = if (isAlerting) androidx.compose.foundation.BorderStroke(2.dp, Color.Red) else null
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = name,
                        fontSize = 18.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Bold
                    )
                    if (isAlerting) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = Color.Red,
                            modifier = Modifier.padding(vertical = 2.dp)
                        ) {
                            Text(
                                text = "RINGING",
                                color = Color.White,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Alert at ${station.alertDistanceKm} km • Distance: $distanceText",
                    fontSize = 14.sp,
                    color = if (isAlerting) Color.Black else Color.Gray,
                    fontWeight = if (isAlerting) FontWeight.Medium else FontWeight.Normal
                )
                if (station.sendReminder && !station.customReminder.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Reminder: ${station.customReminder}",
                        fontSize = 13.sp,
                        color = if (isAlerting) Color.Black else Color.DarkGray,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
            IconButton(
                onClick = onRemove,
                modifier = Modifier
                    .shadow(4.dp, RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f), RoundedCornerShape(8.dp))
            ) {
                Text(
                    text = "✕",
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
fun EmptyState() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "No active stations\nSearch to add an alarm",
            fontSize = 18.sp,
            color = Color.Gray,
            lineHeight = 24.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
}