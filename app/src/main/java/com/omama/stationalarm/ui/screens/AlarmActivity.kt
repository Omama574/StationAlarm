package com.omama.stationalarm.ui.screens

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.omama.stationalarm.R
import com.omama.stationalarm.repository.StationRepository
import com.omama.stationalarm.service.LocationService
import com.omama.stationalarm.service.ServiceNotifications
import com.omama.stationalarm.ui.theme.StationAlarmTheme
import com.omama.stationalarm.util.Logger

class AlarmActivity : ComponentActivity() {

    private var stationId: String? = null
    private var initialStationName: String? = null
    private var dismissed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Wake screen and show over lock screen
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        stationId = intent.getStringExtra("stationId")
        initialStationName = intent.getStringExtra(ServiceNotifications.EXTRA_STATION_NAME)

        setContent {
            var activeStation by remember { mutableStateOf<com.omama.stationalarm.data.ActiveStation?>(null) }
            // Seed the displayed name from the intent extra so the first frame
            // shows the real station name instead of briefly flashing the raw
            // stationId during the async DB lookup.
            val defaultDestination = stringResource(R.string.alarm_screen_default_destination)
            var stationName by remember {
                mutableStateOf(initialStationName ?: stationId ?: defaultDestination)
            }

            LaunchedEffect(stationId) {
                if (stationId != null) {
                    val allActive = StationRepository.getAllActiveStationsList()
                    activeStation = allActive.find { it.stationId == stationId }
                    activeStation?.getStation()?.name?.let { stationName = it }
                }
            }

            StationAlarmTheme(darkTheme = true) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AlarmScreen(
                        stationName = stationName,
                        customReminder = if (activeStation?.sendReminder == true) activeStation?.customReminder else null,
                        onDismiss = { dismissAlarm() }
                    )
                }
            }
        }
    }

    private fun dismissAlarm() {
        if (dismissed) return
        dismissed = true
        val id = stationId ?: return

        // Single dismiss path: let LocationService handle DB delete + sound/notification cleanup
        val dismissIntent = Intent(this, LocationService::class.java).apply {
            action = LocationService.ACTION_DISMISS_ALARM
            putExtra("stationId", id)
        }
        startService(dismissIntent)
        finish()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || 
            keyCode == KeyEvent.KEYCODE_VOLUME_UP || 
            keyCode == KeyEvent.KEYCODE_VOLUME_MUTE) {
            dismissAlarm()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}

@Composable
fun AlarmScreen(stationName: String, customReminder: String?, onDismiss: () -> Unit) {
    BackHandler { onDismiss() }
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = stringResource(R.string.alarm_screen_wake_up),
            fontSize = 36.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = stringResource(R.string.alarm_screen_arrived_at),
            fontSize = 20.sp,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
        )
        Text(
            text = stationName,
            fontSize = 28.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center
        )

        if (!customReminder.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(32.dp))
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(
                    text = customReminder,
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 18.sp,
                    textAlign = TextAlign.Center
                )
            }
        }

        Spacer(modifier = Modifier.height(64.dp))

        Button(
            onClick = onDismiss,
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.onBackground,
                contentColor = MaterialTheme.colorScheme.background
            ),
            shape = RoundedCornerShape(32.dp)
        ) {
            Text(stringResource(R.string.alarm_screen_dismiss), fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }

        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.alarm_screen_press_button_hint),
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
        )
    }
}
