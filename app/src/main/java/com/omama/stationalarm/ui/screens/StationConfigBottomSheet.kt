package com.omama.stationalarm.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.graphics.Color
import com.omama.stationalarm.data.ActiveStation
import com.omama.stationalarm.data.Station
import com.omama.stationalarm.ui.theme.glassBorderColor
import com.omama.stationalarm.ui.theme.spacing
import com.omama.stationalarm.util.LocalDistanceUnit
import com.omama.stationalarm.util.formatDistance
import kotlin.math.roundToInt

private const val MAX_REMINDER_CHARS = 200

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
    initialName: String = "",
    onDismiss: () -> Unit,
    onConfirm: (ActiveStation) -> Unit
) {
    val unit = LocalDistanceUnit.current
    val haptic = LocalHapticFeedback.current
    val spacing = MaterialTheme.spacing
    val colors = MaterialTheme.colorScheme

    var alarmName by remember { mutableStateOf(initialName.ifBlank { "Alarm" }) }
    var alertDistance by remember { mutableStateOf(initialRadius) }
    var notifyEnabled by remember { mutableStateOf(initialNotify) }
    var vibrateEnabled by remember { mutableStateOf(initialVibrate) }
    var soundEnabled by remember { mutableStateOf(initialSound) }
    var customReminder by remember { mutableStateOf(initialNotes ?: "") }
    var sendReminder by remember { mutableStateOf(initialNotes != null) }

    val displayCode = displayableStationCode(station.id)
    val roundedKm = (alertDistance * 10).roundToInt() / 10.0

    val dark = isSystemInDarkTheme()
    val sheetBg = if (dark) Color(0xFF0E1514).copy(alpha = 0.92f) else Color(0xFFFAFDFB).copy(alpha = 0.94f)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = MaterialTheme.shapes.extraLarge,
        containerColor = sheetBg,
        contentColor = colors.onSurface,
        dragHandle = { BottomSheetDefaults.DragHandle(color = glassBorderColor()) }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.lg)
                .padding(bottom = spacing.lg),
            verticalArrangement = Arrangement.spacedBy(spacing.md)
        ) {
            // ── Header: editable alarm name + optional railway code chip ─────
            Column {
                OutlinedTextField(
                    value = alarmName,
                    onValueChange = { alarmName = it.take(60) },
                    label = { Text("Alarm name") },
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = colors.primary,
                        unfocusedBorderColor = colors.outline,
                        focusedLabelColor = colors.primary,
                        unfocusedLabelColor = colors.onSurfaceVariant
                    )
                )
                if (displayCode != null) {
                    Spacer(Modifier.height(spacing.xs))
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = colors.secondaryContainer,
                        contentColor = colors.onSecondaryContainer
                    ) {
                        Text(
                            text = displayCode,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(
                                horizontal = spacing.sm,
                                vertical = 2.dp
                            )
                        )
                    }
                }
            }

            // ── Alert distance ──────────────────────────────────────────────
            SectionLabel("Alert distance")
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Ring when within",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant
                )
                Text(
                    text = formatDistance(roundedKm, unit),
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.primary,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Slider(
                value = alertDistance.toFloat(),
                onValueChange = { alertDistance = it.toDouble() },
                valueRange = 3f..20f,
                steps = 33,
                colors = SliderDefaults.colors(
                    thumbColor = colors.primary,
                    activeTrackColor = colors.primary,
                    inactiveTrackColor = colors.surfaceVariant
                )
            )

            // ── Custom reminder ─────────────────────────────────────────────
            OutlinedTextField(
                value = customReminder,
                onValueChange = { if (it.length <= MAX_REMINDER_CHARS) customReminder = it },
                label = { Text("Reminder note (optional)") },
                supportingText = {
                    Text("${customReminder.length} / $MAX_REMINDER_CHARS")
                },
                singleLine = true,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = colors.primary,
                    unfocusedBorderColor = colors.outline,
                    focusedLabelColor = colors.primary,
                    unfocusedLabelColor = colors.onSurfaceVariant
                )
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.padding(end = spacing.md)) {
                    Text(
                        "Show reminder when alarm fires",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurface
                    )
                    Text(
                        "Displayed on the alarm screen and notification.",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant
                    )
                }
                Switch(
                    checked = sendReminder,
                    onCheckedChange = { sendReminder = it },
                    enabled = customReminder.isNotBlank()
                )
            }

            // ── Alert channels ──────────────────────────────────────────────
            SectionLabel("Alert by")
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spacing.sm)
            ) {
                ChannelChip(
                    label = "Notify",
                    selected = notifyEnabled,
                    onChange = { notifyEnabled = it },
                    modifier = Modifier.weight(1f)
                )
                ChannelChip(
                    label = "Vibrate",
                    selected = vibrateEnabled,
                    onChange = { vibrateEnabled = it },
                    modifier = Modifier.weight(1f)
                )
                ChannelChip(
                    label = "Sound",
                    selected = soundEnabled,
                    onChange = { soundEnabled = it },
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(Modifier.height(spacing.xs))

            Button(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onConfirm(
                        ActiveStation(
                            stationId = station.id,
                            alertDistanceKm = alertDistance,
                            notify = notifyEnabled,
                            vibrate = vibrateEnabled,
                            sound = soundEnabled,
                            customReminder = customReminder.takeIf { it.isNotBlank() },
                            sendReminder = sendReminder,
                            stationName = alarmName.trim().ifBlank { "Alarm" },
                            lat = station.lat,
                            lon = station.lon
                        )
                    )
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.primary,
                    contentColor = colors.onPrimary
                ),
                shape = MaterialTheme.shapes.large,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
            ) {
                Text(
                    text = if (isActive) "Update alarm" else "Set alarm",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = FontWeight.SemiBold
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChannelChip(
    label: String,
    selected: Boolean,
    onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    FilterChip(
        selected = selected,
        onClick = { onChange(!selected) },
        modifier = modifier.height(48.dp),
        label = {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold
            )
        },
        shape = MaterialTheme.shapes.medium,
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
        )
    )
}
