package com.omama.stationalarm.ui.screens

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
    onDismiss: () -> Unit,
    onConfirm: (ActiveStation) -> Unit
) {
    var alertDistance by remember { mutableStateOf(5.0) }
    var notifyEnabled by remember { mutableStateOf(true) }
    var vibrateEnabled by remember { mutableStateOf(true) }
    var soundEnabled by remember { mutableStateOf(true) }
    val haptic = LocalHapticFeedback.current

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        containerColor = Color.White,
        dragHandle = { BottomSheetDefaults.DragHandle(color = Color.Black) }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = station.name,
                fontSize = 22.sp,
                color = Color.Black,
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
                color = Color.Black,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            Slider(
                value = alertDistance.toFloat(),
                onValueChange = { alertDistance = it.toDouble() },
                valueRange = 3f..10f,
                steps = 13,
                colors = SliderDefaults.colors(
                    thumbColor = Color.Black,
                    activeTrackColor = Color.Black,
                    inactiveTrackColor = Color.LightGray
                ),
                modifier = Modifier.padding(horizontal = 8.dp)
            )

            Spacer(modifier = Modifier.height(24.dp))

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

            Spacer(modifier = Modifier.height(32.dp))

            Button(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    val active = ActiveStation(
                        stationId = station.id,
                        alertDistanceKm = alertDistance,
                        notify = notifyEnabled,
                        vibrate = vibrateEnabled,
                        sound = soundEnabled
                    )
                    onConfirm(active)
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.Black,
                    contentColor = Color.White
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .shadow(8.dp, RoundedCornerShape(12.dp))
            ) {
                Text("Set Alarm", fontSize = 18.sp)
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
                checkedThumbColor = Color.Black,
                checkedTrackColor = Color.Black.copy(alpha = 0.5f),
                uncheckedThumbColor = Color.Gray,
                uncheckedTrackColor = Color.LightGray
            )
        )
        Text(
            text = label,
            fontSize = 12.sp,
            color = if (checked) Color.Black else Color.Gray,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}