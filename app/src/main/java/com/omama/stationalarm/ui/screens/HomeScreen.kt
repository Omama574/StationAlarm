package com.omama.stationalarm.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.omama.stationalarm.data.ActiveStation
import com.omama.stationalarm.repository.StationRepository

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen() {

    var activeStations by remember {
        mutableStateOf(StationRepository.getAllActiveStations())
    }

    var showDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("StationAlarm") })
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showDialog = true }) {
                Text("+")
            }
        }
    ) { padding ->

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentAlignment = Alignment.Center
        ) {

            if (activeStations.isEmpty()) {
                Text("No Active Stations")
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(activeStations) { station ->
                        StationRow(
                            station = station,
                            onRemove = {
                                StationRepository.removeStation(station.stationId)
                                activeStations =
                                    StationRepository.getAllActiveStations()
                            }
                        )
                    }
                }
            }
        }

        if (showDialog) {
            AddStationDialog(
                onDismiss = { showDialog = false },
                onAdd = { id ->
                    val active = ActiveStation(
                        stationId = id,
                        alertDistanceKm = 5.0,
                        notify = true,
                        vibrate = true,
                        sound = true
                    )
                    StationRepository.addStation(active)
                    activeStations =
                        StationRepository.getAllActiveStations()
                    showDialog = false
                }
            )
        }
    }
}

@Composable
fun StationRow(
    station: ActiveStation,
    onRemove: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(station.stationId)
        Button(onClick = onRemove) {
            Text("Remove")
        }
    }
}

@Composable
fun AddStationDialog(
    onDismiss: () -> Unit,
    onAdd: (String) -> Unit
) {
    var query by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            Button(onClick = { onAdd(query) }) {
                Text("Add")
            }
        },
        dismissButton = {
            Button(onClick = onDismiss) {
                Text("Cancel")
            }
        },
        title = { Text("Add Station (Enter ID)") },
        text = {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Station ID") }
            )
        }
    )
}