package com.omama.stationalarm.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.runtime.livedata.observeAsState
import com.omama.stationalarm.data.ActiveStation
import com.omama.stationalarm.ui.viewmodel.StationViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onAddClick: () -> Unit,
    onShareLogs: () -> Unit,
    viewModel: StationViewModel = viewModel()
) {
    val activeStations by viewModel.activeStations.observeAsState(emptyList())
    val haptic = LocalHapticFeedback.current

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onAddClick()
                },
                containerColor = Color.Black,
                contentColor = Color.White,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .shadow(8.dp, RoundedCornerShape(16.dp))
                    .animateContentSize()
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add Station")
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.White)
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
                    color = Color.Black,
                    style = MaterialTheme.typography.headlineMedium
                )
                IconButton(
                    onClick = onShareLogs,
                    modifier = Modifier
                        .shadow(4.dp, RoundedCornerShape(12.dp))
                        .background(Color.Black, RoundedCornerShape(12.dp))
                ) {
                    Icon(Icons.Default.Share, contentDescription = "Share Logs", tint = Color.White)
                }
            }

            if (activeStations.isEmpty()) {
                EmptyState()
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(activeStations, key = { it.stationId }) { station ->
                        StationCard(
                            station = station,
                            onRemove = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                viewModel.removeActiveStation(station.stationId)
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun StationCard(station: ActiveStation, onRemove: () -> Unit) {
    val stationData = station.getStation()
    val name = stationData?.name ?: station.stationId
    val distanceText = station.currentDistanceKm?.let { "%.1f km".format(it) } ?: "—"

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(8.dp, RoundedCornerShape(16.dp))
            .animateContentSize(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = name,
                    fontSize = 18.sp,
                    color = Color.Black,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Alert at ${station.alertDistanceKm} km • Distance: $distanceText",
                    fontSize = 14.sp,
                    color = Color.Gray
                )
            }
            IconButton(
                onClick = onRemove,
                modifier = Modifier
                    .shadow(4.dp, RoundedCornerShape(8.dp))
                    .background(Color.Black.copy(alpha = 0.1f), RoundedCornerShape(8.dp))
            ) {
                Text(
                    text = "✕",
                    color = Color.Black,
                    fontSize = 16.sp
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
            text = "No active stations\nTap + to add one",
            fontSize = 18.sp,
            color = Color.Gray,
            lineHeight = 24.sp
        )
    }
}