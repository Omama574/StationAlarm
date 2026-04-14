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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.omama.stationalarm.data.UserPreferences
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
                title = { Text("Settings", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
            SectionTitle("Distance Unit")
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                val opts = listOf(UserPreferences.UNIT_KM to "Kilometres", UserPreferences.UNIT_MILES to "Miles")
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
            SectionTitle("Theme")
            Column(modifier = Modifier.fillMaxWidth()) {
                ThemeOption(
                    label = "System default",
                    selected = themeMode == UserPreferences.THEME_SYSTEM,
                    onClick = { scope.launch { UserPreferences.setThemeMode(UserPreferences.THEME_SYSTEM) } }
                )
                ThemeOption(
                    label = "Light",
                    selected = themeMode == UserPreferences.THEME_LIGHT,
                    onClick = { scope.launch { UserPreferences.setThemeMode(UserPreferences.THEME_LIGHT) } }
                )
                ThemeOption(
                    label = "Dark",
                    selected = themeMode == UserPreferences.THEME_DARK,
                    onClick = { scope.launch { UserPreferences.setThemeMode(UserPreferences.THEME_DARK) } }
                )
            }
            Spacer(Modifier.height(24.dp))

            // ── Alarm Sound ────────────────────────────────────────────────
            SectionTitle("Alarm Sound")
            val currentSoundName = remember(alarmSoundUri) {
                resolveSoundName(context, alarmSoundUri)
            }
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Current: $currentSoundName",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { showSoundPicker = true },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Change")
                        }
                        if (alarmSoundUri.isNotBlank()) {
                            OutlinedButton(
                                onClick = {
                                    scope.launch { UserPreferences.setAlarmSoundUri("") }
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Reset")
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))

            // ── Debug Logs ─────────────────────────────────────────────────
            SectionTitle("Debug Logs")
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Share logs for diagnosing alarm issues.",
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
                            Text("App Logs")
                        }
                        OutlinedButton(
                            onClick = {
                                val intent = GpsLogger.shareLog(context)
                                if (intent != null) context.startActivity(intent)
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("GPS Logs")
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    // Sound-picker chooser dialog
    if (showSoundPicker) {
        AlertDialog(
            onDismissRequest = { showSoundPicker = false },
            title = { Text("Choose Alarm Sound", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text("Pick a source for your alarm sound.", fontSize = 14.sp)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showSoundPicker = false
                    val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                        putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM or RingtoneManager.TYPE_NOTIFICATION or RingtoneManager.TYPE_RINGTONE)
                        putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Select alarm sound")
                        putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                        putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                        val current = alarmSoundUri.takeIf { it.isNotBlank() }?.let { Uri.parse(it) }
                        if (current != null) putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, current)
                    }
                    ringtoneLauncher.launch(intent)
                }) { Text("System ringtones") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showSoundPicker = false
                    fileLauncher.launch(arrayOf("audio/*"))
                }) { Text("Local file") }
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

/** Resolve a human-readable name for the saved URI (or "Default" if blank). */
private fun resolveSoundName(context: android.content.Context, uriStr: String): String {
    if (uriStr.isBlank()) return "Default alarm"
    val uri = try { Uri.parse(uriStr) } catch (_: Exception) { return "Default alarm" }

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

    return "Custom sound"
}
