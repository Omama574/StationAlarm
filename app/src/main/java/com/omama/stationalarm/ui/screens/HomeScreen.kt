package com.omama.stationalarm.ui.screens

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.omama.stationalarm.R
import com.omama.stationalarm.data.ActiveStation
import com.omama.stationalarm.data.Station
import com.omama.stationalarm.ui.theme.LocalAppHazeState
import com.omama.stationalarm.ui.theme.glassBorderColor
import com.omama.stationalarm.ui.theme.glassCardStyle
import com.omama.stationalarm.ui.theme.proximityFar
import com.omama.stationalarm.ui.theme.proximityImminent
import com.omama.stationalarm.ui.theme.proximityNear
import com.omama.stationalarm.ui.theme.spacing
import com.omama.stationalarm.ui.viewmodel.StationViewModel
import com.omama.stationalarm.util.LocalDistanceUnit
import com.omama.stationalarm.util.formatDistance
import dev.chrisbanes.haze.hazeEffect

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
    val spacing = MaterialTheme.spacing

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

    val hazeState = LocalAppHazeState.current
    val cardGlassStyle = glassCardStyle()
    val borderColor = glassBorderColor()

    Scaffold(containerColor = Color.Transparent) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text(stringResource(R.string.home_search_placeholder)) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.home_clear))
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = spacing.md, vertical = spacing.sm),
                shape = MaterialTheme.shapes.large,
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface
                )
            )

            Crossfade(targetState = query.isNotEmpty(), animationSpec = tween(300), label = "homeCrossfade") { isSearching ->
                if (isSearching) {
                    when {
                        query.length < 2 -> HintState(stringResource(R.string.home_hint_type_more))
                        searchResults.isEmpty() -> HintState(stringResource(R.string.home_hint_no_stations))
                        else -> LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(horizontal = spacing.md, vertical = spacing.sm),
                            verticalArrangement = Arrangement.spacedBy(spacing.sm)
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
                            contentPadding = PaddingValues(horizontal = spacing.md, vertical = spacing.sm),
                            verticalArrangement = Arrangement.spacedBy(spacing.md)
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
                                    },
                                    onRename = { newName ->
                                        viewModel.updateStationName(station.stationId, newName)
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }

        stationToRemove?.let { target ->
            AlertDialog(
                onDismissRequest = { stationToRemove = null },
                title = { Text(stringResource(R.string.home_remove_title), fontWeight = FontWeight.SemiBold) },
                text = {
                    Text(
                        stringResource(
                            R.string.home_remove_body_format,
                            target.getStation()?.name ?: target.stationId
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            viewModel.removeActiveStation(target.stationId)
                            stationToRemove = null
                        }
                    ) {
                        Text(
                            stringResource(R.string.common_remove),
                            color = MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                },
                dismissButton = {
                    TextButton(onClick = { stationToRemove = null }) { Text(stringResource(R.string.common_cancel)) }
                },
                shape = MaterialTheme.shapes.extraLarge
            )
        }
    }
}

@Composable
private fun HintState(text: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun StationSearchItem(station: Station, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.98f else 1f,
        animationSpec = tween(150),
        label = "searchItemScale"
    )
    val displayCode = displayableStationCode(station.id)
    val hazeState = LocalAppHazeState.current
    val glassStyle = glassCardStyle()
    val borderColor = glassBorderColor()

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .scale(scale)
            .then(if (hazeState != null) Modifier.hazeEffect(hazeState, glassStyle) else Modifier)
            .clickable(interactionSource = interactionSource, indication = null) { onClick() },
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = BorderStroke(0.8.dp, borderColor)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(MaterialTheme.spacing.md),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Place,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(Modifier.width(MaterialTheme.spacing.md))
            Column(Modifier.weight(1f)) {
                Text(
                    text = station.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (displayCode != null) {
                    Text(
                        text = displayCode,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
fun StationCard(
    station: ActiveStation,
    onEdit: () -> Unit,
    onViewOnMap: () -> Unit,
    onRemove: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onRename: (String) -> Unit
) {
    // The user-edited alarm name lives in active_stations.stationName and is
    // the source of truth. station.getStation() is only used for lat/lon
    // resolution in the action flow — its name is not authoritative here.
    val name = station.stationName.ifEmpty { station.getStation()?.name ?: station.stationId }
    var isEditingName by remember(station.stationId) { mutableStateOf(false) }
    var draftName by remember(station.stationId) { mutableStateOf(name) }

    val unit = LocalDistanceUnit.current
    val colors = MaterialTheme.colorScheme
    val spacing = MaterialTheme.spacing

    val isAlerting = station.status == "ALERTING"
    val isPaused = station.status == "PAUSED"
    val isActive = !isPaused

    val contentColor = when {
        isAlerting -> colors.onErrorContainer
        isPaused -> colors.onSurfaceVariant
        else -> colors.onSurface
    }
    val cardBorder = when {
        isAlerting -> BorderStroke(2.dp, colors.error)
        else -> BorderStroke(0.8.dp, glassBorderColor())
    }
    val cardAlpha = if (isPaused) 0.78f else 1f
    val hazeState = LocalAppHazeState.current
    val cardGlassStyle = glassCardStyle()

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(cardAlpha)
            .then(if (hazeState != null) Modifier.hazeEffect(hazeState, cardGlassStyle) else Modifier),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(
            containerColor = Color.Transparent,
            contentColor = contentColor
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = cardBorder
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(spacing.md)
        ) {
            // ── Row 1: Name (or inline editor) + badges ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isEditingName) {
                    OutlinedTextField(
                        value = draftName,
                        onValueChange = { draftName = it.take(60) },
                        singleLine = true,
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = spacing.xs),
                        shape = MaterialTheme.shapes.medium,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = colors.primary,
                            unfocusedBorderColor = colors.outline
                        )
                    )
                    val fallbackName = stringResource(R.string.alarm_config_default_name)
                    IconButton(
                        onClick = {
                            val trimmed = draftName.trim().ifBlank { fallbackName }
                            if (trimmed != name) onRename(trimmed)
                            isEditingName = false
                        },
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = stringResource(R.string.card_action_save_name),
                            tint = colors.primary
                        )
                    }
                    IconButton(
                        onClick = {
                            draftName = name
                            isEditingName = false
                        },
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = stringResource(R.string.card_action_cancel_rename),
                            tint = colors.onSurfaceVariant
                        )
                    }
                } else {
                    Text(
                        text = name,
                        style = MaterialTheme.typography.titleMedium,
                        color = contentColor,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    IconButton(
                        onClick = {
                            draftName = name
                            isEditingName = true
                        },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            Icons.Default.Edit,
                            contentDescription = stringResource(R.string.card_action_rename),
                            tint = colors.onSurfaceVariant,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    Spacer(Modifier.width(spacing.sm))
                    Row(horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
                        StatusBadge(
                            text = stringResource(if (station.sound) R.string.card_badge_alarm else R.string.card_badge_notify),
                            container = colors.tertiaryContainer,
                            content = colors.onTertiaryContainer
                        )
                        when {
                            isAlerting -> StatusBadge(stringResource(R.string.card_badge_ringing), colors.error, colors.onError)
                            isPaused -> StatusBadge(stringResource(R.string.card_badge_paused), colors.outline, colors.surface)
                        }
                    }
                }
            }

            Spacer(Modifier.height(spacing.xs))
            Text(
                text = stringResource(R.string.card_alerts_within_format, formatDistance(station.alertDistanceKm, unit)),
                style = MaterialTheme.typography.bodySmall,
                color = if (isAlerting) contentColor.copy(alpha = 0.85f) else colors.onSurfaceVariant
            )

            station.lastTriggeredAt?.let { ts ->
                Text(
                    text = stringResource(R.string.card_last_triggered_format, formatLastTriggered(ts)),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(spacing.sm))

            if (isActive) {
                ProximityRow(
                    distanceKm = station.currentDistanceKm,
                    alertDistanceKm = station.alertDistanceKm,
                    stationId = station.stationId,
                    isAlerting = isAlerting
                )
            } else {
                Text(
                    text = stringResource(R.string.card_paused_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant
                )
            }

            if (station.sendReminder && !station.customReminder.isNullOrBlank()) {
                Spacer(Modifier.height(spacing.sm))
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = colors.surfaceVariant.copy(alpha = 0.6f),
                    contentColor = colors.onSurfaceVariant
                ) {
                    Text(
                        text = stringResource(R.string.card_reminder_format, station.customReminder),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(
                            horizontal = spacing.sm,
                            vertical = spacing.xs
                        )
                    )
                }
            }

            Spacer(Modifier.height(spacing.md))

            // ── Action row: Edit / Map / Delete + toggle ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
                    CardActionButton(
                        label = stringResource(R.string.card_action_edit),
                        icon = Icons.Default.Edit,
                        onClick = onEdit,
                        enabled = isActive
                    )
                    CardActionButton(
                        label = stringResource(R.string.card_action_map),
                        icon = Icons.Default.LocationOn,
                        onClick = onViewOnMap,
                        enabled = true
                    )
                    IconButton(
                        onClick = onRemove,
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = stringResource(R.string.card_action_remove),
                            tint = colors.error
                        )
                    }
                }
                Switch(
                    checked = isActive,
                    onCheckedChange = onToggle,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = colors.onPrimary,
                        checkedTrackColor = colors.primary,
                        uncheckedThumbColor = colors.outline,
                        uncheckedTrackColor = colors.surfaceVariant
                    )
                )
            }
        }
    }
}

@Composable
private fun ProximityRow(
    distanceKm: Double?,
    alertDistanceKm: Double,
    stationId: String,
    isAlerting: Boolean
) {
    val colors = MaterialTheme.colorScheme
    val unit = LocalDistanceUnit.current

    // Anchor = the largest distance we've ever seen for this station since the
    // card entered composition. Held in `remember(stationId)` so swapping
    // stations resets it; monotonically grows to handle GPS jitter without
    // shrinking the perceived journey. Until the first GPS fix arrives, the
    // anchor is null and the bar stays at 0 — that's what prevents the
    // "partially full at setup" glitch.
    var anchorKm by remember(stationId) { mutableStateOf<Double?>(null) }
    LaunchedEffect(stationId, distanceKm) {
        val d = distanceKm ?: return@LaunchedEffect
        val current = anchorKm
        if (current == null || d > current) anchorKm = d
    }

    val rawProgress: Float = run {
        val d = distanceKm ?: return@run 0f
        val a = anchorKm ?: return@run 0f
        val span = a - alertDistanceKm
        if (span <= 0.0) return@run 1f
        val covered = (a - d).coerceIn(0.0, span)
        (covered / span).toFloat()
    }

    val progress by animateFloatAsState(
        targetValue = rawProgress,
        animationSpec = tween(durationMillis = 900, easing = LinearEasing),
        label = "proximityProgress"
    )

    val targetBarColor = when {
        isAlerting -> colors.proximityImminent
        progress > 0.85f -> colors.proximityImminent
        progress > 0.4f -> colors.proximityNear
        else -> colors.proximityFar
    }
    val barColor by animateColorAsState(
        targetValue = targetBarColor,
        animationSpec = tween(durationMillis = 500),
        label = "proximityColor"
    )

    val distanceText = distanceKm?.let { formatDistance(it, unit) } ?: stringResource(R.string.card_locating)

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier
                .weight(1f)
                .height(8.dp),
            color = barColor,
            trackColor = colors.surfaceVariant,
            strokeCap = StrokeCap.Round,
            drawStopIndicator = {}
        )
        Spacer(Modifier.width(MaterialTheme.spacing.md))
        Text(
            text = distanceText,
            style = MaterialTheme.typography.labelLarge,
            color = if (isAlerting) colors.onErrorContainer else colors.onSurface,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun CardActionButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    enabled: Boolean
) {
    FilledTonalButton(
        onClick = onClick,
        enabled = enabled,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.height(40.dp)
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun StatusBadge(text: String, container: Color, content: Color) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = container,
        contentColor = content
    ) {
        Text(
            text = text.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
        )
    }
}

@Composable
private fun formatLastTriggered(epochMs: Long): String {
    val now = java.time.LocalDateTime.now()
    val ts = java.time.LocalDateTime.ofInstant(
        java.time.Instant.ofEpochMilli(epochMs),
        java.time.ZoneId.systemDefault()
    )
    val timePart = ts.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
    return when {
        ts.toLocalDate() == now.toLocalDate() -> stringResource(R.string.time_today_format, timePart)
        ts.toLocalDate() == now.toLocalDate().minusDays(1) -> stringResource(R.string.time_yesterday_format, timePart)
        else -> ts.format(java.time.format.DateTimeFormatter.ofPattern("dd MMM HH:mm"))
    }
}

@Composable
fun EmptyState(onNavigateToMap: () -> Unit) {
    val spacing = MaterialTheme.spacing
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(spacing.xl)
        ) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Notifications,
                    contentDescription = null,
                    modifier = Modifier.size(32.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
            Spacer(Modifier.height(spacing.md))
            Text(
                text = stringResource(R.string.home_empty_title),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(spacing.xs))
            Text(
                text = stringResource(R.string.home_empty_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(spacing.lg))
            Button(
                onClick = onNavigateToMap,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ),
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.height(48.dp)
            ) {
                Icon(
                    Icons.Default.LocationOn,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(spacing.sm))
                Text(stringResource(R.string.home_empty_open_map))
            }
        }
    }
}
