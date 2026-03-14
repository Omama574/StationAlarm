package com.omama.stationalarm.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.omama.stationalarm.data.ActiveStation
import com.omama.stationalarm.data.Station
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StationConfigBottomSheet(
    station: Station,
    isActive: Boolean = false,
    initialRadius: Double = 5.0,
    initialNotify: Boolean = true,
    initialVibrate: Boolean = true,
    initialSound: Boolean = true,
    initialNotes: String? = null,
    onDismiss: () -> Unit,
    onConfirm: (ActiveStation) -> Unit
) {
    var alertDistance by remember { mutableStateOf(initialRadius) }
    var notifyEnabled by remember { mutableStateOf(initialNotify) }
    var vibrateEnabled by remember { mutableStateOf(initialVibrate) }
    var soundEnabled by remember { mutableStateOf(initialSound) }
    
    var customReminder by remember { mutableStateOf(initialNotes ?: "") }
    var sendReminder by remember { mutableStateOf(initialNotes != null) }
    
    val haptic = LocalHapticFeedback.current

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = { BottomSheetDefaults.DragHandle(color = MaterialTheme.colorScheme.onSurface) }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = station.name,
                fontSize = 22.sp,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = station.id,
                fontSize = 16.sp,
                color = Color.Gray,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            Text(
                text = "Alert at ${(alertDistance * 10).roundToInt() / 10.0} km",
                fontSize = 16.sp,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            Slider(
                value = alertDistance.toFloat(),
                onValueChange = { alertDistance = it.toDouble() },
                valueRange = 3f..10f,
                steps = 13,
                colors = SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.onSurface,
                    activeTrackColor = MaterialTheme.colorScheme.onSurface,
                    inactiveTrackColor = Color.Gray
                ),
                modifier = Modifier.padding(horizontal = 8.dp)
            )

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = customReminder,
                onValueChange = { customReminder = it },
                label = { Text("Custom Reminder (Optional)", color = Color.Gray) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.onSurface,
                    unfocusedBorderColor = Color.Gray,
                    focusedTextColor = MaterialTheme.colorScheme.onSurface,
                    unfocusedTextColor = MaterialTheme.colorScheme.onSurface
                )
            )

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = "Show Reminder", color = MaterialTheme.colorScheme.onSurface, fontSize = 16.sp)
                Switch(
                    checked = sendReminder,
                    onCheckedChange = { sendReminder = it },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = MaterialTheme.colorScheme.surface,
                        checkedTrackColor = MaterialTheme.colorScheme.onSurface,
                        uncheckedThumbColor = Color.Gray,
                        uncheckedTrackColor = Color.DarkGray
                    )
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                NotificationToggle(
                    label = "Notify",
                    checked = notifyEnabled,
                    onCheckedChange = { notifyEnabled = it }
                )
                NotificationToggle(
                    label = "Vibrate",
                    checked = vibrateEnabled,
                    onCheckedChange = { vibrateEnabled = it }
                )
                NotificationToggle(
                    label = "Sound",
                    checked = soundEnabled,
                    onCheckedChange = { soundEnabled = it }
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    val active = ActiveStation(
                        stationId = station.id,
                        alertDistanceKm = alertDistance,
                        notify = notifyEnabled,
                        vibrate = vibrateEnabled,
                        sound = soundEnabled,
                        customReminder = customReminder.takeIf { it.isNotBlank() },
                        sendReminder = sendReminder
                    )
                    onConfirm(active)
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.onSurface,
                    contentColor = MaterialTheme.colorScheme.surface
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .shadow(8.dp, RoundedCornerShape(12.dp))
            ) {
                Text(if (isActive) "Update Alarm" else "Set Alarm", fontSize = 18.sp)
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
fun NotificationToggle(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.surface,
                checkedTrackColor = MaterialTheme.colorScheme.onSurface,
                uncheckedThumbColor = Color.Gray,
                uncheckedTrackColor = Color.DarkGray
            )
        )
        Text(
            text = label,
            fontSize = 12.sp,
            color = if (checked) MaterialTheme.colorScheme.onSurface else Color.Gray,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}