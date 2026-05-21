package com.omama.stationalarm.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Verifies the write-side clamp on alarm duration. The slider in Settings
 * already snaps to valid values, but DataStore can be hand-edited (root,
 * adb) and a future bug could pass an unbounded value — these tests guard
 * the API surface so a bad write stays inside the documented 60..900s
 * range regardless of source.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class UserPreferencesTest {

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // Wipe any previous DataStore file so each test starts from defaults.
        context.filesDir.resolve("datastore").deleteRecursively()
        UserPreferences.initialize(context)
    }

    @Test
    fun alarmDurationSecs_defaultIs180() = runTest {
        assertEquals(
            UserPreferences.DEFAULT_ALARM_DURATION_SECS,
            UserPreferences.alarmDurationSecsFlow.first()
        )
    }

    @Test
    fun setAlarmDurationSecs_inRangeWritesAsGiven() = runTest {
        UserPreferences.setAlarmDurationSecs(180)
        assertEquals(180, UserPreferences.alarmDurationSecsFlow.first())
    }

    @Test
    fun setAlarmDurationSecs_clampsAboveMax() = runTest {
        UserPreferences.setAlarmDurationSecs(9999)
        assertEquals(
            UserPreferences.MAX_ALARM_DURATION_SECS,
            UserPreferences.alarmDurationSecsFlow.first()
        )
    }

    @Test
    fun setAlarmDurationSecs_clampsBelowMin() = runTest {
        UserPreferences.setAlarmDurationSecs(0)
        assertEquals(
            UserPreferences.MIN_ALARM_DURATION_SECS,
            UserPreferences.alarmDurationSecsFlow.first()
        )
    }

    @Test
    fun setAlarmDurationSecs_clampsNegative() = runTest {
        UserPreferences.setAlarmDurationSecs(-100)
        assertEquals(
            UserPreferences.MIN_ALARM_DURATION_SECS,
            UserPreferences.alarmDurationSecsFlow.first()
        )
    }
}
