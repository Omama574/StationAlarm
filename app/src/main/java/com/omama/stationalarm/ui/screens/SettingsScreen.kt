package com.omama.stationalarm.ui.screens

import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.omama.stationalarm.R
import com.omama.stationalarm.data.UserPreferences
import com.omama.stationalarm.service.LocationService
import kotlin.math.roundToInt
import com.omama.stationalarm.util.BatteryOptimizationHelper
import com.omama.stationalarm.util.GpsLogger
import com.omama.stationalarm.util.Logger
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val themeMode by UserPreferences.themeModeFlow.collectAsState(initial = UserPreferences.THEME_SYSTEM)
    val distanceUnit by UserPreferences.distanceUnitFlow.collectAsState(initial = UserPreferences.UNIT_KM)
    val alarmSoundUri by UserPreferences.alarmSoundUriFlow.collectAsState(initial = "")
    val ringSpeakerWithHeadphones by UserPreferences.ringSpeakerWithHeadphonesFlow.collectAsState(initial = true)

    // Ringtone picker — system ringtones / alarms
    val ringtoneLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val uri = result.data?.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            scope.launch {
                UserPreferences.setAlarmSoundUri(uri?.toString() ?: "")
            }
        }
    }

    // Local file picker — any audio file from storage (SAF)
    val fileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            // Persist read permission so the URI remains usable across reboots
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: SecurityException) {
                // Some providers don't support persistable permissions — best-effort only
            }
            scope.launch {
                UserPreferences.setAlarmSoundUri(uri.toString())
            }
        }
    }

    var showSoundPicker by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title), fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    navigationIconContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            // ── Distance Unit ──────────────────────────────────────────────
            SectionTitle(stringResource(R.string.settings_distance_unit))
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                val opts = listOf(
                    UserPreferences.UNIT_KM to stringResource(R.string.settings_distance_km),
                    UserPreferences.UNIT_MILES to stringResource(R.string.settings_distance_miles),
                )
                opts.forEachIndexed { idx, (value, label) ->
                    SegmentedButton(
                        selected = distanceUnit == value,
                        onClick = { scope.launch { UserPreferences.setDistanceUnit(value) } },
                        shape = SegmentedButtonDefaults.itemShape(index = idx, count = opts.size)
                    ) {
                        Text(label)
                    }
                }
            }
            Spacer(Modifier.height(24.dp))

            // ── Theme ──────────────────────────────────────────────────────
            SectionTitle(stringResource(R.string.settings_theme))
            Column(modifier = Modifier.fillMaxWidth()) {
                ThemeOption(
                    label = stringResource(R.string.settings_theme_system),
                    selected = themeMode == UserPreferences.THEME_SYSTEM,
                    onClick = { scope.launch { UserPreferences.setThemeMode(UserPreferences.THEME_SYSTEM) } }
                )
                ThemeOption(
                    label = stringResource(R.string.settings_theme_light),
                    selected = themeMode == UserPreferences.THEME_LIGHT,
                    onClick = { scope.launch { UserPreferences.setThemeMode(UserPreferences.THEME_LIGHT) } }
                )
                ThemeOption(
                    label = stringResource(R.string.settings_theme_dark),
                    selected = themeMode == UserPreferences.THEME_DARK,
                    onClick = { scope.launch { UserPreferences.setThemeMode(UserPreferences.THEME_DARK) } }
                )
            }
            Spacer(Modifier.height(24.dp))

            // ── Language ───────────────────────────────────────────────────
            // The picker writes the selected tag to DataStore AND immediately
            // hands it to AppCompatDelegate so all open Activities recreate
            // with the new locale. On Android 13+ this is also reflected in
            // the system per-app language picker.
            SectionTitle(stringResource(R.string.settings_language))
            val appLocale by UserPreferences.appLocaleFlow.collectAsState(initial = UserPreferences.LOCALE_SYSTEM)
            val languageOptions = listOf(
                UserPreferences.LOCALE_SYSTEM to stringResource(R.string.settings_language_system),
                "en" to stringResource(R.string.settings_language_english),
            )
            Column(modifier = Modifier.fillMaxWidth()) {
                languageOptions.forEach { (tag, label) ->
                    ThemeOption(
                        label = label,
                        selected = appLocale == tag,
                        onClick = {
                            scope.launch { UserPreferences.setAppLocale(tag) }
                            val locales = if (tag == UserPreferences.LOCALE_SYSTEM) {
                                androidx.core.os.LocaleListCompat.getEmptyLocaleList()
                            } else {
                                androidx.core.os.LocaleListCompat.forLanguageTags(tag)
                            }
                            androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(locales)
                        }
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.settings_language_more_coming),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
            Spacer(Modifier.height(24.dp))

            // ── Alarm Sound ────────────────────────────────────────────────
            SectionTitle(stringResource(R.string.settings_alarm_sound))
            val currentSoundName = remember(alarmSoundUri) {
                resolveSoundName(context, alarmSoundUri)
            }
            // Probe the URI on entry so the user sees a warning if the SAF grant
            // was revoked or the source file deleted between picking and now —
            // otherwise the alarm fire path silently falls back to default.
            val soundReachable = remember(alarmSoundUri) {
                probeAlarmUri(context, alarmSoundUri)
            }
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.settings_alarm_sound_current_format, currentSoundName),
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (!soundReachable) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.settings_alarm_sound_unavailable),
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { showSoundPicker = true },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(stringResource(
                                if (!soundReachable) R.string.settings_alarm_sound_reselect
                                else R.string.settings_alarm_sound_change
                            ))
                        }
                        if (alarmSoundUri.isNotBlank()) {
                            OutlinedButton(
                                onClick = {
                                    scope.launch { UserPreferences.setAlarmSoundUri("") }
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(stringResource(R.string.settings_alarm_sound_reset))
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    // Lets the user hear what the alarm actually sounds like at
                    // current volume + routing without having to walk into a
                    // geofence to test it. Plays for 3 seconds via LocationService.
                    OutlinedButton(
                        onClick = {
                            val intent = Intent(context, LocationService::class.java).apply {
                                action = LocationService.ACTION_TEST_ALARM
                            }
                            context.startForegroundService(intent)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.settings_alarm_sound_test))
                    }
                }
            }
            Spacer(Modifier.height(24.dp))

            // Audio Routing
            SectionTitle(stringResource(R.string.settings_audio_routing))

            val escalatingAlarm by UserPreferences.escalatingAlarmFlow.collectAsState(initial = true)
            val rampSecs by UserPreferences.escalatingAlarmRampSecsFlow
                .collectAsState(initial = UserPreferences.DEFAULT_RAMP_SECS)

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    // Ring speaker with headphones
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.settings_ring_speaker_title),
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = stringResource(R.string.settings_ring_speaker_subtitle),
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Switch(
                            checked = ringSpeakerWithHeadphones,
                            onCheckedChange = { enabled ->
                                scope.launch { UserPreferences.setRingSpeakerWithHeadphones(enabled) }
                            }
                        )
                    }

                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 8.dp),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
                    )

                    // Escalating alarm volume
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.settings_escalating_title),
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = stringResource(R.string.settings_escalating_subtitle_format, rampSecs),
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Switch(
                            checked = escalatingAlarm,
                            onCheckedChange = { enabled ->
                                scope.launch { UserPreferences.setEscalatingAlarm(enabled) }
                            }
                        )
                    }

                    if (escalatingAlarm) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.settings_ramp_duration_format, rampSecs),
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                        Slider(
                            value = rampSecs.toFloat(),
                            onValueChange = { raw ->
                                val snapped = (raw / 5).roundToInt() * 5
                                scope.launch { UserPreferences.setEscalatingAlarmRampSecs(snapped) }
                            },
                            valueRange = 15f..60f,
                            steps = 8,
                            colors = SliderDefaults.colors(
                                thumbColor = MaterialTheme.colorScheme.primary,
                                activeTrackColor = MaterialTheme.colorScheme.primary,
                                inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(stringResource(R.string.settings_ramp_15s), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(stringResource(R.string.settings_ramp_60s), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))

            // ── Alarm Duration ─────────────────────────────────────────────
            SectionTitle(stringResource(R.string.settings_alarm_duration))
            val alarmDurationSecs by UserPreferences.alarmDurationSecsFlow
                .collectAsState(initial = UserPreferences.DEFAULT_ALARM_DURATION_SECS)
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.settings_alarm_duration_subtitle),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.settings_alarm_duration_format, alarmDurationSecs / 60),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Slider(
                        value = alarmDurationSecs.toFloat(),
                        onValueChange = { raw ->
                            // Snap to whole-minute steps so the label stays clean.
                            val snapped = (raw / 60f).roundToInt() * 60
                            scope.launch { UserPreferences.setAlarmDurationSecs(snapped) }
                        },
                        valueRange = UserPreferences.MIN_ALARM_DURATION_SECS.toFloat()..
                                UserPreferences.MAX_ALARM_DURATION_SECS.toFloat(),
                        steps = 13, // 60..900 in 60s steps → 14 intervals → 13 in-between stops
                        colors = SliderDefaults.colors(
                            thumbColor = MaterialTheme.colorScheme.primary,
                            activeTrackColor = MaterialTheme.colorScheme.primary,
                            inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(stringResource(R.string.settings_alarm_duration_min), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(stringResource(R.string.settings_alarm_duration_max), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Spacer(Modifier.height(24.dp))

            // ── Background Running ─────────────────────────────────────────
            SectionTitle(stringResource(R.string.settings_background_running))
            // Re-check on every ON_RESUME so the row updates immediately when
            // the user returns from the system battery settings — without
            // this, the screen stays "Restricted" until next recomposition.
            val lifecycleOwner = LocalLifecycleOwner.current
            var isExempt by remember {
                mutableStateOf(BatteryOptimizationHelper.isIgnoringBatteryOptimizations(context))
            }
            DisposableEffect(lifecycleOwner) {
                val observer = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_RESUME) {
                        isExempt = BatteryOptimizationHelper.isIgnoringBatteryOptimizations(context)
                    }
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
            }
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.settings_battery_exemption),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = stringResource(
                                if (isExempt) R.string.settings_battery_exempt_yes
                                else R.string.settings_battery_exempt_no
                            ),
                            fontSize = 13.sp,
                            color = if (isExempt)
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                            else
                                MaterialTheme.colorScheme.error
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    OutlinedButton(onClick = { BatteryOptimizationHelper.openBatterySettings(context) }) {
                        Text(stringResource(R.string.settings_battery_open))
                    }
                }
            }
            Spacer(Modifier.height(24.dp))

            // ── Debug Logs ─────────────────────────────────────────────────
            SectionTitle(stringResource(R.string.settings_debug_logs))
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.settings_debug_logs_subtitle),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                val intent = Logger.shareLog(context)
                                if (intent != null) context.startActivity(intent)
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(stringResource(R.string.settings_debug_logs_app))
                        }
                        OutlinedButton(
                            onClick = {
                                val intent = GpsLogger.shareLog(context)
                                if (intent != null) context.startActivity(intent)
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(stringResource(R.string.settings_debug_logs_gps))
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    // Sound-picker chooser dialog
    if (showSoundPicker) {
        val pickerTitle = stringResource(R.string.settings_sound_picker_select_title)
        AlertDialog(
            onDismissRequest = { showSoundPicker = false },
            title = { Text(stringResource(R.string.settings_sound_picker_title), fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text(stringResource(R.string.settings_sound_picker_body), fontSize = 14.sp)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showSoundPicker = false
                    val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                        putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM or RingtoneManager.TYPE_NOTIFICATION or RingtoneManager.TYPE_RINGTONE)
                        putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, pickerTitle)
                        putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                        putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                        val current = alarmSoundUri.takeIf { it.isNotBlank() }?.let { Uri.parse(it) }
                        if (current != null) putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, current)
                    }
                    ringtoneLauncher.launch(intent)
                }) { Text(stringResource(R.string.settings_sound_picker_system)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    showSoundPicker = false
                    fileLauncher.launch(arrayOf("audio/*"))
                }) { Text(stringResource(R.string.settings_sound_picker_file)) }
            },
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(16.dp)
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        fontSize = 16.sp,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.padding(vertical = 8.dp)
    )
}

@Composable
private fun ThemeOption(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = selected,
            onClick = onClick,
            colors = RadioButtonDefaults.colors(
                selectedColor = MaterialTheme.colorScheme.onSurface,
                unselectedColor = Color.Gray
            )
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = label,
            fontSize = 15.sp,
            color = MaterialTheme.colorScheme.onBackground
        )
    }
}

/** Cheap reachability probe — opens then immediately closes a stream. Returns
 *  true for blank (= use system default, always available) and for URIs we can
 *  open. Mirrors the LocationService.resolveValidatedAlarmUri logic so what the
 *  Settings screen reports matches what the alarm will actually do. */
private fun probeAlarmUri(context: android.content.Context, uriStr: String): Boolean {
    if (uriStr.isBlank()) return true
    val uri = try { Uri.parse(uriStr) } catch (_: Exception) { return false }
    return try {
        context.contentResolver.openInputStream(uri)?.use { true } ?: false
    } catch (_: Exception) {
        false
    }
}

/** Resolve a human-readable name for the saved URI (or "Default" if blank). */
private fun resolveSoundName(context: android.content.Context, uriStr: String): String {
    val defaultName = context.getString(R.string.settings_alarm_sound_default_name)
    if (uriStr.isBlank()) return defaultName
    val uri = try { Uri.parse(uriStr) } catch (_: Exception) { return defaultName }

    // Ringtone lookup first (system ringtones have friendly titles)
    try {
        val title = RingtoneManager.getRingtone(context, uri)?.getTitle(context)
        if (!title.isNullOrBlank()) return title
    } catch (_: Exception) { /* ignore */ }

    // Fall back to SAF display name
    try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) {
                    val name = c.getString(idx)
                    if (!name.isNullOrBlank()) return name
                }
            }
        }
    } catch (_: Exception) { /* ignore */ }

    return context.getString(R.string.settings_alarm_sound_custom_name)
}
